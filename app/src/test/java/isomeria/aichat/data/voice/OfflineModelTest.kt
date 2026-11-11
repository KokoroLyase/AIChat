package isomeria.aichat.data.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
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
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 0.7.1 离线识别模型的下载：断点续传、服务器不支持续传时从头下、按写死的 SHA-256 校验、只跟随认可的跳转、
 * 中断和校验失败不留坏文件、空间不够不下载、暂停后接着下、删除。用本机模拟服务器，不连外网。
 */
class OfflineModelTest {
    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val tokens = Random(1).nextBytes(3_000)
    private val model = Random(2).nextBytes(1_200_000)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val files get() = listOf(ModelFile("tokens.txt", tokens.size.toLong(), sha(tokens)), ModelFile("model.int8.onnx", model.size.toLong(), sha(model)))

    @Before fun setUp() { server = MockWebServer().apply { start() } }

    @After fun tearDown() = server.close()

    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    private fun store(free: Long = Long.MAX_VALUE, allowHost: (okhttp3.HttpUrl) -> Boolean = { it.host == server.hostName }) = OfflineModelStore(
        temp.root,
        ModelDownloader(client, server.url("/m/").toString(), allowHost, allowHttp = true),
        files,
        freeBytes = { free },
    )

    private fun ok(bytes: ByteArray) = MockResponse.Builder().body(Buffer().write(bytes)).build()

    private fun partial(bytes: ByteArray, from: Int) = MockResponse.Builder().code(206)
        .addHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
        .body(Buffer().write(bytes.copyOfRange(from, bytes.size))).build()

