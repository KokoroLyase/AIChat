package isomeria.aichat.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** 应用内更新：下载、SHA-256 校验、核对安装包、失败和取消。 */
class ApkDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var downloader: ApkDownloader
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val apkBytes = ByteArray(200_000) { (it % 251).toByte() }
    private val apkHash = sha256(apkBytes)

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = temp.newFolder("updates")
        downloader = ApkDownloader(OkHttpClient(), dir)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun release(apk: String? = url("/a.apk"), sha: String? = url("/a.apk.sha256")) = ReleaseInfo(
        version = "0.4.0", tag = "v0.4.0", notes = "", pageUrl = "https://github.com/x/y/releases/tag/v0.4.0",
        apkUrl = apk, sha256Url = sha,
    )

    private fun enqueueGood() {
        server.enqueue(MockResponse.Builder().body("$apkHash  AIChat-0.4.0.apk\n").build())
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(apkBytes)).build())
    }

    // ---- 校验文件格式 ----

    @Test
    fun sha256File_parsesSha256sumOutput() {
        val hash = "A".repeat(64)
        assertEquals("a".repeat(64), Sha256File.parse("$hash  AIChat-0.3.0.apk\n"))
        assertEquals("a".repeat(64), Sha256File.parse("$hash\n"))
        assertEquals("a".repeat(64), Sha256File.parse("  $hash"))
    }

    @Test
    fun sha256File_rejectsInvalid() {
        for (bad in listOf("", "not a hash", "a".repeat(63), "a".repeat(65), "g".repeat(64), "<html>404</html>")) {
            assertNull(bad, Sha256File.parse(bad))
        }
    }

    // ---- 下载 ----

    @Test
    fun download_verifiedFileReturned_withProgress() = runBlocking {
        enqueueGood()
        var last = 0L to 0L
        val file = downloader.download(url("/a.apk"), url("/a.apk.sha256")) { done, total -> last = done to total }
        assertArrayEquals(apkBytes, file.readBytes())
        assertEquals(apkBytes.size.toLong() to apkBytes.size.toLong(), last)
        assertFalse("半个文件不留", dir.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun download_checksumMismatch_rejectedAndDeleted() = runBlocking {
        server.enqueue(MockResponse.Builder().body("${"0".repeat(64)}  a.apk\n").build())
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(apkBytes)).build())
        expectFailure(UpdateFailure.CHECKSUM)
        assertTrue("校验不通过的文件必须删掉", dir.listFiles()!!.isEmpty())
    }

    @Test
    fun download_badChecksumFile_rejectedBeforeDownloadingApk() = runBlocking {
        server.enqueue(MockResponse.Builder().body("<html>not found</html>").build())
        expectFailure(UpdateFailure.CHECKSUM)
        assertEquals("不用白下 APK", 1, server.requestCount)
    }

    @Test
    fun download_missingChecksumOrApk_isNetworkFailure() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        expectFailure(UpdateFailure.NETWORK)

        server.enqueue(MockResponse.Builder().body("$apkHash  a.apk\n").build())
        server.enqueue(MockResponse.Builder().code(500).build())
        expectFailure(UpdateFailure.NETWORK)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun download_followsRedirectsLikeGitHub() = runBlocking {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        val redirecting = ApkDownloader(client, dir)
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", url("/cdn/sha")).build())
        server.enqueue(MockResponse.Builder().body("$apkHash  a.apk\n").build())
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", url("/cdn/apk")).build())
        server.enqueue(MockResponse.Builder().body(okio.Buffer().write(apkBytes)).build())
        assertArrayEquals(apkBytes, redirecting.download(url("/a.apk"), url("/a.apk.sha256")) { _, _ -> }.readBytes())
    }

    private suspend fun expectFailure(expected: UpdateFailure) {
        try {
            downloader.download(url("/a.apk"), url("/a.apk.sha256")) { _, _ -> }
            fail("应该失败：$expected")
        } catch (e: UpdateException) {
            assertEquals(expected, e.failure)
        }
    }

    @Test
    fun cleanup_removesDownloads() {
        File(dir, "update.apk").writeText("x")
        downloader.cleanup()
        assertFalse(dir.exists())
    }

    // ---- 流程 ----

    private fun installer(verify: Boolean = true) = UpdateInstaller(downloader, { verify }, scope)

    private fun finished(installer: UpdateInstaller) = runBlocking {
        withTimeout(10_000) {
            installer.state.first { it is UpdateInstallState.Ready || it is UpdateInstallState.Failed }
        }
    }

    @Test
    fun install_success_becomesReady() {
        enqueueGood()
        val installer = installer()
        installer.start(release())
        val state = finished(installer) as UpdateInstallState.Ready
        assertArrayEquals(apkBytes, state.apk.readBytes())
        installer.reset()
        assertEquals(UpdateInstallState.Idle, installer.state.value)
    }

    @Test
    fun install_noAssets_failsImmediately_withoutRequests() {
        for (release in listOf(release(apk = null), release(sha = null))) {
            val installer = installer()
            installer.start(release)
            assertEquals(UpdateFailure.MISSING_ASSET, (installer.state.value as UpdateInstallState.Failed).failure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun install_verifierRejects_deletesFile() {
        enqueueGood()
        val installer = installer(verify = false)
        installer.start(release())
        val state = finished(installer) as UpdateInstallState.Failed
        assertEquals(UpdateFailure.INVALID_APK, state.failure)
        assertFalse(File(dir, "update.apk").exists())
    }

    @Test
    fun install_downloadFailure_keepsReleaseForBrowserFallback() {
        server.enqueue(MockResponse.Builder().code(503).build())
        val installer = installer()
        val release = release()
        installer.start(release)
        val state = finished(installer) as UpdateInstallState.Failed
        assertEquals(UpdateFailure.NETWORK, state.failure)
        assertEquals(release.pageUrl, state.release.pageUrl)
    }

    @Test
    fun install_cancel_backToIdle_noFileLeft() {
        server.enqueue(MockResponse.Builder().body("$apkHash  a.apk\n").build())
        // APK 请求没有排响应，一直卡在下载中
        val installer = installer()
        installer.start(release())
        assertTrue(installer.state.value is UpdateInstallState.Downloading)
        installer.cancel()
        assertEquals(UpdateInstallState.Idle, installer.state.value)
        assertFalse(File(dir, "update.apk").exists())
    }

    @Test
    fun install_startWhileDownloading_isIgnored() {
        server.enqueue(MockResponse.Builder().body("$apkHash  a.apk\n").build())
        val installer = installer()
        installer.start(release())
        installer.start(release())
        assertTrue(installer.state.value is UpdateInstallState.Downloading)
        installer.cancel()
    }

    // ---- 取版本信息 ----

    @Test
    fun gitHubSource_readsApkAndChecksumAssets() = runBlocking {
        val body = """
            {"tag_name":"v0.4.0","html_url":"https://github.com/KokoroLyase/AIChat/releases/tag/v0.4.0","body":"说明",
             "assets":[
               {"name":"AIChat-0.4.0.apk.sha256","browser_download_url":"https://github.com/d/AIChat-0.4.0.apk.sha256"},
               {"name":"AIChat-0.4.0.apk","browser_download_url":"https://github.com/d/AIChat-0.4.0.apk"}
             ]}
        """.trimIndent()
        server.enqueue(MockResponse.Builder().body(body).build())
        val source = GitHubUpdateSource(OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }, "o/r", server.url("/"))
        val release = source.latest()
        assertEquals("https://github.com/d/AIChat-0.4.0.apk", release.apkUrl)
        assertEquals("https://github.com/d/AIChat-0.4.0.apk.sha256", release.sha256Url)
    }

    @Test
    fun gitHubSource_withoutChecksumAsset_hasNoChecksumUrl() = runBlocking {
        val body = """{"tag_name":"v0.4.0","assets":[{"name":"a.apk","browser_download_url":"https://github.com/d/a.apk"}]}"""
        server.enqueue(MockResponse.Builder().body(body).build())
        val source = GitHubUpdateSource(OkHttpClient(), kotlinx.serialization.json.Json { ignoreUnknownKeys = true }, "o/r", server.url("/"))
        assertNull(source.latest().sha256Url)
    }
}
