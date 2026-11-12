package isomeria.aichat.data.llm

import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * 智谱的错误码（0.7.2）：余额不足、限流都是 HTTP 429，要看返回内容里的 error.code；聊天接口和语音共用。
 * 别家的错误内容（OpenAI 的字符串 code、OpenRouter 的数字状态码）不受影响。
 */
class ZhipuErrorsTest {
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val provider = GenericOpenAiProvider(OkHttpClient(), json)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun endpoint() = LlmEndpoint(server.url("/v1").toString(), "sk-test")

    @Test
    fun codesInTheBody_decideTheError() {
        assertEquals(LlmError.BALANCE, ZhipuErrors.of(429, """{"error":{"code":"1113","message":"余额不足或无可用资源包,请充值。"}}""").error)
        assertEquals(LlmError.BALANCE, ZhipuErrors.of(429, """{"error":{"code":1308}}""").error)
        assertEquals(LlmError.RATE_LIMIT, ZhipuErrors.of(429, """{"error":{"code":"1302"}}""").error)
        assertEquals(LlmError.AUTH, ZhipuErrors.of(401, """{"error":{"code":"1000"}}""").error)
        assertEquals(LlmError.NOT_FOUND, ZhipuErrors.of(400, """{"error":{"code":"1211"}}""").error)
        assertEquals(429, ZhipuErrors.of(429, """{"error":{"code":"1113"}}""").httpCode)
    }

    @Test
    fun otherBodies_fallBackToTheStatus() {
        assertEquals(LlmError.RATE_LIMIT, ZhipuErrors.of(429, """{"error":{"code":"insufficient_quota"}}""").error)
        assertEquals(LlmError.RATE_LIMIT, ZhipuErrors.of(429, """{"error":{"code":429,"message":"x"}}""").error)
        assertEquals(LlmError.RATE_LIMIT, ZhipuErrors.of(429, """{"error":{"code":"1999"}}""").error)
        assertEquals(LlmError.SERVER, ZhipuErrors.of(502, "<html>bad gateway</html>").error)
        assertEquals(LlmError.BAD_REQUEST, ZhipuErrors.of(400, "").error)
    }

    @Test
    fun chatStream_zhipuBalanceIsNotRateLimit() = runBlocking {
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":{"code":"1113","message":"x"}}""").build())
        try {
            provider.streamChat(endpoint(), ChatRequest("glm-5", listOf(ChatMessage(ChatRole.USER, "hi")), ThinkingLevel.OFF, 1f)).toList()
            fail("应该失败")
        } catch (e: LlmException) {
            assertEquals(LlmError.BALANCE, e.error)
            assertEquals(429, e.httpCode)
        }
    }

    @Test
    fun listModels_zhipuAuthCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"code":"1000","message":"x"}}""").build())
        try {
            provider.listModels(endpoint())
            fail("应该失败")
        } catch (e: LlmException) {
            assertEquals(LlmError.AUTH, e.error)
        }
    }
}
