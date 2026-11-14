package isomeria.aichat.data.parse

import isomeria.aichat.BuildConfig
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.voice.awaitResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 文件解析失败的原因（0.9.2），对应消息的失败原因 */
enum class ParseError {
    /** Token 不对或过期（HTTP 401/403，或 MinerU 的 A0202、A0211） */
    AUTH,

    /** 文件超过 200 MB 或 200 页（-60005、-60006） */
    TOO_LARGE,

    /** 不支持的格式（发之前按扩展名查） */
    UNSUPPORTED,

    /** 今天的解析额度用完了（-60018） */
    QUOTA,

    /** 等了 [MineruApi.MAX_WAIT_MILLIS] 还没解析完 */
    TIMEOUT,

    /** 连不上 */
    NETWORK,

    /** 解析失败，或返回的内容不对 */
    FAILED,
}

class ParseException(val error: ParseError, val code: String? = null, cause: Throwable? = null) :
    Exception("parse failed: $error${code?.let { " ($it)" }.orEmpty()}", cause)

/** 文件解析服务（0.9.2）：目前只有 MinerU 精准解析 API。单元测试换成假的 */
interface DocumentParser {
    /** 解析 [file]（[name] 是显示的文件名，服务端按扩展名认格式），返回 Markdown；图片引用换成 [imageLabel] */
    suspend fun parse(endpoint: LlmEndpoint, file: File, name: String, imageLabel: String): String

    /** 设置里的“测试连接”：Token 能用就正常返回 */
    suspend fun test(endpoint: LlmEndpoint)
}

/**
 * MinerU 精准解析 API（2026-11-14 按 mineru.net 的文档核对）：
 *
 * 1. `POST {Base URL}/file-urls/batch`，带 Token，申请上传链接，拿到 batch_id 和上传地址
 * 2. 用 PUT 把文件传到上传地址（对象存储，不带 Token 和 Content-Type），传完服务端自动开始解析
 * 3. 每 [POLL_MILLIS] 查一次 `GET {Base URL}/extract-results/batch/{batch_id}`，最多等 [MAX_WAIT_MILLIS]
 * 4. done 时下载 full_zip_url（CDN），只读 zip 里的 full.md，不解压到磁盘
 *
 * 上传地址和结果地址只接受 https（debug 包允许本机的 http，测试用）；不跟随重定向；zip 最大 [MAX_ZIP_BYTES]，
 * full.md 最多读 [MAX_MARKDOWN_BYTES]。日志不记 Token、文件名和内容
 */
