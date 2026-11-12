package isomeria.aichat.data.reply

import android.util.Log
import androidx.room.withTransaction
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.Clock
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.ContextWindow
import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.core.ProactiveBlock
import isomeria.aichat.core.ProactivePolicy
import isomeria.aichat.core.ProactiveQuota
import isomeria.aichat.core.ProactiveSettingsSource
import isomeria.aichat.core.ProactiveSituation
import isomeria.aichat.core.ReplyPreferencesSource
import isomeria.aichat.core.forContact
import isomeria.aichat.core.TokenEstimator
import isomeria.aichat.core.UsageStats
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.ProactiveReminder
import isomeria.aichat.data.db.ProactiveState
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.core.ProactiveResult
import isomeria.aichat.core.ProactiveResultLog
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.memory.NicknameSource
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.ModelSnapshot
import isomeria.aichat.data.repo.PromptGlobalsSource
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.StorageOperations
import isomeria.aichat.data.repo.UsageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.random.Random

/** 主动请求里只在这一次请求出现、不存库的那条 user 指令所用的文字，从 strings.xml 读取后传入。 */
data class ProactiveLabels(
    /** 参数：现在的时间、距上一条消息多久 */
    val instruction: String,
    /** 约定提醒。参数：现在的时间、约定的时间、事由 */
    val reminderInstruction: String,
    /** 参数：年、月、日、星期、时、分 */
    val time: String,
    val weekdays: List<String>,
    /** 参数：分钟数 / 小时数 / 天数 */
    val sinceMinutes: String,
    val sinceHours: String,
    val sinceDays: String,
    val noHistory: String,
    /** 约定里没有写事由时用 */
    val reminderNoNote: String,
    /** 约定标记的关键词，和系统提示词里教模型写的一致 */
    val reminderKeyword: String,
)

enum class ProactiveOutcome {
    /** 生成了主动消息，交给投递 */
    SENT,

    /** 模型回了 [SKIP]（或空内容）：不显示，已占一次额度 */
    SKIPPED,

    /** 请求失败或超时：不重试、不通知，已占一次额度 */
    FAILED,

    /** 请求期间用户发了消息或关掉了开关：这次的内容作废，优先回复用户，已占一次额度 */
    DISCARDED,

    /** 没有调用模型，原因见 [ProactiveAttempt.block] */
    BLOCKED,
}

data class ProactiveAttempt(val outcome: ProactiveOutcome, val block: ProactiveBlock? = null)

/** 一次检查的结果。[ran] 为 false 表示另一处正在检查，这次什么也没做。 */
data class ProactiveRun(val ran: Boolean = true, val attempts: List<Pair<Long, ProactiveAttempt>> = emptyList()) {
    val calls: Int get() = attempts.count { it.second.outcome != ProactiveOutcome.BLOCKED }
}

/**
 * 联系人主动聊天（0.6.0）：没有用户操作就调用模型，所以默认关闭，每一步都先看能不能不调。
 *
 * 一次检查（[run]，由周期任务、回到前台、约定提醒任务触发）：
 * 1. 没有联系人开启时直接返回，不记运行时间
 * 2. 到点的约定提醒：用户明确约的时间，不看免打扰时段、暂停和概率，仍占每日次数；过了宽限期的丢掉
 * 3. 其余开启的联系人：按频率、距上次互动、时段算概率，掷中才继续
 * 4. 对要继续的联系人（[attempt]）：免打扰时段、今日次数用完、连续未回复已暂停、正在等回复或还有没送达的回复、
 *    用户正在看这个聊天、没配 Key、模型配置无效，都直接跳过，不调用模型、不占次数
 * 5. **先扣次数再发请求**，[SKIP] 和失败都算；失败不重试、不通知
 *
 * 请求内容和普通聊天一样（系统提示词、记忆、预算内的聊天记录），最后追加一条只在这次请求里的 user 指令，
 * 不存库：说明现在的时间、距上次聊天多久，「不想找对方就只输出 [SKIP]」。历史可以以助手消息结束，也可以为空。
 *
 * 生成的内容走和普通回复一样的拆条、间隔和投递（PENDING → `deliver-{contactId}`），来源记为 PROACTIVE；
 * 送达时才更新预览、未读和通知。请求期间用户发了消息时这次的内容作废：回复任务照常进行，用户的消息不会被吞掉或晚回。
 * 这里不用也不取消 `reply-{contactId}` 任务。
 */
