package isomeria.aichat.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
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
import isomeria.aichat.data.reply.ReplyNotifications
import isomeria.aichat.data.reply.ReplyQueue
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
    private val replyScheduler: ReplyQueue,
    private val notifier: ReplyNotifications,
    private val previews: MessagePreviewer = MessagePreviewer.PLAIN,
    /** 0.8.0：删掉或清空正在通话的聊天前先挂断 */
    private val calls: isomeria.aichat.data.call.CallControl = isomeria.aichat.data.call.CallControl.NONE,
) : isomeria.aichat.data.voice.VoiceQueueSource {
    private val paging = ChatPaging(db)
    private val messageActions = ChatMessageActions(db)
    suspend fun chatEntry(contactId: Long, focusId: Long): ChatEntry = paging.entry(contactId, focusId)
    fun pagedMessages(contactId: Long) = AbsoluteRefreshPagingSource(messageDao.pagingByContact(contactId))
    fun timeline(contactId: Long): Flow<ChatTimeline> = paging.timeline(contactId)
    suspend fun latestVisible(contactId: Long): Message? = messageDao.latestVisible(contactId)
    suspend fun newerVisibleCount(contactId: Long, anchor: Message): Int =
        messageDao.countNewerVisible(contactId, anchor.createdAt, anchor.id)

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

    /**
     * 发送一条语音（0.7.0）。录音文件 [file] 已经在 files/voice 里并固定着；先显示语音条（等待识别，content 为空），
     * 回复任务先识别出文字、识别成功后才进入等待回复，识别失败时这条标为失败、不请求模型，点击可重试
     */
    suspend fun sendVoice(contactId: Long, file: java.io.File, millis: Long) = StorageOperations.gate.activity {
        try {
            insertUserMessages(
                contactId,
                listOf(
                    Message(
                        contactId = contactId,
                        role = Role.USER,
                        type = MessageType.VOICE,
                        voicePath = file.absolutePath,
                        voiceMillis = millis,
                        voicePlayed = true,
                        status = MessageStatus.SENDING,
                        createdAt = System.currentTimeMillis(),
                    ),
                ),
            )
        } catch (e: Throwable) {
            files.delete(file.absolutePath)
            throw e
        } finally {
            files.commitAvatar(file.absolutePath)
        }
        replyScheduler.enqueue(contactId)
    }

    /** 点开播放过（或转了文字）：红点去掉 */
    override suspend fun markVoicePlayed(id: Long) = messageDao.setVoicePlayed(id)

    /** 「转文字」/「取消转文字」 */
    suspend fun setVoiceShowText(id: Long, show: Boolean) = messageDao.setVoiceShowText(id, show)

    /** 连续朗读、依次播放：某条之后的语音消息 */
    override suspend fun voiceAfter(message: Message): List<Message> = messageDao.voiceAfter(message.contactId, message.createdAt, message.id)

    override suspend fun message(id: Long): Message? = messageDao.get(id)

    /** 一次可选多张，每张单独成一条消息 */
    suspend fun sendImages(contactId: Long, uris: List<Uri>): Int = StorageOperations.gate.activity { sendImagesProtected(contactId, uris) }

    private suspend fun sendImagesProtected(contactId: Long, uris: List<Uri>): Int {
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
            // 用户发言了：连续没回复的主动消息清零，暂停的主动聊天恢复
            conversationDao.clearProactiveUnanswered(contactId)
        }
    }

    /** 重试：把最后一条回复之后失败的消息重新发出 */
    suspend fun retry(contactId: Long) {
        if (messageActions.retry(contactId)) replyScheduler.enqueue(contactId)
    }

    /** 重新生成：删除最后一次回复（分条的几条一起删），重新请求 */
    suspend fun regenerate(contactId: Long) {
        val removed = mutableListOf<String>()
        val ok = messageActions.regenerate(contactId, removed) { refreshLastMessage(contactId) }
        removed.forEach(files::delete)
        if (ok) replyScheduler.enqueue(contactId)
    }

    /**
     * 取消回复：丢弃未完成的内容，等待中的消息标记失败。已经生成、等着投递的回复照常送达；
     * 还在合成的语音回复不再合成，改成文字送达（0.7.0）
     */
    suspend fun cancelReply(contactId: Long) {
        replyScheduler.cancel(contactId)
        messageDao.failSending(contactId, FailReason.CANCELLED.code)
        val held = messageDao.awaitingSynthesis(contactId)
        if (held.isNotEmpty()) {
            messageDao.degradeToText(held.map { it.id })
            replyScheduler.enqueueDelivery(contactId)
        }
    }

    suspend fun deleteMessage(id: Long) {
        val message = messageDao.get(id) ?: return
        // 通话（0.8.0）：每一条话的音频一起删；正在进行的通话不能删
        if (message.callOngoing) return
        val callAudio = if (message.type == MessageType.CALL) db.callDao().audioPaths(id) else emptyList()
        db.withTransaction {
            messageDao.delete(id)
            if (message.type == MessageType.CALL) db.callDao().deleteOrphans()
            refreshLastMessage(message.contactId)
        }
        files.delete(message.imagePath)
        files.delete(message.voicePath)
        callAudio.forEach(files::delete)
        // 删掉的是最后一条等待中的消息时，取消这次请求
        if (message.status == MessageStatus.SENDING && messageDao.sendingIds(message.contactId).isEmpty()) {
            replyScheduler.cancel(message.contactId)
        }
    }

    /**
     * 多选删除（0.9.0）：规则和 [deleteMessage] 一样（通话连同每一条话、文件在事务后删），只是一个事务删完、每个聊天的预览只刷新一次。
     * 等待回复中的消息、待投递的回复、正在进行的通话不删（多选里本来也选不了）。返回删了几条
     */
    suspend fun deleteMessages(ids: Collection<Long>): Int {
        val messages = ids.distinct().chunked(ID_CHUNK).flatMap { messageDao.getByIds(it) }.filter(::isSelectable)
        if (messages.isEmpty()) return 0
        val calls = messages.filter { it.type == MessageType.CALL }.map { it.id }
        val callAudio = calls.flatMap { db.callDao().audioPaths(it) }
        db.withTransaction {
            messages.map { it.id }.chunked(ID_CHUNK).forEach { messageDao.deleteByIds(it) }
            if (calls.isNotEmpty()) db.callDao().deleteOrphans()
            messages.mapTo(LinkedHashSet()) { it.contactId }.forEach { refreshLastMessage(it) }
        }
        for (message in messages) {
            files.delete(message.imagePath)
            files.delete(message.voicePath)
        }
        callAudio.forEach(files::delete)
        return messages.size
    }

    /** 多选里还在、还能选的消息（0.9.0）：删掉或变成不能选的从选择里去掉 */
    suspend fun selectableIds(ids: Collection<Long>): Set<Long> =
        ids.distinct().chunked(ID_CHUNK).flatMap { messageDao.getByIds(it) }.filter(::isSelectable).mapTo(LinkedHashSet()) { it.id }

    /** 多选复制和长按通话复制（0.9.0）的文字，见 [isomeria.aichat.core.MessageCopy] */
    suspend fun copyText(contactId: Long, ids: Collection<Long>): String {
        val messages = ids.distinct().chunked(ID_CHUNK).flatMap { messageDao.getByIds(it) }.filter { it.status != MessageStatus.PENDING }
        val calls = messages.filter { it.type == MessageType.CALL }.map { it.id }
        val turns = if (calls.isEmpty()) emptyMap() else db.callDao().turnsOf(calls).groupBy { it.messageId }
        val name = contactDao.get(contactId)?.name.orEmpty()
        return isomeria.aichat.core.MessageCopy.text(messages, turns, copyLabels(context, name))
    }

    /** 删除聊天：只删聊天记录，不删联系人 */
    suspend fun deleteConversation(contactId: Long) {
        calls.hangUpFor(contactId)
        replyScheduler.cancel(contactId)
        replyScheduler.cancelDelivery(contactId)
        val images = messageDao.imagePaths(contactId) + messageDao.voicePaths(contactId)
        val background = conversationDao.get(contactId)?.backgroundPath
        db.withTransaction {
            messageDao.deleteByContact(contactId)
            db.callDao().deleteOrphans()
            conversationDao.reset(contactId)
            // 约好的提醒是聊天里说的，聊天删了就不再提醒
            db.proactiveDao().deleteRemindersOf(contactId)
        }
        images.forEach(files::delete)
        files.delete(background)
        notifier.cancelReply(contactId)
    }

    /** 聊天信息页「清空聊天记录」：删掉消息、图片和语音，聊天仍留在列表里（置顶和草稿保留） */
    suspend fun clearHistory(contactId: Long) {
        calls.hangUpFor(contactId)
        replyScheduler.cancel(contactId)
        replyScheduler.cancelDelivery(contactId)
        val images = messageDao.imagePaths(contactId) + messageDao.voicePaths(contactId)
        db.withTransaction {
            ensureConversation(contactId)
            messageDao.deleteByContact(contactId)
            db.callDao().deleteOrphans()
            conversationDao.clearHistory(contactId)
            db.proactiveDao().deleteRemindersOf(contactId)
        }
        images.forEach(files::delete)
        notifier.cancelReply(contactId)
    }

    /** 设置里「清空所有聊天记录」：所有聊天都像「删除该聊天」一样清掉，联系人保留 */
    suspend fun clearAllHistory() {
        calls.hangUpFor(null)
        val contacts = contactDao.listAll().map { it.id }
        contacts.forEach(replyScheduler::cancel)
        contacts.forEach(replyScheduler::cancelDelivery)
        val images = messageDao.allImagePaths() + messageDao.allVoicePaths()
        db.withTransaction {
            messageDao.deleteAll()
            db.callDao().deleteAll()
            conversationDao.resetAll()
            db.proactiveDao().deleteAllReminders()
        }
        images.forEach(files::delete)
        contacts.forEach(notifier::cancelReply)
    }

    /** 消息免打扰：只影响回复到达通知 */
    suspend fun setMuted(contactId: Long, muted: Boolean) {
        ensureConversation(contactId)
        conversationDao.setMuted(contactId, muted)
        // 已经弹出的这个联系人的通知也收起
        if (muted) notifier.cancelReply(contactId)
    }

    /** 设置当前聊天背景：按聊天图片的压缩流程存到 files/background，写库成功后再删旧文件 */
    suspend fun setBackground(contactId: Long, uri: Uri) = setBackground(contactId) { files.importBackground(uri) }

    internal suspend fun setBackground(contactId: Long, import: suspend () -> StoredImage) = StorageOperations.gate.activity {
        val image = import()
        val old = try {
            db.withTransaction {
                ensureConversation(contactId)
                val previous = conversationDao.get(contactId)?.backgroundPath
                conversationDao.setBackground(contactId, image.path)
                previous
            }
        } catch (e: Throwable) {
            files.delete(image.path)
            throw e
        } finally {
            files.commitAvatar(image.path)
        }
        if (old != image.path) files.delete(old)
    }

    /** 恢复默认背景：去掉引用并删除背景文件 */
    suspend fun clearBackground(contactId: Long) {
        val old = conversationDao.get(contactId)?.backgroundPath ?: return
        conversationDao.setBackground(contactId, null)
        files.delete(old)
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
            val single = if (group.hitCount == 1) messageDao.searchInChat(group.contactId, q, limit = 1).firstOrNull()?.let { withCallHit(it, query.trim()) } else null
            MessageSearchResult(group, single)
        }
    }

    /**
     * 搜索结果里的通话（0.8.0）：通话消息本身没有文字，显示命中的那几条话（用换行连起来），方便高亮；只用来显示，不写库
     */
    private suspend fun withCallHit(message: Message, query: String): Message {
        if (message.type != MessageType.CALL) return message
        val hits = db.callDao().turns(message.id).filter { it.text.contains(query, ignoreCase = true) }.map { it.text }
        return message.copy(content = hits.joinToString("\n"))
    }

    /** 聊天信息页「查找聊天记录」：这个聊天里命中的文字消息，最新的在前 */
    suspend fun searchInChat(contactId: Long, query: String): List<Message> =
        if (query.isBlank()) emptyList() else messageDao.searchInChat(contactId, escapeLike(query.trim())).map { withCallHit(it, query.trim()) }

    /**
     * 启动时检查：等待中但已经没有对应任务的消息（App 在等待中被系统杀掉），标记为失败，可重试。
     * 只看等待回复的用户消息；已经生成、待投递的回复（PENDING）不受影响，由 [isomeria.aichat.data.reply.DeliveryEngine.recover] 送达。
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
        val latest = messageDao.latestVisible(contactId)
        if (latest == null) {
            conversationDao.setLastMessage(contactId, 0, "")
        } else {
            conversationDao.setLastMessage(contactId, latest.createdAt, previewOf(latest))
        }
    }

    private fun previewOf(message: Message): String = previews.preview(message)
}

/**
 * 多选（0.9.0）能不能选这条：等待回复中的消息（含还在识别的语音）、待投递的回复、正在进行的通话不能选
 */
