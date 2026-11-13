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
    /** 一条近期记忆。参数：年、月、日、内容 */
    val memoryItem: String,
    /** 核心记忆（0.7.2）。参数：内容 */
    val memoryCore: String = "%1\$s",
    /** 有核心记忆时，近期记忆前面的小标题 */
    val memoryRecentHeader: String = "",
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
    /** 约定时间的写法（0.6.0）：只发给开启主动聊天的联系人 */
    val reminderHint: String = "",
    /** 现在是语音通话（0.8.0）：只在通话中的请求里 */
    val callHint: String = "",
    /** 引用（0.9.0）：用户消息引用了联系人说的话、自己说的话时，前面加的一行。参数：被引用的文字（过长时截断） */
    val quoteOther: String = "",
    val quoteSelf: String = "",
)

/** 全局记忆里要发出去的一条：已启用、所在的类别和主题。 */
data class GlobalMemoryEntry(val category: String, val topic: String, val content: String)

/**
 * 系统提示词里由 App 统一加的内容（0.5.0）：联系人开启「使用全局记忆」时的全局记忆，
 * 开启「使用回复偏好」时的回复要求。两项都空时，系统提示词和 0.4.2 完全一样。
 *
 * 0.6.0：联系人开启主动聊天时，再加一段「约定时间怎么写」（[reminderHint]）；没开启时系统提示词和 0.5.0 完全一样。
 *
 * 0.8.0：通话中的请求再加一段「现在是语音通话」（[callHint]），放在约定时间的写法之后；不在通话时和 0.7.2 完全一样。
 */
data class PromptGlobals(
    val globalMemory: List<GlobalMemoryEntry> = emptyList(),
    val replyStyle: ReplyPreferences? = null,
    val reminderHint: Boolean = false,
    /** 通话中的请求（0.8.0）：加一段“现在是语音通话”；不在通话时为 false，请求和 0.7.2 一样 */
    val callHint: Boolean = false,
) {
    companion object {
        val NONE = PromptGlobals()
    }
}

/**
 * 每次请求 = 系统提示词 + 记忆 + 预算内的聊天记录（由 [ContextWindow] 选出）。
 *
 * - 系统提示词里的 {名称} 换成联系人名称；模板里没改过的人设占位行去掉；人设、记忆、全局内容都为空时不发送
 * - 顺序：全局记忆 → 人设 → 长期记忆 → 回复偏好要求 → 约定时间的写法（开启主动聊天时） → 现在是语音通话（通话中，0.8.0） →
 *   当前日期、星期、时间和自己的昵称
 * - 历史回复里的思考内容不回传
 * - 只有最近 [MAX_IMAGES] 张图片带原图，更早的替换为「[图片]」；模型不支持图片时全部替换
 * - 连续同一角色的消息合并成一条（文字和图片作为同一条消息的不同部分）
 * - 引用了别的消息的用户消息（0.9.0）前面加一行 [ContextLabels.quoteOther] 或 [ContextLabels.quoteSelf]；没有引用的和 0.8.0 逐字一致
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
                MessageType.TEXT -> ChatPart.Text(textOf(message))
                // 语音（0.7.0）只发转写文字；还没识别出文字的语音是空的，下面会跳过
                MessageType.VOICE -> ChatPart.Text(if (message.content.isBlank()) message.content else textOf(message))
                MessageType.IMAGE -> {
                    val data = if (message.id in imagesWithData) message.imagePath?.let(loadImage) else null
                    if (data != null) ChatPart.Image(data) else ChatPart.Text(labels.imagePlaceholder)
                }
                // 通话（0.8.0）由调用方先展开成每一条话（CallTranscript）；没展开的通话消息没有内容，下面会跳过
                MessageType.CALL -> ChatPart.Text(message.content)
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

    /**
     * 文字和语音转写在请求里的样子（0.9.0）：引用了别的消息的用户消息前面加一行“（回复你之前说的“……”）”，
     * 被引用的文字超过 [QUOTE_MAX_CHARS] 个字时截断加“…”；没有引用时就是原文
     */
    fun textOf(message: Message): String {
        val line = quoteLine(message) ?: return message.content
        return line + "\n" + message.content
    }

    /** 引用的那一行；没有引用、不是用户消息、没配说法时为 null */
    fun quoteLine(message: Message): String? {
        val quoted = message.quoteText ?: return null
        if (message.role != Role.USER) return null
        val template = if (message.quoteRole == Role.USER) labels.quoteSelf else labels.quoteOther
        if (template.isEmpty()) return null
        return template.format(truncateQuote(quoted))
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
        val reminder = if (globals.reminderHint) labels.reminderHint else ""
        val call = if (globals.callHint) labels.callHint else ""
        if (prompt.isBlank() && memories.isEmpty() && global.isEmpty() && style.isEmpty() && reminder.isEmpty() && call.isEmpty()) return null
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
        return listOf(global, prompt, memoryBlock(memories), style, reminder, call, extra).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /**
     * 主动请求（0.6.0）：在拼好的消息后面追加一条只在这次请求里的 user 指令，不存库。
     * 历史以助手消息结束或为空时单独成一条；以用户消息结束时（例如发送失败的消息）并进那一条，保持角色交替。
     */
    fun appendInstruction(messages: List<ChatMessage>, instruction: String): List<ChatMessage> {
        val last = messages.lastOrNull()
        if (last == null || last.role != ChatRole.USER) return messages + ChatMessage(ChatRole.USER, instruction)
        val parts = last.parts.toMutableList()
        val tail = parts.lastOrNull()
        if (tail is ChatPart.Text) {
            parts[parts.lastIndex] = ChatPart.Text(tail.text + "\n\n" + instruction)
        } else {
            parts += ChatPart.Text(instruction)
        }
        return messages.dropLast(1) + ChatMessage(ChatRole.USER, parts)
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

    /** 核心记忆在前（0.7.2），近期记忆按时间正序、带日期；没有核心记忆时和 0.7.1 完全一样 */
    private fun memoryBlock(memories: List<Memory>): String {
        if (memories.isEmpty()) return ""
        val core = memories.firstOrNull { it.core }
        val recent = memories.filterNot { it.core }.map { memory ->
            val date = Instant.ofEpochMilli(memory.toTime).atZone(zone)
            labels.memoryItem.format(date.year, date.monthValue, date.dayOfMonth, memory.content)
        }
        val lines = buildList {
            add(labels.memoryHeader)
            if (core != null) {
                add(labels.memoryCore.format(core.content.trim()))
                if (recent.isNotEmpty() && labels.memoryRecentHeader.isNotEmpty()) add(labels.memoryRecentHeader)
            }
            addAll(recent)
        }
        return lines.joinToString("\n")
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

        /** 请求里引用的文字最多带这么多字（按 Unicode 字符算），多了截断加“…” */
        const val QUOTE_MAX_CHARS = 100

        /** 引用的文字：换行换成空格，超过 [QUOTE_MAX_CHARS] 个字截断加“…”（不切开 emoji 这类两个 char 的字） */
        fun truncateQuote(text: String): String {
            val flat = MessageCopy.oneLine(text)
            if (flat.codePointCount(0, flat.length) <= QUOTE_MAX_CHARS) return flat
            return flat.substring(0, flat.offsetByCodePoints(0, QUOTE_MAX_CHARS)) + "…"
        }
    }
}
