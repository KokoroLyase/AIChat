package isomeria.aichat.data.reply

import android.util.Log
import androidx.room.withTransaction
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.ContextWindow
import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.core.TokenEstimator
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.ContactDao
import isomeria.aichat.data.db.MemoryDao
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.db.ConversationDao
import isomeria.aichat.data.db.MessageDao
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatRequest
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.core.Clock
import isomeria.aichat.core.ReplyPreferencesSource
import isomeria.aichat.core.ReplyRhythm
import isomeria.aichat.core.Sleeper
import kotlin.random.Random
import isomeria.aichat.data.memory.NicknameSource
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.PromptGlobalsSource
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.UsageRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次「等待回复」的完整过程，在 [ReplyWorker] 里执行。
 *
 * - 联系人开了「使用回复偏好」时，先等用户最后一条消息之后的设定秒数（期间又发消息，这个任务会被新任务替换）
 * - 接收过程中不显示气泡，正文和思考内容只在内存里分开累积
 * - 完成后一次写库。回复偏好开启时按换行拆条、去句尾标点，各条按估算的打字间隔排开，第一条扣掉生成用掉的时间；
 *   关闭时保留旧的分条显示：开了分条的按空行拆、相隔约 1 秒，没开的一条
 * - 0.5.0 起各条以 PENDING 写入（createdAt 是计划时间），交给独立的投递任务按时送达；
 *   会话预览、未读、通知和自动记忆都在送达时处理（[DeliveryEngine]）
 * - 上一轮还有没送达的回复时，先等它们送达再开始（插话时新请求排在后面）；
 *   拼上下文时把这次等待回复的用户消息排到最后，保证发给模型的最后一条是用户消息
 * - 失败（含 401/402/429/5xx/断网）时把这次等待回复的消息标记为失败，可手动重试
 * - 被取消（用户取消，或又发了新消息替换了任务）时什么也不写，由发起取消的一方处理状态
 * - 0.6.0：联系人开启主动聊天时，回复里的约定标记不显示，约好的时间记下来，到点由主动聊天处理；
 *   没开启时这一步什么也不做，回复和 0.5.0 一样
 * - 0.7.0：等待回复的消息里有语音时先识别（[VoicePipeline]），识别失败时这一轮都标为失败、不请求模型；
 *   没有语音时这一步什么也不做
 */
