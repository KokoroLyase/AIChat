package isomeria.aichat.data.llm

import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.coroutines.flow.Flow

/**
 * 大语言模型服务。0.1.0 只实现 DeepSeek；以后加入其他兼容 OpenAI 格式的服务商，只需新增一个实现。
 */
interface LlmProvider {
    /** 与 provider_config.provider 对应，如 deepseek */
    val id: String

    /** 拉取模型列表；「测试连接」也用它 */
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
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val thinking: ThinkingLevel,
    val temperature: Float,
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
    /** 401 认证失败 */
    AUTH,
    /** 402 余额不足 */
    BALANCE,
    /** 429 请求过快 */
    RATE_LIMIT,
    /** 400 / 422 请求格式或参数错误 */
    BAD_REQUEST,
    /** 5xx */
    SERVER,
    /** 断网、超时、连接中断 */
    NETWORK,
    /** 其他（如返回内容无法解析） */
    UNKNOWN,
}

class LlmException(val error: LlmError, val httpCode: Int = 0, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: error.name, cause) {
    companion object {
        fun fromHttp(code: Int): LlmException {
            val error = when (code) {
                401 -> LlmError.AUTH
                402 -> LlmError.BALANCE
                429 -> LlmError.RATE_LIMIT
                400, 422 -> LlmError.BAD_REQUEST
                in 500..599 -> LlmError.SERVER
                else -> LlmError.UNKNOWN
            }
            return LlmException(error, code, "HTTP $code")
        }
    }
}
