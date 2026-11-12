package isomeria.aichat.data.voice

import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 0.7.2 后加的格式（2026-11-12 按官方文档）：智谱合成和识别（含长录音分段、按错误码分类）、Fish Audio 识别、
 * OpenRouter 合成（pcm 包成 WAV、instructions）和识别（标准接口读 usage.seconds）。只用本机模拟接口。
 */
class VoiceApiMoreVendorsTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val api = VoiceApi(OkHttpClient(), json, timeoutMillis = 2_000)
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 1)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun endpoint() = LlmEndpoint(server.url("/v1").toString(), "sk-test")

    private fun body(recorded: RecordedRequest): JsonObject = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject

    /** [seconds] 秒的 16 kHz 单声道录音：每秒前 0.9 秒是响声，后 0.1 秒是静音 */
    private fun speech(seconds: Int): ByteArray {
        val samples = ShortArray(seconds * 16_000) { i -> if (i % 16_000 < 14_400) (if (i % 2 == 0) 8_000 else -8_000).toShort() else 0 }
        val pcm = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            pcm[i * 2] = (s.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = (s.toInt() shr 8).toByte()
        }
        return WavFormat.wrap(pcm)
    }

    /** multipart 里一个文字字段的值（字段头几行之后、空行后面那一行） */
    private fun field(raw: String, name: String): String? =
        Regex("name=\"" + Regex.escape(name) + "\"\r\n(?:[^\r\n]+\r\n)*\r\n([^\r\n]*)\r\n").find(raw)?.groupValues?.get(1)

    private suspend fun expectError(expected: LlmError, block: suspend () -> Unit) {
        try {
            block()
            fail("应该失败")
        } catch (e: LlmException) {
            assertEquals(expected, e.error)
        }
    }

    // ---------- 智谱 ----------

    @Test
    fun zhipuTts_asksForWav_noStyleNoWatermark_readsBinary() = runBlocking {
        val wav = WavFormat.header(4, 24_000, 1) + byteArrayOf(1, 0, 2, 0)
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/wav").body(okio.Buffer().write(wav)).build())
        val audio = api.synthesize(VoiceProtocol.ZHIPU_TTS, endpoint(), TtsRequest("glm-tts", "你好", "tongtong", "温柔一点"))
        assertArrayEquals(wav, audio.bytes)
        assertEquals("wav", audio.extension)
        val recorded = server.takeRequest()
        assertEquals("/v1/audio/speech", recorded.url.encodedPath)
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        val sent = body(recorded)
        assertEquals("glm-tts", sent["model"]!!.jsonPrimitive.content)
        assertEquals("你好", sent["input"]!!.jsonPrimitive.content)
        assertEquals("tongtong", sent["voice"]!!.jsonPrimitive.content)
        assertEquals("wav", sent["response_format"]!!.jsonPrimitive.content)
        assertEquals("false", sent["stream"]!!.jsonPrimitive.content)
        assertFalse("智谱没有风格字段", sent.toString().contains("温柔一点"))
        assertFalse("水印按服务商默认，不发", sent.containsKey("watermark_enabled"))
        assertFalse(VoiceProtocol.supportsStyle(VoiceProtocol.ZHIPU_TTS, "glm-tts"))
    }

    @Test
    fun zhipuErrors_readTheCodeInTheBody_notJustTheStatus() = runBlocking {
        val cases = listOf(
            429 to "1113" to LlmError.BALANCE,
            429 to "1302" to LlmError.RATE_LIMIT,
            401 to "1000" to LlmError.AUTH,
            400 to "1214" to LlmError.BAD_REQUEST,
            400 to "1301" to LlmError.BAD_REQUEST,
            429 to "1305" to LlmError.SERVER,
        )
        for ((pair, expected) in cases) {
            val (status, code) = pair
            server.enqueue(MockResponse.Builder().code(status).body("""{"error":{"code":"$code","message":"x"}}""").build())
            expectError(expected) { api.synthesize(VoiceProtocol.ZHIPU_TTS, endpoint(), TtsRequest("glm-tts", "你好", "tongtong")) }
        }
        // 认不出的内容按状态码
        server.enqueue(MockResponse.Builder().code(429).body("busy").build())
        expectError(LlmError.RATE_LIMIT) { api.synthesize(VoiceProtocol.ZHIPU_TTS, endpoint(), TtsRequest("glm-tts", "你好", "tongtong")) }
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":{"code":"1113"}}""").build())
        expectError(LlmError.BALANCE) { api.transcribe(VoiceProtocol.ZHIPU_ASR, endpoint(), AsrRequest("glm-asr-2512", speech(3))) }
    }

    @Test
    fun zhipuAsr_shortRecording_oneMultipartRequest_noPrompt() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"id":"1","text":" 你好呀 ","model":"glm-asr-2512"}""").build())
        val result = api.transcribe(VoiceProtocol.ZHIPU_ASR, endpoint(), AsrRequest("glm-asr-2512", speech(10)))
        assertEquals("你好呀", result.text)
        assertEquals("智谱不返回时长，按 WAV 长度记", null, result.seconds)
        val recorded = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", recorded.url.encodedPath)
        val raw = recorded.body!!.utf8()
        assertTrue(raw.contains("name=\"file\"; filename=\"audio.wav\""))
        assertTrue(raw.contains("glm-asr-2512"))
        assertFalse(raw.contains("name=\"prompt\""))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun zhipuAsr_sixtySeconds_threePiecesInOrder_promptCarriesEarlierText_silentPieceSkipped() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"text":"第一段"}""").build())
        server.enqueue(MockResponse.Builder().body("""{"text":""}""").build())
        server.enqueue(MockResponse.Builder().body("""{"text":"第三段"}""").build())
        val result = api.transcribe(VoiceProtocol.ZHIPU_ASR, endpoint(), AsrRequest("glm-asr-2512", speech(60)))
        assertEquals("第一段第三段", result.text)
        assertEquals(3, server.requestCount)
        val first = server.takeRequest().body!!.utf8()
        val second = server.takeRequest().body!!.utf8()
        val third = server.takeRequest().body!!.utf8()
        assertFalse(first.contains("name=\"prompt\""))
        assertTrue("第二段带上第一段的文字", second.contains("name=\"prompt\"") && second.contains("第一段"))
        assertTrue(third.contains("第一段") && !third.contains("第三段"))
    }

    @Test
    fun zhipuAsr_anyPieceFails_wholeRecordingFails() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"text":"第一段"}""").build())
        server.enqueue(MockResponse.Builder().code(500).body("""{"error":{"code":"1234"}}""").build())
        expectError(LlmError.SERVER) { api.transcribe(VoiceProtocol.ZHIPU_ASR, endpoint(), AsrRequest("glm-asr-2512", speech(40))) }
        assertEquals(2, server.requestCount)
    }

    // ---------- 分段 ----------

    @Test
    fun splitter_keepsShortRecordings_cutsLongOnesAtQuietSpots_underThirtySeconds() {
        val short = speech(25)
        assertEquals(1, AsrSplitter.split(short).size)
        assertTrue(AsrSplitter.split(short)[0] === short)
        for (seconds in listOf(26, 40, 51, 60)) {
            val wav = speech(seconds)
            val pieces = AsrSplitter.split(wav)
            val expected = (seconds * 1000 + AsrSplitter.MAX_PIECE_MILLIS - 1) / AsrSplitter.MAX_PIECE_MILLIS
            assertEquals("$seconds 秒", expected.toInt(), pieces.size)
            val infos = pieces.map { WavFormat.parse(it)!! }
            assertTrue("每段不超过 29 秒", infos.all { it.durationMillis <= 29_000 })
            assertTrue(infos.all { it.sampleRate == 16_000 && it.channels == 1 && it.bits == 16 })
            assertEquals("拼起来就是原来的录音", WavFormat.parse(wav)!!.dataBytes, infos.sumOf { it.dataBytes })
            // 切点落在每秒最后 0.1 秒的静音里
            var offset = 0L
            for (info in infos.dropLast(1)) {
                offset += info.dataBytes / 2
                assertTrue("$seconds 秒的切点 $offset 应在静音里", offset % 16_000 >= 14_400)
            }
        }
    }

    @Test
    fun splitter_joinsChineseDirectly_andEnglishWithSpace() {
        assertEquals("你好世界", AsrSplitter.join("你好", "世界"))
        assertEquals("hello world", AsrSplitter.join("hello", "world"))
        assertEquals("你好", AsrSplitter.join("你好", ""))
        assertEquals("世界", AsrSplitter.join("", "世界"))
    }

    // ---------- Fish Audio 识别 ----------

    @Test
    fun fishAsr_audioFieldAndModelHeader_turnsOffTagsAndSpeakers_readsDuration() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"text":"<|speaker:0|>你好呀","duration":3.2,"segments":[]}""").build())
        val result = api.transcribe(VoiceProtocol.FISH_ASR, endpoint(), AsrRequest("transcribe-1-pro", speech(3)))
        assertEquals("你好呀", result.text)
        assertEquals(3.2, result.seconds!!, 0.001)
        val recorded = server.takeRequest()
        assertEquals("/v1/v1/asr", recorded.url.encodedPath)
        assertEquals("transcribe-1-pro", recorded.headers["model"])
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        val raw = recorded.body!!.utf8()
        assertTrue(raw.contains("name=\"audio\"; filename=\"audio.wav\""))
        assertEquals("false", field(raw, "tag_audio_events"))
        assertEquals("false", field(raw, "diarize"))
        assertEquals("true", field(raw, "ignore_timestamps"))
        assertFalse("语言自动时不发", raw.contains("name=\"language\""))
        assertFalse("模型在请求头里", raw.contains("name=\"model\""))
    }

    @Test
    fun fishAsr_errorsByStatus() = runBlocking {
        server.enqueue(MockResponse.Builder().code(402).body("{}").build())
        expectError(LlmError.BALANCE) { api.transcribe(VoiceProtocol.FISH_ASR, endpoint(), AsrRequest("transcribe-1-pro", speech(1))) }
        server.enqueue(MockResponse.Builder().body("""{"text":"","duration":1.0}""").build())
        expectError(LlmError.UNKNOWN) { api.transcribe(VoiceProtocol.FISH_ASR, endpoint(), AsrRequest("transcribe-1-pro", speech(1))) }
    }

    // ---------- OpenRouter ----------

    @Test
    fun openRouterTts_gemini_asksForPcm_wrapsByContentTypeRate_sendsInstructions() = runBlocking {
        val pcm = ByteArray(4_800) { (it % 5).toByte() }
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/l16;rate=16000;channels=1").body(okio.Buffer().write(pcm)).build())
        val audio = api.synthesize(VoiceProtocol.OPENROUTER_TTS, endpoint(), TtsRequest("google/gemini-3.8-flash-tts", "你好", "Kore", "温柔一点"))
        assertEquals("wav", audio.extension)
        val info = WavFormat.parse(audio.bytes)!!
        assertEquals(16_000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(pcm.size.toLong(), info.dataBytes)
        val recorded = server.takeRequest()
        assertEquals("/v1/audio/speech", recorded.url.encodedPath)
        val sent = body(recorded)
        assertEquals("google/gemini-3.8-flash-tts", sent["model"]!!.jsonPrimitive.content)
        assertEquals("你好", sent["input"]!!.jsonPrimitive.content)
        assertEquals("Kore", sent["voice"]!!.jsonPrimitive.content)
        assertEquals("pcm", sent["response_format"]!!.jsonPrimitive.content)
        assertEquals("温柔一点", sent["instructions"]!!.jsonPrimitive.content)
        assertFalse("不发 OpenRouter 的来源标识", recorded.headers.names().any { it.equals("X-Title", true) || it.equals("HTTP-Referer", true) })
    }

    @Test
    fun openRouterTts_pcmWithoutRate_defaultsTo24k_otherModelsGetMp3AndNoInstructions() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/pcm").body(okio.Buffer().write(ByteArray(480))).build())
        val wrapped = api.synthesize(VoiceProtocol.OPENROUTER_TTS, endpoint(), TtsRequest("google/gemini-3.8-flash-tts", "你好", "Kore"))
        assertEquals(24_000, WavFormat.parse(wrapped.bytes)!!.sampleRate)
        assertFalse(body(server.takeRequest()).containsKey("instructions"))

        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").body(okio.Buffer().write(mp3)).build())
        val audio = api.synthesize(VoiceProtocol.OPENROUTER_TTS, endpoint(), TtsRequest("mistralai/voxtral-mini-tts-2603", "你好", "en_paul_neutral", "温柔一点"))
        assertArrayEquals(mp3, audio.bytes)
        val sent = body(server.takeRequest())
        assertEquals("mp3", sent["response_format"]!!.jsonPrimitive.content)
        assertFalse("这个模型不认 instructions", sent.containsKey("instructions"))
        assertFalse(VoiceProtocol.supportsStyle(VoiceProtocol.OPENROUTER_TTS, "mistralai/voxtral-mini-tts-2603"))
        assertTrue(VoiceProtocol.supportsStyle(VoiceProtocol.OPENROUTER_TTS, "openai/gpt-4o-mini-tts-2025-12-15"))
    }

    @Test
    fun openRouterTts_jsonErrorOn200_andBalance() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"error":{"code":400}}""").build())
        expectError(LlmError.UNKNOWN) { api.synthesize(VoiceProtocol.OPENROUTER_TTS, endpoint(), TtsRequest("google/gemini-3.8-flash-tts", "你好", "Kore")) }
        server.enqueue(MockResponse.Builder().code(402).body("""{"error":{"code":402,"message":"x"}}""").build())
        expectError(LlmError.BALANCE) { api.synthesize(VoiceProtocol.OPENROUTER_TTS, endpoint(), TtsRequest("google/gemini-3.8-flash-tts", "你好", "Kore")) }
    }

    @Test
    fun pcmAudio_leavesKnownFormatsAlone_rejectsOddLength() {
        assertArrayEquals(mp3, PcmAudio.wrapIfPcm(mp3, "audio/mpeg", requestedPcm = false))
        assertArrayEquals("要的是 pcm 但返回的是 mp3", mp3, PcmAudio.wrapIfPcm(mp3, "", requestedPcm = true))
        assertTrue(WavFormat.isWav(PcmAudio.wrapIfPcm(byteArrayOf(1, 2, 3, 4), "", requestedPcm = true)))
        try {
            PcmAudio.wrapIfPcm(byteArrayOf(1, 2, 3), "audio/pcm", requestedPcm = true)
            fail("奇数字节不是 16 位 pcm")
        } catch (e: LlmException) {
            assertEquals(LlmError.UNKNOWN, e.error)
        }
    }

    @Test
    fun openRouterAsr_standardFormat_readsUsageSeconds() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"text":"你好","usage":{"seconds":2.5,"cost":0.0001}}""").build())
        val result = api.transcribe(VoiceProtocol.OPENAI_AUDIO, endpoint(), AsrRequest("openai/whisper-large-v3", speech(3)))
        assertEquals("你好", result.text)
        assertEquals(2.5, result.seconds!!, 0.001)
        val raw = server.takeRequest().body!!.utf8()
        assertTrue(raw.contains("openai/whisper-large-v3"))
    }
}
