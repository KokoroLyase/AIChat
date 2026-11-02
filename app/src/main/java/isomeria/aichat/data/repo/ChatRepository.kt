package isomeria.aichat.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.R
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ContactDao
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.ConversationDao
import isomeria.aichat.data.db.ConversationItem
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageDao
import isomeria.aichat.data.db.MessageSearchGroup
import isomeria.aichat.data.db.escapeLike
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.reply.ReplyScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** 聊天列表、聊天记录与收发。页面只经过这里访问消息数据和回复任务。 */
@Singleton
class ChatRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val contactDao: ContactDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val files: FileStorage,
    private val replyScheduler: ReplyScheduler,
    private val notifier: ReplyNotifier,
) {
    private val paging = ChatPaging(db)
    private val messageActions = ChatMessageActions(db)
    suspend fun chatEntry(contactId: Long, focusId: Long): ChatEntry = paging.entry(contactId, focusId)
    fun pagedMessages(contactId: Long) = messageDao.pagingByContact(contactId)
    fun timeline(contactId: Long): Flow<ChatTimeline> = paging.timeline(contactId)
    suspend fun latestVisible(contactId: Long): Message? = messageDao.latestVisible(contactId, System.currentTimeMillis())
    suspend fun newerVisibleCount(contactId: Long, anchor: Message, now: Long): Int =
        messageDao.countNewerVisible(contactId, anchor.createdAt, anchor.id, now)

    fun observeConversations(): Flow<List<ConversationItem>> = conversationDao.observeList()

    fun observeTotalUnread(): Flow<Int> = conversationDao.observeTotalUnread()

    fun observeConversation(contactId: Long): Flow<Conversation?> = conversationDao.observe(contactId)

    fun observeMessages(contactId: Long): Flow<List<Message>> = messageDao.observeByContact(contactId)

    suspend fun sendText(contactId: Long, text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        insertUserMessages(
            contactId,
            listOf(
                Message(
                    contactId = contactId,
                    role = Role.USER,
                    type = MessageType.TEXT,
                    content = content,
                    status = MessageStatus.SENDING,
                    createdAt = System.currentTimeMillis(),
                ),
            ),
        )
        conversationDao.setDraft(contactId, "")
        replyScheduler.enqueue(contactId)
    }

    /** 一次可选多张，每张单独成一条消息 */
    suspend fun sendImages(contactId: Long, uris: List<Uri>): Int {
        val now = System.currentTimeMillis()
        val rows = uris.mapIndexedNotNull { index, uri ->
            val image = runCatching { files.importChatImage(uri) }.getOrNull() ?: return@mapIndexedNotNull null
            Message(
                contactId = contactId,
                role = Role.USER,
                type = MessageType.IMAGE,
                imagePath = image.path,
                imageWidth = image.width,
                imageHeight = image.height,
                status = MessageStatus.SENDING,
                createdAt = now + index,
            )
        }
        if (rows.isEmpty()) return 0
        insertUserMessages(contactId, rows)
        replyScheduler.enqueue(contactId)
        return rows.size
    }

    private suspend fun insertUserMessages(contactId: Long, rows: List<Message>) {
        db.withTransaction {
            ensureConversation(contactId)
            messageDao.insertAll(rows)
            val last = rows.last()
            conversationDao.setLastMessage(contactId, last.createdAt, previewOf(last))
            conversationDao.markRead(contactId)
        }
    }

    /** 重试：把最后一条回复之后失败的消息重新发出 */
    suspend fun retry(contactId: Long) {
        if (messageActions.retry(contactId)) replyScheduler.enqueue(contactId)
    }

    /** 重新生成：删除最后一次回复（分条的几条一起删），重新请求 */
    suspend fun regenerate(contactId: Long) {
        if (messageActions.regenerate(contactId) { refreshLastMessage(contactId) }) replyScheduler.enqueue(contactId)
    }

    /** 取消回复：丢弃未完成的内容，等待中的消息标记失败 */
    suspend fun cancelReply(contactId: Long) {
        replyScheduler.cancel(contactId)
        messageDao.failSending(contactId, FailReason.CANCELLED.code)
    }

    suspend fun deleteMessage(id: Long) {
        val message = messageDao.get(id) ?: return
        db.withTransaction {
            messageDao.delete(id)
            refreshLastMessage(message.contactId)
        }
        files.delete(message.imagePath)
        // 删掉的是最后一条等待中的消息时，取消这次请求
        if (message.status == MessageStatus.SENDING && messageDao.sendingIds(message.contactId).isEmpty()) {
            replyScheduler.cancel(message.contactId)
        }
    }

    /** 删除聊天：只删聊天记录，不删联系人 */
    suspend fun deleteConversation(contactId: Long) {
        replyScheduler.cancel(contactId)
        val images = messageDao.imagePaths(contactId)
        db.withTransaction {
            messageDao.deleteByContact(contactId)
            conversationDao.reset(contactId)
        }
        images.forEach(files::delete)
        notifier.cancelReply(contactId)
    }

    /** 聊天信息页「清空聊天记录」：删掉消息和图片，聊天仍留在列表里（置顶和草稿保留） */
    suspend fun clearHistory(contactId: Long) {
        replyScheduler.cancel(contactId)
        val images = messageDao.imagePaths(contactId)
        db.withTransaction {
            ensureConversation(contactId)
            messageDao.deleteByContact(contactId)
            conversationDao.clearHistory(contactId)
        }
        images.forEach(files::delete)
        notifier.cancelReply(contactId)
    }

    /** 设置里「清空所有聊天记录」：所有聊天都像「删除该聊天」一样清掉，联系人保留 */
    suspend fun clearAllHistory() {
        val contacts = contactDao.listAll().map { it.id }
        contacts.forEach(replyScheduler::cancel)
        val images = messageDao.allImagePaths()
        db.withTransaction {
            messageDao.deleteAll()
            conversationDao.resetAll()
        }
        images.forEach(files::delete)
        contacts.forEach(notifier::cancelReply)
    }

    suspend fun setPinned(contactId: Long, pinned: Boolean) {
        ensureConversation(contactId)
        conversationDao.setPinned(contactId, pinned)
    }

    suspend fun markUnread(contactId: Long) = conversationDao.markUnread(contactId)

    suspend fun markRead(contactId: Long) {
        conversationDao.markRead(contactId)
        notifier.cancelReply(contactId)
    }

    suspend fun saveDraft(contactId: Long, draft: String) {
        ensureConversation(contactId)
        conversationDao.setDraft(contactId, draft)
    }

    suspend fun searchContacts(query: String): List<Contact> =
        if (query.isBlank()) emptyList() else contactDao.search(escapeLike(query.trim()))

    /**
     * 全局搜索聊天记录：按聊天分组。只命中一条的聊天带上那条消息，点击直接跳过去；
     * 命中多条的显示「N 条相关聊天记录」，点击进入这个聊天的查找结果。
     */
    suspend fun searchMessageGroups(query: String): List<MessageSearchResult> {
        if (query.isBlank()) return emptyList()
        val q = escapeLike(query.trim())
        return messageDao.searchGrouped(q).map { group ->
            val single = if (group.hitCount == 1) messageDao.searchInChat(group.contactId, q, limit = 1).firstOrNull() else null
            MessageSearchResult(group, single)
        }
    }

    /** 聊天信息页「查找聊天记录」：这个聊天里命中的文字消息，最新的在前 */
    suspend fun searchInChat(contactId: Long, query: String): List<Message> =
        if (query.isBlank()) emptyList() else messageDao.searchInChat(contactId, escapeLike(query.trim()))

    /**
     * 启动时检查：等待中但已经没有对应任务的消息（App 在等待中被系统杀掉），标记为失败，可重试。
     */
    suspend fun markInterruptedRequests() {
        for (contactId in messageDao.contactsWithSending()) {
            if (!replyScheduler.isActive(contactId)) {
                messageDao.failSending(contactId, FailReason.INTERRUPTED.code)
            }
        }
    }

    private suspend fun ensureConversation(contactId: Long) {
        if (conversationDao.get(contactId) == null) conversationDao.upsert(Conversation(contactId = contactId))
    }

    private suspend fun refreshLastMessage(contactId: Long) {
        val latest = messageDao.latest(contactId)
        if (latest == null) {
            conversationDao.setLastMessage(contactId, 0, "")
        } else {
            conversationDao.setLastMessage(contactId, latest.createdAt, previewOf(latest))
        }
    }

    private fun previewOf(message: Message): String = when (message.type) {
        MessageType.TEXT -> message.content
        MessageType.IMAGE -> context.getString(R.string.preview_image)
    }
}

/** 全局搜索里的一个聊天；[single] 是只命中一条时的那条消息 */
data class MessageSearchResult(val group: MessageSearchGroup, val single: Message?)
