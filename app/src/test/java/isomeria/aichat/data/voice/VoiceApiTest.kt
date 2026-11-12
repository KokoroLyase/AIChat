package isomeria.aichat.data.voice

import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 两种请求格式的语音合成和识别（按 2026-11-11 的官方文档）：请求体的字段和位置、返回内容的读取、失败、空内容和超时。
 * 只用本机模拟接口。
 */
class VoiceApiTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val api = VoiceApi(OkHttpClient(), json, timeoutMillis = 1_000)
    private val wav = WavFormat.wrap(ByteArray(3_200) { (it % 7).toByte() })

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun endpoint() = LlmEndpoint(server.url("/v1").toString(), "sk-test")

    private fun sent(): Pair<String, JsonObject> {
        val recorded = server.takeRequest()
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        return recorded.url.encodedPath to json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
    }

    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 1)

    @Test
    fun chatAudioTts_textInAssistant_styleInUser_mp3AndVoice_readsBase64() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"role":"assistant","content":"","audio":{"id":"a","data":"${Base64.getEncoder().encodeToString(mp3)}"}}}]}""").build())
        val audio = api.synthesize(VoiceProtocol.CHAT_AUDIO, endpoint(), TtsRequest("mimo-v2.5-tts", "你好呀", "冰糖", "温柔一点"))
        assertArrayEquals(mp3, audio.bytes)
        assertEquals("mp3", audio.extension)
        val (path, body) = sent()
        assertEquals("/v1/chat/completions", path)
        assertEquals("mimo-v2.5-tts", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant"), messages.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("要读的文字放在 assistant 消息里", "你好呀", messages[1]["content"]!!.jsonPrimitive.content)
        assertEquals("温柔一点", messages[0]["content"]!!.jsonPrimitive.content)
        assertEquals("mp3", body["audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals("冰糖", body["audio"]!!.jsonObject["voice"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
    }

    @Test
    fun chatAudioTts_withoutStyleOrVoice_sendsOnlyAssistantAndNoVoice() {
        val body = VoiceApi.chatAudioTtsBody(TtsRequest("m", "读这句", "", "  "))
        assertEquals(listOf("assistant"), body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertFalse(body["audio"]!!.jsonObject.containsKey("voice"))
    }

    @Test
    fun openAiTts_postsSpeechJson_readsBinary_detectsWav() = runBlocking {
        val audioWav = WavFormat.wrap(ByteArray(64))
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/wav").body(Buffer().write(audioWav)).build())
        val audio = api.synthesize(VoiceProtocol.OPENAI_AUDIO, endpoint(), TtsRequest("tts-1", "hello", "alloy", "不会发出去"))
        assertArrayEquals(audioWav, audio.bytes)
        assertEquals("wav", audio.extension)
        val (path, body) = sent()
        assertEquals("/v1/audio/speech", path)
        assertEquals("hello", body["input"]!!.jsonPrimitive.content)
        assertEquals("alloy", body["voice"]!!.jsonPrimitive.content)
        assertEquals("mp3", body["response_format"]!!.jsonPrimitive.content)
        assertEquals(setOf("model", "input", "voice", "response_format"), body.keys)
    }

    @Test
    fun chatAudioAsr_singleInputAudioDataUrl_languageAuto_readsTextAndSeconds() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"role":"assistant","content":" 你好 "}}],"usage":{"seconds":4}}""").build())
        val result = api.transcribe(VoiceProtocol.CHAT_AUDIO, endpoint(), AsrRequest("mimo-v2.5-asr", wav))
        assertEquals("你好", result.text)
        assertEquals(4.0, result.seconds!!, 0.0)
        val (path, body) = sent()
        assertEquals("/v1/chat/completions", path)
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        val part = message["content"]!!.jsonArray.single().jsonObject
        assertEquals("input_audio", part["type"]!!.jsonPrimitive.content)
        val data = part["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content
        assertTrue(data.startsWith("data:audio/wav;base64,"))
        assertArrayEquals(wav, Base64.getDecoder().decode(data.removePrefix("data:audio/wav;base64,")))
        assertEquals("wav", part["input_audio"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals("auto", body["asr_options"]!!.jsonObject["language"]!!.jsonPrimitive.content)
    }

    @Test
    fun openAiAsr_multipartFileAndModel_readsText() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"text":"识别结果"}""").build())
        val result = api.transcribe(VoiceProtocol.OPENAI_AUDIO, endpoint(), AsrRequest("FunAudioLLM/SenseVoiceSmall", wav))
        assertEquals("识别结果", result.text)
        val recorded = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", recorded.url.encodedPath)
        assertTrue(recorded.headers["Content-Type"]!!.startsWith("multipart/form-data"))
        val raw = recorded.body!!.utf8()
        assertTrue(raw.contains("name=\"model\""))
        assertTrue(raw.contains("FunAudioLLM/SenseVoiceSmall"))
        assertTrue(raw.contains("name=\"file\"; filename=\"audio.wav\""))
        assertTrue(raw.contains("Content-Type: audio/wav"))
    }

    private suspend fun expectError(expected: LlmError, block: suspend () -> Unit) {
        try {
            block()
            fail("应该失败")
        } catch (e: LlmException) {
            assertEquals(expected, e.error)
        }
    }

    @Test
    fun failures_httpEmptyAndUnparseable_areLlmExceptions() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).build())
        expectError(LlmError.RATE_LIMIT) { api.synthesize(VoiceProtocol.CHAT_AUDIO, endpoint(), TtsRequest("m", "x", "")) }
        server.enqueue(MockResponse.Builder().code(401).build())
        expectError(LlmError.AUTH) { api.transcribe(VoiceProtocol.OPENAI_AUDIO, endpoint(), AsrRequest("m", wav)) }
        // 200 但没有音频 / 没有文字
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"content":"","audio":{"data":""}}}]}""").build())
        expectError(LlmError.UNKNOWN) { api.synthesize(VoiceProtocol.CHAT_AUDIO, endpoint(), TtsRequest("m", "x", "")) }
        server.enqueue(MockResponse.Builder().body("""{"text":"   "}""").build())
        expectError(LlmError.UNKNOWN) { api.transcribe(VoiceProtocol.OPENAI_AUDIO, endpoint(), AsrRequest("m", wav)) }
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").body("").build())
        expectError(LlmError.UNKNOWN) { api.synthesize(VoiceProtocol.OPENAI_AUDIO, endpoint(), TtsRequest("m", "x", "")) }
        // 出错时返回 200 + JSON 说明
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "application/json").body("""{"message":"quota"}""").build())
        expectError(LlmError.UNKNOWN) { api.synthesize(VoiceProtocol.OPENAI_AUDIO, endpoint(), TtsRequest("m", "x", "")) }
        server.enqueue(MockResponse.Builder().body("not json").build())
        expectError(LlmError.UNKNOWN) { api.transcribe(VoiceProtocol.CHAT_AUDIO, endpoint(), AsrRequest("m", wav)) }
    }

    @Test
    fun slowServer_timesOutAsNetworkError() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(3, TimeUnit.SECONDS).body("""{"text":"晚了"}""").build())
        expectError(LlmError.NETWORK) { api.transcribe(VoiceProtocol.OPENAI_AUDIO, endpoint(), AsrRequest("m", wav)) }
    }

    @Test
    fun invalidKeyCharacters_failBeforeSending() = runBlocking {
        val bad = LlmEndpoint(server.url("/v1").toString(), "sk－全角")
        expectError(LlmError.AUTH) { api.synthesize(VoiceProtocol.CHAT_AUDIO, bad, TtsRequest("m", "x", "")) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun redirect_isNotFollowed() = runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/elsewhere").toString()).build())
        expectError(LlmError.UNKNOWN) { api.transcribe(VoiceProtocol.OPENAI_AUDIO, endpoint(), AsrRequest("m", wav)) }
        assertEquals("Key 不会跟着重定向发到别的地址", 1, server.requestCount)
    }

    @Test
    fun audioFormats_detectByHeader() {
        assertEquals("wav", AudioFormats.extension(WavFormat.wrap(ByteArray(4))))
        assertEquals("mp3", AudioFormats.extension(mp3))
        assertEquals("mp3", AudioFormats.extension(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0, 0)))
        assertEquals("ogg", AudioFormats.extension("OggS....".toByteArray()))
        assertEquals("m4a", AudioFormats.extension("....ftypM4A ".toByteArray()))
    }

    @Test
    fun wavFormat_header16kMono16bit_andDuration() {
        val header = WavFormat.header(32_000)
        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals(16_000, java.nio.ByteBuffer.wrap(header, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(1, java.nio.ByteBuffer.wrap(header, 22, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt())
        assertEquals(1_000L, WavFormat.durationMillis(44 + 32_000L))
        assertTrue(WavFormat.isWav(WavFormat.wrap(ByteArray(2))))
    }

    // ---------- 0.7.2 各家服务商的格式（按 2026-11-12 的官方文档） ----------

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun volcTts_apiKeyHeaderNotBearer_resourceIdFromModel_concatsChunks_contextTexts() = runBlocking {
        val first = byteArrayOf(0x49, 0x44, 0x33, 1)
        val second = byteArrayOf(2, 3, 4)
        // 分块返回：两段音频（中间夹着一段没有音频的字幕），最后 20000000 结束；第二段和第一段之间没有换行
        server.enqueue(
            MockResponse.Builder().body(
                """{"code":0,"message":"","data":"${b64(first)}"}{"code":0,"data":null,"sentence":{"text":"你好"}}
                |{"code":0,"message":"","data":"${b64(second)}"}
                |{"code":20000000,"message":"ok","data":null}""".trimMargin(),
            ).build(),
        )
        val audio = api.synthesize(VoiceProtocol.VOLC_TTS, endpoint(), TtsRequest("seed-tts-2.0", "你好", "zh_female_vv_uranus_bigtts", "开心一点"))
        assertArrayEquals(first + second, audio.bytes)
        val recorded = server.takeRequest()
        assertEquals("/v1/tts/unidirectional", recorded.url.encodedPath)
        assertEquals("sk-test", recorded.headers["X-Api-Key"])
        assertEquals("火山不用 Bearer", null, recorded.headers["Authorization"])
        assertEquals("seed-tts-2.0", recorded.headers["X-Api-Resource-Id"])
        assertTrue(recorded.headers["X-Api-Request-Id"]!!.length >= 32)
        val params = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject["req_params"]!!.jsonObject
        assertEquals("你好", params["text"]!!.jsonPrimitive.content)
        assertEquals("zh_female_vv_uranus_bigtts", params["speaker"]!!.jsonPrimitive.content)
        assertEquals("mp3", params["audio_params"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        val additions = json.parseToJsonElement(params["additions"]!!.jsonPrimitive.content).jsonObject
        assertEquals("开心一点", additions["context_texts"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun volcTts_errorCodeInChunk_mapsToConfiguration_andNoStyleMeansNoAdditions() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"code":45000000,"message":"speaker permission denied","data":null}""").build())
        expectError(LlmError.CONFIGURATION) { api.synthesize(VoiceProtocol.VOLC_TTS, endpoint(), TtsRequest("seed-tts-2.0", "你好", "x")) }
        assertFalse(VoiceApi.volcTtsBody(TtsRequest("m", "t", "v", " ")).getValue("req_params").jsonObject.containsKey("additions"))
    }

    @Test
    fun volcAsr_headersAndBase64Body_readsTextAndDurationFromStatusHeader() = runBlocking {
        server.enqueue(
            MockResponse.Builder().addHeader("X-Api-Status-Code", "20000000")
                .body("""{"audio_info":{"duration":2500},"result":{"text":"  你好呀 ","utterances":[]}}""").build(),
        )
        val result = api.transcribe(VoiceProtocol.VOLC_ASR, endpoint(), AsrRequest("volc.bigasr.auc_turbo", wav))
        assertEquals("你好呀", result.text)
        assertEquals(2.5, result.seconds!!, 0.001)
        val recorded = server.takeRequest()
        assertEquals("/v1/auc/bigmodel/recognize/flash", recorded.url.encodedPath)
        assertEquals("sk-test", recorded.headers["X-Api-Key"])
        assertEquals("volc.bigasr.auc_turbo", recorded.headers["X-Api-Resource-Id"])
        assertEquals("-1", recorded.headers["X-Api-Sequence"])
        val body = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertArrayEquals(wav, Base64.getDecoder().decode(body["audio"]!!.jsonObject["data"]!!.jsonPrimitive.content))
        assertEquals("bigmodel", body["request"]!!.jsonObject["model_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun volcAsr_silenceIsEmpty_andServerBusyIsServerError() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("X-Api-Status-Code", "20000003").body("{}").build())
        expectError(LlmError.UNKNOWN) { api.transcribe(VoiceProtocol.VOLC_ASR, endpoint(), AsrRequest("r", wav)) }
        server.enqueue(MockResponse.Builder().addHeader("X-Api-Status-Code", "55000031").body("{}").build())
        expectError(LlmError.SERVER) { api.transcribe(VoiceProtocol.VOLC_ASR, endpoint(), AsrRequest("r", wav)) }
    }

    @Test
    fun qwenTts_sseHeader_instruction_concatsAudioChunks_ignoresFinalUrl() = runBlocking {
        val first = byteArrayOf(0x49, 0x44, 0x33, 9)
        val second = byteArrayOf(8, 7)
        server.enqueue(
            MockResponse.Builder().addHeader("Content-Type", "text/event-stream").body(
                """id:1
                |event:result
                |data:{"output":{"finish_reason":"null","audio":{"data":"${b64(first)}","url":""}},"request_id":"r"}
                |
                |id:2
                |data:{"output":{"finish_reason":"null","audio":{"data":"${b64(second)}","url":""}},"request_id":"r"}
                |
                |id:3
                |data:{"output":{"finish_reason":"stop","audio":{"data":"","url":"https://elsewhere.example/a.mp3"}},"usage":{"characters":2},"request_id":"r"}
                |""".trimMargin(),
            ).build(),
        )
        val audio = api.synthesize(VoiceProtocol.QWEN_TTS, endpoint(), TtsRequest("qwen-audio-3.1-tts-flash", "你好", "longanhuan_v3.1", "用撒娇的语气"))
        assertArrayEquals(first + second, audio.bytes)
        val recorded = server.takeRequest()
        assertEquals("/v1/services/audio/tts/SpeechSynthesizer", recorded.url.encodedPath)
        assertEquals("enable", recorded.headers["X-DashScope-SSE"])
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        val input = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject["input"]!!.jsonObject
        assertEquals("longanhuan_v3.1", input["voice"]!!.jsonPrimitive.content)
        assertEquals("mp3", input["format"]!!.jsonPrimitive.content)
        assertEquals("用撒娇的语气", input["instruction"]!!.jsonPrimitive.content)
        assertEquals("只请求一次，不去下载别的域名上的链接", 1, server.requestCount)
    }

    @Test
    fun qwenTts_errorEvent_isMapped() = runBlocking {
        server.enqueue(MockResponse.Builder().body("data:{\"code\":\"InvalidParameter\",\"message\":\"voice not found\",\"request_id\":\"r\"}\n\n").build())
        expectError(LlmError.CONFIGURATION) { api.synthesize(VoiceProtocol.QWEN_TTS, endpoint(), TtsRequest("m", "t", "v")) }
        assertFalse(VoiceApi.qwenTtsBody(TtsRequest("m", "t", "v")).getValue("input").jsonObject.containsKey("instruction"))
    }

    @Test
    fun qwenAsr_autoLanguageIsOmitted_itnOn_readsContentAndSeconds() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"choices":[{"message":{"role":"assistant","content":"在吗"}}],"usage":{"seconds":3}}""").build())
        val result = api.transcribe(VoiceProtocol.QWEN_ASR, endpoint(), AsrRequest("qwen3-asr-flash", wav))
        assertEquals("在吗", result.text)
        assertEquals(3.0, result.seconds!!, 0.0)
        val (path, body) = sent()
        assertEquals("/v1/chat/completions", path)
        val options = body["asr_options"]!!.jsonObject
        assertFalse("语种不确定时不发 language", options.containsKey("language"))
        assertEquals("true", options["enable_itn"]!!.jsonPrimitive.content)
        val audio = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("input_audio", audio["type"]!!.jsonPrimitive.content)
        assertTrue(audio["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content.startsWith("data:audio/wav;base64,"))
    }

    @Test
    fun miniMaxTts_hexAudio_voiceSetting_andBaseRespErrors() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"data":{"audio":"4944330102ff","status":2},"base_resp":{"status_code":0,"status_msg":"success"}}""").build())
        val audio = api.synthesize(VoiceProtocol.MINIMAX_TTS, endpoint(), TtsRequest("speech-2.8-hd", "你好", "male-qn-qingse"))
        assertArrayEquals(byteArrayOf(0x49, 0x44, 0x33, 1, 2, 0xFF.toByte()), audio.bytes)
        val (path, body) = sent()
        assertEquals("/v1/t2a_v2", path)
        assertEquals("male-qn-qingse", body["voice_setting"]!!.jsonObject["voice_id"]!!.jsonPrimitive.content)
        assertEquals("mp3", body["audio_setting"]!!.jsonObject["format"]!!.jsonPrimitive.content)
        assertEquals("hex", body["output_format"]!!.jsonPrimitive.content)
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)

        server.enqueue(MockResponse.Builder().body("""{"base_resp":{"status_code":1004,"status_msg":"auth failed"}}""").build())
        expectError(LlmError.AUTH) { api.synthesize(VoiceProtocol.MINIMAX_TTS, endpoint(), TtsRequest("m", "t", "v")) }
        server.enqueue(MockResponse.Builder().body("""{"base_resp":{"status_code":1008,"status_msg":"insufficient balance"}}""").build())
        expectError(LlmError.BALANCE) { api.synthesize(VoiceProtocol.MINIMAX_TTS, endpoint(), TtsRequest("m", "t", "v")) }
    }

    @Test
    fun fishTts_modelHeader_referenceId_binaryAudio() = runBlocking {
        server.enqueue(MockResponse.Builder().addHeader("Content-Type", "audio/mpeg").body(Buffer().write(mp3)).build())
        val audio = api.synthesize(VoiceProtocol.FISH_TTS, endpoint(), TtsRequest("s2.1-pro", "你好", "abc123"))
        assertArrayEquals(mp3, audio.bytes)
        val recorded = server.takeRequest()
        assertEquals("/v1/v1/tts", recorded.url.encodedPath)
        assertEquals("s2.1-pro", recorded.headers["model"])
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        val body = json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("abc123", body["reference_id"]!!.jsonPrimitive.content)
        assertEquals("mp3", body["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun cosyVoiceStyle_isWrittenBeforeText_withEndOfPromptToken() {
        val body = VoiceApi.openAiTtsBody(TtsRequest("FunAudioLLM/CosyVoice2-0.5B", "今天好开心", "FunAudioLLM/CosyVoice2-0.5B:anna", "用开心的语气说"))
        assertEquals("用开心的语气说<|endofprompt|>今天好开心", body["input"]!!.jsonPrimitive.content)
        assertTrue(VoiceProtocol.supportsStyle(VoiceProtocol.OPENAI_AUDIO, "FunAudioLLM/CosyVoice2-0.5B"))
        assertFalse(VoiceProtocol.supportsStyle(VoiceProtocol.OPENAI_AUDIO, "tts-1"))
        assertFalse(VoiceProtocol.supportsStyle(VoiceProtocol.MINIMAX_TTS, "speech-2.8-hd"))
        assertFalse(VoiceProtocol.supportsStyle(VoiceProtocol.FISH_TTS, "s2.1-pro"))
        assertTrue(VoiceProtocol.supportsStyle(VoiceProtocol.VOLC_TTS, "seed-tts-2.0"))
        assertTrue(VoiceProtocol.supportsStyle(VoiceProtocol.QWEN_TTS, "qwen-audio-3.1-tts-flash"))
    }

    @Test
    fun jsonChunks_andHex_helpers() {
        assertEquals(listOf("""{"a":"}{"}""", """{"b":{"c":1}}"""), JsonChunks.split("""{"a":"}{"}  {"b":{"c":1}}"""))
        assertEquals(listOf("""{"s":"say \"hi\" {"}"""), JsonChunks.split("""{"s":"say \"hi\" {"}"""))
        assertArrayEquals(byteArrayOf(0, 0x7F, 0x80.toByte(), 0xFF.toByte()), Hex.decode("007f80FF"))
        try {
            Hex.decode("0g")
            fail("不是十六进制")
        } catch (_: IllegalArgumentException) {
        }
    }
}
