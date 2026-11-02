package isomeria.aichat.core

import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.llm.ChatMessage
import isomeria.aichat.data.llm.ChatPart
import kotlin.math.ceil

/**
 * 估算 token 数，用来判断上下文是否超预算。不用分词库：各服务商的分词不一样，库还会增大 APK。
 *
 * - DeepSeek 用官方给的比例：汉字 0.6、英文字符 0.3（api-docs.deepseek.com/quick_start/token_usage）
 * - 其他服务商的汉字按 1 算，宁可少带一点，也不超预算
 * - U+2000 以下（拉丁、希腊、西里尔字母等）按英文算；以上（汉字、假名、全角标点、emoji 等）按汉字算
 * - 每条消息额外算 [MESSAGE_OVERHEAD]（角色和分隔符）；带原图的图片每张按 [IMAGE_TOKENS] 算
 */
class TokenEstimator(private val wideCharTokens: Double) {
    fun text(text: String): Int {
        var total = 0.0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            total += if (codePoint < WIDE_CHAR_START) NARROW_CHAR_TOKENS else wideCharTokens
            index += Character.charCount(codePoint)
        }
        return ceil(total).toInt()
    }

    /** 一条消息占的 token；[withImage] 为 true 时这张图片带原图，否则按文字「[图片]」算 */
    fun message(message: Message, withImage: Boolean = false): Int = MESSAGE_OVERHEAD + when (message.type) {
        MessageType.TEXT -> text(message.content)
        MessageType.IMAGE -> if (withImage) IMAGE_TOKENS else IMAGE_PLACEHOLDER_TOKENS
    }

    /** 一次请求的 token：服务商没有返回用量时，用它估算 */
    fun request(messages: List<ChatMessage>): Int = messages.sumOf { message ->
        MESSAGE_OVERHEAD + message.parts.sumOf { part ->
            when (part) {
                is ChatPart.Text -> text(part.text)
                is ChatPart.Image -> IMAGE_TOKENS
            }
        }
    }

    companion object {
        const val MESSAGE_OVERHEAD = 4
        const val IMAGE_TOKENS = 1000
        private const val IMAGE_PLACEHOLDER_TOKENS = 4
        private const val NARROW_CHAR_TOKENS = 0.3
        private const val WIDE_CHAR_START = 0x2000
        private const val DEEPSEEK_WIDE_CHAR_TOKENS = 0.6
        private const val DEFAULT_WIDE_CHAR_TOKENS = 1.0

        fun forProtocol(protocol: String): TokenEstimator = TokenEstimator(
            if (protocol == ProviderProtocol.DEEPSEEK) DEEPSEEK_WIDE_CHAR_TOKENS else DEFAULT_WIDE_CHAR_TOKENS,
        )
    }
}
