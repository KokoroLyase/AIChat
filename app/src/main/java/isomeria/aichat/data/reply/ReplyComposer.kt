package isomeria.aichat.data.reply

import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.core.ReplyRhythm
import isomeria.aichat.core.ReplySplitter
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import java.util.UUID
import kotlin.random.Random

/**
 * 把模型返回的一段正文变成要写库的几条待投递消息。普通回复（[ReplyEngine]）和主动消息（[ProactiveEngine]）共用：
 *
 * - 联系人开了「使用回复偏好」（[preferences] 不为空）：按换行拆条、去句尾标点，按估算的打字间隔排开，第一条扣掉生成用时
 * - 没开：保留旧的分条显示，开了分条的按空行拆、相隔约 1 秒，没开的一条
 * - 都以 PENDING 写入，createdAt 是计划出现的时间；思考内容和用量只记在第一条上
 */
internal object ReplyComposer {
    fun rows(
        contact: Contact,
        text: String,
        reasoning: String,
        promptTokens: Int,
        completionTokens: Int,
        startedAt: Long,
        firstContentAt: Long,
        finishedAt: Long,
        preferences: ReplyPreferences?,
        random: Random,
        origin: MessageOrigin = MessageOrigin.NORMAL,
    ): List<Message> {
        val parts = when {
            preferences != null -> ReplyRhythm.split(text, preferences)
            contact.splitReplies -> ReplySplitter.split(text).ifEmpty { listOf(text) }
            else -> listOf(text)
        }
        val times = preferences?.let { ReplyRhythm.schedule(parts, it, startedAt, finishedAt, random) }
            ?: parts.indices.map { finishedAt + it * ReplySplitter.PART_INTERVAL_MILLIS }
        val thinkingMillis = if (reasoning.isNotEmpty()) {
            (if (firstContentAt > 0) firstContentAt else finishedAt) - startedAt
        } else {
            0L
        }
        val group = UUID.randomUUID().toString()
        return parts.mapIndexed { index, part ->
            Message(
                contactId = contact.id,
                role = Role.ASSISTANT,
                type = MessageType.TEXT,
                content = part,
                reasoning = if (index == 0) reasoning.trim().ifEmpty { null } else null,
                reasoningMillis = if (index == 0) thinkingMillis else 0L,
                status = MessageStatus.PENDING,
                model = contact.model,
                promptTokens = if (index == 0) promptTokens else 0,
                completionTokens = if (index == 0) completionTokens else 0,
                replyGroup = group,
                createdAt = times[index],
                origin = origin,
            )
        }
    }
}
