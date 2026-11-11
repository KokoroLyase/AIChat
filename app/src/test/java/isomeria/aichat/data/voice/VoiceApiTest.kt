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
}
