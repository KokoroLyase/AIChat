package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.Role

/**
 * 重试和重新生成。网络请求在事务提交后才排队。
 *
 * 0.5.0 起投递期间发的消息，时间会夹在几条回复中间，所以边界不再用「最后一条回复」：
 * - 重试：最新一条已经得到回复（SUCCESS）的用户消息之后、失败的用户消息
 * - 重新生成：上一次回复的第一条之后的用户消息，就是最后一次回复所回答的
 *
 * 0.6.0：联系人主动发来的消息没有对应的用户消息，不能重新生成（否则会删掉它，再把更早的用户消息重发一遍）。
 */
class ChatMessageActions(private val db: AppDatabase) {
    private val messages get() = db.messageDao()
    suspend fun retry(contactId: Long): Boolean = db.withTransaction {
        val answered = messages.latestAnsweredUser(contactId)
        val failed = messages.trailingUserIds(contactId, answered?.createdAt ?: Long.MIN_VALUE, answered?.id ?: Long.MIN_VALUE, MessageStatus.FAILED)
        if (failed.isNotEmpty()) messages.setStatus(failed, MessageStatus.SENDING, 0)
        failed.isNotEmpty()
    }

    /** [last] 这次回复所回答的用户消息；不能重新生成时为空 */
    suspend fun regenerationTurn(contactId: Long, last: isomeria.aichat.data.db.Message): List<Long> {
        if (last.origin == MessageOrigin.PROACTIVE) return emptyList()
        val previous = messages.previousAssistant(contactId, last.id, last.replyGroup)
        val boundary = previous?.replyGroup?.let { messages.firstOfGroup(contactId, it) } ?: previous
        return messages.trailingUserIds(contactId, boundary?.createdAt ?: Long.MIN_VALUE, boundary?.id ?: Long.MIN_VALUE, null)
    }

    suspend fun regenerate(contactId: Long, refreshPreview: suspend () -> Unit = {}): Boolean = db.withTransaction {
        // 还有没送达的回复、或正在等回复时不能重新生成
        if (messages.hasPending(contactId) || messages.hasSending(contactId)) return@withTransaction false
        val last = messages.latestVisible(contactId) ?: return@withTransaction false
        if (last.role != Role.ASSISTANT) return@withTransaction false
        val toResend = regenerationTurn(contactId, last)
        if (toResend.isEmpty()) return@withTransaction false
        if (last.replyGroup != null) messages.deleteReplyGroup(contactId, last.replyGroup) else messages.delete(last.id)
        messages.setStatus(toResend, MessageStatus.SENDING, 0)
        refreshPreview()
        true
    }
}
