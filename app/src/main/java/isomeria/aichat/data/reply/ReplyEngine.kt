package isomeria.aichat.data.reply

import android.util.Log
import androidx.room.withTransaction
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.ReplySplitter
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.ContactDao
import isomeria.aichat.data.db.ConversationDao
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageDao
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatRequest
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.settings.SettingsStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次「等待回复」的完整过程，在 [ReplyWorker] 里执行。
 *
 * - 接收过程中不显示气泡，正文和思考内容只在内存里分开累积
 * - 完成后一次写库：分条后的各条回复时间依次相隔约 1 秒，聊天页按时间逐条显示出来
 * - 失败（含 401/402/429/5xx/断网）时把这次等待回复的消息标记为失败，可手动重试
 * - 被取消（用户取消，或又发了新消息替换了任务）时什么也不写，由发起取消的一方处理状态
 */
@Singleton
class ReplyEngine @Inject constructor(
    private val db: AppDatabase,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val providers: ProviderRepository,
    private val settings: SettingsStore,
    private val files: FileStorage,
    private val contextBuilder: ContextBuilder,
    private val notifier: ReplyNotifier,
    private val activeChat: ActiveChatTracker,
) {
    suspend fun run(contactId: Long) {
        // 只处理此刻在等待回复的消息；之后新发的消息会由替换上来的新任务处理
        val waiting = messageDao.sendingIds(contactId)
        if (waiting.isEmpty()) return
        val contact = contactDao.get(contactId) ?: return
        val config = providers.config(contact.providerId) ?: providers.llm()
        val endpoint = providers.endpoint(config)
        if (endpoint == null) {
            fail(waiting, FailReason.NO_API_KEY)
            return
        }
        val supportsImage = providers.supportsImage(config.id, contact.model)
        val history = messageDao.recent(contactId, contact.contextCount.coerceAtLeast(1))
        val startedAt = System.currentTimeMillis()
        val messages = contextBuilder.build(
            contact = contact,
            history = history,
            supportsImage = supportsImage,
            nickname = settings.nickname.value,
            now = startedAt,
            loadImage = files::readAsDataUrl,
        )
        val request = ChatRequest(contact.model, messages, contact.thinking, contact.temperature)

        val content = StringBuilder()
        val reasoning = StringBuilder()
        var firstContentAt = 0L
        var promptTokens = 0
        var completionTokens = 0
        try {
            providers.provider(config).streamChat(endpoint, request).collect { event ->
                when (event) {
                    is ChatEvent.Reasoning -> reasoning.append(event.delta)
                    is ChatEvent.Content -> {
                        if (firstContentAt == 0L) firstContentAt = System.currentTimeMillis()
                        content.append(event.delta)
                    }
                    is ChatEvent.Usage -> {
                        promptTokens = event.promptTokens
                        completionTokens = event.completionTokens
                    }
                    is ChatEvent.Finished -> Unit
                }
            }
        } catch (e: LlmException) {
            debugLog("reply failed: ${e.error} http=${e.httpCode}")
            fail(waiting, FailReason.of(e))
            return
        }

        val text = content.toString().trim()
        if (text.isEmpty()) {
            fail(waiting, FailReason.EMPTY_REPLY)
            return
        }
        val parts = if (contact.splitReplies) ReplySplitter.split(text).ifEmpty { listOf(text) } else listOf(text)
        val finishedAt = System.currentTimeMillis()
        val thinkingMillis = if (reasoning.isNotEmpty()) {
            (if (firstContentAt > 0) firstContentAt else finishedAt) - startedAt
        } else {
            0L
        }
        val group = UUID.randomUUID().toString()
        val rows = parts.mapIndexed { index, part ->
            Message(
                contactId = contactId,
                role = Role.ASSISTANT,
                type = MessageType.TEXT,
                content = part,
                reasoning = if (index == 0) reasoning.toString().trim().ifEmpty { null } else null,
                reasoningMillis = if (index == 0) thinkingMillis else 0L,
                status = MessageStatus.SUCCESS,
                model = contact.model,
                promptTokens = if (index == 0) promptTokens else 0,
                completionTokens = if (index == 0) completionTokens else 0,
                replyGroup = group,
                createdAt = finishedAt + index * ReplySplitter.PART_INTERVAL_MILLIS,
            )
        }
        val viewing = activeChat.isViewing(contactId)
        db.withTransaction {
            messageDao.insertAll(rows)
            messageDao.setStatus(waiting, MessageStatus.SUCCESS, 0)
            conversationDao.setLastMessage(contactId, rows.last().createdAt, rows.last().content)
            if (!viewing) conversationDao.addUnread(contactId, rows.size)
        }
        debugLog("reply ok: parts=${parts.size} prompt=$promptTokens completion=$completionTokens")
        if (!activeChat.isViewing(contactId)) notifier.notifyReply(contact, parts)
    }

    /** 任务被系统中断后重新调度：不再自动重试，标记失败 */
    suspend fun failInterrupted(contactId: Long) {
        messageDao.failSending(contactId, FailReason.INTERRUPTED.code)
    }

    private suspend fun fail(ids: List<Long>, reason: FailReason) {
        messageDao.setStatus(ids, MessageStatus.FAILED, reason.code)
    }

    /** 只在 debug 包输出；不记录 API Key 和消息正文 */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "ReplyEngine"
    }
}
