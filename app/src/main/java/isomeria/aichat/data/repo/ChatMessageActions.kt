package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.Role

/** Mutations shared by the repository and behavior tests. Network work is scheduled only after commit. */
class ChatMessageActions(private val db: AppDatabase) {
    private val messages get() = db.messageDao()
    suspend fun retry(contactId: Long): Boolean = db.withTransaction {
        val last = messages.latestAssistant(contactId)
        val failed = messages.trailingUserIds(contactId, last?.createdAt ?: Long.MIN_VALUE, last?.id ?: Long.MIN_VALUE, MessageStatus.FAILED)
        if (failed.isNotEmpty()) messages.setStatus(failed, MessageStatus.SENDING, 0)
        failed.isNotEmpty()
    }

    suspend fun regenerate(contactId: Long, refreshPreview: suspend () -> Unit = {}): Boolean = db.withTransaction {
        val last = messages.latest(contactId) ?: return@withTransaction false
        if (last.role != Role.ASSISTANT || last.createdAt > System.currentTimeMillis() || messages.hasSending(contactId)) return@withTransaction false
        val previous = messages.previousAssistant(contactId, last.id, last.replyGroup)
        val toResend = messages.trailingUserIds(contactId, previous?.createdAt ?: Long.MIN_VALUE, previous?.id ?: Long.MIN_VALUE, null)
        if (toResend.isEmpty()) return@withTransaction false
        if (last.replyGroup != null) messages.deleteReplyGroup(contactId, last.replyGroup) else messages.delete(last.id)
        messages.setStatus(toResend, MessageStatus.SENDING, 0)
        refreshPreview()
        toResend.isNotEmpty()
    }
}
