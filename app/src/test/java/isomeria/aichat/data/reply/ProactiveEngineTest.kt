package isomeria.aichat.data.reply

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.core.ProactiveBlock
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.ProactiveReminder
import isomeria.aichat.data.db.ProactiveState
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.llm.ChatRole
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.ChatMessageActions
import isomeria.aichat.data.repo.ChatPaging
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.core.ProactiveResult
import isomeria.aichat.data.repo.FileStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 主动聊天的请求和投递：请求末尾的临时 user 指令不存库；走回复偏好和投递；[SKIP]、失败、额度、免打扰时段、暂停；
 * 用户插话时放弃；约定提醒；主动消息不能重新生成、重试不出错；上下文和记忆照常包含主动消息。
 * 只用假的服务商，不发任何网络请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ProactiveEngineTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(day: Int, hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    /** 2026-11-10 星期二 15:00 */
    private val start = at(10, 15)
    private lateinit var db: AppDatabase
    private lateinit var h: ReplyHarness
    private lateinit var chat: ChatRepository
    private val id = 1L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        h = ReplyHarness(context, db, zone, start)
        chat = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), FileStorage(context), h.queue, h.notifications)
        runBlocking {
            db.providerDao().insert(
                ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "测试", baseUrl = "https://example.com/v1", apiKeyEncrypted = "enc:k", defaultModel = "m"),
            )
            db.providerDao().upsertModels(listOf(ProviderModel(1, "m", "M")))
            db.contactDao().insert(
                Contact(
                    id = id, name = "小雨", avatarColor = 0, providerId = 1, model = "m", systemPrompt = "人设",
                    proactiveEnabled = true, createdAt = 1, updatedAt = start - 10 * hour,
                ),
            )
            db.conversationDao().upsert(Conversation(contactId = id, lastMessageTime = start - 5 * hour + 1_000, lastMessagePreview = "在呀"))
            db.messageDao().insert(text(Role.USER, "在吗", start - 5 * hour))
            db.messageDao().insert(text(Role.ASSISTANT, "在呀", start - 5 * hour + 1_000, group = "g1"))
        }
        h.provider.reply = "在忙吗\n晚上一起吃饭"
    }

    @After
    fun tearDown() = db.close()

    private fun text(role: Role, content: String, at: Long, status: MessageStatus = MessageStatus.SUCCESS, group: String? = null, origin: MessageOrigin = MessageOrigin.NORMAL) =
        Message(contactId = id, role = role, type = MessageType.TEXT, content = content, status = status, replyGroup = group, createdAt = at, origin = origin)

    private suspend fun rows() = db.messageDao().listByContact(id)
    private suspend fun proactiveRows() = rows().filter { it.origin == MessageOrigin.PROACTIVE }
    private suspend fun conversation() = db.conversationDao().get(id)!!
    private suspend fun used() = db.proactiveDao().state()?.used ?: 0
    private suspend fun proactiveUsage() = db.usageDao().listAll().filter { it.purpose == UsagePurpose.PROACTIVE }.sumOf { it.requests }
    private val requests get() = h.provider.requests

    /** 「立即运行一次」：不掷概率，其余条件照常；返回这个联系人的结果 */
    private suspend fun force(): ProactiveAttempt? = h.proactive.run(force = true).attempts.firstOrNull { it.first == id }?.second

    /** 把生成的主动消息全部送达 */
    private suspend fun deliver() = h.delivery.run(id)

    private suspend fun contact(transform: (Contact) -> Contact) = db.contactDao().update(transform(db.contactDao().get(id)!!))

    private val instruction = "【主动】现在是 2026年11月10日 星期二 15:00，上一条消息在 4 小时前。不想找对方只输出 [SKIP]。"
    private val system = "人设\n\n回复方式：每次 1～5 条，每条 14 字左右，用换行分隔。\n\n${ReplyHarness.REMINDER_HINT}\n\n现在是 2026年11月10日 星期二 15:00。对方的昵称是「阿哲」。"

    // ---------- 请求内容 ----------

    @Test
    fun request_isSystemHistoryAndATemporaryUserInstruction_whichIsNeverStored() = runBlocking {
        val before = rows()
        assertEquals(ProactiveOutcome.SENT, force()!!.outcome)
        val request = requests.single()
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), request.messages.map { it.role })
        assertEquals(listOf(system, "在吗", "在呀", instruction), request.messages.map { it.plainText() })
        // 指令只在这次请求里：库里没有多出用户消息，也没有任何一条消息带着它
        val after = rows()
        assertEquals(before, after.filter { it.origin == MessageOrigin.NORMAL })
        assertEquals(1, after.count { it.role == Role.USER })
        assertTrue(after.none { "【主动】" in it.content })
        assertTrue(after.filter { it.origin == MessageOrigin.PROACTIVE }.all { it.role == Role.ASSISTANT })
    }

    /** 0.8.0：更早的一次通话在主动请求的上下文里按每一条话展开 */
    @Test
    fun request_earlierCallIsExpandedIntoTurns() = runBlocking {
        val call = db.messageDao().insert(Message(contactId = id, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, voiceMillis = 5_000, createdAt = start - 6 * hour))
        db.callDao().insertAll(
            listOf(
                isomeria.aichat.data.db.CallTurn(messageId = call, seq = 0, role = Role.USER, text = "电话里说的", createdAt = start - 6 * hour + 1_000),
                isomeria.aichat.data.db.CallTurn(messageId = call, seq = 1, role = Role.ASSISTANT, text = "电话里回的", createdAt = start - 6 * hour + 3_000),
            ),
        )
        force()
        assertEquals(listOf(system, "电话里说的", "电话里回的", "在吗", "在呀", instruction), requests.last().messages.map { it.plainText() })
    }

    @Test
    fun request_emptyHistory_orOnlyEarlierProactiveMessages_stillEndsWithTheInstruction() = runBlocking {
        db.messageDao().deleteByContact(id)
        h.provider.reply = "[SKIP]"
        force()
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), requests.last().messages.map { it.role })
        assertEquals("【主动】现在是 2026年11月10日 星期二 15:00，还没有聊过。不想找对方只输出 [SKIP]。", requests.last().messages.last().plainText())
        // 只有之前的主动消息：开头的助手消息按现有规则去掉（有些服务商要求第一条是用户消息），请求照常
        db.messageDao().insert(text(Role.ASSISTANT, "早", start - 30 * 60_000, group = "p0", origin = MessageOrigin.PROACTIVE))
        force()
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), requests.last().messages.map { it.role })
        assertEquals("【主动】现在是 2026年11月10日 星期二 15:00，上一条消息在 30 分钟前。不想找对方只输出 [SKIP]。", requests.last().messages.last().plainText())
    }

    @Test
    fun request_historyEndingWithAFailedUserMessage_mergesTheInstructionIntoIt() = runBlocking {
        db.messageDao().insert(text(Role.USER, "你人呢", start - hour, MessageStatus.FAILED))
        force()
        val request = requests.single()
        // 最后是用户消息时不另起一条，保持角色交替；失败的消息没有被改动
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), request.messages.map { it.role })
        assertEquals("你人呢\n\n【主动】现在是 2026年11月10日 星期二 15:00，上一条消息在 1 小时前。不想找对方只输出 [SKIP]。", request.messages.last().plainText())
        assertEquals("你人呢" to MessageStatus.FAILED, rows().first { it.role == Role.USER && it.status == MessageStatus.FAILED }.let { it.content to it.status })
    }

    // ---------- 发送与投递 ----------

    @Test
    fun sent_goesThroughReplyPreferencesAndDelivery_previewUnreadAndNotificationOnlyOnDelivery() = runBlocking {
        assertEquals(ProactiveAttempt(ProactiveOutcome.SENT), force())
        val sent = proactiveRows()
        assertEquals(listOf("在忙吗", "晚上一起吃饭"), sent.map { it.content })
        assertTrue(sent.all { it.status == MessageStatus.PENDING && it.role == Role.ASSISTANT && it.model == "m" })
        assertEquals(1, sent.map { it.replyGroup }.distinct().size)
        // 回复偏好的间隔：第一条 3 秒后，第二条再 3 秒后（生成不耗时）
        assertEquals(listOf(start + 3_000, start + 6_000), sent.map { it.createdAt })
        assertEquals(listOf(id), h.queue.deliveries)
        assertTrue("不碰回复任务", h.queue.enqueued.isEmpty() && h.queue.cancelled.isEmpty())
        // 送达前：预览、未读、通知都不动
        assertEquals("在呀", conversation().lastMessagePreview)
        assertEquals(0, conversation().unreadCount)
        assertTrue(h.notifications.notified.isEmpty())
        assertEquals(1, conversation().proactiveUnanswered)
        assertEquals(start, conversation().lastProactiveAt)
        assertEquals(1, used())
        assertEquals(1, proactiveUsage())

        deliver()
        assertTrue(proactiveRows().all { it.status == MessageStatus.SUCCESS })
        assertEquals("晚上一起吃饭", conversation().lastMessagePreview)
        assertEquals(2, conversation().unreadCount)
        // 同一次主动发送只在第一条响
        assertEquals(listOf(Triple(id, listOf("在忙吗"), true), Triple(id, listOf("在忙吗", "晚上一起吃饭"), false)), h.notifications.notified)
    }

    /** 0.7.2：单独设置了回复偏好的联系人，主动发的消息也按自己的拆条和间隔 */
    @Test
    fun sent_withOwnReplyPreferences_usesThem() = runBlocking {
        contact { it.copy(replyPreferences = isomeria.aichat.core.ReplyPreferences(maxCount = 1, minIntervalSeconds = 9, maxIntervalSeconds = 9)) }
        force()
        assertEquals("最多 1 条：多出来的接在最后一条里", listOf("在忙吗\n晚上一起吃饭"), proactiveRows().map { it.content })
    }

    @Test
    fun sent_withReplyPreferencesOff_usesTheContactsOwnSplitSetting() = runBlocking {
        contact { it.copy(useReplyPreferences = false, splitReplies = true) }
        h.provider.reply = "第一段\n还是第一段\n\n第二段"
        force()
        assertEquals(listOf("第一段\n还是第一段", "第二段"), proactiveRows().map { it.content })
        db.messageDao().deleteByContact(id)
        db.conversationDao().clearProactiveUnanswered(id)
        contact { it.copy(splitReplies = false) }
        force()
        assertEquals(listOf("第一段\n还是第一段\n\n第二段"), proactiveRows().map { it.content })
    }

    @Test
    fun mutedChat_stillSendsButDoesNotNotify() = runBlocking {
        db.conversationDao().setMuted(id, true)
        assertEquals(ProactiveOutcome.SENT, force()!!.outcome)
        deliver()
        assertEquals(2, proactiveRows().count { it.status == MessageStatus.SUCCESS })
        assertEquals(2, conversation().unreadCount)
        assertTrue("单聊消息免打扰只是不发通知", h.notifications.notified.isEmpty())
    }

    // ---------- [SKIP] 和失败 ----------

    @Test
    fun skip_isNeverShown_butCountsTowardsTheDailyLimitAndUsage() = runBlocking {
        for (reply in listOf("[SKIP]", "【skip】", "现在不合适\n[SKIP]", "  ")) {
            h.provider.reply = reply
            assertEquals(reply, ProactiveAttempt(ProactiveOutcome.SKIPPED), force())
        }
        assertEquals(4, requests.size)
        assertTrue(proactiveRows().isEmpty())
        assertEquals(2, rows().size)
        assertEquals(4, used())
        assertEquals(4, proactiveUsage())
        assertEquals("不算一次发送，不会因此暂停", 0, conversation().proactiveUnanswered)
        assertEquals(start, conversation().lastProactiveAt)
        assertTrue(h.notifications.notified.isEmpty())
        assertTrue(h.queue.deliveries.isEmpty())
        assertEquals("在呀", conversation().lastMessagePreview)
    }

    @Test
    fun failure_isNotRetried_notNotified_andStillCounts() = runBlocking {
        h.provider.failure = LlmException(LlmError.SERVER, 500)
        assertEquals(ProactiveAttempt(ProactiveOutcome.FAILED), force())
        assertEquals("失败不重试", 1, requests.size)
        assertEquals(1, used())
        assertTrue(proactiveRows().isEmpty())
        assertTrue(h.notifications.notified.isEmpty())
        assertTrue(h.queue.deliveries.isEmpty() && h.queue.enqueued.isEmpty())
        assertEquals(0, conversation().proactiveUnanswered)
        assertEquals(0, proactiveUsage())
        // 意外的异常同样只算失败，不会抛出去
        h.provider.failure = IllegalStateException("boom")
        assertEquals(ProactiveAttempt(ProactiveOutcome.FAILED), force())
        assertEquals(2, used())
        // 没有任何消息被标成失败
        assertTrue(rows().none { it.status == MessageStatus.FAILED })
    }

    @Test
    fun aRequestThatTimesOut_countsAsAFailure_andIsRecordedAsATimeout() = runBlocking {
        // 服务商一直不答：和真实请求一样在流里抛出超时
        h.provider.beforeReply = { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(60_000) } }
        assertEquals(ProactiveAttempt(ProactiveOutcome.FAILED), force())
        assertEquals(1, requests.size)
        assertEquals(1, used())
        assertTrue(proactiveRows().isEmpty())
        assertEquals(start to ProactiveResult.TIMED_OUT, h.results[id])
    }

    @Test
    fun everyRequest_recordsItsResult_forTheStatusPage() = runBlocking {
        assertNull(h.results[id])
        assertEquals(ProactiveOutcome.SENT, force()!!.outcome)
        assertEquals(start to ProactiveResult.SENT, h.results[id])
        assertEquals("结果按请求开始的时间和会话对上", start, conversation().lastProactiveAt)

        deliver()
        h.now += hour
        h.provider.reply = "[SKIP]"
        assertEquals(ProactiveOutcome.SKIPPED, force()!!.outcome)
        assertEquals(h.now to ProactiveResult.SKIPPED, h.results[id])

        h.now += hour
        h.provider.failure = LlmException(LlmError.SERVER, 500)
        assertEquals(ProactiveOutcome.FAILED, force()!!.outcome)
        assertEquals(h.now to ProactiveResult.FAILED, h.results[id])

        // 请求期间用户发了消息：作废
        h.now += hour
        h.provider.failure = null
        h.provider.reply = "在吗"
        h.provider.beforeReply = { db.messageDao().insert(text(Role.USER, "我先说", h.now + 1)) }
        assertEquals(ProactiveOutcome.DISCARDED, force()!!.outcome)
        val discardedAt = h.now
        assertEquals(discardedAt to ProactiveResult.DISCARDED, h.results[id])
        assertEquals(discardedAt, conversation().lastProactiveAt)

        // 被挡住的不发请求，也不改上一次的结果
        h.provider.beforeReply = null
        h.proactiveSettings = h.proactiveSettings.copy(dailyLimit = 4)
        assertEquals(ProactiveOutcome.BLOCKED, force()!!.outcome)
        assertEquals(discardedAt to ProactiveResult.DISCARDED, h.results[id])
    }

    @Test
    fun aSlowRequest_usesUpTheRunBudget_soTheNextContactWaitsForTheNextCheck() = runBlocking {
        db.contactDao().insert(
            Contact(id = 2, name = "阿青", avatarColor = 0, providerId = 1, model = "m", proactiveEnabled = true, createdAt = 2, updatedAt = start - 10 * hour),
        )
        // 第一个请求用了 4 分钟多（带思考的模型）
        h.provider.beforeReply = { h.now += 250_000 }
        val run = h.proactive.run(force = true)
        assertEquals("只为一个联系人发了请求", 1, requests.size)
        assertEquals(1, run.attempts.size)
        assertEquals(1, used())
        // 下一次检查轮到另一个
        h.provider.beforeReply = null
        h.now += 15 * 60_000
        val next = h.proactive.run(force = true)
        assertTrue(next.attempts.any { it.second.outcome == ProactiveOutcome.SENT })
    }

    // ---------- 不调用模型就跳过 ----------

    @Test
    fun quietHours_acrossMidnight_makeNoModelCall() = runBlocking {
        for (moment in listOf(at(10, 23), at(10, 23, 59), at(11, 0), at(11, 6, 59))) {
            h.now = moment
            assertEquals(ProactiveAttempt(ProactiveOutcome.BLOCKED, ProactiveBlock.QUIET_HOURS), force())
        }
        assertTrue(requests.isEmpty())
        assertEquals(0, used())
        assertEquals(0L, conversation().lastProactiveAt)
        h.now = at(11, 7)
        assertEquals(ProactiveOutcome.SENT, force()!!.outcome)
        assertEquals(1, requests.size)
    }

    @Test
    fun quietHours_followTheSettings() = runBlocking {
        h.proactiveSettings = ProactiveSettings(quietStartMinutes = 14 * 60, quietEndMinutes = 16 * 60)
        assertEquals(ProactiveBlock.QUIET_HOURS, force()!!.block)
        h.proactiveSettings = ProactiveSettings(quietEnabled = false)
        h.now = at(11, 3)
        assertEquals(ProactiveOutcome.SENT, force()!!.outcome)
    }

    @Test
    fun dailyLimit_stopsFurtherCalls_andResetsOnTheNextLocalDay() = runBlocking {
        h.proactiveSettings = ProactiveSettings(dailyLimit = 2)
        h.provider.reply = "[SKIP]"
        repeat(2) { assertEquals(ProactiveOutcome.SKIPPED, force()!!.outcome) }
        assertEquals(ProactiveAttempt(ProactiveOutcome.BLOCKED, ProactiveBlock.QUOTA_EXHAUSTED), force())
        assertEquals(2, requests.size)
        assertEquals(2, used())
        // 当天晚些时候仍然不行
        h.now = at(10, 22, 59)
        assertEquals(ProactiveBlock.QUOTA_EXHAUSTED, force()!!.block)
        // 第二天恢复
        h.now = at(11, 9)
        assertEquals(ProactiveOutcome.SKIPPED, force()!!.outcome)
        assertEquals(3, requests.size)
        assertEquals(1, used())
    }

    @Test
    fun twoUnansweredSends_pause_untilTheUserSpeaksAgain() = runBlocking {
        repeat(2) {
            assertEquals(ProactiveOutcome.SENT, force()!!.outcome)
            deliver()
            h.now += hour
        }
        assertEquals(2, conversation().proactiveUnanswered)
        assertEquals(ProactiveAttempt(ProactiveOutcome.BLOCKED, ProactiveBlock.PAUSED), force())
        h.now += 5 * hour
        assertEquals(ProactiveBlock.PAUSED, force()!!.block)
        assertEquals("暂停期间不调用模型", 2, requests.size)
        assertEquals(2, used())

        // 用户发言：恢复。sendText 用真实时间记用户消息，假时钟要跟到真实时间之后、关掉免打扰时段，
        // 否则写死的日期过了某个时刻，回复会排在假时钟之后送不到，测试从那天起一直失败
        h.proactiveSettings = ProactiveSettings(quietEnabled = false)
        h.now = System.currentTimeMillis()
        chat.sendText(id, "刚才在忙")
        assertEquals(0, conversation().proactiveUnanswered)
        h.provider.reply = "没事"
        h.engine.run(id)
        deliver()
        h.provider.reply = "在忙吗"
        h.now += 4 * hour
        assertEquals(ProactiveOutcome.SENT, force()!!.outcome)
        assertEquals(1, conversation().proactiveUnanswered)
    }

    @Test
    fun waitingForAReply_pendingDelivery_orViewingTheChat_makeNoModelCall() = runBlocking {
        val waiting = db.messageDao().insert(text(Role.USER, "等回复", start - 1_000, MessageStatus.SENDING))
        assertEquals(ProactiveAttempt(ProactiveOutcome.BLOCKED, ProactiveBlock.BUSY), force())
        db.messageDao().setStatus(listOf(waiting), MessageStatus.SUCCESS, 0)
        val pending = db.messageDao().insert(text(Role.ASSISTANT, "还没送达", start + 5_000, MessageStatus.PENDING, group = "g2"))
        assertEquals(ProactiveBlock.BUSY, force()!!.block)
        db.messageDao().delete(pending)
        h.activeChat.enter(id)
        assertEquals(ProactiveAttempt(ProactiveOutcome.BLOCKED, ProactiveBlock.VIEWING), force())
        assertTrue(requests.isEmpty())
        assertEquals(0, used())
        h.activeChat.leave(id)
        assertEquals(ProactiveOutcome.SENT, force()!!.outcome)
    }

    @Test
    fun missingOrUnusableKey_orInvalidModelSettings_makeNoModelCallAndSpendNothing() = runBlocking {
        val provider = db.providerDao().get(1)!!
        db.providerDao().update(provider.copy(apiKeyEncrypted = null))
        assertEquals(ProactiveAttempt(ProactiveOutcome.BLOCKED, ProactiveBlock.NO_KEY), force())
        db.providerDao().update(provider.copy(apiKeyEncrypted = "enc:sk－全角"))
        assertEquals(ProactiveBlock.NO_KEY, force()!!.block)
        db.providerDao().update(provider)
        contact { it.copy(thinkingOption = "不存在的档位") }
        assertEquals(ProactiveAttempt(ProactiveOutcome.BLOCKED, ProactiveBlock.INVALID_CONFIG), force())
        assertTrue(requests.isEmpty())
        assertEquals(0, used())
        assertEquals(0L, conversation().lastProactiveAt)
    }

    // ---------- 概率和开关 ----------

    @Test
    fun withoutForce_theInjectedRandomDecides_andTheMinimumGapApplies() = runBlocking {
        // 距上次消息约 5 小时、适中：概率大于 0。掷出 0.99 不触发
        h.roll = 0.99
        assertTrue(h.proactive.run().attempts.isEmpty())
        assertTrue(requests.isEmpty())
        assertEquals("检查过就记下运行时间", start, db.proactiveDao().state()!!.lastRunAt)
        assertEquals(0, used())
        // 掷出 0 触发
        h.roll = 0.0
        h.now += 15 * 60_000
        assertEquals(ProactiveOutcome.SENT, h.proactive.run().attempts.single().second.outcome)
        assertEquals(1, requests.size)
        deliver()
        // 刚主动过：最短间隔（适中 3 小时）内不会再来
        h.now += 2 * hour
        assertTrue(h.proactive.run().attempts.isEmpty())
        h.now += hour
        assertEquals(1, h.proactive.run().attempts.size)
        assertEquals(2, requests.size)
    }

    @Test
    fun frequencyChangesTheMinimumGap() = runBlocking {
        // 上一条消息在 5 小时前；刚改过联系人设置的话从改的时候算
        contact { it.copy(proactiveFrequency = ProactiveFrequency.LOW) }
        assertTrue("偶尔：6 小时内不主动", h.proactive.run().attempts.isEmpty())
        contact { it.copy(proactiveFrequency = ProactiveFrequency.HIGH) }
        assertEquals(1, h.proactive.run().attempts.size)
        db.messageDao().deleteByContact(id)
        db.conversationDao().upsert(Conversation(contactId = id))
        contact { it.copy(updatedAt = start - 30 * 60_000) }
        assertTrue("刚开启 30 分钟：还不到最短间隔", h.proactive.run().attempts.isEmpty())
    }

    @Test
    fun noContactEnabled_doesNothingAtAll() = runBlocking {
        contact { it.copy(proactiveEnabled = false) }
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = start - 1_000, note = "x", createdAt = 1))
        val run = h.proactive.run(force = true)
        assertTrue(run.ran && run.attempts.isEmpty())
        assertTrue(requests.isEmpty())
        assertNull("没有联系人开启时不记运行时间、不占次数", db.proactiveDao().state())
        assertEquals("关掉开关的联系人不保留约定", 0, db.proactiveDao().reminderCount())
    }

    @Test
    fun atMostThreeContactsAreCalledInOneCheck() = runBlocking {
        for (other in 2L..5L) {
            db.contactDao().insert(Contact(id = other, name = "联系人$other", avatarColor = 0, providerId = 1, model = "m", proactiveEnabled = true, createdAt = 1, updatedAt = start - 10 * hour))
        }
        h.provider.reply = "[SKIP]"
        val run = h.proactive.run(force = true)
        assertEquals(3, run.calls)
        assertEquals(3, requests.size)
        assertEquals(3, used())
    }

    // ---------- 用户在主动请求进行中发消息 ----------

    @Test
    fun userMessageDuringTheRequest_discardsTheProactiveReply_andTheUserIsAnsweredNormally() = runBlocking {
        h.provider.beforeReply = { chat.sendText(id, "等等") }
        assertEquals(ProactiveAttempt(ProactiveOutcome.DISCARDED), force())
        h.provider.beforeReply = null
        assertTrue("这次的内容作废", proactiveRows().isEmpty())
        assertEquals(1, used())
        assertEquals(1, proactiveUsage())
        assertEquals(0, conversation().proactiveUnanswered)
        assertTrue(h.queue.deliveries.isEmpty())
        // 用户的消息还在等回复，回复任务已经排上，没有被取消
        assertEquals(MessageStatus.SENDING, rows().last().status)
        assertEquals(listOf(id), h.queue.enqueued)
        assertTrue(h.queue.cancelled.isEmpty())
        // 回复照常进行，最后一条是用户的消息
        h.provider.reply = "怎么了"
        h.engine.run(id)
        assertEquals("等等", requests.last().messages.last().plainText())
        assertEquals(ChatRole.USER, requests.last().messages.last().role)
        assertEquals(MessageStatus.SUCCESS, rows().first { it.content == "等等" }.status)
        assertEquals(listOf("怎么了"), rows().filter { it.status == MessageStatus.PENDING }.map { it.content })
    }

    @Test
    fun switchTurnedOffDuringTheRequest_discardsTheReply() = runBlocking {
        h.provider.beforeReply = { contact { it.copy(proactiveEnabled = false) } }
        assertEquals(ProactiveOutcome.DISCARDED, force()!!.outcome)
        assertTrue(proactiveRows().isEmpty())
    }

    // ---------- 约定提醒 ----------

    @Test
    fun ordinaryReply_withMarker_hidesItAndRecordsTheTime_onlyWhenProactiveIsOn() = runBlocking {
        // sendText 用真实时间记用户消息，「等你说完再回」会把假时钟推到真实时间之后：
        // 约定的时间按真实日期往后取，测试才不会在某一天之后开始失败
        h.now = System.currentTimeMillis()
        val due = java.time.LocalDate.now(zone).plusDays(2).atTime(7, 0)
        chat.sendText(id, "后天早上7点叫我")
        h.provider.reply = "好的\n[提醒 $due 叫他起床]".replace('T', ' ')
        h.engine.run(id)
        assertEquals(listOf("好的"), rows().filter { it.status == MessageStatus.PENDING }.map { it.content })
        assertTrue(rows().none { "提醒 20" in it.content })
        val reminder = db.proactiveDao().remindersOf(id).single()
        assertEquals(due.atZone(zone).toInstant().toEpochMilli(), reminder.dueAt)
        assertEquals("叫他起床", reminder.note)
        assertEquals("记下约定后重新排任务", 1, h.control.syncs)
        assertTrue(requests.last().messages.first().plainText().contains(ReplyHarness.REMINDER_HINT))
        // 同一个时间不重复记
        deliver()
        chat.sendText(id, "别忘了")
        h.engine.run(id)
        assertEquals(1, db.proactiveDao().reminderCount())
    }

    @Test
    fun ordinaryReply_withProactiveOff_isLeftExactlyAsBefore() = runBlocking {
        contact { it.copy(proactiveEnabled = false) }
        h.now = at(10, 22)
        chat.sendText(id, "明早7点叫我")
        h.provider.reply = "好的\n[提醒 2026-11-11 07:00 叫他起床]"
        h.engine.run(id)
        assertEquals(listOf("好的", "[提醒 2026-11-11 07:00 叫他起床]"), rows().filter { it.status == MessageStatus.PENDING }.map { it.content })
        assertEquals(0, db.proactiveDao().reminderCount())
        assertEquals(0, h.control.syncs)
        assertFalse(requests.last().messages.first().plainText().contains(ReplyHarness.REMINDER_HINT))
    }

    @Test
    fun dueReminder_firesEvenInQuietHoursWhilePausedAndViewing_withItsOwnInstruction() = runBlocking {
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = at(11, 6, 30), note = "叫他起床", createdAt = start))
        db.conversationDao().addProactiveSent(id, start)
        db.conversationDao().addProactiveSent(id, start)
        h.activeChat.enter(id)
        h.provider.reply = "起床啦"
        // 还没到点：什么也不做
        h.now = at(11, 6, 29)
        assertTrue(h.proactive.run(remindersOnly = true).attempts.isEmpty())
        assertTrue(requests.isEmpty())
        h.now = at(11, 6, 31)
        val run = h.proactive.run(remindersOnly = true)
        assertEquals(ProactiveAttempt(ProactiveOutcome.SENT), run.attempts.single().second)
        assertEquals("【约定】现在是 2026年11月11日 星期三 06:31。约定 2026年11月11日 星期三 06:30：叫他起床。", requests.single().messages.last().plainText())
        assertEquals(ChatRole.USER, requests.single().messages.last().role)
        assertEquals(listOf("起床啦"), proactiveRows().map { it.content })
        assertEquals("用掉的约定删除", 0, db.proactiveDao().reminderCount())
        assertEquals("约定也占每日次数", 1, used())
        assertTrue(h.control.syncs >= 1)
    }

    @Test
    fun dueReminder_isKeptWhileBusyOrOutOfQuota_droppedAfterTheGracePeriod_andNeverRetriedAfterACall() = runBlocking {
        val due = at(10, 14)
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = due, note = "", createdAt = 1))
        val waiting = db.messageDao().insert(text(Role.USER, "等回复", start - 1_000, MessageStatus.SENDING))
        assertEquals(ProactiveBlock.BUSY, h.proactive.run(remindersOnly = true).attempts.single().second.block)
        assertEquals("正在等回复：留到下次检查", 1, db.proactiveDao().reminderCount())
        db.messageDao().setStatus(listOf(waiting), MessageStatus.SUCCESS, 0)

        h.proactiveSettings = ProactiveSettings(dailyLimit = 1)
        db.proactiveDao().saveState(ProactiveState(day = isomeria.aichat.core.UsageStats.epochDay(start, zone), used = 1))
        assertEquals(ProactiveBlock.QUOTA_EXHAUSTED, h.proactive.run(remindersOnly = true).attempts.single().second.block)
        assertEquals(1, db.proactiveDao().reminderCount())
        assertTrue(requests.isEmpty())

        // 失败也不重试：发起过请求就删除
        h.proactiveSettings = ProactiveSettings()
        h.provider.failure = LlmException(LlmError.NETWORK)
        assertEquals(ProactiveOutcome.FAILED, h.proactive.run(remindersOnly = true).attempts.single().second.outcome)
        assertEquals(0, db.proactiveDao().reminderCount())
        assertEquals(1, requests.size)

        // 晚了超过 12 小时（后台被系统冻结）：不再发
        h.provider.failure = null
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = due, note = "", createdAt = 1))
        h.now = due + 12 * hour + 1
        assertTrue(h.proactive.run(remindersOnly = true).attempts.isEmpty())
        assertEquals(0, db.proactiveDao().reminderCount())
        assertEquals(1, requests.size)
    }

    @Test
    fun reminderWithoutNote_usesTheFallbackText_andPeriodicCheckAlsoPicksUpDueReminders() = runBlocking {
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = start - 60_000, note = "", createdAt = 1))
        h.roll = 0.99
        h.provider.reply = "到点啦"
        val run = h.proactive.run()
        assertEquals(1, run.attempts.size)
        assertEquals("【约定】现在是 2026年11月10日 星期二 15:00。约定 2026年11月10日 星期二 14:59：按说好的做。", requests.single().messages.last().plainText())
    }

    // ---------- 重新生成、重试 ----------

    @Test
    fun proactiveMessage_cannotBeRegenerated_evenWhenAnOlderUserMessageWouldMatch() = runBlocking {
        // 很久以前有一条发送失败的消息，之后联系人主动发来消息：旧逻辑会删掉主动消息、把那条旧消息重发
        db.messageDao().deleteByContact(id)
        db.messageDao().insert(text(Role.USER, "很久以前没发出去", start - 30 * hour, MessageStatus.FAILED))
        force()
        deliver()
        val before = rows()
        assertEquals(MessageOrigin.PROACTIVE, before.last().origin)

        assertFalse(ChatPaging(db).timeline(id).first().regenerationAllowed)
        assertFalse(ChatMessageActions(db).regenerate(id))
        chat.regenerate(id)
        assertEquals(before, rows())
        assertTrue(h.queue.enqueued.isEmpty())
    }

    @Test
    fun ordinaryReplyAfterAProactiveMessage_canStillBeRegenerated() = runBlocking {
        force()
        deliver()
        h.now += hour
        chat.sendText(id, "不忙")
        h.provider.reply = "那走吧"
        h.engine.run(id)
        deliver()
        assertTrue(ChatPaging(db).timeline(id).first().regenerationAllowed)
        assertTrue(ChatMessageActions(db).regenerate(id))
        // 删掉的是这次回复，主动消息还在；重发的是它回答的那条用户消息
        assertEquals(2, proactiveRows().size)
        assertEquals(listOf("不忙"), rows().filter { it.status == MessageStatus.SENDING }.map { it.content })
        assertTrue(rows().none { it.content == "那走吧" })
    }

    @Test
    fun retry_afterAProactiveMessage_resendsTheFailedUserMessageWithoutErrors() = runBlocking {
        force()
        deliver()
        h.now += hour
        chat.sendText(id, "刚看到")
        h.provider.failure = LlmException(LlmError.NETWORK)
        h.engine.run(id)
        assertEquals(MessageStatus.FAILED, rows().last().status)
        h.provider.failure = null
        h.provider.reply = "没事"

        chat.retry(id)
        assertEquals(MessageStatus.SENDING, rows().last().status)
        h.engine.run(id)
        val request = requests.last()
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), request.messages.map { it.role })
        // 普通回复和主动消息是连续两轮助手消息：合并成一条，按回复偏好用换行连接
        assertEquals("在呀\n在忙吗\n晚上一起吃饭", request.messages[2].plainText())
        assertEquals("刚看到", request.messages.last().plainText())
        assertEquals(MessageStatus.SUCCESS, rows().first { it.content == "刚看到" }.status)
        assertEquals(2, proactiveRows().size)
    }

    // ---------- 上下文和记忆 ----------

    @Test
    fun consecutiveAssistantTurns_mergeWithBlankLines_whenReplyPreferencesAreOff() = runBlocking {
        contact { it.copy(useReplyPreferences = false) }
        h.provider.reply = "在忙吗"
        force()
        deliver()
        h.now += hour
        chat.sendText(id, "不忙")
        h.engine.run(id)
        assertEquals("在呀\n\n在忙吗", requests.last().messages[2].plainText())
    }

    @Test
    fun memorySummary_includesProactiveMessages() = runBlocking {
        h.provider.reply = "在忙吗"
        force()
        deliver()
        val transcript = MemoryPolicy.transcript(rows(), "我", "小雨", "[图片]")
        assertEquals("我：在吗\n小雨：在呀\n小雨：在忙吗", transcript)
    }
}
