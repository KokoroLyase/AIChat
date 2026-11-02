package isomeria.aichat.data.llm

import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.coroutines.flow.Flow

/**
 * 大语言模型服务，每种请求格式一个实现：DeepSeek 和通用的 OpenAI 兼容格式。
 */
interface LlmProvider {
    /** 与 provider_config.provider 对应，见 [isomeria.aichat.data.db.ProviderProtocol] */
    val id: String

    /** 主动拉取模型候选；不代表聊天能力已经实测 */
    suspend fun listModels(endpoint: LlmEndpoint): List<RemoteModel>

    /**
     * 发起流式对话。请求在收集期间进行，取消收集即取消请求。
     * 失败时抛出 [LlmException]。
     */
    fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent>
}

data class LlmEndpoint(val baseUrl: String, val apiKey: String) {
    override fun toString(): String = "LlmEndpoint(baseUrl=$baseUrl, apiKey=***)"
}

data class RemoteModel(
    val id: String,
    val name: String?,
    /** 接口报告的图片输入能力；接口没给时为 null */
    val supportsImage: Boolean?,
    /** 接口报告的思考能力；接口没给时为 null */
    val supportsThinking: Boolean? = null,
    val config: ModelConfig = ModelConfig(),
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val thinking: ThinkingLevel,
    val temperature: Float,
    val parameters: RequestParameters? = null,
)

enum class ChatRole { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: ChatRole, val parts: List<ChatPart>) {
    constructor(role: ChatRole, text: String) : this(role, listOf(ChatPart.Text(text)))

    val hasImage: Boolean get() = parts.any { it is ChatPart.Image }

    /** 只有文字时拼成一个字符串 */
    fun plainText(): String = parts.filterIsInstance<ChatPart.Text>().joinToString("\n") { it.text }
}

sealed interface ChatPart {
    data class Text(val text: String) : ChatPart

    /** 图片，data URL（data:image/jpeg;base64,...） */
    data class Image(val dataUrl: String) : ChatPart {
        override fun toString(): String = "Image(${dataUrl.length} chars)"
    }
}

sealed interface ChatEvent {
    data class Reasoning(val delta: String) : ChatEvent
    data class Content(val delta: String) : ChatEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int) : ChatEvent
    data class Finished(val reason: String?) : ChatEvent
}

enum class LlmError {
    CONFIGURATION,
    /** 401 / 403 认证失败 */
    AUTH,
    /** 402 余额不足 */
    BALANCE,
    /** 429 请求过快 */
    RATE_LIMIT,
    /** 400 / 422 请求格式或参数错误 */
    BAD_REQUEST,
    /** 404 接口或模型不存在：通常是 Base URL 或模型名不对 */
    NOT_FOUND,
    /** 5xx */
    SERVER,
    /** 断网、超时、连接中断 */
    NETWORK,
    /** 其他（如返回内容无法解析） */
    UNKNOWN,
}

class LlmException(val error: LlmError, val httpCode: Int = 0, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: error.name, cause) {
    /** 没有发出请求就因为没有 Key 而失败；服务商拒绝 Key 时一定带 HTTP 状态码 */
    val isMissingKey: Boolean get() = error == LlmError.AUTH && httpCode == 0

    companion object {
        fun fromHttp(code: Int): LlmException {
            val error = when (code) {
                401, 403 -> LlmError.AUTH
                402 -> LlmError.BALANCE
                429 -> LlmError.RATE_LIMIT
                400, 422 -> LlmError.BAD_REQUEST
                404 -> LlmError.NOT_FOUND
                in 500..599 -> LlmError.SERVER
                else -> LlmError.UNKNOWN
            }
            return LlmException(error, code, "HTTP $code")
        }
    }
}
