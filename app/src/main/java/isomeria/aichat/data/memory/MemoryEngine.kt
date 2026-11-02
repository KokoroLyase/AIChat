package isomeria.aichat.data.memory

import android.util.Log
import androidx.room.withTransaction
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.ContextWindow
import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.core.TokenEstimator
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Memory
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatMessage
import isomeria.aichat.data.llm.ChatRole
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.UsageRepository
import javax.inject.Inject
import javax.inject.Singleton

/** 总结用到的文字，从 strings.xml 读取后传入。 */
data class MemoryLabels(
    /** 参数：联系人名称、对方称呼 */
    val summaryPrompt: String,
    /** 参数：联系人名称 */
    val mergePrompt: String,
    /** 没有设置昵称时对方的称呼 */
    val defaultMyName: String,
    val imagePlaceholder: String,
)

/** 自己的昵称。单元测试里换成固定值（MMKV 需要原生库）。 */
fun interface NicknameSource {
    fun nickname(): String
}

/** 「总结更早的聊天」的结果。 */
enum class BacklogResult {
    /** 总结了一批，可能还有更早的 */
    DONE,

    /** 没有更早的聊天了 */
    NOTHING,

    /** 这个联系人的服务商没有配置 API Key */
    NO_API_KEY,
}

/**
 * 把较早的聊天总结成记忆。
 *
 * - 使用联系人独立的记忆模型和思考设置，未指定模型时跟随服务商默认，默认空时回退聊天模型。
 *   这样 Key 一定可用，聊天内容也不会发给联系人没在用的服务商
 * - [autoRun] 在回复成功后由后台任务调用，失败时不打扰用户，下次回复后再试
 * - 等模型返回期间聊天可能被恢复数据改掉，写库前检查进度没有变
 * - 日志不记录消息和记忆的内容
 */
