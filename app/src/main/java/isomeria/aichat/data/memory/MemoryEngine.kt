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
import isomeria.aichat.data.repo.PromptGlobalsSource
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.UsageRepository
import javax.inject.Inject
import javax.inject.Singleton

/** 总结和整理用到的文字，从 strings.xml 读取后传入。 */
data class MemoryLabels(
    /** 参数：联系人名称、对方称呼 */
    val summaryPrompt: String,
    /** 整理核心记忆的指令。参数：联系人名称、核心记忆最多多少字（[MemoryPolicy.coreMaxChars]） */
    val foldPrompt: String,
    /** 没有设置昵称时对方的称呼 */
    val defaultMyName: String,
    val imagePlaceholder: String,
    /** 这段聊天没有值得记的内容时，模型按总结指令回答的这个词（strings.xml 里是「无」） */
    val nothingReply: String = "none",
    /** 整理时发给模型的内容。参数：现有的核心记忆（没有时是 [foldNoCore]）、要整理的近期记忆 */
    val foldInput: String = "%1\$s\n\n%2\$s",
    val foldNoCore: String = "-",
    /** 要整理的一条近期记忆。参数：年、月、日、内容 */
    val foldItem: String = "- [%1\$d-%2\$d-%3\$d] %4\$s",
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
 * 把较早的聊天总结成记忆（0.7.2 起分两层，规则见 [MemoryPolicy]）。
 *
 * - 使用联系人独立的记忆模型和思考设置，未指定模型时跟随服务商默认，默认空时回退聊天模型。
 *   这样 Key 一定可用，聊天内容也不会发给联系人没在用的服务商
 * - [autoRun] 在回复成功后由后台任务调用，失败时不打扰用户，下次回复后再试
 * - 等模型返回期间聊天可能被恢复数据改掉，写库前检查进度没有变
 * - 日志不记录消息和记忆的内容
 * - 总结和整理的请求只用各自的指令，不带全局记忆和回复偏好要求；
 *   但判断何时触发时，系统提示词按聊天请求的完整内容算（[historyBudget]），和聊天一致
 */
@Singleton
class MemoryEngine @Inject constructor(
    private val db: AppDatabase,
    private val providers: ProviderRepository,
    private val usage: UsageRepository,
    private val contextBuilder: ContextBuilder,
    private val labels: MemoryLabels,
    private val promptGlobals: PromptGlobalsSource = PromptGlobalsSource.NONE,
    val tasks: MemoryTasks = MemoryTasks(),
    private val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    private val nickname: NicknameSource,
) {
    private val contactDao get() = db.contactDao()
    private val conversationDao get() = db.conversationDao()
    private val messageDao get() = db.messageDao()
    private val memoryDao get() = db.memoryDao()
    /** 通话（0.8.0）按每一条话总结；没有通话时和直接查消息一样 */
    private val calls = isomeria.aichat.data.repo.CallHistory(db)


    /** 一次回复成功后检查：需要时总结一批，近期记忆超出时整理一次核心记忆 */
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
                foldIfOverBudget(contact, estimator)
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

        val older = calls.before(contact.id, start, MemoryPolicy.BACKLOG_MAX_ROWS)
        val batch = MemoryPolicy.planBacklog(older, estimator)
        if (batch.isEmpty()) {
            conversationDao.setMemoryRange(contact.id, conversation.memoryCursor, 0)
            return@run BacklogResult.NOTHING
        }
        val summary = summarize(contact, batch)
        db.withTransaction {
            val current = conversationDao.get(contact.id) ?: return@withTransaction
            if (db.restoreInProgress.get() > 0 || db.restoreGeneration.get() != generation || current.memoryStart != start || contactDao.get(contact.id)?.createdAt != contact.createdAt) return@withTransaction
            save(contact.id, summary, batch)
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
        val recent = calls.recent(contact.id, ContextWindow.maxRows(budget)).filter { it.status != isomeria.aichat.data.db.MessageStatus.PENDING }
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
        val unsummarized = calls.after(contact.id, cursor)
        val batch = MemoryPolicy.planBatch(unsummarized, historyBudget(contact, estimator), estimator)
        if (batch.isEmpty()) return
        val summary = summarize(contact, batch)
        db.withTransaction {
            val current = conversationDao.get(contact.id) ?: return@withTransaction
            if (db.restoreInProgress.get() > 0 || db.restoreGeneration.get() != generation || current.memoryCursor != cursor || contactDao.get(contact.id)?.createdAt != contact.createdAt) return@withTransaction
            save(contact.id, summary, batch)
            conversationDao.setMemoryRange(contact.id, batch.last().createdAt, current.memoryStart)
        }
        debugLog("memory saved: messages=${batch.size}")
    }

    /**
     * 近期记忆超出时整理一次：把最早的几条和现有的核心记忆一起交给模型，换成更新后的核心记忆。
     * 一次最多处理 [MemoryPolicy.FOLD_MAX_TOKENS]，升级前积累的大量记忆在之后的几次回复里陆续整理完
     */
    private suspend fun foldIfOverBudget(
        contact: Contact,
        estimator: TokenEstimator,
    ) {
        val generation = db.restoreGeneration.get()
        val all = memoryDao.listByContact(contact.id)
        val budget = MemoryPolicy.memoryBudget(contact.contextTokens)
        val rows = MemoryPolicy.planFold(all, budget, estimator)
        if (rows.isEmpty()) return
        val core = all.firstOrNull { it.core }
        val recent = rows.joinToString("\n") { memory ->
            val date = java.time.Instant.ofEpochMilli(memory.toTime).atZone(zone)
            labels.foldItem.format(date.year, date.monthValue, date.dayOfMonth, memory.content)
        }
        val input = labels.foldInput.format(core?.content ?: labels.foldNoCore, recent)
        val result = complete(contact, labels.foldPrompt.format(contact.name, MemoryPolicy.coreMaxChars(budget, estimator)), input)
        db.withTransaction {
            // 等待期间用户改过、删过其中的记忆或核心记忆时放弃这次整理
            val current = memoryDao.listByContact(contact.id)
            val still = current.filter { row -> rows.any { it.id == row.id } }
            if (db.restoreInProgress.get() > 0 || db.restoreGeneration.get() != generation || still != rows || current.firstOrNull { it.core } != core) return@withTransaction
            memoryDao.deleteIds(rows.map { it.id })
            val now = System.currentTimeMillis()
            if (core != null) {
                memoryDao.update(
                    core.copy(
                        content = result.text,
                        fromTime = minOf(core.fromTime, rows.first().fromTime),
                        toTime = maxOf(core.toTime, rows.last().toTime),
                        edited = false,
                        updatedAt = now,
                    ),
                )
            } else {
                memoryDao.insert(
                    Memory(
                        contactId = contact.id,
                        content = result.text,
                        fromTime = rows.first().fromTime,
                        toTime = rows.last().toTime,
                        createdAt = now,
                        updatedAt = now,
                        core = true,
                    ),
                )
            }
            tasks.succeeded(contact.id)
        }
        debugLog("memory folded: rows=${rows.size}")
    }

    /** 留给聊天记录的预算：总预算减去系统提示词和带上的记忆 */
    private suspend fun historyBudget(contact: Contact, estimator: TokenEstimator): Int {
        val memories = MemoryPolicy.forRequest(
            memoryDao.listByContact(contact.id),
            MemoryPolicy.memoryBudget(contact.contextTokens),
            estimator,
        )
        val globals = promptGlobals.forContact(contact)
        val system = contextBuilder.systemPrompt(contact, nickname.nickname(), System.currentTimeMillis(), memories, globals).orEmpty()
        return contact.contextTokens - estimator.text(system)
    }

    /**
     * 一批消息的总结结果。[text] 为 null 表示不生成记忆：里面没有可总结的内容（比如都是失败的消息，[requested] 为 false），
     * 或者模型回答这段没有值得记的（[requested] 为 true）
     */
    private class Summary(val text: String?, val requested: Boolean)

    private suspend fun summarize(
        contact: Contact,
        batch: List<Message>,
    ): Summary {
        val myName = nickname.nickname().trim().ifEmpty { labels.defaultMyName }
        val transcript = MemoryPolicy.transcript(batch, myName, contact.name, labels.imagePlaceholder)
        if (transcript.isBlank()) return Summary(null, requested = false)
        val text = complete(contact, labels.summaryPrompt.format(contact.name, myName), transcript).text
        return Summary(text.takeUnless(::isNothing), requested = true)
    }

    /** 模型回答「无」（可能带句号、引号）时这段没有值得记的 */
    private fun isNothing(text: String): Boolean =
        text.trim().trim { it in NOTHING_PUNCTUATION }.trim() == labels.nothingReply

    private suspend fun save(contactId: Long, summary: Summary, batch: List<Message>) {
        summary.text?.let { saveSummary(contactId, it, batch) }
        if (summary.requested) tasks.succeeded(contactId)
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

        /** 模型回答「无」时可能带的标点和引号 */
        const val NOTHING_PUNCTUATION = "。.!！\"“”「」"
    }
}
