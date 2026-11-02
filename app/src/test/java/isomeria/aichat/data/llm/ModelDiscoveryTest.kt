package isomeria.aichat.data.llm

import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ModelDiscoveryTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun onlyIdStaysUnknownNonChatIsExplicitAndEffortValuesAreReported() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().body("""{"data":[{"id":"id-only"},{"id":"audio-item","type":"tts"},{"id":"explicit","task":"chat","input_modalities":["text","image"],"effort":{"supported_levels":["low","medium","high","max"]},"temperature":{"min":0.1,"max":1.5}}]}""").build())
            server.start()
            val models = GenericOpenAiProvider(OkHttpClient(), json).listModels(LlmEndpoint(server.url("/v1").toString(), "fake"))
            assertNull(models[0].supportsImage)
            assertNull(models[0].config.chat)
            assertNull(models[0].config.thinking)
            assertNull(models[0].config.temperature)
            assertEquals(false, models[1].config.chat)
            assertEquals(listOf("low", "medium", "high", "max"), models[2].config.thinking!!.efforts.map { it.value })
            assertEquals(ThinkingMode.UNKNOWN, models[2].config.thinking!!.mode) // 强度报告不等于报告可以关闭
            assertEquals(1.5f, models[2].config.temperature!!.normal.max)
        }
    }
    @Test fun emptyIsValidButMalformedBlankOrDuplicateListsFail() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val p = GenericOpenAiProvider(OkHttpClient(), json)
            val endpoint = LlmEndpoint(server.url("/").toString(), "fake")
            server.enqueue(MockResponse.Builder().body("""{"data":[]}""").build())
            assertTrue(p.listModels(endpoint).isEmpty())
            for (bad in listOf("{}", "oops", """{"data":[{"id":""}]}""", """{"data":[{"id":"x"},{"id":"x"}]}""", """{"data":[{"id":9}]}""")) {
                server.enqueue(MockResponse.Builder().body(bad).build())
                try { p.listModels(endpoint); fail("应拒绝异常列表 $bad") } catch (e: LlmException) { assertEquals(LlmError.UNKNOWN, e.error) }
            }
        }
    }
    @Test fun neitherListNorChatFollowRedirectsEvenWithDefaultOkHttp() = runBlocking {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            origin.start(); target.start()
            val p = GenericOpenAiProvider(OkHttpClient(), json)
            val endpoint = LlmEndpoint(origin.url("/").toString(), "fake-secret")
            for (chat in listOf(false, true)) {
                origin.enqueue(MockResponse.Builder().code(307).addHeader("Location", target.url("/capture").toString()).build())
                try {
                    if (chat) p.streamChat(endpoint, ChatRequest("m", emptyList(), ThinkingLevel.OFF, 1f)).toList() else p.listModels(endpoint)
                    fail("重定向必须失败")
                } catch (e: LlmException) { assertEquals(307, e.httpCode) }
            }
            assertNull(target.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }
}
