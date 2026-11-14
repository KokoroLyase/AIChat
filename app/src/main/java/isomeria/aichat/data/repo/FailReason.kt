package isomeria.aichat.data.repo

import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException

/** 消息失败原因，存在 message.errorCode 里。HTTP 类沿用状态码，其余用小数字。 */
enum class FailReason(val code: Int) {
    AUTH(401),
    BALANCE(402),
    RATE_LIMIT(429),
    BAD_REQUEST(400),
    NOT_FOUND(404),
    SERVER(500),
    NETWORK(1),
    CANCELLED(2),
    /** 等待中 App 被系统杀掉，下次启动时标记 */
    INTERRUPTED(3),
    NO_API_KEY(4),
    EMPTY_REPLY(5),
    CONFIGURATION(6),
    /** 语音识别失败（0.7.0）：没有请求模型，点击重试会重新识别 */
    ASR_FAILED(10),
    /** 没有设置语音识别服务，或它没有 Key（0.7.0） */
    ASR_UNAVAILABLE(11),
    /** 发了文件，但没有设置文件解析服务或它没有 Token（0.9.2）：没有请求模型 */
    PARSE_UNAVAILABLE(12),
    /** 文件解析的 Token 不对或过期（0.9.2） */
    PARSE_AUTH(13),
    /** 文件超过 200 MB 或 200 页（0.9.2） */
    PARSE_TOO_LARGE(14),
    /** 文件解析不支持这种格式（0.9.2） */
    PARSE_UNSUPPORTED(15),
    /** 文件解析失败（0.9.2），点击重试会重新解析 */
    PARSE_FAILED(16),
    /** 等了 10 分钟还没解析完（0.9.2） */
    PARSE_TIMEOUT(17),
    /** 今天的文件解析额度用完了（0.9.2） */
    PARSE_QUOTA(18),
    UNKNOWN(9),
    ;

    companion object {
        fun of(code: Int): FailReason = entries.firstOrNull { it.code == code } ?: UNKNOWN

        fun of(e: LlmException): FailReason = when (e.error) {
            LlmError.CONFIGURATION -> CONFIGURATION
            LlmError.AUTH -> AUTH
            LlmError.BALANCE -> BALANCE
            LlmError.RATE_LIMIT -> RATE_LIMIT
            LlmError.BAD_REQUEST -> BAD_REQUEST
            LlmError.NOT_FOUND -> NOT_FOUND
            LlmError.SERVER -> SERVER
            LlmError.NETWORK -> NETWORK
            LlmError.UNKNOWN -> UNKNOWN
        }
    }
}