@Singleton
class ProactiveEngine @Inject constructor(
    private val db: AppDatabase,
    private val providers: ProviderRepository,
    private val usage: UsageRepository,
    private val nicknames: NicknameSource,
    private val files: FileStorage,
    private val contextBuilder: ContextBuilder,
    private val clock: Clock,
    private val promptGlobals: PromptGlobalsSource,
    private val preferences: ReplyPreferencesSource,
    private val settings: ProactiveSettingsSource,
    private val random: Random,
    private val activeChat: ActiveChatTracker,
    /** 0.8.0：正在通话的联系人跳过 */
    private val callControl: isomeria.aichat.data.call.CallControl = isomeria.aichat.data.call.CallControl.NONE,
    private val queue: ReplyQueue,
    private val reminders: ProactiveReminders,
    private val labels: ProactiveLabels,
    private val zone: Provider<ZoneId>,
    private val results: ProactiveResultLog = ProactiveResultLog.NONE,
    private val voice: VoicePipeline? = null,
) {
    private val contacts get() = db.contactDao()
    private val conversations get() = db.conversationDao()
    private val messages get() = db.messageDao()
    private val proactive get() = db.proactiveDao()
    /** 通话（0.8.0）在上下文里展开成每一条话 */
    private val calls = isomeria.aichat.data.repo.CallHistory(db)

    /** 同一时间只做一次检查：周期任务、回到前台和约定任务可能撞在一起 */
    private val mutex = Mutex()

    /**
     * @param force 调试用「立即运行一次」：不掷概率、不看最短间隔，其余跳过条件和额度照常
     * @param remindersOnly 约定提醒任务触发：只处理到点的约定
     */
    suspend fun run(force: Boolean = false, remindersOnly: Boolean = false): ProactiveRun {
        if (!mutex.tryLock()) return ProactiveRun(ran = false)
        try {
            return StorageOperations.gate.activity { runLocked(force, remindersOnly) }
        } finally {
            mutex.unlock()
            // 约定用掉或丢掉后重新排下一个；任务被取消时也要做完
            withContext(NonCancellable) { reminders.schedule() }
        }
    }

    private suspend fun runLocked(force: Boolean, remindersOnly: Boolean): ProactiveRun {
        val enabled = contacts.proactiveContacts()
        if (enabled.isEmpty()) {
            proactive.deleteRemindersOfDisabled()
            return ProactiveRun()
        }
        val now = clock.now()
        // 一次检查的时间有限（系统给后台任务约 10 分钟）：过了这个时间不再发起新的请求，剩下的留到下次检查
        val deadline = now + RUN_BUDGET_MILLIS
        val lastRunAt = proactive.state()?.lastRunAt ?: 0L
        val sinceLastCheck = if (lastRunAt <= 0L) ProactivePolicy.CHECK_INTERVAL_MILLIS else (now - lastRunAt).coerceAtLeast(0)
        val attempts = mutableListOf<Pair<Long, ProactiveAttempt>>()
        val reminded = HashSet<Long>()

        for (reminder in proactive.dueReminders(now)) {
            val contact = enabled.firstOrNull { it.id == reminder.contactId }
            if (contact == null || now - reminder.dueAt > ProactivePolicy.REMINDER_GRACE_MILLIS) {
                proactive.deleteReminder(reminder.id)
                continue
            }
            // 同一个联系人一次检查只发一个约定，其余留到下次
            if (!reminded.add(contact.id)) continue
            if (clock.now() >= deadline) break
            val attempt = attempt(contact, reminder)
            attempts += contact.id to attempt
            // 被挡住的（正在等回复、今天次数用完、没配 Key）留到下次检查，过了宽限期再丢；发起过请求的不论结果都不重试
            if (attempt.outcome != ProactiveOutcome.BLOCKED) proactive.deleteReminder(reminder.id)
        }

        if (!remindersOnly) {
            val hour = Instant.ofEpochMilli(now).atZone(zone.get()).hour
            var calls = 0
            for (contact in enabled) {
                if (calls >= MAX_CALLS_PER_RUN || clock.now() >= deadline) break
                if (contact.id in reminded) continue
                if (!force) {
                    val idle = now - lastInteraction(contact)
                    val probability = ProactivePolicy.triggerProbability(contact.proactiveFrequency, idle, sinceLastCheck, hour)
                    if (!ProactivePolicy.roll(probability, random)) continue
                }
                val attempt = attempt(contact, null)
                attempts += contact.id to attempt
                if (attempt.outcome != ProactiveOutcome.BLOCKED) calls++
            }
        }
        db.withTransaction { proactive.saveState((proactive.state() ?: ProactiveState()).copy(lastRunAt = now)) }
        debugLog("run: contacts=${enabled.size} attempts=${attempts.map { it.second.outcome.name + (it.second.block?.let { b -> ":$b" } ?: "") }}")
        return ProactiveRun(attempts = attempts)
    }

    /** 上次互动：最后一条已经出现的消息、上一次主动请求、最近一次改联系人设置（刚开启时从这里算）里最晚的 */
    private suspend fun lastInteraction(contact: Contact): Long = maxOf(
        messages.latestVisible(contact.id)?.createdAt ?: 0L,
        conversations.get(contact.id)?.lastProactiveAt ?: 0L,
        contact.updatedAt,
    )

    /**
     * 现在为这个联系人发起主动请求会被什么挡住；null 表示条件都满足（是否真的发起还要看概率）。
     * 不调用模型、不占次数；状态页用它显示「是否暂停及原因」。
     */
    suspend fun blockNow(contact: Contact, reminder: Boolean = false): ProactiveBlock? {
        val now = clock.now()
        val limits = settings.proactiveSettings()
        val state = proactive.state() ?: ProactiveState()
        val situation = ProactiveSituation(
            quietHours = ProactivePolicy.inQuietHours(now, limits, zone.get()),
            quotaLeft = ProactiveQuota(state.day, state.used).canSpend(UsageStats.epochDay(now, zone.get()), limits.dailyLimit),
            unanswered = conversations.get(contact.id)?.proactiveUnanswered ?: 0,
            busy = messages.hasSending(contact.id) || messages.hasPending(contact.id),
            viewing = activeChat.isViewing(contact.id),
            inCall = callControl.inCall(contact.id),
        )
        ProactivePolicy.blockFor(situation, reminder)?.let { return it }
        return when (val checked = snapshot(contact.id)) {
            is Checked.Blocked -> checked.block
            is Checked.Ready -> null
        }
    }

    private sealed interface Checked {
        class Ready(val snapshot: ModelSnapshot) : Checked
        class Blocked(val block: ProactiveBlock) : Checked
    }

    /** 读配置并校验；Key 没配或含请求头放不下的字符、模型配置无效时不调用模型 */
    private suspend fun snapshot(contactId: Long): Checked = try {
        val snapshot = providers.snapshot(contactId, memory = false)
        snapshot.endpoint.authorization()
        Checked.Ready(snapshot)
    } catch (e: LlmException) {
        Checked.Blocked(if (e.error == LlmError.AUTH) ProactiveBlock.NO_KEY else ProactiveBlock.INVALID_CONFIG)
    }

    private suspend fun attempt(contact: Contact, reminder: ProactiveReminder?): ProactiveAttempt {
        val contactId = contact.id
        blockNow(contact, reminder != null)?.let { return ProactiveAttempt(ProactiveOutcome.BLOCKED, it) }
        val snapshot = when (val checked = snapshot(contactId)) {
            is Checked.Blocked -> return ProactiveAttempt(ProactiveOutcome.BLOCKED, checked.block)
            is Checked.Ready -> checked.snapshot
        }
        val startedAt = clock.now()
        val today = UsageStats.epochDay(startedAt, zone.get())
        val limit = settings.proactiveSettings().dailyLimit

        // 先扣次数再发请求：[SKIP]、失败、被放弃都算
        val spent = db.withTransaction {
            val state = proactive.state() ?: ProactiveState()
            val quota = ProactiveQuota(state.day, state.used)
            if (!quota.canSpend(today, limit)) {
                false
            } else {
                val next = quota.spend(today)
                proactive.saveState(state.copy(day = next.day, used = next.used))
                if (conversations.get(contactId) == null) conversations.upsert(Conversation(contactId = contactId))
                conversations.setLastProactiveAt(contactId, startedAt)
                true
            }
        }
        if (!spent) return ProactiveAttempt(ProactiveOutcome.BLOCKED, ProactiveBlock.QUOTA_EXHAUSTED)

        val config = snapshot.config
        val nickname = nicknames.nickname()
        val estimator = TokenEstimator.forProtocol(config.provider)
        val memories = MemoryPolicy.forRequest(
            db.memoryDao().listByContact(contactId),
            MemoryPolicy.memoryBudget(contact.contextTokens),
            estimator,
        )
        val globals = promptGlobals.forContact(contact)
        val system = contextBuilder.systemPrompt(contact, nickname, startedAt, memories, globals).orEmpty()
        val instruction = instruction(startedAt, messages.latestVisible(contactId)?.createdAt, reminder)
        val historyBudget = contact.contextTokens - estimator.text(system) - estimator.text(instruction) - TokenEstimator.MESSAGE_OVERHEAD
        val history = ContextWindow.select(
            calls.recent(contactId, ContextWindow.maxRows(historyBudget)).filter { it.status != MessageStatus.PENDING },
            historyBudget,
            estimator,
            snapshot.supportsImage,
        )
        val request = snapshot.request(
            contextBuilder.appendInstruction(
                contextBuilder.build(
                    contact = contact,
                    history = history,
                    supportsImage = snapshot.supportsImage,
                    nickname = nickname,
                    now = startedAt,
                    memories = memories,
                    globals = globals,
                    loadImage = files::readAsDataUrl,
                ),
                instruction,
            ),
        )

        val content = StringBuilder()
        val reasoning = StringBuilder()
        var firstContentAt = 0L
        var promptTokens = 0
        var completionTokens = 0
        try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) {
                providers.provider(config).streamChat(snapshot.endpoint, request).collect { event ->
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
            }
        } catch (_: TimeoutCancellationException) {
            debugLog("proactive failed: timeout")
            results.record(contactId, startedAt, ProactiveResult.TIMED_OUT)
            return ProactiveAttempt(ProactiveOutcome.FAILED)
        } catch (e: LlmException) {
            debugLog("proactive failed: ${e.error} http=${e.httpCode}")
            results.record(contactId, startedAt, ProactiveResult.FAILED)
            return ProactiveAttempt(ProactiveOutcome.FAILED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不记录异常内容
            debugLog("proactive failed unexpectedly: ${e.javaClass.simpleName}")
            results.record(contactId, startedAt, ProactiveResult.FAILED)
            return ProactiveAttempt(ProactiveOutcome.FAILED)
        }

        val finishedAt = clock.now()
        val usedPrompt = promptTokens.takeIf { it > 0 } ?: estimator.request(request.messages)
        val usedCompletion = completionTokens.takeIf { it > 0 } ?: estimator.text(content.toString() + reasoning)
        val raw = content.toString().trim()
        val (text, parsed) = reminders.extract(contact, raw, finishedAt)
        if (ProactivePolicy.isSkip(raw) || text.isBlank()) {
            usage.record(contactId, UsagePurpose.PROACTIVE, usedPrompt, usedCompletion)
            results.record(contactId, startedAt, ProactiveResult.SKIPPED)
            return ProactiveAttempt(ProactiveOutcome.SKIPPED)
        }
        // 主动消息：消息发送类型是「语音」时发语音，「跟随」和「文字」发文字（0.7.0）
        val pipeline = voice?.takeIf { it.replyAsVoice(contact, lastWaitingIsVoice = null) }
        val rows = pipeline?.heldRows(
            contact = contact,
            text = text,
            reasoning = reasoning.toString(),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            startedAt = startedAt,
            firstContentAt = firstContentAt,
            finishedAt = finishedAt,
            origin = MessageOrigin.PROACTIVE,
        ) ?: ReplyComposer.rows(
            contact = contact,
            text = text,
            reasoning = reasoning.toString(),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            startedAt = startedAt,
            firstContentAt = firstContentAt,
            finishedAt = finishedAt,
            preferences = preferences.forContact(contact),
            random = random,
            origin = MessageOrigin.PROACTIVE,
        )
        val sent = db.withTransaction {
            usage.record(contactId, UsagePurpose.PROACTIVE, usedPrompt, usedCompletion)
            // 请求期间用户发了消息（回复任务会接着处理）、又生成了别的回复，或关掉了开关：这次的内容作废
            val current = contacts.get(contactId)
            val interrupted = current == null || !current.proactiveEnabled ||
                messages.hasSending(contactId) || messages.hasPending(contactId) ||
                messages.hasUserAfter(contactId, startedAt - 1, Long.MAX_VALUE)
            if (interrupted) {
                false
            } else {
                messages.insertAll(rows)
                conversations.addProactiveSent(contactId, startedAt)
                reminders.save(contactId, parsed, finishedAt)
                true
            }
        }
        if (!sent) {
            results.record(contactId, startedAt, ProactiveResult.DISCARDED)
            return ProactiveAttempt(ProactiveOutcome.DISCARDED)
        }
        results.record(contactId, startedAt, ProactiveResult.SENT)
        pipeline?.synthesizeHeld(contactId)
        // 预览、未读、通知和记忆都在送达时处理
        queue.enqueueDelivery(contactId)
        return ProactiveAttempt(ProactiveOutcome.SENT)
    }

    /** 只在这次请求里的 user 指令 */
    private fun instruction(now: Long, lastMessageAt: Long?, reminder: ProactiveReminder?): String {
        if (reminder != null) {
            return labels.reminderInstruction.format(time(now), time(reminder.dueAt), reminder.note.ifBlank { labels.reminderNoNote })
        }
        val since = when {
            lastMessageAt == null -> labels.noHistory
            else -> {
                val minutes = ((now - lastMessageAt).coerceAtLeast(0) / 60_000).toInt()
                when {
                    minutes < 60 -> labels.sinceMinutes.format(minutes)
                    minutes < 48 * 60 -> labels.sinceHours.format(minutes / 60)
                    else -> labels.sinceDays.format(minutes / (24 * 60))
                }
            }
        }
        return labels.instruction.format(time(now), since)
    }

    private fun time(at: Long): String {
        val time = Instant.ofEpochMilli(at).atZone(zone.get())
        return labels.time.format(time.year, time.monthValue, time.dayOfMonth, labels.weekdays[time.dayOfWeek.value - 1], time.hour, time.minute)
    }

    /** 只在 debug 包输出；不记录 API Key、消息正文和约定的事由 */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "ProactiveEngine"

        /** 一次检查最多为几个联系人调用模型：周期任务的运行时间有限，也避免一醒来就连发 */
        const val MAX_CALLS_PER_RUN = 3

        /**
         * 带思考的模型一次回复常常要一分多钟（真机实测 90 秒不够，两次主动请求都超时），所以等到 4 分钟；
         * 超时算失败，不重试。系统给后台任务约 10 分钟：一次检查里过了 [RUN_BUDGET_MILLIS] 就不再发起新请求，
         * 最坏情况是两个请求各等满 4 分钟
         */
        const val REQUEST_TIMEOUT_MILLIS = 240_000L
        const val RUN_BUDGET_MILLIS = 240_000L
    }
}
