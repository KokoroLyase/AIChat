package isomeria.aichat.data.reply

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.escapeLike
import isomeria.aichat.data.llm.ChatRole
import isomeria.aichat.data.repo.ChatPaging
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.FailReason
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
import java.time.ZoneId

/**
 * 可靠投递：PENDING 写库、只在送达时更新预览/未读/通知、独立投递任务、杀进程后恢复、插话、
 * 免打扰、取消、删除/清空，以及各个读消息的地方都不把待投递的当成已出现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DeliveryTest {
    private val context = RuntimeEnvironment.getApplication()
    private val start = 1_800_000_000_000L
    private lateinit var db: AppDatabase
    private lateinit var h: ReplyHarness
    private var id = 0L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        h = ReplyHarness(context, db, ZoneId.of("Asia/Shanghai"), start)
        runBlocking {
            db.providerDao().insert(
                ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "测试", baseUrl = "https://example.com/v1", apiKeyEncrypted = "enc:k", defaultModel = "m"),
            )
            db.providerDao().upsertModels(listOf(ProviderModel(1, "m", "M")))
            id = db.contactDao().insert(Contact(name = "小雨", avatarColor = 0, providerId = 1, model = "m", systemPrompt = "人设", createdAt = 1, updatedAt = 1))
            db.conversationDao().upsert(Conversation(contactId = id, lastMessageTime = start - 10_000, lastMessagePreview = "在吗"))
            db.messageDao().insert(text(Role.USER, "在吗", start - 10_000, MessageStatus.SENDING))
        }
        // 两条回复：生成不耗时，第一条 3 秒后、第二条再 3 秒后
        h.provider.reply = "好的\n马上来"
    }

    @After
    fun tearDown() = db.close()

    private fun text(role: Role, content: String, at: Long, status: MessageStatus = MessageStatus.SUCCESS, group: String? = null) =
        Message(contactId = id, role = role, type = MessageType.TEXT, content = content, status = status, replyGroup = group, createdAt = at)

    private suspend fun conversation() = db.conversationDao().get(id)!!
    private suspend fun statuses() = db.messageDao().listByContact(id).filter { it.role == Role.ASSISTANT }.map { it.content to it.status }

    // ---------- 写库与送达 ----------

    @Test
    fun reply_isWrittenAsPending_withoutPreviewUnreadNotificationOrMemory() = runBlocking {
        h.engine.run(id)
        assertEquals(listOf("好的" to MessageStatus.PENDING, "马上来" to MessageStatus.PENDING), statuses())
        assertEquals(MessageStatus.SUCCESS, db.messageDao().listByContact(id).first().status)
        assertEquals("在吗", conversation().lastMessagePreview)
        assertEquals(0, conversation().unreadCount)
        assertTrue(h.notifications.notified.isEmpty())
        assertTrue(h.memoryQueue.isEmpty())
        assertEquals(listOf(id), h.queue.deliveries)
        // 聊天页看不到，标题显示「对方正在输入…」
        val timeline = ChatPaging(db).timeline(id).first()
        assertEquals("在吗", timeline.visibleLatest?.content)
        assertTrue(timeline.pending)
    }

    @Test
    fun delivery_updatesPreviewUnreadAndNotification_perPart_ringsOnlyForFirst() = runBlocking {
        h.engine.run(id)
        val (first, second) = db.messageDao().listByContact(id).filter { it.role == Role.ASSISTANT }
        assertEquals(start + 3_000, first.createdAt)

        h.now = first.createdAt - 1
        assertFalse(h.delivery.deliverDueAndNotify(id))
        h.now = first.createdAt
        assertTrue(h.delivery.deliverDueAndNotify(id))
        assertEquals(listOf("好的" to MessageStatus.SUCCESS, "马上来" to MessageStatus.PENDING), statuses())
        assertEquals("好的", conversation().lastMessagePreview)
        assertEquals(first.createdAt, conversation().lastMessageTime)
        assertEquals(1, conversation().unreadCount)
        assertEquals(Triple(id, listOf("好的"), true), h.notifications.notified.single())

        h.now = second.createdAt
        assertTrue(h.delivery.deliverDueAndNotify(id))
        assertEquals("马上来", conversation().lastMessagePreview)
        assertEquals(2, conversation().unreadCount)
        // 同一次回复的第二条：通知内容随投递更新，不再响
        assertEquals(Triple(id, listOf("好的", "马上来"), false), h.notifications.notified.last())
        assertEquals(2, h.notifications.notified.size)
    }

    @Test
    fun deliveryRun_deliversAllAtTheirTimes_thenQueuesMemoryOnce_andIsIdempotent() = runBlocking {
        h.engine.run(id)
        h.sleeps.clear()
        h.delivery.run(id)
        assertEquals(listOf(3_000L, 3_000L), h.sleeps)
        assertEquals(listOf("好的" to MessageStatus.SUCCESS, "马上来" to MessageStatus.SUCCESS), statuses())
        assertEquals(listOf(id), h.memoryQueue)
        // 投递任务重试或第二个投递者再跑一遍：没有可送的，不重复加未读、不重复通知
        h.delivery.run(id)
        assertEquals(2, conversation().unreadCount)
        assertEquals(2, h.notifications.notified.size)
        assertEquals(1, h.memoryQueue.size)
    }

    @Test
    fun viewingTheChat_noUnreadNoNotification_previewStillUpdates() = runBlocking {
        h.engine.run(id)
        h.activeChat.enter(id)
        h.delivery.run(id)
        assertEquals(0, conversation().unreadCount)
        assertTrue(h.notifications.notified.isEmpty())
        assertEquals("马上来", conversation().lastMessagePreview)
    }

    @Test
    fun mutedChat_noNotificationOnDelivery_unreadAndPreviewAsUsual() = runBlocking {
        db.conversationDao().setMuted(id, true)
        h.engine.run(id)
        h.delivery.run(id)
        assertTrue(h.notifications.notified.isEmpty())
        assertEquals(2, conversation().unreadCount)
        assertEquals("马上来", conversation().lastMessagePreview)
    }

    // ---------- 杀进程后恢复 ----------

    @Test
    fun recoverAfterKill_dueDeliveredOnce_futureRequeued_noLossNoDuplicate() = runBlocking {
        h.engine.run(id)
        val rowsBefore = db.messageDao().listByContact(id).map { it.id }
        // 进程在第一条到点后、第二条到点前被杀；重新打开 App
        h.now = start + 4_000
        h.queue.deliveries.clear()
        val chats = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), FileStorage(context), h.queue, h.notifications)
        chats.markInterruptedRequests()
        h.delivery.recover()
        assertEquals(listOf("好的" to MessageStatus.SUCCESS, "马上来" to MessageStatus.PENDING), statuses())
        assertEquals(listOf(id), h.queue.deliveries)
        assertEquals(1, conversation().unreadCount)
        // 投递任务接着跑：剩下的一条送达，消息条数不变、没有重复
        h.delivery.run(id)
        assertEquals(rowsBefore, db.messageDao().listByContact(id).map { it.id })
        assertEquals(listOf("好的" to MessageStatus.SUCCESS, "马上来" to MessageStatus.SUCCESS), statuses())
        assertEquals(2, conversation().unreadCount)
        assertEquals("马上来", conversation().lastMessagePreview)
    }

    @Test
    fun recoverLongAfterKill_allDueAtOnce_nothingRequeued() = runBlocking {
        h.engine.run(id)
        h.now = start + 3_600_000
        h.queue.deliveries.clear()
        h.delivery.recover()
        assertTrue(statuses().all { it.second == MessageStatus.SUCCESS })
        assertTrue(h.queue.deliveries.isEmpty())
        assertEquals(2, conversation().unreadCount)
        assertEquals(listOf(id), h.memoryQueue)
    }

    @Test
    fun markInterruptedRequests_failsOrphanSending_butNeverTouchesPending() = runBlocking {
        h.engine.run(id)
        db.messageDao().insert(text(Role.USER, "又一条", start + 1_000, MessageStatus.SENDING))
        val chats = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), FileStorage(context), h.queue, h.notifications)
        chats.markInterruptedRequests()
        assertEquals(listOf(MessageStatus.PENDING, MessageStatus.PENDING), statuses().map { it.second })
        assertEquals(FailReason.INTERRUPTED.code, db.messageDao().listByContact(id).first { it.content == "又一条" }.errorCode)
    }

    // ---------- 插话 ----------

    @Test
    fun interjectionDuringDelivery_previousRepliesFinishFirst_lastMessageToModelIsUser() = runBlocking {
        h.engine.run(id)
        // 第一条送达后（3 秒），用户插了一句；第二条（6 秒）还没送达
        h.now = start + 4_000
        h.delivery.deliverDueAndNotify(id)
        db.messageDao().insert(text(Role.USER, "插一句", start + 4_000, MessageStatus.SENDING))
        h.provider.reply = "收到"
        var pendingAtRequest = true
        h.provider.beforeReply = { pendingAtRequest = db.messageDao().hasPending(id) }
        h.engine.run(id)
        // 新请求在上一轮全部送达之后才发
        assertFalse(pendingAtRequest)
        val request = h.provider.requests.last()
        // 「插一句」的时间夹在两条回复之间，但发给模型的最后一条是它
        assertEquals(ChatRole.USER, request.messages.last().role)
        assertEquals("插一句", request.messages.last().plainText())
        assertEquals(ChatRole.ASSISTANT, request.messages[request.messages.size - 2].role)
        val ordered = db.messageDao().listByContact(id).map { it.content }
        assertEquals(listOf("在吗", "好的", "插一句", "马上来", "收到"), ordered)
    }

    @Test
    fun userCancel_keepsPendingAndDelivery_deleteAndClearCancelDelivery() = runBlocking {
        h.engine.run(id)
        db.messageDao().insert(text(Role.USER, "算了", start + 1_000, MessageStatus.SENDING))
        val chats = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), FileStorage(context), h.queue, h.notifications)
        chats.cancelReply(id)
        assertEquals(listOf(MessageStatus.PENDING, MessageStatus.PENDING), statuses().map { it.second })
        assertTrue(h.queue.deliveryCancelled.isEmpty())
        // 取消的那条可以重试，之后送达的回复不算回答过它
        h.delivery.run(id)
        chats.retry(id)
        assertEquals(MessageStatus.SENDING, db.messageDao().listByContact(id).first { it.content == "算了" }.status)

        chats.clearHistory(id)
        assertEquals(listOf(id), h.queue.deliveryCancelled)
        chats.deleteConversation(id)
        chats.clearAllHistory()
        assertEquals(listOf(id, id, id), h.queue.deliveryCancelled)
        val contacts = ContactRepository(db, db.contactDao(), db.conversationDao(), db.messageDao(), db.memoryDao(), db.usageDao(), FileStorage(context), { it }, h.queue)
        contacts.delete(id)
        assertEquals(4, h.queue.deliveryCancelled.size)
    }

    // ---------- 读消息的地方 ----------

    @Test
    fun pendingIsInvisibleToSearchPreviewUnreadAnchorAndRegenerate() = runBlocking {
        h.engine.run(id)
        val q = escapeLike("马上")
        assertTrue(db.messageDao().searchGrouped(q).isEmpty())
        assertTrue(db.messageDao().searchInChat(id, q).isEmpty())
        assertNull(db.messageDao().unreadAnchor(id, 0))
        assertEquals(0, db.messageDao().countNewer(id, start - 10_000, 1))
        assertFalse(isomeria.aichat.data.repo.ChatMessageActions(db).regenerate(id))
        h.delivery.run(id)
        assertEquals(1, db.messageDao().searchGrouped(q).single().hitCount)
        assertEquals("马上来", db.messageDao().unreadAnchor(id, 0)?.content)
        assertEquals(2, db.messageDao().countNewer(id, start - 10_000, 1))
        assertTrue(ChatPaging(db).timeline(id).first().regenerationAllowed)
    }
}
