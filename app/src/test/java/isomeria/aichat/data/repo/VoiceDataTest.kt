package isomeria.aichat.data.repo

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import isomeria.aichat.core.Clock
import isomeria.aichat.core.Sleeper
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.reply.ActiveChatTracker
import isomeria.aichat.data.reply.DeliveryEngine
import isomeria.aichat.data.reply.ReplyAlerts
import isomeria.aichat.data.reply.ReplyNotifications
import isomeria.aichat.data.reply.ReplyQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 0.7.0 语音消息在数据层的检查清单（路线图第四节第 7 条）：发送后先识别再等待回复、会话预览「[语音]」、
 * 搜索包含转写文字、删除和清空带上语音文件、存储空间和孤立文件、通知里显示「[语音]」，
 * 还在合成的语音回复不会送达、杀进程后按文字送达。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var filesDir: File
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private lateinit var chats: ChatRepository
    private lateinit var contacts: ContactRepository
    private val queue = FakeQueue()
    private val notifications = FakeNotifications()
    private val previews = MessagePreviewer { m -> if (m.type == MessageType.VOICE) "[语音]" else m.content }
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        val cacheDir = temporary.newFolder("cache")
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = this@VoiceDataTest.filesDir
            override fun getCacheDir() = cacheDir
        }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).allowMainThreadQueries().build()
        files = FileStorage(context)
        chats = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), files, queue, notifications, previews)
        contacts = ContactRepository(db, db.contactDao(), db.conversationDao(), db.messageDao(), db.memoryDao(), db.usageDao(), files, { it }, queue)
        runBlocking {
            for (id in 1L..2L) {
                db.contactDao().insert(Contact(id = id, name = "联系人$id", avatarColor = 0, providerId = 1, model = "m", createdAt = id, updatedAt = id))
                db.conversationDao().upsert(Conversation(contactId = id))
            }
        }
    }

    @After
    fun tearDown() = db.close()

    private fun recording(bytes: Int = 100): File = files.newVoiceFile("wav").apply { writeBytes(ByteArray(bytes) { 1 }) }

    private fun voiceDir() = File(filesDir, FileStorage.VOICE_DIR)

    @Test
    fun sendVoice_showsVoiceBarFirst_notWaitingForReplyUntilTranscribed() = runBlocking {
        val file = recording()
        chats.sendVoice(1, file, 3_400)
        val message = db.messageDao().listByContact(1).single()
        assertEquals(MessageType.VOICE, message.type)
        assertEquals(MessageStatus.SENDING, message.status)
        assertEquals("", message.content)
        assertEquals(file.absolutePath, message.voicePath)
        assertEquals(3_400L, message.voiceMillis)
        assertEquals(listOf(1L), queue.enqueued)
        assertEquals("[语音]", db.conversationDao().get(1)!!.lastMessagePreview)
        assertFalse("识别成功前不显示「对方正在输入…」", db.messageDao().hasWaitingReply(1))
        assertTrue("但它确实在等（回复任务会先识别）", db.messageDao().hasSending(1))
        assertEquals(listOf(message.id), db.messageDao().untranscribedVoice(1).map { it.id })
        assertTrue("写库后解除固定：清理孤立文件按引用判断", file.canonicalPath !in StorageOperations.pinnedPaths())

        db.messageDao().setTranscript(message.id, "识别出的文字", null)
        assertTrue(db.messageDao().hasWaitingReply(1))
        assertTrue(db.messageDao().untranscribedVoice(1).isEmpty())
    }

    @Test
    fun search_findsVoiceTranscripts() = runBlocking {
        db.messageDao().insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "明天去爬山", voicePath = "/v/a.mp3", status = MessageStatus.SUCCESS, createdAt = 10))
        db.messageDao().insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "爬山要带水", voicePath = "/v/b.mp3", status = MessageStatus.PENDING, createdAt = 20))
        assertEquals(listOf("明天去爬山"), chats.searchInChat(1, "爬山").map { it.content })
        assertEquals(1, chats.searchMessageGroups("爬山").single().group.hitCount)
    }

    @Test
    fun deletingMessage_clearingAndDeletingChatOrContact_deleteVoiceFiles() = runBlocking {
        fun insert(contactId: Long, at: Long): Pair<Long, File> {
            val file = recording()
            return runBlocking {
                db.messageDao().insert(Message(contactId = contactId, role = Role.USER, type = MessageType.VOICE, content = "x", voicePath = file.absolutePath, status = MessageStatus.SUCCESS, createdAt = at))
            } to file
        }
        val (single, singleFile) = insert(1, 10)
        val (_, keep) = insert(1, 20)
        chats.deleteMessage(single)
        assertFalse(singleFile.exists())
        assertTrue(keep.exists())

        chats.clearHistory(1)
        assertFalse("清空聊天记录带上语音文件", keep.exists())

        val (_, deleted) = insert(1, 30)
        chats.deleteConversation(1)
        assertFalse(deleted.exists())

        val (_, all1) = insert(1, 40)
        val (_, all2) = insert(2, 50)
        chats.clearAllHistory()
        assertFalse(all1.exists() || all2.exists())

        val (_, contactFile) = insert(2, 60)
        contacts.delete(2)
        assertFalse("删除联系人带上语音文件", contactFile.exists())
    }

    @Test
    fun orphanScan_keepsVoiceInUse_findsUnusedVoice() = runBlocking {
        val used = recording()
        val unused = recording()
        files.commitAvatar(used.absolutePath)
        files.commitAvatar(unused.absolutePath)
        db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.VOICE, content = "x", voicePath = used.absolutePath, status = MessageStatus.SUCCESS, createdAt = 10))
        val referenced = mediaReferences(emptyList(), emptyList(), null, emptyList(), db.messageDao().allVoicePaths())
        assertEquals(listOf(unused.canonicalPath), files.unreferenced(referenced).map { it.canonicalPath })
        // 还没写库的新录音固定着，不会被当成孤立文件
        val fresh = recording()
        assertFalse(files.unreferenced(referenced).any { it.canonicalPath == fresh.canonicalPath })
        assertEquals(voiceDir().canonicalPath, used.parentFile!!.canonicalPath)
    }

    @Test
    fun heldVoiceReply_isNotDelivered_untilSynthesised_andRecoverDeliversItAsText() = runBlocking {
        val activeChat = ActiveChatTracker()
        val delivery = DeliveryEngine(db, ReplyAlerts(db.conversationDao(), notifications, activeChat), activeChat, {}, queue, Clock { now }, Sleeper { now += it }, previews)
        val held = db.messageDao().insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "等合成的话", status = MessageStatus.PENDING, replyGroup = "g", createdAt = now - 1))
        assertTrue(db.messageDao().hasPending(1))
        assertTrue("没有语音文件，不送达", delivery.deliverDue(1).isEmpty())
        assertNull("投递循环不会等它（不会空转）", db.messageDao().nextPendingAt(1))
        assertFalse(delivery.hasWaiting(1))
        delivery.run(1)
        assertEquals(MessageStatus.PENDING, db.messageDao().get(held)!!.status)

        // 合成好了：填上文件，按时送达；预览和通知是「[语音]」
        val file = recording()
        assertEquals(1, db.messageDao().finishSynthesis(held, file.absolutePath, 2_000, now))
        assertTrue(delivery.deliverDueAndNotify(1))
        assertEquals(MessageStatus.SUCCESS, db.messageDao().get(held)!!.status)
        assertEquals("[语音]", db.conversationDao().get(1)!!.lastMessagePreview)
        assertEquals(listOf(listOf("[语音]")), notifications.parts)

        // 合成途中 App 被杀：启动恢复时按文字送达，不再合成
        val second = db.messageDao().insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "被打断的语音", status = MessageStatus.PENDING, replyGroup = "h", createdAt = now))
        delivery.recover()
        db.messageDao().get(second)!!.let {
            assertEquals(MessageType.TEXT, it.type)
            assertEquals(MessageStatus.SUCCESS, it.status)
        }
        assertEquals("被打断的语音", db.conversationDao().get(1)!!.lastMessagePreview)
    }

    @Test
    fun voiceFlags_playedAndShowText() = runBlocking {
        val id = db.messageDao().insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "话", voicePath = "/v/x.mp3", status = MessageStatus.SUCCESS, createdAt = 10))
        chats.setVoiceShowText(id, true)
        db.messageDao().get(id)!!.let { assertTrue("转文字也算看过，红点去掉", it.voiceShowText && it.voicePlayed) }
        chats.setVoiceShowText(id, false)
        db.messageDao().get(id)!!.let { assertTrue(!it.voiceShowText && it.voicePlayed) }
        val other = db.messageDao().insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "另一条", voicePath = "/v/y.mp3", status = MessageStatus.SUCCESS, createdAt = 20))
        chats.markVoicePlayed(other)
        assertTrue(db.messageDao().get(other)!!.voicePlayed)
        assertEquals(listOf(other), chats.voiceAfter(db.messageDao().get(id)!!).map { it.id })
    }

    @Test
    fun storageUsageReference_coversVoice() {
        // StorageUsage 有单独的「语音」一项，mediaReferences 收语音路径
        assertEquals(setOf("/a", "/v"), mediaReferences(listOf("/a"), emptyList(), null, emptyList(), listOf("/v")))
        assertEquals(7L, StorageUsage(0, 0, 0, 0, 0, voice = 7).voice)
    }

    private class FakeNotifications : ReplyNotifications {
        val parts = mutableListOf<List<String>>()
        override fun notifyReply(contact: Contact, parts: List<String>, alert: Boolean) { this.parts += parts }
        override fun cancelReply(contactId: Long) = Unit
    }

    private class FakeQueue : ReplyQueue {
        val enqueued = mutableListOf<Long>()
        override fun enqueue(contactId: Long) { enqueued += contactId }
        override fun enqueueDelivery(contactId: Long) = Unit
        override fun cancel(contactId: Long) = Unit
        override suspend fun isActive(contactId: Long) = false
        override fun cancelDelivery(contactId: Long) = Unit
        override suspend fun isDelivering(contactId: Long) = false
    }
}