@Singleton
class MineruApi(
    client: OkHttpClient,
    private val json: Json,
    private val pollMillis: Long = POLL_MILLIS,
    private val maxWaitMillis: Long = MAX_WAIT_MILLIS,
    private val allowLocalHttp: Boolean = BuildConfig.DEBUG,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DocumentParser {
    @Inject constructor(client: OkHttpClient, json: Json) : this(client, json, POLL_MILLIS)

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun parse(endpoint: LlmEndpoint, file: File, name: String, imageLabel: String): String = withContext(Dispatchers.IO) {
        if (!file.isFile) throw ParseException(ParseError.FAILED, "missing-file")
        if (!MineruFormats.supports(name)) throw ParseException(ParseError.UNSUPPORTED)
        if (file.length() > MineruFormats.MAX_BYTES) throw ParseException(ParseError.TOO_LARGE)

        val apply = buildJsonObject {
            putJsonArray("files") {
                addJsonObject {
                    put("name", name)
                    put("data_id", UUID.randomUUID().toString())
                }
            }
            put("model_version", MODEL_VERSION)
        }
        val applied = data(call(api(endpoint, "file-urls/batch").post(apply.toString().toRequestBody(JSON_TYPE)), endpoint))
        val batchId = applied["batch_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it.all(::isIdChar) }
            ?: throw ParseException(ParseError.FAILED, "no-batch")
        val uploadUrl = applied["file_urls"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull
            ?: throw ParseException(ParseError.FAILED, "no-upload-url")

        // 上传：对象存储的预签名地址，不带 Token，也不带 Content-Type（带了签名对不上）
        val put = Request.Builder().url(external(uploadUrl)).put(file.asRequestBody(null)).build()
        client.newCall(put).awaitResponse().use { response ->
            if (!response.isSuccessful) throw ParseException(ParseError.FAILED, "upload-${response.code}")
        }

        val zipUrl = waitForResult(endpoint, batchId)
        val markdown = downloadMarkdown(zipUrl)
        MineruMarkdown.clean(markdown, imageLabel)
    }

    override suspend fun test(endpoint: LlmEndpoint) = withContext(Dispatchers.IO) {
        // 查一个不存在的任务：Token 对时返回“任务不存在”（-60012），不对时返回 A0202、A0211 或 401
        val body = call(api(endpoint, "extract-results/batch/" + UUID.randomUUID()).get(), endpoint)
        val code = codeOf(body)
        if (code == "0" || code == CODE_NOT_FOUND) return@withContext
        throw errorOf(code)
    }

    private suspend fun waitForResult(endpoint: LlmEndpoint, batchId: String): String {
        var waited = 0L
        while (true) {
            val data = data(call(api(endpoint, "extract-results/batch/$batchId").get(), endpoint))
            val result = data["extract_result"]?.jsonArray?.firstOrNull()?.jsonObject
            when (result?.get("state")?.jsonPrimitive?.contentOrNull) {
                "done" -> return result["full_zip_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: throw ParseException(ParseError.FAILED, "no-zip")
                "failed" -> throw ParseException(ParseError.FAILED, "state-failed")
            }
            if (waited >= maxWaitMillis) throw ParseException(ParseError.TIMEOUT)
            sleep(pollMillis)
            waited += pollMillis
        }
    }

    private suspend fun downloadMarkdown(url: String): String {
        val request = Request.Builder().url(external(url)).get().build()
        return client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw ParseException(ParseError.FAILED, "zip-${response.code}")
            val length = response.body.contentLength()
            if (length > MAX_ZIP_BYTES) throw ParseException(ParseError.FAILED, "zip-too-large")
            try {
                readFullMarkdown(response.body.byteStream())
            } catch (e: IOException) {
                throw ParseException(ParseError.NETWORK, cause = e)
            }
        }
    }

    /** 申请上传链接、查结果：Base URL 下的接口，带 Token */
    private fun api(endpoint: LlmEndpoint, path: String): Request.Builder {
        val url = endpoint.baseUrl.trim().trimEnd('/') + "/" + path
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw ParseException(ParseError.FAILED, "bad-url", e)
        }
        return try {
            builder.header("Authorization", endpoint.authorization())
        } catch (e: LlmException) {
            throw ParseException(ParseError.AUTH, cause = e)
        }
    }

    /** MinerU 返回的上传和下载地址：只要 https；debug 包允许本机的 http（本机模拟接口） */
    private fun external(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: throw ParseException(ParseError.FAILED, "bad-link")
        val local = parsed.host == "127.0.0.1" || parsed.host == "localhost" || parsed.host == "10.0.2.2"
        if (parsed.scheme != "https" && !(allowLocalHttp && local)) throw ParseException(ParseError.FAILED, "insecure-link")
        return parsed.toString()
    }

    private suspend fun call(builder: Request.Builder, endpoint: LlmEndpoint): JsonObject {
        val response: Response = try {
            client.newCall(builder.build()).awaitResponse()
        } catch (e: LlmException) {
            throw ParseException(ParseError.NETWORK, cause = e)
        }
        return response.use {
            val text = try {
                it.body.source().let { source ->
                    source.request(MAX_JSON_BYTES + 1)
                    if (source.buffer.size > MAX_JSON_BYTES) throw ParseException(ParseError.FAILED, "json-too-large")
                    source.buffer.readUtf8()
                }
            } catch (e: IOException) {
                throw ParseException(ParseError.NETWORK, cause = e)
            }
            val body = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (it.code == 401 || it.code == 403) throw ParseException(ParseError.AUTH, it.code.toString())
            if (body == null) throw ParseException(if (it.isSuccessful) ParseError.FAILED else httpError(it.code), "http-${it.code}")
            // 出错时 HTTP 状态码不一定不是 200，看返回里的 code
            val code = codeOf(body)
            if (!it.isSuccessful && (code == null || code == "0")) throw ParseException(httpError(it.code), "http-${it.code}")
            body
        }
    }

    /** code 为 0 时取 data，否则按错误码报错 */
    private fun data(body: JsonObject): JsonObject {
        val code = codeOf(body)
        if (code != "0") throw errorOf(code)
        return body["data"] as? JsonObject ?: throw ParseException(ParseError.FAILED, "no-data")
    }

    private fun codeOf(body: JsonObject): String? = body["code"]?.jsonPrimitive?.contentOrNull

    private fun errorOf(code: String?): ParseException = ParseException(
        when (code) {
            "A0202", "A0211" -> ParseError.AUTH
            "-60005", "-60006" -> ParseError.TOO_LARGE
            "-60018" -> ParseError.QUOTA
            else -> ParseError.FAILED
        },
        code,
    )

    private fun httpError(code: Int): ParseError = when (code) {
        401, 403 -> ParseError.AUTH
        413 -> ParseError.TOO_LARGE
        429 -> ParseError.QUOTA
        else -> ParseError.FAILED
    }

    private fun isIdChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_'

    companion object {
        const val POLL_MILLIS = 3_000L
        const val MAX_WAIT_MILLIS = 10 * 60_000L
        const val REQUEST_TIMEOUT_SECONDS = 300L
        const val MODEL_VERSION = "vlm"
        const val MAX_ZIP_BYTES = 50L shl 20
        const val MAX_MARKDOWN_BYTES = 20L shl 20
        private const val MAX_JSON_BYTES = 1L shl 20
        private const val CODE_NOT_FOUND = "-60012"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 从 zip 流里只读 full.md（根目录或某个子目录下的都算，取路径最短的那个）。不解压别的文件，也不写磁盘，
         * 所以条目名里的“../”之类不影响；整个 zip 最多读 [MAX_ZIP_BYTES]，full.md 最多 [MAX_MARKDOWN_BYTES]
         */
        internal fun readFullMarkdown(input: InputStream): String {
            var best: String? = null
            var bestDepth = Int.MAX_VALUE
            ZipInputStream(LimitedStream(input, MAX_ZIP_BYTES)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    val depth = name.count { it == '/' }
                    if (!entry.isDirectory && (name == "full.md" || name.endsWith("/full.md")) && depth < bestDepth) {
                        val bytes = readLimited(zip, MAX_MARKDOWN_BYTES)
                        best = bytes.toString(Charsets.UTF_8)
                        bestDepth = depth
                    }
                }
            }
            return best ?: throw ParseException(ParseError.FAILED, "no-full-md")
        }

        private fun readLimited(input: InputStream, limit: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw ParseException(ParseError.FAILED, "markdown-too-large")
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }
}

/** 读超过 [limit] 字节时报错的输入流 */
private class LimitedStream(input: InputStream, private val limit: Long) : java.io.FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) check(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) check(n.toLong())
        return n
    }

    private fun check(n: Long) {
        count += n
        if (count > limit) throw ParseException(ParseError.FAILED, "zip-too-large")
    }
}

