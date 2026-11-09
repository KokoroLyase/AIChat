package isomeria.aichat.core

import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Memory
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
    /** 记忆前面的说明 */
    val memoryHeader: String,
    /** 一条记忆。参数：年、月、日、内容 */
    val memoryItem: String,
    /** 全局记忆前面的说明（0.5.0） */
    val globalMemoryHeader: String = "",
    /** 全局记忆的一个主题。参数：类别、主题 */
    val globalMemoryTopic: String = "[%1\$s / %2\$s]",
    /** 全局记忆的一条。参数：内容 */
    val globalMemoryItem: String = "- %1\$s",
    /** 回复偏好要求（0.5.0）。参数：条数（如 1～5）、每条字数 */
    val replyStyle: String = "",
    /** 条数范围。参数：最少、最多 */
    val countRange: String = "%1\$d～%2\$d",
)

/** 全局记忆里要发出去的一条：已启用、所在的类别和主题。 */
data class GlobalMemoryEntry(val category: String, val topic: String, val content: String)

/**
 * 系统提示词里由 App 统一加的内容（0.5.0）：联系人开启「使用全局记忆」时的全局记忆，
 * 开启「使用回复偏好」时的回复要求。两项都空时，系统提示词和 0.4.2 完全一样。
 */
data class PromptGlobals(
    val globalMemory: List<GlobalMemoryEntry> = emptyList(),
    val replyStyle: ReplyPreferences? = null,
) {
    companion object {
        val NONE = PromptGlobals()
    }
}

/**
 * 每次请求 = 系统提示词 + 记忆 + 预算内的聊天记录（由 [ContextWindow] 选出）。
 *
 * - 系统提示词里的 {名称} 换成联系人名称；模板里没改过的人设占位行去掉；人设、记忆、全局内容都为空时不发送
 * - 顺序：全局记忆 → 人设 → 长期记忆 → 回复偏好要求 → 当前日期、星期、时间和自己的昵称
 * - 历史回复里的思考内容不回传
 * - 只有最近 [MAX_IMAGES] 张图片带原图，更早的替换为「[图片]」；模型不支持图片时全部替换
 * - 连续同一角色的消息合并成一条（文字和图片作为同一条消息的不同部分）
 */
class ContextBuilder(
    private val labels: ContextLabels,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * @param history 按时间正序、已按预算选好
     * @param memories 这次带上的记忆，按时间正序
     * @param loadImage 读取图片文件并返回 data URL；读不到时返回 null
     */
    fun build(
        contact: Contact,
        history: List<Message>,
        supportsImage: Boolean,
        nickname: String,
        now: Long,
        memories: List<Memory> = emptyList(),
        loadImage: (String) -> String?,
        globals: PromptGlobals = PromptGlobals.NONE,
    ): List<ChatMessage> {
        val result = mutableListOf<ChatMessage>()
        systemPrompt(contact, nickname, now, memories, globals)?.let { result += ChatMessage(ChatRole.SYSTEM, it) }

        val imagesWithData = if (supportsImage) ContextWindow.imagesWithData(history) else emptySet()

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
                // 用户连发的几条用换行连接；同一次回复拆出的几条还原成空行分隔，
                // 开了回复偏好时按它的约定还原成换行分隔
                val separator = if (role == ChatRole.ASSISTANT && globals.replyStyle == null) "\n\n" else "\n"
                currentParts[currentParts.lastIndex] = ChatPart.Text(last.text + separator + part.text)
            } else {
                currentParts += part
            }
        }
        flush()
        return result
    }

    fun systemPrompt(
        contact: Contact,
        nickname: String,
        now: Long,
        memories: List<Memory> = emptyList(),
        globals: PromptGlobals = PromptGlobals.NONE,
    ): String? {
        val prompt = resolvePrompt(contact.systemPrompt, contact.name)
        val global = globalMemoryBlock(globals.globalMemory)
        val style = globals.replyStyle?.let(::replyStyleBlock).orEmpty()
        if (prompt.isBlank() && memories.isEmpty() && global.isEmpty() && style.isEmpty()) return null
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
        return listOf(global, prompt, memoryBlock(memories), style, extra).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /** 全局记忆：按类别、主题分组，顺序和「我 → 全局记忆」里一样；没有已启用的条目时为空 */
    fun globalMemoryBlock(entries: List<GlobalMemoryEntry>): String {
        val usable = entries.filter { it.content.isNotBlank() }
        if (usable.isEmpty()) return ""
        return buildString {
            append(labels.globalMemoryHeader)
            var topic: Pair<String, String>? = null
            for (entry in usable) {
                val key = entry.category to entry.topic
                if (key != topic) {
                    append('\n').append(labels.globalMemoryTopic.format(entry.category, entry.topic))
                    topic = key
                }
                append('\n').append(labels.globalMemoryItem.format(entry.content.trim()))
            }
        }.trim()
    }

    /** 回复偏好里写进提示词的部分：条数和每条字数是风格引导；「用换行分隔每一条」是 App 拆条的约定 */
    fun replyStyleBlock(preferences: ReplyPreferences): String {
        val p = preferences.normalized()
        val count = if (p.minCount == p.maxCount) p.minCount.toString() else labels.countRange.format(p.minCount, p.maxCount)
        return labels.replyStyle.format(count, p.targetLength)
    }

    private fun memoryBlock(memories: List<Memory>): String {
        if (memories.isEmpty()) return ""
        return memories.joinToString("\n", prefix = labels.memoryHeader + "\n") { memory ->
            val date = Instant.ofEpochMilli(memory.toTime).atZone(zone)
            labels.memoryItem.format(date.year, date.monthValue, date.dayOfMonth, memory.content)
        }
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
