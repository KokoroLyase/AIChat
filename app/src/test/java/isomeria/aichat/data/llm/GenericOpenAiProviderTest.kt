package isomeria.aichat.data.llm

import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 其他兼容 OpenAI 格式的服务商：只发通用字段，能力读取兼容不同写法。 */
class GenericOpenAiProviderTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val provider = GenericOpenAiProvider(OkHttpClient(), json)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun endpoint() = LlmEndpoint(server.url("/v1").toString(), "sk-test")

    private fun request(thinking: ThinkingLevel) =
        ChatRequest("some-model", listOf(ChatMessage(ChatRole.USER, "hi")), thinking, 1.3f)

    @Test
    fun thinkingOff_sendsTemperatureOnly_noDeepSeekFields() {
        val body = OpenAiRequestBuilder.build(request(ThinkingLevel.OFF))
        assertEquals("1.3", body["temperature"]!!.jsonPrimitive.content)
        assertFalse("严格校验的服务商会因为多余字段报 400", body.containsKey("thinking"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertEquals("some-model", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun thinkingHighAndMax_bothSendHigh_noTemperature() {
        for (level in listOf(ThinkingLevel.HIGH, ThinkingLevel.MAX)) {
            val body = OpenAiRequestBuilder.build(request(level))
            assertEquals("max 不是通用取值", "high", body["reasoning_effort"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("temperature"))
            assertFalse(body.containsKey("thinking"))
        }
    }

    @Test
    fun deepSeekFormatIsUnchanged() {
        val off = DeepSeekRequestBuilder.build(request(ThinkingLevel.OFF))
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("max", DeepSeekRequestBuilder.build(request(ThinkingLevel.MAX))["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun stream_readsReasoningFromEitherField_andSendsToBaseUrlPath() = runBlocking {
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"reasoning\":\"想\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"一想\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse.Builder().body(sse).build())
        val events = provider.streamChat(endpoint(), request(ThinkingLevel.HIGH)).toList()
        assertEquals(listOf("想", "一想"), events.filterIsInstance<ChatEvent.Reasoning>().map { it.delta })
        assertEquals(listOf("好"), events.filterIsInstance<ChatEvent.Content>().map { it.delta })
        assertEquals(ChatEvent.Usage(3, 1), events.filterIsInstance<ChatEvent.Usage>().single())

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
    }

    @Test
    fun listModels_readsCapabilitiesInDifferentShapes() = runBlocking {
        val body = """
            {"object":"list","data":[
              {"id":"deepseek-style","name":"DS","input_modalities":["text","image"],"effort":{"supported_levels":["high"]}},
              {"id":"openrouter-style","architecture":{"input_modalities":["text"]},"supported_parameters":["temperature","reasoning"]},
              {"id":"openai-style","object":"model","created":1,"owned_by":"x"}
            ]}
        """.trimIndent()
        server.enqueue(MockResponse.Builder().body(body).build())
        val models = provider.listModels(endpoint())
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)

        val deepseek = models[0]
        assertEquals("DS", deepseek.name)
        assertEquals(true, deepseek.supportsImage)
        assertEquals(true, deepseek.supportsThinking)

        val openRouter = models[1]
        assertEquals(false, openRouter.supportsImage)
        assertEquals(true, openRouter.supportsThinking)

        val plain = models[2]
        assertNull(plain.name)
        assertNull("接口没说就是未知，由用户标记", plain.supportsImage)
        assertNull(plain.supportsThinking)
    }

    @Test
    fun notFound_and403_mapToClearErrors() = runBlocking {
        for ((code, expected) in listOf(404 to LlmError.NOT_FOUND, 403 to LlmError.AUTH)) {
            server.enqueue(MockResponse.Builder().code(code).build())
            try {
                provider.listModels(endpoint())
                fail("HTTP $code 应该失败")
            } catch (e: LlmException) {
                assertEquals(expected, e.error)
            }
        }
    }
}
