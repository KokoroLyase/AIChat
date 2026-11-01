package isomeria.aichat.core

import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.llm.ChatMessage
import isomeria.aichat.data.llm.ChatPart
import isomeria.aichat.data.llm.ChatRole
import java.time.Instant
import java.time.ZoneId

/** 上下文拼装用到的文字，从 strings.xml 读取后传入。 */
data class ContextLabels(
    /** 系统提示词里代表联系人名称的占位符：{名称} */
    val namePlaceholder: String,
    /** 模板里「在这里写人设」那一行的开头，用户没改时整行去掉 */
    val personaPlaceholderPrefix: String,
    /** 参数：年、月、日、星期、时、分 */
    val timeLine: String,
    /** 参数：昵称 */
    val nicknameLine: String,
    /** 星期一到星期日 */
    val weekdays: List<String>,
    /** 不带原图的图片在请求里的文字：[图片] */
    val imagePlaceholder: String,
)

/**
 * 每次请求 = 系统提示词 + 最近 N 条消息。
 *
 * - 系统提示词里的 {名称} 换成联系人名称；模板里没改过的人设占位行去掉；为空时不发送
 * - 系统提示词末尾附上当前日期、星期、时间和自己的昵称
 * - 历史回复里的思考内容不回传
 * - 只有最近 [MAX_IMAGES] 张图片带原图，更早的替换为「[图片]」；模型不支持图片时全部替换
 * - 连续同一角色的消息合并成一条（文字和图片作为同一条消息的不同部分）
 */
class ContextBuilder(
    private val labels: ContextLabels,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * @param history 按时间正序、已截取最近 N 条
     * @param loadImage 读取图片文件并返回 data URL；读不到时返回 null
     */
    fun build(
        contact: Contact,
        history: List<Message>,
        supportsImage: Boolean,
        nickname: String,
        now: Long,
        loadImage: (String) -> String?,
    ): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        systemPrompt(contact, nickname, now)?.let { result += ChatMessage(ChatRole.SYSTEM, it) }

        val imagesWithData = if (supportsImage) {
            history.filter { it.type == MessageType.IMAGE && it.imagePath != null }
                .takeLast(MAX_IMAGES)
                .map { it.id }
                .toSet()
        } else {
            emptySet()
        }

        var currentRole: ChatRole? = null
        val currentParts = mutableListOf<ChatPart>()

        fun flush() {
            val role = currentRole ?: return
            if (currentParts.isNotEmpty()) result += ChatMessage(role, currentParts.toList())
            currentParts.clear()
        }

        for (message in history) {
            val role = if (message.role == Role.USER) ChatRole.USER else ChatRole.ASSISTANT
            val part: ChatPart = when (message.type) {
                MessageType.TEXT -> ChatPart.Text(message.content)
                MessageType.IMAGE -> {
                    val data = if (message.id in imagesWithData) message.imagePath?.let(loadImage) else null
                    if (data != null) ChatPart.Image(data) else ChatPart.Text(labels.imagePlaceholder)
                }
            }
            if (part is ChatPart.Text && part.text.isBlank()) continue
            if (role != currentRole) {
                flush()
                currentRole = role
            }
            val last = currentParts.lastOrNull()
            if (last is ChatPart.Text && part is ChatPart.Text) {
                // 用户连发的几条用换行连接；同一次回复拆出的几条还原成空行分隔
                val separator = if (role == ChatRole.ASSISTANT) "\n\n" else "\n"
                currentParts[currentParts.lastIndex] = ChatPart.Text(last.text + separator + part.text)
            } else {
                currentParts += part
            }
        }
        flush()
        return result
    }

    fun systemPrompt(contact: Contact, nickname: String, now: Long): String? {
        val prompt = resolvePrompt(contact.systemPrompt, contact.name)
        if (prompt.isBlank()) return null
        val time = Instant.ofEpochMilli(now).atZone(zone)
        val extra = buildString {
            append(
                labels.timeLine.format(
                    time.year,
                    time.monthValue,
                    time.dayOfMonth,
                    labels.weekdays[time.dayOfWeek.value - 1],
                    time.hour,
                    time.minute,
                ),
            )
            if (nickname.isNotBlank()) append(labels.nicknameLine.format(nickname.trim()))
        }
        return prompt + "\n\n" + extra
    }

    /** 替换 {名称}，去掉没改过的人设占位行 */
    fun resolvePrompt(template: String, name: String): String =
        template.lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith(labels.personaPlaceholderPrefix) && trimmed.endsWith("}")
            }
            .joinToString("\n")
            .replace(labels.namePlaceholder, name)
            .trim()

    companion object {
        const val MAX_IMAGES = 3
    }
}