/** MinerU 支持的格式（2026-11-14 按文档）：PDF、Word、PPT、Excel 和常见图片；按扩展名判断 */
object MineruFormats {
    val documents = listOf("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx")
    val images = listOf("png", "jpg", "jpeg", "jp2", "webp", "gif", "bmp")

    /** 系统文件选择器里列出的类型 */
    val mimeTypes = arrayOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "image/png", "image/jpeg", "image/jp2", "image/webp", "image/gif", "image/bmp",
    )

    /** 单个文件最大 200 MB */
    const val MAX_BYTES = 200L shl 20

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun supports(name: String): Boolean = extension(name) in documents || extension(name) in images
}

/** 解析出的 Markdown 怎么进请求（0.9.2） */
object MineruMarkdown {
    private val imageRef = Regex("""!\[[^\]\n]*]\([^)\n]*\)""")
    private val htmlImage = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val blankLines = Regex("""\n{3,}""")

    /** 图片引用（![](images/…)、<img>）换成 [imageLabel]，多余的空行压成一行，去掉首尾空白 */
    fun clean(markdown: String, imageLabel: String): String = markdown
        .replace("\r\n", "\n")
        .replace(imageRef, imageLabel)
        .replace(htmlImage, imageLabel)
        .replace(blankLines, "\n\n")
        .trim()
}