@Singleton
class ReplyEngine @Inject constructor(
    private val db: AppDatabase,
    private val contactDao: ContactDao,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val memoryDao: MemoryDao,
    private val providers: ProviderRepository,
    private val usage: UsageRepository,
    private val nicknames: NicknameSource,
    private val files: FileStorage,
    private val contextBuilder: ContextBuilder,
    private val clock: Clock,
    private val promptGlobals: PromptGlobalsSource,
    private val preferences: ReplyPreferencesSource,
    private val random: Random,
    private val sleeper: Sleeper,
    private val delivery: DeliveryEngine,
    private val queue: ReplyQueue,
    private val reminders: ProactiveReminders,
    private val voice: VoicePipeline? = null,
) {
    suspend fun run(contactId: Long) {
        // 用户发的语音先识别成文字（识别成功后才算进入等待回复）；失败时不请求模型
        if (voice != null && !transcribe(contactId)) return
        // 上一轮已经生成的先发完：投递任务在发，这里只是等（到点时顺手送达，不会重复）
        delivery.run(contactId)
        waitForQuiet(contactId)
        isomeria.aichat.data.repo.StorageOperations.gate.activity { runProtected(contactId) }
    }

    /** 识别这一轮的语音；失败时把等待回复的消息都标为失败（可以点击重试，会重新识别），返回 false */
    private suspend fun transcribe(contactId: Long): Boolean {
        val pipeline = voice ?: return true
        val reason = isomeria.aichat.data.repo.StorageOperations.gate.activity { pipeline.transcribeWaiting(contactId) } ?: return true
        val waiting = messageDao.sendingIds(contactId)
        if (waiting.isNotEmpty()) fail(waiting, reason)
        debugLog("voice transcription failed: $reason")
        return false
    }

    /** 回复偏好的「稍等几秒」：从等待回复的最新一条消息算起；可以取消，不占用存储操作的闸门 */
    private suspend fun waitForQuiet(contactId: Long) {
        val contact = contactDao.get(contactId) ?: return
        if (!contact.useReplyPreferences) return
        while (true) {
            val last = messageDao.latestSendingAt(contactId) ?: return
            val wait = ReplyRhythm.waitBeforeReply(last, clock.now(), preferences.replyPreferences())
            if (wait <= 0) return
            sleeper.sleep(wait)
        }
    }

    private suspend fun runProtected(contactId: Long) {
        // 只处理此刻在等待回复的消息；之后新发的消息会由替换上来的新任务处理
        val waiting = messageDao.sendingIds(contactId)
        if (waiting.isEmpty()) return
        val snapshot = try { providers.snapshot(contactId, memory = false) } catch (e: LlmException) {
            fail(waiting, if (e.error == isomeria.aichat.data.llm.LlmError.AUTH) FailReason.NO_API_KEY else FailReason.of(e))
            return
        }
        val contact = snapshot.contact
        val config = snapshot.config
        val endpoint = snapshot.endpoint
        val supportsImage = snapshot.supportsImage
        val startedAt = clock.now()
        val nickname = nicknames.nickname()

        // 预算 = 系统提示词（含全局记忆和回复要求）+ 记忆 + 聊天记录。记忆最多占 1/4，放不下的这次先不带
        val estimator = TokenEstimator.forProtocol(config.provider)
        val memories = MemoryPolicy.forRequest(
            memoryDao.listByContact(contactId),
            MemoryPolicy.memoryBudget(contact.contextTokens),
            estimator,
        )
        val globals = promptGlobals.forContact(contact)
        val system = contextBuilder.systemPrompt(contact, nickname, startedAt, memories, globals).orEmpty()
        val historyBudget = contact.contextTokens - estimator.text(system)
        val selected = ContextWindow.select(
            messageDao.recent(contactId, ContextWindow.maxRows(historyBudget)).filter { it.status != MessageStatus.PENDING },
            historyBudget,
            estimator,
            supportsImage,
        )
        // 投递期间发的消息，时间会夹在几条回复中间：等待回复的这几条排到最后
        val waitingIds = waiting.toHashSet()
        val history = selected.filterNot { it.id in waitingIds } + selected.filter { it.id in waitingIds }
        val messages = contextBuilder.build(
            contact = contact,
            history = history,
            supportsImage = supportsImage,
            nickname = nickname,
            now = startedAt,
            memories = memories,
            globals = globals,
            loadImage = files::readAsDataUrl,
        )
        val request = snapshot.request(messages)

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
                        if (firstContentAt == 0L) firstContentAt = clock.now()
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 意外的异常也要把等待中的消息标为失败，不能一直停在「对方正在输入…」；不记录异常内容
            debugLog("reply failed unexpectedly: ${e.javaClass.simpleName}")
            fail(waiting, FailReason.UNKNOWN)
            return
        }

        val finishedAt = clock.now()
        // 开了主动聊天的联系人：约定标记不显示，记下约好的时间（没开时原样返回）
        val (text, parsed) = reminders.extract(contact, content.toString().trim(), finishedAt)
        if (text.isEmpty()) {
            fail(waiting, FailReason.EMPTY_REPLY)
            return
        }
        val rows = ReplyComposer.rows(
            contact = contact,
            text = text,
            reasoning = reasoning.toString(),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            startedAt = startedAt,
            firstContentAt = firstContentAt,
            finishedAt = finishedAt,
            preferences = if (contact.useReplyPreferences) preferences.replyPreferences() else null,
            random = random,
        )
        db.withTransaction {
            messageDao.insertAll(rows)
            messageDao.setStatus(waiting, MessageStatus.SUCCESS, 0)
            reminders.save(contactId, parsed, finishedAt)
            // 服务商没有返回用量时用估算值
            usage.record(
                contactId = contactId,
                purpose = UsagePurpose.CHAT,
                promptTokens = promptTokens.takeIf { it > 0 } ?: estimator.request(messages),
                completionTokens = completionTokens.takeIf { it > 0 } ?: estimator.text(content.toString() + reasoning),
            )
        }
        debugLog("reply ok: parts=${rows.size} prompt=$promptTokens completion=$completionTokens")
        // 预览、未读、通知和记忆都在送达时处理
        queue.enqueueDelivery(contactId)
        if (parsed.isNotEmpty()) reminders.schedule()
    }

    /** 任务被系统中断后重新调度：不再自动重试，标记失败 */
    suspend fun failInterrupted(contactId: Long) {
        messageDao.failSending(contactId, FailReason.INTERRUPTED.code)
    }

    /** 回复任务里出了意外的异常：等待中的消息标为失败，可以点击重试 */
    suspend fun failUnexpected(contactId: Long) {
        messageDao.failSending(contactId, FailReason.UNKNOWN.code)
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
