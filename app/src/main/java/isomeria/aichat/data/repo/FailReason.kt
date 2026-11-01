package isomeria.aichat.data.repo

import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException

/** 消息失败原因，存在 message.errorCode 里。HTTP 类沿用状态码，其余用小数字。 */
enum class FailReason(val code: Int) {
    AUTH(401),
    BALANCE(402),
    RATE_LIMIT(429),
    BAD_REQUEST(400),
    SERVER(500),
    NETWORK(1),
    CANCELLED(2),
    /** 等待中 App 被系统杀掉，下次启动时标记 */
    INTERRUPTED(3),
    NO_API_KEY(4),
    EMPTY_REPLY(5),
    UNKNOWN(9),
    ;

    companion object {
        fun of(code: Int): FailReason = entries.firstOrNull { it.code == code } ?: UNKNOWN

        fun of(e: LlmException): FailReason = when (e.error) {
            LlmError.AUTH -> AUTH
            LlmError.BALANCE -> BALANCE
            LlmError.RATE_LIMIT -> RATE_LIMIT
            LlmError.BAD_REQUEST -> BAD_REQUEST
            LlmError.SERVER -> SERVER
            LlmError.NETWORK -> NETWORK
            LlmError.UNKNOWN -> UNKNOWN
        }
    }
}