fun isSelectable(message: Message): Boolean =
    message.status != MessageStatus.SENDING && message.status != MessageStatus.PENDING && !message.callOngoing

/** 复制（0.9.0）用到的说法：聊天和收藏共用 */
fun copyLabels(context: Context, contactName: String): isomeria.aichat.core.CopyLabels {
    val time = java.time.format.DateTimeFormatter.ofPattern(context.getString(isomeria.aichat.R.string.copy_call_time_pattern))
    return isomeria.aichat.core.CopyLabels(
        me = context.getString(isomeria.aichat.R.string.copy_me),
        contact = contactName,
        image = context.getString(isomeria.aichat.R.string.copy_image),
        voice = context.getString(isomeria.aichat.R.string.copy_voice),
        asrFailed = context.getString(isomeria.aichat.R.string.call_turn_asr_failed),
        callHeader = { call ->
            val start = java.time.Instant.ofEpochMilli(call.createdAt).atZone(java.time.ZoneId.systemDefault())
            context.getString(isomeria.aichat.R.string.copy_call_header, time.format(start), isomeria.aichat.core.CallDurations.format(call.voiceMillis))
        },
        speaker = { speaker, text -> context.getString(isomeria.aichat.R.string.copy_speaker_line, speaker, text) },
    )
}

/** 按 id 查或删消息时每批的个数，免得超过 SQLite 的参数上限 */
private const val ID_CHUNK = 500

/** 全局搜索里的一个聊天；[single] 是只命中一条时的那条消息 */
data class MessageSearchResult(val group: MessageSearchGroup, val single: Message?)