    @Test
    fun fullDownload_verifiesAndRenames_reportsProgress_installed() = runBlocking {
        server.enqueue(ok(tokens))
        server.enqueue(ok(model))
        val s = store()
        assertEquals(ModelState.Missing, s.state.value)
        val seen = mutableListOf<Long>()
        assertTrue(s.download { seen += it })
        assertEquals(ModelState.Installed, s.state.value)
        assertTrue(s.installed())
        assertArrayEquals(model, java.io.File(s.dir, "model.int8.onnx").readBytes())
        assertEquals("没有留下 .part", 0, s.dir.listFiles()!!.count { it.name.endsWith(".part") })
        assertEquals(tokens.size.toLong() + model.size, seen.last())
        assertTrue("进度只增不减", seen.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(listOf("/m/tokens.txt", "/m/model.int8.onnx"), listOf(server.takeRequest().target, server.takeRequest().target))
        assertEquals(tokens.size.toLong() + model.size, s.usedBytes())
    }

    @Test
    fun resume_sendsRange_andAppends() = runBlocking {
        val s = store()
        s.dir.mkdirs()
        java.io.File(s.dir, "tokens.txt").writeBytes(tokens)
        java.io.File(s.dir, "model.int8.onnx.part").writeBytes(model.copyOfRange(0, 400_000))
        s.refresh()
        assertEquals(ModelState.Partial(tokens.size + 400_000L), s.state.value)
        server.enqueue(partial(model, 400_000))
        assertTrue(s.download())
        val request = server.takeRequest()
        assertEquals("下好的 tokens 不再下载", "/m/model.int8.onnx", request.target)
        assertEquals("bytes=400000-", request.headers["Range"])
        assertArrayEquals(model, java.io.File(s.dir, "model.int8.onnx").readBytes())
    }

    @Test
    fun serverIgnoresRange_restartsFromZero() = runBlocking {
        val s = store()
        s.dir.mkdirs()
        java.io.File(s.dir, "tokens.txt").writeBytes(tokens)
        java.io.File(s.dir, "model.int8.onnx.part").writeBytes(ByteArray(400_000) { 7 })
        server.enqueue(ok(model))
        assertTrue(s.download())
        assertArrayEquals(model, java.io.File(s.dir, "model.int8.onnx").readBytes())
    }

    @Test
    fun partialFromTheWrongOffset_restartsFromZero() = runBlocking {
        val s = store()
        s.dir.mkdirs()
        java.io.File(s.dir, "tokens.txt").writeBytes(tokens)
        java.io.File(s.dir, "model.int8.onnx.part").writeBytes(model.copyOfRange(0, 400_000))
        // 服务器从 0 开始给，却标成 206：不能接在已有的部分后面
        server.enqueue(partial(model, 0))
        assertTrue(s.download())
        assertArrayEquals(model, java.io.File(s.dir, "model.int8.onnx").readBytes())
    }

    @Test
    fun checksumMismatch_deletesTheFile_noBadFileLeft() = runBlocking {
        server.enqueue(ok(tokens))
        server.enqueue(ok(model.copyOf().also { it[10] = (it[10] + 1).toByte() }))
        val s = store()
        assertFalse(s.download())
        assertEquals(ModelFailure.CHECKSUM, (s.state.value as ModelState.Failed).failure)
        assertFalse(java.io.File(s.dir, "model.int8.onnx").exists())
        assertFalse(java.io.File(s.dir, "model.int8.onnx.part").exists())
        assertTrue("校验通过的 tokens 留着", java.io.File(s.dir, "tokens.txt").isFile)
        assertFalse(s.installed())
    }

    @Test
    fun longerThanExpected_isDeleted() = runBlocking {
        server.enqueue(ok(tokens + byteArrayOf(1, 2, 3)))
        val s = store()
        assertFalse(s.download())
        assertEquals(ModelFailure.CHECKSUM, (s.state.value as ModelState.Failed).failure)
        assertNull(s.dir.listFiles()?.firstOrNull())
    }

    @Test
    fun muchLongerThanExpected_stopsEarly_progressNeverPassesTheSize() = runBlocking {
        server.enqueue(ok(tokens))
        server.enqueue(ok(model + ByteArray(2_000_000)))
        val s = store()
        val seen = mutableListOf<Long>()
        assertFalse(s.download { seen += it })
        assertEquals(ModelFailure.CHECKSUM, (s.state.value as ModelState.Failed).failure)
        assertTrue("超出应有大小就停下，不再报进度：${seen.max()}", seen.all { it <= tokens.size + model.size })
        assertFalse(java.io.File(s.dir, "model.int8.onnx.part").exists())
    }

    @Test
    fun finalFileWithWrongSize_isNotInstalled_andIsDownloadedAgain() = runBlocking {
        val s = store()
        s.dir.mkdirs()
        java.io.File(s.dir, "tokens.txt").writeBytes(tokens)
        java.io.File(s.dir, "model.int8.onnx").writeBytes(model.copyOfRange(0, 1000))
        s.refresh()
        assertFalse(s.installed())
        assertFalse(s.state.value == ModelState.Installed)
        server.enqueue(ok(model))
        assertTrue(s.download())
        assertArrayEquals(model, java.io.File(s.dir, "model.int8.onnx").readBytes())
    }

    @Test
    fun interrupted_keepsThePart_resumesLater() = runBlocking {
        server.enqueue(ok(tokens))
        server.enqueue(ok(model.copyOfRange(0, 700_000)))
        val s = store()
        assertFalse(s.download())
        val failed = s.state.value as ModelState.Failed
        assertEquals(ModelFailure.NETWORK, failed.failure)
        assertEquals(tokens.size + 700_000L, failed.bytes)
        assertEquals(700_000L, java.io.File(s.dir, "model.int8.onnx.part").length())
        server.enqueue(partial(model, 700_000))
        assertTrue(s.download())
        assertArrayEquals(model, java.io.File(s.dir, "model.int8.onnx").readBytes())
    }

    @Test
    fun redirect_onlyToAllowedHosts() = runBlocking {
        val target = MockWebServer().apply { start() }
        try {
            target.enqueue(ok(tokens))
            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.url("/cdn/tokens.txt").toString()).build())
            // 只认这个服务器：跳到别的端口也不行（模拟别的域名）
            val strict = store(allowHost = { it.host == server.hostName && it.port == server.port })
            assertFalse(strict.download())
            assertEquals(ModelFailure.REDIRECT, (strict.state.value as ModelState.Failed).failure)
            assertEquals(0, target.requestCount)

            server.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.url("/cdn/tokens.txt").toString()).build())
            server.enqueue(ok(model))
            assertTrue(store().download())
            assertEquals("/cdn/tokens.txt", target.takeRequest().target)
        } finally {
            target.close()
        }
    }

    @Test
    fun httpError_failsWithoutFiles() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        val s = store()
        assertFalse(s.download())
        assertEquals(ModelFailure.HTTP, (s.state.value as ModelState.Failed).failure)
        assertTrue(s.dir.listFiles().orEmpty().none { it.length() > 0 })
    }

    @Test
    fun notEnoughSpace_doesNotRequest() = runBlocking {
        val s = store(free = 1_000)
        assertFalse(s.download())
        assertEquals(ModelFailure.NO_SPACE, (s.state.value as ModelState.Failed).failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancel_keepsWhatWasDownloaded_thenResumes() = runBlocking {
        server.enqueue(ok(tokens))
        server.enqueue(ok(model))
        val s = store()
        try {
            coroutineScope {
                val job = async { s.download { if (it > tokens.size + 100_000) cancel("暂停") } }
                job.await()
            }
            fail("应当被取消")
        } catch (_: CancellationException) {
        }
        val partial = s.state.value
        assertTrue("暂停后是部分下载：$partial", partial is ModelState.Partial)
        val have = java.io.File(s.dir, "model.int8.onnx.part").length()
        assertTrue(have in 1 until model.size)
        server.takeRequest(); server.takeRequest()
        server.enqueue(partial(model, have.toInt()))
        assertTrue(s.download())
        assertEquals("bytes=$have-", server.takeRequest().headers["Range"])
        assertArrayEquals(model, java.io.File(s.dir, "model.int8.onnx").readBytes())
    }

    @Test
    fun delete_removesModelAndParts() = runBlocking {
        server.enqueue(ok(tokens))
        server.enqueue(ok(model.copyOfRange(0, 500_000)))
        val s = store()
        s.download()
        assertTrue(s.usedBytes() > 0)
        s.delete()
        assertEquals(ModelState.Missing, s.state.value)
        assertFalse(s.dir.exists())
        assertEquals(0L, s.usedBytes())
    }

    @Test
    fun hostIn_matchesDomainAndSubdomains_only() {
        fun url(host: String) = okhttp3.HttpUrl.Builder().scheme("https").host(host).build()
        assertTrue(ModelDownloader.hostIn(url("modelscope.cn"), OfflineAsrModel.ALLOWED_HOSTS))
        assertTrue(ModelDownloader.hostIn(url("cdn-lfs-cn-1.modelscope.cn"), OfflineAsrModel.ALLOWED_HOSTS))
        assertFalse(ModelDownloader.hostIn(url("evilmodelscope.cn"), OfflineAsrModel.ALLOWED_HOSTS))
        assertFalse(ModelDownloader.hostIn(url("modelscope.cn.evil.com"), OfflineAsrModel.ALLOWED_HOSTS))
    }

    @Test
    fun pinnedModelFiles_matchTheOfficialRelease() {
        // 2026-11-12 核对：和 sherpa-onnx 在 Hugging Face 发布的文件一致
        assertEquals(239_233_841L + 315_894L, OfflineAsrModel.TOTAL_BYTES)
        assertTrue(OfflineAsrModel.BASE_URL.startsWith("https://"))
        assertEquals(setOf("tokens.txt", "model.int8.onnx"), OfflineAsrModel.FILES.map { it.name }.toSet())
        assertTrue(OfflineAsrModel.FILES.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun pcmSamples_fromWav_scalesAndTakesFirstChannel() {
        val pcm = byteArrayOf(0, 0, 0, 0x40, 0, 0x80.toByte(), 0xFF.toByte(), 0x7F)
        val (mono, rate) = PcmSamples.fromWav(WavFormat.wrap(pcm))
        assertEquals(16_000, rate)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f, 32767 / 32768f), mono, 1e-6f)
        // 双声道：只取左声道
        val stereo = WavFormat.wrap(pcm).also {
            it[22] = 2 // channels
        }
        val (left, _) = PcmSamples.fromWav(stereo)
        assertArrayEquals(floatArrayOf(0f, -1f), left, 1e-6f)
        try {
            PcmSamples.fromWav(byteArrayOf(1, 2, 3))
            fail()
        } catch (_: java.io.IOException) {
        }
    }
}
