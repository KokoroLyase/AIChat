package isomeria.aichat.data.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 离线识别模型下载失败的原因 */
enum class ModelFailure {
    /** 连不上、断了；已下载的部分留着，可以继续 */
    NETWORK,

    /** 服务器返回错误（文件不在了、拒绝访问等） */
    HTTP,

    /** 下载完的文件和写死的 SHA-256 或大小对不上，已经删掉 */
    CHECKSUM,

    /** 手机存储空间不够 */
    NO_SPACE,

    /** 服务器要跳转到不认识的地址，没有跟过去 */
    REDIRECT,
}

class ModelDownloadException(val failure: ModelFailure, message: String? = null) : IOException(message ?: failure.name)

/** 离线识别模型现在的状态。字节数都是所有文件合计 */
sealed interface ModelState {
    /** 什么都没下载 */
    data object Missing : ModelState

    /** 下载了一部分（暂停、被打断或失败后），可以继续 */
    data class Partial(val bytes: Long) : ModelState

    data class Downloading(val bytes: Long) : ModelState

    /** 下载完了，正在按 SHA-256 校验 */
    data object Verifying : ModelState

    data object Installed : ModelState

    data class Failed(val failure: ModelFailure, val bytes: Long) : ModelState
}

/**
 * 下载模型文件（0.7.1），断点续传，下载后按写死的 SHA-256 校验。纯 OkHttp，便于用本机模拟服务器测试。
 *
 * - 每个文件先写到 `{名称}.part`，校验通过后才改名成正式文件；正式文件存在、大小对就算下好了
 * - 已有 `.part` 时用 Range 接着下；服务器不支持（返回 200）就从头下
 * - 不自动跟随跳转：只跟到 [allowHost] 认可的域名，并且只用 HTTPS（[allowHttp] 只给测试用）
 * - 中断（网络、取消）时 `.part` 留着；校验不对、比应有的大时删掉，不留坏文件
 */
class ModelDownloader(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val allowHost: (HttpUrl) -> Boolean,
    private val allowHttp: Boolean = false,
) {
    /**
     * 把 [files] 下载到 [dir]。[freeBytes] 返回可用空间；[progress] 报告所有文件合计已下载的字节数。
     * 失败时抛 [ModelDownloadException]；协程被取消时照常抛 CancellationException
     */
    suspend fun download(dir: File, files: List<ModelFile>, freeBytes: () -> Long, progress: suspend (Long) -> Unit, verifying: suspend () -> Unit = {}) =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            var done = 0L
            for (file in files) {
                val target = File(dir, file.name)
                if (target.isFile && target.length() == file.bytes) {
                    done += file.bytes
                    continue
                }
                target.delete()
                val part = File(dir, file.name + PART)
                if (part.length() > file.bytes) part.delete()
                if (part.length() < file.bytes) {
                    val need = file.bytes - part.length()
                    if (freeBytes() < need + SPACE_MARGIN) throw ModelDownloadException(ModelFailure.NO_SPACE)
                    fetch(file, part) { written -> progress(done + written) }
                }
                verifying()
                if (part.length() != file.bytes || sha256(part) != file.sha256) {
                    part.delete()
                    throw ModelDownloadException(ModelFailure.CHECKSUM)
                }
                if (!part.renameTo(target)) throw ModelDownloadException(ModelFailure.NETWORK, "RenameFailed")
                done += file.bytes
                progress(done)
            }
        }

    private suspend fun fetch(file: ModelFile, part: File, progress: suspend (Long) -> Unit) {
        var have = part.length()
        var url = (baseUrl + file.name).toHttpUrl()
        repeat(MAX_REDIRECTS + 1) {
            val request = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw ModelDownloadException(ModelFailure.NETWORK, e.javaClass.simpleName)
            }
            response.use {
                when {
                    response.isRedirect -> {
                        val next = response.header("Location")?.let { url.resolve(it) }
                        if (next == null || !permitted(next)) throw ModelDownloadException(ModelFailure.REDIRECT)
                        url = next
                        return@repeat
                    }
                    response.code == 206 && have > 0 -> {
                        // 只认从我们要的位置接着给的；对不上就从头下
                        val start = response.header("Content-Range")?.let { RANGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
                        if (start != have) {
                            part.delete()
                            have = 0
                        }
                    }
                    response.code == 200 -> {
                        part.delete()
                        have = 0
                    }
                    else -> throw ModelDownloadException(ModelFailure.HTTP, "HTTP ${response.code}")
                }
                if (have == 0L && part.exists()) part.delete()
                val body = response.body
                try {
                    FileOutputStream(part, true).use { out ->
                        val source = body.byteStream()
                        val buffer = ByteArray(BUFFER)
                        var written = have
                        var reported = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = source.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            if (written > file.bytes) {
                                out.close()
                                part.delete()
                                throw ModelDownloadException(ModelFailure.CHECKSUM, "TooLong")
                            }
                            if (written - reported >= REPORT_EVERY || written == file.bytes) {
                                reported = written
                                progress(written)
                            }
                        }
                    }
                } catch (e: ModelDownloadException) {
                    throw e
                } catch (e: IOException) {
                    throw ModelDownloadException(ModelFailure.NETWORK, e.javaClass.simpleName)
                }
                if (part.length() < file.bytes) throw ModelDownloadException(ModelFailure.NETWORK, "Incomplete")
                return
            }
        }
        throw ModelDownloadException(ModelFailure.REDIRECT, "TooManyRedirects")
    }

    private fun permitted(url: HttpUrl): Boolean = (url.isHttps || allowHttp) && allowHost(url)

    companion object {
        const val PART = ".part"
        private const val BUFFER = 64 * 1024
        private const val REPORT_EVERY = 512 * 1024L
        private const val MAX_REDIRECTS = 5
        /** 下载前要求多留出的空间 */
        const val SPACE_MARGIN = 32L * 1024 * 1024
        private val RANGE = Regex("""bytes\s+(\d+)-""")

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** 域名是 [suffixes] 之一或它的子域名 */
        fun hostIn(url: HttpUrl, suffixes: List<String>): Boolean = suffixes.any { url.host == it || url.host.endsWith(".$it") }
    }
}

