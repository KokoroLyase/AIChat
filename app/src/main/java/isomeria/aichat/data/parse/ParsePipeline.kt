package isomeria.aichat.data.parse

import android.util.Log
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageDao
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 发请求前先解析这一轮的文件和图片（0.9.2），仿照语音的先识别：
 *
 * - 这一轮等待回复、还没解析过的文件都要解析；没有可用的文件解析服务时这一轮失败（[FailReason.PARSE_UNAVAILABLE]），不请求模型
 * - 模型不能看图、又配了文件解析时，这一轮的图片也交给它识别；没配时照旧按“[图片]”发
 * - 模型能看图时图片不解析，即使配了文件解析
 * - 一个失败就停，返回原因（这一轮都标为失败，点重试会重新解析没解析成的）；成功的结果写进 files/parsed
 *
 * 以前的消息不补解析（联系人换了模型以后，免得悄悄产生费用）
 */
@Singleton
class ParsePipeline @Inject constructor(
    private val messages: MessageDao,
    private val files: FileStorage,
    private val parsers: ParserRepository,
    private val contextBuilder: ContextBuilder,
) {
    /** 返回 null 表示都解析好了（或这一轮没有要解析的） */
    suspend fun parseWaiting(contactId: Long, supportsImage: Boolean): FailReason? {
        val pending = messages.unparsed(contactId).filter { it.type == MessageType.FILE || (it.type == MessageType.IMAGE && it.role == Role.USER && !supportsImage) }
        if (pending.isEmpty()) return null
        if (!parsers.available()) {
            // 只有图片时照旧按“[图片]”发
            return if (pending.any { it.type == MessageType.FILE }) FailReason.PARSE_UNAVAILABLE else null
        }
        for (message in pending) {
            val reason = parse(message)
            if (reason != null) return reason
        }
        return null
    }

    private suspend fun parse(message: Message): FailReason? {
        val path = if (message.type == MessageType.FILE) message.filePath else message.imagePath
        val file = path?.let(::File)?.takeIf { it.isFile } ?: return FailReason.PARSE_FAILED
        val text = try {
            parsers.parse(file, uploadName(message, file), contextBuilder.imageLabel)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ParserUnavailableException) {
            return FailReason.PARSE_UNAVAILABLE
        } catch (e: ParseException) {
            debugLog("parse failed: ${e.error} ${e.code.orEmpty()}")
            return reasonOf(e.error)
        } catch (e: Exception) {
            debugLog("parse failed unexpectedly: ${e.javaClass.simpleName}")
            return FailReason.PARSE_FAILED
        }
        val out = files.writeParsed(text)
        try {
            messages.setParsed(message.id, out)
        } catch (e: Throwable) {
            files.delete(out)
            throw e
        } finally {
            files.commitAvatar(out)
        }
        debugLog("parse ok: type=${message.type} chars=${text.length}")
        return null
    }

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    companion object {
        private const val TAG = "ParsePipeline"

        /** 上传时的文件名：MinerU 按扩展名认格式。显示的名字没有对的扩展名时用本机文件的扩展名 */
        fun uploadName(message: Message, file: File): String {
            if (message.type == MessageType.IMAGE) return "image." + file.extension.ifEmpty { "jpg" }
            val name = message.content.trim()
            if (MineruFormats.supports(name)) return name
            return name.ifEmpty { "file" } + "." + file.extension
        }

        fun reasonOf(error: ParseError): FailReason = when (error) {
            ParseError.AUTH -> FailReason.PARSE_AUTH
            ParseError.TOO_LARGE -> FailReason.PARSE_TOO_LARGE
            ParseError.UNSUPPORTED -> FailReason.PARSE_UNSUPPORTED
            ParseError.QUOTA -> FailReason.PARSE_QUOTA
            ParseError.TIMEOUT -> FailReason.PARSE_TIMEOUT
            ParseError.NETWORK -> FailReason.NETWORK
            ParseError.FAILED -> FailReason.PARSE_FAILED
        }
    }
}
