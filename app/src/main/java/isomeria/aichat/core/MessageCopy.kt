package isomeria.aichat.core

import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role

/**
 * 复制出来的文字里用到的说法（0.9.0），由调用方从 strings.xml 取。
 * [callHeader] 是通话的第一行（“语音通话 日期 时间 · 时长”），[speaker] 把说话人和这句拼成“我：……”
 */
data class CopyLabels(
    val me: String,
    val contact: String,
    val image: String,
    val voice: String,
    val asrFailed: String,
    val callHeader: (Message) -> String,
    val speaker: (name: String, text: String) -> String,
)

/**
 * 多选复制和长按通话“复制”（0.9.0）：按时间顺序，每条一行，不带说话人和时间；消息里本来的换行换成空格。
 * - 语音复制转写文字，没有转写的写“[语音]”；图片写“[图片]”
 * - 通话复制完整内容：第一行是 [CopyLabels.callHeader]，之后每句一行，带说话人；识别失败的写 [CopyLabels.asrFailed]
 * - 译文（0.9.0）不复制，只复制原文
 */
object MessageCopy {
    fun text(messages: List<Message>, turns: Map<Long, List<CallTurn>>, labels: CopyLabels): String =
        messages.sortedWith(compareBy({ it.createdAt }, { it.id }))
            .flatMap { lines(it, turns[it.id].orEmpty(), labels) }
            .joinToString("\n")

    fun lines(message: Message, turns: List<CallTurn>, labels: CopyLabels): List<String> = when (message.type) {
        MessageType.TEXT -> listOf(oneLine(message.content))
        MessageType.VOICE -> listOf(oneLine(message.content).ifEmpty { labels.voice })
        MessageType.IMAGE -> listOf(labels.image)
        MessageType.CALL -> listOf(labels.callHeader(message)) + turns.sortedWith(compareBy({ it.seq }, { it.id })).map { turn ->
            val text = if (turn.status == CallTurnStatus.ASR_FAILED || turn.text.isBlank()) labels.asrFailed else oneLine(turn.text)
            labels.speaker(if (turn.role == Role.USER) labels.me else labels.contact, text)
        }
    }

    /** 换行（含 \r\n、\r、Unicode 的行和段分隔符）换成一个空格，连着的几个也只算一个 */
    fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ").trim()

    private val LINE_BREAKS = Regex("""[ \t]*(?:\r\n|[\r\n  ])+[ \t]*""")
}