/**
 * 离线识别模型的文件和状态（0.7.1）：存在 `files/asr-models/{模型}`，计入存储空间，可以单独删除，不随备份。
 * 下载在 [OfflineModelDownloads] 安排的后台任务里调用 [download]。
 */
@Singleton
class OfflineModelStore internal constructor(
    private val root: File,
    private val downloader: ModelDownloader,
    private val files: List<ModelFile>,
    private val freeBytes: (File) -> Long,
) {
    @Inject constructor(@ApplicationContext context: Context, client: OkHttpClient) : this(
        root = context.filesDir,
        downloader = ModelDownloader(
            client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build(),
            OfflineAsrModel.BASE_URL,
            allowHost = { ModelDownloader.hostIn(it, OfflineAsrModel.ALLOWED_HOSTS) },
        ),
        files = OfflineAsrModel.FILES,
        freeBytes = { allocatableBytes(context, it) },
    )

    private val lock = Mutex()

    val dir: File get() = File(root, OfflineAsrModel.DIR)

    private val _state = MutableStateFlow(diskState())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    val totalBytes: Long get() = files.sumOf { it.bytes }

    /** 所有文件都下好、校验过（只有校验通过才会改成正式文件名） */
    fun installed(): Boolean = files.all { File(dir, it.name).let { f -> f.isFile && f.length() == it.bytes } }

    /** 占用的空间（含没下完的部分），存储空间页用 */
    fun usedBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 下载，直到完成、失败或被取消；返回是否下好了。同一时间只有一个下载 */
    suspend fun download(onProgress: suspend (Long) -> Unit = {}): Boolean = lock.withLock {
        if (installed()) {
            _state.value = ModelState.Installed
            return@withLock true
        }
        _state.value = ModelState.Downloading(downloadedBytes())
        try {
            downloader.download(
                dir, files,
                freeBytes = { freeBytes(root) },
                progress = { bytes ->
                    _state.value = ModelState.Downloading(bytes)
                    onProgress(bytes)
                },
                verifying = { _state.value = ModelState.Verifying },
            )
            _state.value = diskState()
            installed()
        } catch (e: ModelDownloadException) {
            _state.value = ModelState.Failed(e.failure, downloadedBytes())
            false
        } catch (e: Throwable) {
            // 取消（暂停）等：已下载的部分留着，下次接着下
            _state.value = diskState()
            throw e
        }
    }

    /** 删除模型和没下完的部分。正在下载时先由调用方取消下载任务；这里会等下载停下再删 */
    suspend fun delete() = lock.withLock {
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
        _state.value = diskState()
    }

    /** 重新按磁盘上的文件确定状态（例如 App 重启后、失败提示看过后） */
    fun refresh() {
        if (!lock.isLocked) _state.value = diskState()
    }

    private fun downloadedBytes(): Long = files.sumOf { file ->
        val done = File(dir, file.name)
        if (done.isFile && done.length() == file.bytes) file.bytes else File(dir, file.name + ModelDownloader.PART).length()
    }

    private companion object {
        /** 能写多少：按系统的说法（会算上系统可以腾出来的缓存）；查不到时退回文件系统的剩余空间 */
        fun allocatableBytes(context: Context, dir: File): Long = try {
            val storage = context.getSystemService(android.os.storage.StorageManager::class.java)
            storage.getAllocatableBytes(storage.getUuidForPath(dir))
        } catch (_: IOException) {
            dir.freeSpace
        }
    }

    private fun diskState(): ModelState = when {
        installed() -> ModelState.Installed
        downloadedBytes() > 0 -> ModelState.Partial(downloadedBytes())
        else -> ModelState.Missing
    }
}