@Singleton
class MemoryEngine @Inject constructor(
    private val db: AppDatabase,
    private val providers: ProviderRepository,
    private val usage: UsageRepository,
    private val contextBuilder: ContextBuilder,
    private val labels: MemoryLabels,
    val tasks: MemoryTasks = MemoryTasks(),
    private val nickname: NicknameSource,
) {
    private val contactDao get() = db.contactDao()
    private val conversationDao get() = db.conversationDao()
    private val messageDao get() = db.messageDao()
    private val memoryDao get() = db.memoryDao()


    /** 一次回复成功后检查：需要时总结一批，记忆超预算时合并 */
    suspend fun autoRun(contactId: Long) {
        try {
            tasks.run(contactId) {
                val contact = contactDao.get(contactId) ?: return@run
                if (!contact.autoMemory) return@run
                val config = providers.config(contact.providerId) ?: return@run
                providers.endpoint(config) ?: throw LlmException(LlmError.AUTH)
                val estimator = TokenEstimator.forProtocol(config.provider)
                val conversation = readyConversation(contact, config, estimator) ?: return@run
                summarizeNewBatch(contact, estimator, conversation)
                mergeIfOverBudget(contact, estimator)
            }
        } catch (e: LlmException) {
            debugLog("auto memory failed: ${e.error} http=${e.httpCode}")
        }
    }

    /**
     * 记忆页「总结更早的聊天」：往前总结一批，最多 [MemoryPolicy.BATCH_MAX_TOKENS]。
     * 失败时抛 [LlmException]，由界面提示。
     */
    suspend fun summarizeBacklog(contactId: Long): BacklogResult = tasks.run(contactId) {
        val contact = contactDao.get(contactId) ?: return@run BacklogResult.NOTHING
        val config = providers.config(contact.providerId) ?: return@run BacklogResult.NOTHING
        if (providers.endpoint(config) == null) return@run BacklogResult.NO_API_KEY
        val estimator = TokenEstimator.forProtocol(config.provider)
        val conversation = readyConversation(contact, config, estimator) ?: return@run BacklogResult.NOTHING
        val generation = db.restoreGeneration.get()
        val start = conversation.memoryStart
        if (start <= 0) return@run BacklogResult.NOTHING

        val older = messageDao.before(contact.id, start, MemoryPolicy.BACKLOG_MAX_ROWS)
        val batch = MemoryPolicy.planBacklog(older, estimator)
        if (batch.isEmpty()) {
            conversationDao.setMemoryRange(contact.id, conversation.memoryCursor, 0)
            return@run BacklogResult.NOTHING
        }
        val summary = summarize(contact, batch)
        db.withTransaction {
            val current = conversationDao.get(contact.id) ?: return@withTransaction
            if (db.restoreInProgress.get() > 0 || db.restoreGeneration.get() != generation || current.memoryStart != start || contactDao.get(contact.id)?.createdAt != contact.createdAt) return@withTransaction
            summary?.let { saveSummary(contact.id, it, batch); tasks.succeeded(contact.id) }
            val newStart = batch.first().createdAt
            val rest = messageDao.countBefore(contact.id, newStart)
            conversationDao.setMemoryRange(contact.id, current.memoryCursor, if (rest > 0) newStart else 0)
        }
        BacklogResult.DONE
    }

    /** 取出会话并确定记忆起点：还没确定时，设为当前上下文窗口的开头，更早的留给手动总结 */
    private suspend fun readyConversation(contact: Contact, config: ProviderConfig, estimator: TokenEstimator): Conversation? = db.withTransaction {
        if (db.restoreInProgress.get() > 0 || contactDao.get(contact.id)?.createdAt != contact.createdAt) return@withTransaction null
        val generation = db.restoreGeneration.get()
        val conversation = conversationDao.get(contact.id) ?: return@withTransaction null
        if (conversation.memoryCursor != null) return@withTransaction conversation
        val budget = historyBudget(contact, estimator)
        val recent = messageDao.recent(contact.id, ContextWindow.maxRows(budget))
        val window = ContextWindow.select(recent, budget, estimator, providers.supportsImage(config.id, contact.model))
        val first = window.firstOrNull()?.createdAt ?: 0L
        val hasOlder = first > 0 && messageDao.countBefore(contact.id, first) > 0
        val cursor = (first - 1).coerceAtLeast(0)
        val start = if (hasOlder) first else 0L
        if (db.restoreInProgress.get() > 0 || db.restoreGeneration.get() != generation) return@withTransaction null
        conversationDao.setMemoryRange(contact.id, cursor, start)
        conversation.copy(memoryCursor = cursor, memoryStart = start)
    }

    private suspend fun summarizeNewBatch(
        contact: Contact,
        estimator: TokenEstimator,
        conversation: Conversation,
    ) {
        val generation = db.restoreGeneration.get()
        val cursor = conversation.memoryCursor ?: return
        val unsummarized = messageDao.after(contact.id, cursor)
        val batch = MemoryPolicy.planBatch(unsummarized, historyBudget(contact, estimator), estimator)
        if (batch.isEmpty()) return
        val summary = summarize(contact, batch)
        db.withTransaction {
            val current = conversationDao.get(contact.id) ?: return@withTransaction
            if (db.restoreInProgress.get() > 0 || db.restoreGeneration.get() != generation || current.memoryCursor != cursor || contactDao.get(contact.id)?.createdAt != contact.createdAt) return@withTransaction
            summary?.let { saveSummary(contact.id, it, batch); tasks.succeeded(contact.id) }
            conversationDao.setMemoryRange(contact.id, batch.last().createdAt, current.memoryStart)
        }
        debugLog("memory saved: messages=${batch.size}")
    }

    private suspend fun mergeIfOverBudget(
        contact: Contact,
        estimator: TokenEstimator,
    ) {
        val generation = db.restoreGeneration.get()
        val budget = MemoryPolicy.memoryBudget(contact.contextTokens)
        val rows = MemoryPolicy.planMerge(memoryDao.listByContact(contact.id), budget, estimator)
        if (rows.isEmpty()) return
        val text = rows.joinToString("\n") { "- ${it.content}" }
        val result = complete(contact, labels.mergePrompt.format(contact.name), text)
        db.withTransaction {
            // 等待期间用户改过或删过其中的记忆时放弃这次合并
            val still = memoryDao.listByContact(contact.id).filter { row -> rows.any { it.id == row.id } }
            if (db.restoreInProgress.get() > 0 || db.restoreGeneration.get() != generation || still != rows) return@withTransaction
            memoryDao.deleteIds(rows.map { it.id })
            val now = System.currentTimeMillis()
            memoryDao.insert(
                Memory(
                    contactId = contact.id,
                    content = result.text,
                    fromTime = rows.first().fromTime,
                    toTime = rows.last().toTime,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            tasks.succeeded(contact.id)
        }
        debugLog("memory merged: rows=${rows.size}")
    }

    /** 留给聊天记录的预算：总预算减去系统提示词和带上的记忆 */
    private suspend fun historyBudget(contact: Contact, estimator: TokenEstimator): Int {
        val memories = MemoryPolicy.forRequest(
            memoryDao.listByContact(contact.id),
            MemoryPolicy.memoryBudget(contact.contextTokens),
            estimator,
        )
        val system = contextBuilder.systemPrompt(contact, nickname.nickname(), System.currentTimeMillis(), memories).orEmpty()
        return contact.contextTokens - estimator.text(system)
    }

    /** 这批消息的总结；里面没有可总结的内容（比如都是失败的消息）时为 null */
    private suspend fun summarize(
        contact: Contact,
        batch: List<Message>,
    ): String? {
        val myName = nickname.nickname().trim().ifEmpty { labels.defaultMyName }
        val transcript = MemoryPolicy.transcript(batch, myName, contact.name, labels.imagePlaceholder)
        if (transcript.isBlank()) return null
        return complete(contact, labels.summaryPrompt.format(contact.name, myName), transcript).text
    }

    private suspend fun saveSummary(contactId: Long, content: String, batch: List<Message>) {
        val now = System.currentTimeMillis()
        memoryDao.insert(
            Memory(
                contactId = contactId,
                content = content,
                fromTime = batch.first().createdAt,
                toTime = batch.last().createdAt,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private class Completion(val text: String)

    /** 请求模型并记下用量；模型没有返回内容时抛 [LlmException] */
    private suspend fun complete(
        contact: Contact,
        system: String,
        user: String,
    ): Completion {
        val messages = listOf(ChatMessage(ChatRole.SYSTEM, system), ChatMessage(ChatRole.USER, user))
        val snapshot = providers.snapshot(contact.id, memory = true)
        val request = snapshot.request(messages)
        val content = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0
        providers.provider(snapshot.config).streamChat(snapshot.endpoint, request).collect { event ->
            when (event) {
                is ChatEvent.Content -> content.append(event.delta)
                is ChatEvent.Usage -> {
                    promptTokens = event.promptTokens
                    completionTokens = event.completionTokens
                }
                is ChatEvent.Reasoning, is ChatEvent.Finished -> Unit
            }
        }
        val text = content.toString().trim()
        if (text.isEmpty()) throw LlmException(LlmError.UNKNOWN, message = "模型没有返回内容")
        val estimator = TokenEstimator.forProtocol(snapshot.config.provider)
        usage.record(
            contactId = contact.id,
            purpose = UsagePurpose.MEMORY,
            promptTokens = promptTokens.takeIf { it > 0 } ?: estimator.request(messages),
            completionTokens = completionTokens.takeIf { it > 0 } ?: estimator.text(text),
        )
        return Completion(text.take(MemoryPolicy.CONTENT_MAX_LENGTH))
    }

    /** 只在 debug 包输出；不记录消息和记忆的内容 */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "MemoryEngine"
    }
}
