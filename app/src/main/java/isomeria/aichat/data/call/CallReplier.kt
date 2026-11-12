package isomeria.aichat.data.call

import isomeria.aichat.core.Clock
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.ContextWindow
import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.core.SpeechSentences
import isomeria.aichat.core.TokenEstimator
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.memory.NicknameSource
import isomeria.aichat.data.reply.ProactiveReminders
import isomeria.aichat.data.repo.CallHistory
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.PromptGlobalsSource
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.UsageRepository
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 一轮回复生成完的结果；[sentences] 是交出去朗读的几句 */
data class CallReply(val text: String, val sentences: Int, val firstContentAt: Long)

/**
 * 通话里请求联系人的回复（0.8.0）。和普通回复一样的系统提示词和预算内的聊天记录（通话按每一条话展开，最后一条是用户刚说的话），
 * 系统提示词多一段“现在是语音通话”（[isomeria.aichat.core.PromptGlobals.callHint]）；不在通话时没有这一段，请求和 0.7.2 一样。
 *
 * - 回复按流式接收，凑满一句（[SpeechSentences]）就交给 [reply] 的 onSentence，用来马上合成、播放
 * - 约定提醒的标记照常去掉、记下（开启主动聊天的联系人）
 * - 用量记在「聊天」；被打断（挂断、点了打断）时按已经收到的内容估算
 * - 失败时抛 [LlmException]
 */
@Singleton
class CallReplier @Inject constructor(
    private val db: AppDatabase,
    private val providers: ProviderRepository,
    private val usage: UsageRepository,
    private val nicknames: NicknameSource,
    private val files: FileStorage,
    private val contextBuilder: ContextBuilder,
    private val clock: Clock,
    private val promptGlobals: PromptGlobalsSource,
    private val reminders: ProactiveReminders,
) {
    private val calls = CallHistory(db)

    suspend fun reply(contactId: Long, onSentence: suspend (String) -> Unit): CallReply {
        val snapshot = providers.snapshot(contactId, memory = false)
        val contact = snapshot.contact
        val config = snapshot.config
        val startedAt = clock.now()
        val nickname = nicknames.nickname()
        val estimator = TokenEstimator.forProtocol(config.provider)
        val memories = MemoryPolicy.forRequest(db.memoryDao().listByContact(contactId), MemoryPolicy.memoryBudget(contact.contextTokens), estimator)
        val globals = promptGlobals.forContact(contact).copy(callHint = true)
        val system = contextBuilder.systemPrompt(contact, nickname, startedAt, memories, globals).orEmpty()
        val historyBudget = contact.contextTokens - estimator.text(system)
        val rows = calls.recent(contactId, ContextWindow.maxRows(historyBudget)).filter { it.status != MessageStatus.PENDING }
        // 用户刚说的那句一定带上（和普通回复里等待回复的消息一样，超出预算也带）
        val lastUser = rows.indexOfLast { it.role == Role.USER }
        val marked = if (lastUser < 0) rows else rows.mapIndexed { index, row -> if (index == lastUser) row.copy(status = MessageStatus.SENDING) else row }
        val history = ContextWindow.select(marked, historyBudget, estimator, snapshot.supportsImage)
        val messages = contextBuilder.build(
            contact = contact,
            history = history,
            supportsImage = snapshot.supportsImage,
            nickname = nickname,
            now = startedAt,
            memories = memories,
            globals = globals,
            loadImage = files::readAsDataUrl,
        )
        val request = snapshot.request(messages)

        val splitter = SpeechSentences()
        val content = StringBuilder()
        var firstContentAt = 0L
        var sentences = 0
        var promptTokens = 0
        var completionTokens = 0
        var completed = false
        suspend fun speak(sentence: String) {
            // 约定提醒的标记不读出来（没开主动聊天的联系人原样）
            val spoken = reminders.extract(contact, sentence, startedAt).first.trim()
            if (spoken.isEmpty() || spoken.none { it.isLetterOrDigit() }) return
            sentences++
            onSentence(spoken)
        }
        try {
            providers.provider(config).streamChat(snapshot.endpoint, request).collect { event ->
                when (event) {
                    is ChatEvent.Content -> {
                        if (firstContentAt == 0L) firstContentAt = clock.now()
                        content.append(event.delta)
                        for (sentence in splitter.accept(event.delta)) speak(sentence)
                    }
                    is ChatEvent.Usage -> {
                        promptTokens = event.promptTokens
                        completionTokens = event.completionTokens
                    }
                    is ChatEvent.Reasoning, is ChatEvent.Finished -> Unit
                }
            }
            for (sentence in splitter.finish()) speak(sentence)
            completed = true
        } finally {
            // 被打断或失败时也按已经收到的记用量（没收到任何内容的失败不记）
            if (completed || content.isNotEmpty()) {
                withContext(NonCancellable) {
                    usage.record(
                        contactId = contactId,
                        purpose = UsagePurpose.CHAT,
                        promptTokens = promptTokens.takeIf { it > 0 } ?: estimator.request(messages),
                        completionTokens = completionTokens.takeIf { it > 0 } ?: estimator.text(content.toString()),
                    )
                }
            }
        }
        val finishedAt = clock.now()
        val (text, parsed) = reminders.extract(contact, content.toString().trim(), finishedAt)
        if (parsed.isNotEmpty()) {
            reminders.save(contactId, parsed, finishedAt)
            reminders.schedule()
        }
        return CallReply(text, sentences, firstContentAt)
    }
}
