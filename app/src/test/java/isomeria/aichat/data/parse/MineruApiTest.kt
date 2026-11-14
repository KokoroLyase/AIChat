package isomeria.aichat.data.parse

import isomeria.aichat.data.llm.LlmEndpoint
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * MinerU 精准解析（0.9.2，2026-11-14 按 mineru.net 文档）：申请上传链接 → PUT 上传 → 轮询结果 → 下载 zip 只读 full.md。
 * 只用本机模拟接口；上传和下载地址也指回本机（测试里允许本机的 http）。
 */
class MineruApiTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }
    private val sleeps = mutableListOf<Long>()
    private fun api(allowLocalHttp: Boolean = true, maxWait: Long = 9_000) =
        MineruApi(OkHttpClient(), json, pollMillis = 3_000, maxWaitMillis = maxWait, allowLocalHttp = allowLocalHttp, sleep = { sleeps += it })

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun endpoint(token: String = "mineru-token") = LlmEndpoint(server.url("/api/v4").toString(), token)

    private fun file(name: String = "report.pdf", bytes: ByteArray = "%PDF-1.4 假的".toByteArray()) = File(folder.root, name).apply { writeBytes(bytes) }

    private fun ok(data: String) = MockResponse.Builder().body("""{"code":0,"msg":"ok","data":$data}""").build()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun enqueueApply() {
        server.enqueue(ok("""{"batch_id":"batch-1","file_urls":["${server.url("/upload/abc?sig=1")}"]}"""))
        server.enqueue(MockResponse.Builder().code(200).build())
    }

    private fun state(state: String, extra: String = "") =
        ok("""{"batch_id":"batch-1","extract_result":[{"file_name":"report.pdf","state":"$state"$extra}]}""")

    @Test
    fun parse_fullFlow_readsOnlyFullMd_imagesReplaced() = runBlocking {
        enqueueApply()
        server.enqueue(state("waiting-file"))
        server.enqueue(state("running", ""","extract_progress":{"extracted_pages":1,"total_pages":3}"""))
        server.enqueue(state("done", ""","full_zip_url":"${server.url("/cdn/result.zip")}""""))
        val markdown = "# 标题\r\n\r\n\r\n\r\n正文 ![](images/a.jpg) 结尾\n<img src=\"images/b.png\">"
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(zip("layout.json" to "{}", "images/a.jpg" to "x", "full.md" to markdown, "sub/full.md" to "更深的不要"))).build())

        val text = api().parse(endpoint(), file(), "周报.pdf", "[图片]")
        assertEquals("# 标题\n\n正文 [图片] 结尾\n[图片]", text)

        val apply = server.takeRequest()
        assertEquals("POST", apply.method)
        assertEquals("/api/v4/file-urls/batch", apply.url.encodedPath)
        assertEquals("Bearer mineru-token", apply.headers["Authorization"])
        val body = json.parseToJsonElement(apply.body!!.utf8()).jsonObject
        assertEquals("vlm", body["model_version"]!!.jsonPrimitive.content)
        val entry = body["files"]!!.jsonArray.single().jsonObject
        assertEquals("周报.pdf", entry["name"]!!.jsonPrimitive.content)
        assertTrue(entry["data_id"]!!.jsonPrimitive.content.isNotBlank())

        // 上传：PUT 原文件，不带 Token，也不带 Content-Type
        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertEquals("/upload/abc", upload.url.encodedPath)
        assertNull(upload.headers["Authorization"])
        assertNull(upload.headers["Content-Type"])
        assertEquals("%PDF-1.4 假的", upload.body!!.utf8())

        repeat(3) {
            val poll = server.takeRequest()
            assertEquals("/api/v4/extract-results/batch/batch-1", poll.url.encodedPath)
            assertEquals("Bearer mineru-token", poll.headers["Authorization"])
        }
        val download = server.takeRequest()
        assertEquals("/cdn/result.zip", download.url.encodedPath)
        assertNull(download.headers["Authorization"])
        // 每 3 秒查一次：查了三次，中间等了两次
        assertEquals(listOf(3_000L, 3_000L), sleeps)
    }

    @Test
    fun parse_tokenErrors_areAuth() = runBlocking {
        for (code in listOf("A0202", "A0211")) {
            server.enqueue(MockResponse.Builder().body("""{"code":"$code","msg":"token error"}""").build())
            assertError(ParseError.AUTH) { api().parse(endpoint(), file(), "a.pdf", "[图片]") }
        }
        server.enqueue(MockResponse.Builder().code(401).body("""{"msg":"unauthorized"}""").build())
        assertError(ParseError.AUTH) { api().parse(endpoint(), file(), "a.pdf", "[图片]") }
    }

    @Test
    fun parse_fileErrorCodes_mapToReasons() = runBlocking {
        val cases = mapOf("-60005" to ParseError.TOO_LARGE, "-60006" to ParseError.TOO_LARGE, "-60018" to ParseError.QUOTA, "-60010" to ParseError.FAILED)
        for ((code, error) in cases) {
            server.enqueue(MockResponse.Builder().body("""{"code":$code,"msg":"x"}""").build())
            assertError(error) { api().parse(endpoint(), file(), "a.pdf", "[图片]") }
        }
    }

    @Test
    fun parse_failedState_andTimeout() = runBlocking {
        enqueueApply()
        server.enqueue(state("failed", ""","err_msg":"文件损坏""""))
        assertError(ParseError.FAILED) { api().parse(endpoint(), file(), "a.pdf", "[图片]") }

        sleeps.clear()
        enqueueApply()
        repeat(4) { server.enqueue(state("pending")) }
        assertError(ParseError.TIMEOUT) { api(maxWait = 9_000).parse(endpoint(), file(), "a.pdf", "[图片]") }
        assertEquals(listOf(3_000L, 3_000L, 3_000L), sleeps)
    }

    @Test
    fun parse_checksBeforeUpload_unsupportedTooLargeMissing() = runBlocking {
        assertError(ParseError.UNSUPPORTED) { api().parse(endpoint(), file("a.txt"), "a.txt", "[图片]") }
        assertError(ParseError.FAILED) { api().parse(endpoint(), File(folder.root, "gone.pdf"), "gone.pdf", "[图片]") }
        assertEquals("没有发出任何请求", 0, server.requestCount)
        assertTrue(MineruFormats.supports("扫描.JPG"))
        assertTrue(MineruFormats.supports("表.xlsx"))
        assertFalse(MineruFormats.supports("说明"))
        assertFalse(MineruFormats.supports("a.zip"))
    }

    @Test
    fun parse_rejectsHttpLinks_whenNotDebug() = runBlocking {
        enqueueApply()
        assertError(ParseError.FAILED) { api(allowLocalHttp = false).parse(endpoint(), file(), "a.pdf", "[图片]") }
        // 只申请了上传链接，没有往 http 地址上传
        assertEquals(1, server.requestCount)
    }

    @Test
    fun parse_zipWithoutFullMd_fails() = runBlocking {
        enqueueApply()
        server.enqueue(state("done", ""","full_zip_url":"${server.url("/cdn/r.zip")}""""))
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(zip("content_list.json" to "[]", "../full.md.bak" to "x"))).build())
        assertError(ParseError.FAILED) { api().parse(endpoint(), file(), "a.pdf", "[图片]") }
    }

    @Test
    fun readFullMarkdown_takesShallowest_andIgnoresTraversalNames() {
        val bytes = zip("deep/inner/full.md" to "深", "../../full.md" to "穿越", "x/full.md" to "浅")
        // “../../full.md”只是个名字，不写磁盘；按斜杠数算深度，取最浅的“x/full.md”（1 层）而不是 2 层的
        assertEquals("浅", MineruApi.readFullMarkdown(bytes.inputStream()))
        assertEquals("根", MineruApi.readFullMarkdown(zip("full.md" to "根", "a/full.md" to "子").inputStream()))
    }

    @Test
    fun test_notFoundTaskMeansTokenOk_tokenErrorsAreAuth() = runBlocking {
        server.enqueue(MockResponse.Builder().body("""{"code":-60012,"msg":"task not found"}""").build())
        api().test(endpoint())
        val recorded = server.takeRequest()
        assertTrue(recorded.url.encodedPath.startsWith("/api/v4/extract-results/batch/"))
        assertEquals("Bearer mineru-token", recorded.headers["Authorization"])

        server.enqueue(MockResponse.Builder().body("""{"code":"A0211","msg":"token expired"}""").build())
        assertError(ParseError.AUTH) { api().test(endpoint()) }
        // Token 里有请求头放不下的字符：不发请求，按 Token 不对
        val before = server.requestCount
        assertError(ParseError.AUTH) { api().test(endpoint("中文token")) }
        assertEquals(before, server.requestCount)
    }

    @Test
    fun markdownClean_onlyTouchesImages() {
        assertEquals("a [图] b\n\nc", MineruMarkdown.clean("  a ![图注](images/x.png) b\n\n\n\nc  ", "[图]"))
        assertEquals("[链接](http://x)", MineruMarkdown.clean("[链接](http://x)", "[图]"))
    }

    private suspend fun assertError(expected: ParseError, block: suspend () -> Unit) {
        try {
            block()
            fail("应该失败：$expected")
        } catch (e: ParseException) {
            assertEquals(expected, e.error)
        }
    }
}
