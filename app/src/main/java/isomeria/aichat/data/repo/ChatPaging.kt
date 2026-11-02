package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest

data class ChatEntry(val offset: Int = 0, val targetId: Long = 0)
data class ChatTimeline(
    val latest: Message? = null,
    val visibleLatest: Message? = null,
    val waiting: Boolean = false,
    val pending: Boolean = false,
    val now: Long = 0,
)

/** Entry and reveal queries contain no unbounded message list. Schema and request encoding stay unchanged. */
class ChatPaging(private val db: AppDatabase) {
    private val messages get() = db.messageDao()

    suspend fun entry(contactId: Long, focusId: Long): ChatEntry = db.withTransaction {
        val unread = db.conversationDao().get(contactId)?.unreadCount ?: 0
        val target = if (focusId != 0L) messages.get(focusId)?.takeIf { it.contactId == contactId }
        else if (unread > 0) messages.unreadAnchor(contactId, unread - 1) else null
        if (target == null) ChatEntry() else ChatEntry(messages.countNewer(contactId, target.createdAt, target.id), target.id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun timeline(contactId: Long): Flow<ChatTimeline> = messages.observeLatest(contactId).transformLatest { latest ->
        while (true) {
            val now = System.currentTimeMillis()
            val (visible, waiting, next) = db.withTransaction {
                Triple(messages.latestVisible(contactId, now), messages.hasSending(contactId), messages.nextRevealTime(contactId, now))
            }
            emit(ChatTimeline(latest, visible, waiting, next != null, now))
            if (next == null) break
            delay((next - System.currentTimeMillis()).coerceAtLeast(1))
        }
    }.flowOn(Dispatchers.IO)
}
