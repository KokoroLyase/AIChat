package isomeria.aichat.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import isomeria.aichat.data.llm.GenericOpenAiProvider
import isomeria.aichat.data.llm.LlmEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 回归：列表异步返回 headers 后，response.body.string() 也必须在 IO 线程。 */
@RunWith(AndroidJUnit4::class)
class ModelListDeviceTest {
    @Test fun listModelsFromMainDispatcher_readsBodyOffMain(): Unit = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val response = executor.submit {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) { /* consume headers only; never log */ }
                        val body = """{"data":[{"id":"local-test"}]}""".toByteArray()
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        output.flush()
                        Thread.sleep(100) // headers 先返回，迫使客户端随后读取正文
                        output.write(body)
                        output.flush()
                    }
                }
                val models = withContext(Dispatchers.Main) {
                    GenericOpenAiProvider(OkHttpClient(), Json { ignoreUnknownKeys = true })
                        .listModels(LlmEndpoint("http://127.0.0.1:${server.localPort}", "fake-local-only"))
                }
                assertEquals("local-test", models.single().id)
                response.get(5, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
