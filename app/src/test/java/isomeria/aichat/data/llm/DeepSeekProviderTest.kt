package isomeria.aichat.data.llm

import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DeepSeekProviderTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val provider = DeepSeekProvider(OkHttpClient(), json)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun endpoint() = LlmEndpoint(server.url("/").toString().trimEnd('/'), "sk-test")

    private fun request(thinking: ThinkingLevel = ThinkingLevel.OFF, messages: List<ChatMessage> = listOf(ChatMessage(ChatRole.USER, "hi"))) =
        ChatRequest("deepseek-flash", messages, thinking, 1.3f)

    // ---- 请求体字段 ----

    @Test
    fun thinkingOff_sendsDisabledAndTemperature() {
        val body = DeepSeekRequestBuilder.build(request(ThinkingLevel.OFF))
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("1.3", body["temperature"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning_effort"))
        assertEquals(JsonPrimitive(true), body["stream"])
        assertEquals(JsonPrimitive(true), body["stream_options"]!!.jsonObject["include_usage"])
    }

    @Test
    fun thinkingHighAndMax_sendEffort_withoutTemperature() {
        val high = DeepSeekRequestBuilder.build(request(ThinkingLevel.HIGH))
        assertEquals("enabled", high["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", high["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(high.containsKey("temperature"))

        val max = DeepSeekRequestBuilder.build(request(ThinkingLevel.MAX))
        assertEquals("max", max["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun imageMessage_usesContentParts() {
        val body = DeepSeekRequestBuilder.build(
            request(
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "你是小雨"),
                    ChatMessage(ChatRole.USER, listOf(ChatPart.Image("data:image/jpeg;base64,AAA"), ChatPart.Text("这是什么"))),
                ),
            ),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你是小雨", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val parts = messages[1].jsonObject["content"] as JsonArray
        assertEquals("image_url", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AAA", parts[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("text", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("这是什么", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun temperatureIsRoundedToOneDecimal() {
        val body = DeepSeekRequestBuilder.build(ChatRequest("m", emptyList(), ThinkingLevel.OFF, 0.70000005f))
        assertEquals("0.7", body["temperature"]!!.jsonPrimitive.content)
    }

    // ---- 流式接收 ----

    @Test
    fun stream_separatesReasoningAndContent_andReadsUsage() = runBlocking {
        val sse = buildString {
            append(": keep-alive\n\n")
            append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"reasoning_content\":\"想一想\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"呀\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":5,\"total_tokens\":17}}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sse)
                .build(),
        )
        val events = provider.streamChat(endpoint(), request()).toList()
        assertEquals(
            listOf(
                ChatEvent.Reasoning("想一想"),
                ChatEvent.Content("你好"),
                ChatEvent.Content("呀"),
                ChatEvent.Finished("stop"),
                ChatEvent.Usage(12, 5),
            ),
            events,
        )
        val recorded = server.takeRequest()
        assertEquals("/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer sk-test", recorded.headers["Authorization"])
        val sent = json.parseToJsonElement(recorded.body!!.utf8()) as JsonObject
        assertEquals("deepseek-flash", sent["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun httpErrors_mapToLlmErrors() = runBlocking {
        val cases = listOf(
            401 to LlmError.AUTH,
            402 to LlmError.BALANCE,
            429 to LlmError.RATE_LIMIT,
            400 to LlmError.BAD_REQUEST,
            422 to LlmError.BAD_REQUEST,
            500 to LlmError.SERVER,
            503 to LlmError.SERVER,
        )
        for ((code, expected) in cases) {
            server.enqueue(MockResponse.Builder().code(code).body("{\"error\":{}}").build())
            try {
                provider.streamChat(endpoint(), request()).toList()
                fail("HTTP $code 应该抛出异常")
            } catch (e: LlmException) {
                assertEquals("HTTP $code", expected, e.error)
                assertEquals(code, e.httpCode)
            }
        }
    }

    @Test
    fun connectionRefused_isNetworkError() = runBlocking {
        val dead = LlmEndpoint("http://127.0.0.1:1", "sk-test")
        try {
            provider.streamChat(dead, request()).toList()
            fail("应该抛出网络异常")
        } catch (e: LlmException) {
            assertEquals(LlmError.NETWORK, e.error)
        }
    }

    // ---- 模型列表 ----

    @Test
    fun listModels_readsImageCapability() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {"object":"list","data":[
                      {"id":"deepseek-flash","object":"model","owned_by":"deepseek","name":"DeepSeek V4.1 Flash",
                       "input_modalities":["text","image"]},
                      {"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek","input_modalities":["text"]},
                      {"id":"future-model","object":"model","owned_by":"deepseek"}
                    ]}
                    """.trimIndent(),
                )
                .build(),
        )
        val models = provider.listModels(endpoint())
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro", "future-model"), models.map { it.id })
        assertEquals("DeepSeek V4.1 Flash", models[0].name)
        assertEquals(true, models[0].supportsImage)
        assertEquals(false, models[1].supportsImage)
        assertEquals(null, models[2].supportsImage)
        val recorded = server.takeRequest()
        assertEquals("/models", recorded.url.encodedPath)
        assertTrue(recorded.headers["Authorization"]!!.startsWith("Bearer "))
    }

    @Test
    fun listModels_authFailure() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())
        try {
            provider.listModels(endpoint())
            fail("应该抛出认证错误")
        } catch (e: LlmException) {
            assertEquals(LlmError.AUTH, e.error)
        }
    }
}
