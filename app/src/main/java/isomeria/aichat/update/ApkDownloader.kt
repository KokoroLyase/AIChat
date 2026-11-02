package isomeria.aichat.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

/** 下载失败的原因。 */
enum class UpdateFailure {
    /** Release 里没有 APK 或校验文件 */
    MISSING_ASSET,

    /** 断网、超时、服务器返回错误 */
    NETWORK,

    /** 校验文件格式不对，或 SHA-256 不一致 */
    CHECKSUM,

    /** 不是智信的安装包、版本不比当前新，或签名不一致 */
    INVALID_APK,
}

class UpdateException(val failure: UpdateFailure, cause: Throwable? = null) : Exception(failure.name, cause)

/** Release 里的 .sha256 文件：sha256sum 的输出，「十六进制值  文件名」。 */
object Sha256File {
    private val pattern = Regex("""^\s*([0-9a-fA-F]{64})(\s|$)""")

    /** 取出十六进制值（小写）；格式不对时返回 null */
    fun parse(text: String): String? = pattern.find(text)?.groupValues?.get(1)?.lowercase()
}

/**
 * 下载 APK 并用 .sha256 校验。校验不通过时删掉文件，绝不交给系统安装。
 * 请求不带任何凭据；GitHub 的下载地址会重定向到别的域名，所以这里允许重定向。
 */
class ApkDownloader(client: OkHttpClient, private val dir: File) {
    @Inject constructor(client: OkHttpClient, @ApplicationContext context: Context) :
        this(client, File(context.cacheDir, DIR_NAME))

    private val http = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 下载并校验，返回校验过的 APK。[onProgress] 的参数是已下载字节数和总字节数（未知时为 -1）。
     * 失败时抛 [UpdateException]；协程取消时中断下载并删掉半个文件。
     */
    suspend fun download(apkUrl: String, sha256Url: String, onProgress: (Long, Long) -> Unit): File =
        isomeria.aichat.data.repo.StorageOperations.gate.activity { withContext(Dispatchers.IO) {
            dir.mkdirs()
            val part = File(dir, "$APK_NAME.part")
            val target = File(dir, APK_NAME)
            target.delete()
            try {
                val expected = fetchChecksum(sha256Url)
                val actual = fetchApk(apkUrl, part, onProgress)
                if (actual != expected) throw UpdateException(UpdateFailure.CHECKSUM)
                if (!part.renameTo(target)) throw UpdateException(UpdateFailure.NETWORK)
                target
            } catch (e: IOException) {
                throw UpdateException(UpdateFailure.NETWORK, e)
            } finally {
                part.delete()
            }
        } }

    /** 上次下载留下的文件：装完之后已经没用了 */
    fun cleanup() {
        dir.deleteRecursively()
    }

    private suspend fun fetchChecksum(url: String): String = execute(url).use { response ->
        // 校验文件只有一行，只读开头这么多就够了
        val source = response.body.source()
        source.request(MAX_CHECKSUM_BYTES)
        val text = source.buffer.readByteArray(source.buffer.size.coerceAtMost(MAX_CHECKSUM_BYTES)).toString(Charsets.UTF_8)
        Sha256File.parse(text) ?: throw UpdateException(UpdateFailure.CHECKSUM)
    }

    /** 边下载边算 SHA-256，返回十六进制值（小写） */
    private suspend fun fetchApk(url: String, out: File, onProgress: (Long, Long) -> Unit): String =
        execute(url).use { response ->
            val total = response.body.contentLength()
            if (total > MAX_APK_BYTES) throw UpdateException(UpdateFailure.INVALID_APK)
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            onProgress(0, total)
            response.body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (done > MAX_APK_BYTES) throw UpdateException(UpdateFailure.INVALID_APK)
                        onProgress(done, total)
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    /** 发起请求；协程取消时立即断开连接（连接和读数据期间都是），不用等读超时 */
    private suspend fun execute(url: String): Response {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw UpdateException(UpdateFailure.MISSING_ASSET, e)
        }
        val call = http.newCall(request)
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        val response = call.execute()
        if (!response.isSuccessful) {
            response.close()
            throw UpdateException(UpdateFailure.NETWORK)
        }
        return response
    }

    companion object {
        const val DIR_NAME = "updates"
        private const val APK_NAME = "update.apk"
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_APK_BYTES = 300L shl 20
        private const val MAX_CHECKSUM_BYTES = 4096L
    }
}
