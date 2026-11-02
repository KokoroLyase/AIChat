package isomeria.aichat.update

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class UpdateCheckerTest {
    private class FakePrefs(
        override var autoCheckUpdate: Boolean = true,
        override var lastUpdateCheck: Long = 0,
        override var ignoredVersion: String? = null,
    ) : UpdatePrefs

    private class FakeSource(var release: ReleaseInfo? = release("v0.3.0"), var fail: Boolean = false) : UpdateSource {
        var calls = 0
        override suspend fun latest(): ReleaseInfo {
            calls++
            if (fail) throw IOException("offline")
            return release!!
        }
    }

    private var now = 100L * DAY

    private fun checker(source: UpdateSource, prefs: UpdatePrefs, current: String = "0.2.0") =
        UpdateChecker(source, prefs, current) { now }

    @Test
    fun autoCheck_newerVersion_showsDialogAndRecordsTime() = runBlocking {
        val prefs = FakePrefs()
        val checker = checker(FakeSource(), prefs)
        checker.autoCheck()
        assertEquals("0.3.0", checker.pending.value?.version)
        assertEquals("0.3.0", checker.newer.value?.version)
        assertEquals(now, prefs.lastUpdateCheck)
    }

    @Test
    fun autoCheck_atMostOncePer24Hours() = runBlocking {
        val source = FakeSource()
        val prefs = FakePrefs(lastUpdateCheck = now - DAY + 1)
        val checker = checker(source, prefs)
        checker.autoCheck()
        assertEquals(0, source.calls)

        now += 1 // 正好过了 24 小时
        checker.autoCheck()
        assertEquals(1, source.calls)
    }

    @Test
    fun autoCheck_clockMovedBackwards_stillChecks() = runBlocking {
        val source = FakeSource()
        checker(source, FakePrefs(lastUpdateCheck = now + DAY)).autoCheck()
        assertEquals(1, source.calls)
    }

    @Test
    fun autoCheck_disabled_doesNothing() = runBlocking {
        val source = FakeSource()
        checker(source, FakePrefs(autoCheckUpdate = false)).autoCheck()
        assertEquals(0, source.calls)
    }

    @Test
    fun autoCheck_failure_isSilent() = runBlocking {
        val prefs = FakePrefs()
        val checker = checker(FakeSource(fail = true), prefs)
        checker.autoCheck()
        assertNull(checker.pending.value)
        assertEquals(now, prefs.lastUpdateCheck)
    }

    @Test
    fun autoCheck_ignoredVersion_noDialog_butNewerVersionShowsAgain() = runBlocking {
        val source = FakeSource()
        val prefs = FakePrefs(ignoredVersion = "0.3.0")
        val checker = checker(source, prefs)
        checker.autoCheck()
        assertNull(checker.pending.value)
        assertEquals("0.3.0", checker.newer.value?.version)

        source.release = release("v0.3.1")
        now += DAY
        checker.autoCheck()
        assertEquals("0.3.1", checker.pending.value?.version)
    }

    @Test
    fun autoCheck_sameOrOlderVersion_noDialog() = runBlocking {
        val checker = checker(FakeSource(release("v0.2.0")), FakePrefs())
        checker.autoCheck()
        assertNull(checker.pending.value)
        assertNull(checker.newer.value)
    }

    @Test
    fun manualCheck_showsIgnoredVersion_andReportsResults() = runBlocking {
        val source = FakeSource()
        val prefs = FakePrefs(ignoredVersion = "0.3.0", lastUpdateCheck = now)
        val checker = checker(source, prefs)
        val result = checker.manualCheck()
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("0.3.0", checker.pending.value?.version)

        source.release = release("v0.2.0")
        assertEquals(UpdateCheckResult.UpToDate, checker.manualCheck())

        source.fail = true
        assertTrue(checker.manualCheck() is UpdateCheckResult.Failed)
    }

    @Test
    fun laterAndIgnore_clearDialog() = runBlocking {
        val prefs = FakePrefs()
        val checker = checker(FakeSource(), prefs)
        checker.autoCheck()
        checker.later()
        assertNull(checker.pending.value)
        assertNull(prefs.ignoredVersion)

        checker.manualCheck()
        checker.ignore(checker.pending.value!!)
        assertNull(checker.pending.value)
        assertEquals("0.3.0", prefs.ignoredVersion)
    }

    @Test
    fun debugBuildVersion_comparesWithoutSuffix() = runBlocking {
        val checker = checker(FakeSource(release("v0.2.0")), FakePrefs(), current = "0.2.0-debug")
        assertEquals(UpdateCheckResult.UpToDate, checker.manualCheck())
    }

    // ---- GitHub 接口 ----

    @Test
    fun gitHubSource_parsesLatestRelease() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .body(
                        """
                        {"tag_name":"v0.2.0","name":"智信 0.2.0","draft":false,"prerelease":false,
                         "html_url":"https://github.com/KokoroLyase/AIChat/releases/tag/v0.2.0",
                         "body":"## 新功能\n- **导出**与恢复\n- 检查更新",
                         "assets":[{"name":"AIChat-0.2.0.apk.sha256","browser_download_url":"https://x/a.sha256"},
                                   {"name":"AIChat-0.2.0.apk","browser_download_url":"https://x/a.apk"}],
                         "extra":1}
                        """.trimIndent(),
                    )
                    .build(),
            )
            val source = GitHubUpdateSource(OkHttpClient(), Json { ignoreUnknownKeys = true }, "KokoroLyase/AIChat", server.url("/"))
            val release = source.latest()
            assertEquals("0.2.0", release.version)
            assertEquals("v0.2.0", release.tag)
            assertEquals("新功能\n• 导出与恢复\n• 检查更新", release.notes)
            assertEquals("https://github.com/KokoroLyase/AIChat/releases/tag/v0.2.0", release.pageUrl)
            assertEquals("https://x/a.apk", release.apkUrl)

            val request = server.takeRequest()
            assertEquals("/repos/KokoroLyase/AIChat/releases/latest", request.url.encodedPath)
            assertEquals("application/vnd.github+json", request.headers["Accept"])
            assertNull(request.headers["Authorization"])
        }
    }

    @Test
    fun gitHubSource_untrustedPageUrl_fallsBackToGitHub() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .body("""{"tag_name":"v0.2.0","html_url":"https://evil.example/x","assets":[]}""")
                    .build(),
            )
            val source = GitHubUpdateSource(OkHttpClient(), Json { ignoreUnknownKeys = true }, "KokoroLyase/AIChat", server.url("/"))
            assertEquals("https://github.com/KokoroLyase/AIChat/releases/tag/v0.2.0", source.latest().pageUrl)
        }
    }

    @Test
    fun gitHubSource_errorsThrow() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(404).body("{}").build())
            server.enqueue(MockResponse.Builder().body("""{"tag_name":"v0.3.0-beta.1","prerelease":true}""").build())
            val source = GitHubUpdateSource(OkHttpClient(), Json { ignoreUnknownKeys = true }, "KokoroLyase/AIChat", server.url("/"))
            repeat(2) {
                try {
                    source.latest()
                    fail("应该抛出异常")
                } catch (_: IOException) {
                }
            }
        }
    }

    private companion object {
        const val DAY = UpdateChecker.CHECK_INTERVAL_MS

        fun release(tag: String) = ReleaseInfo(tag.removePrefix("v"), tag, "", "https://github.com/x", null)
    }
}
