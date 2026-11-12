package isomeria.aichat.data.repo

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.reply.ReplyNotifications
import isomeria.aichat.data.reply.ReplyQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 0.8.0 通话消息在数据层的检查清单（路线图第四节第 7 条）：会话预览「[通话]」和时长、搜索命中通话里的话、
 * 删除通话和清空、删除聊天和联系人带上通话音频、孤立文件检查认得通话音频、正在进行的通话不能删；
 * 上下文和记忆读到的聊天记录里通话按条展开（[CallHistory]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CallDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var filesDir: File
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private lateinit var chats: ChatRepository
    private lateinit var contacts: ContactRepository
    private lateinit var history: CallHistory

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        val cacheDir = temporary.newFolder("cache")
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = this@CallDataTest.filesDir
            override fun getCacheDir() = cacheDir
        }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).allowMainThreadQueries().build()
        files = FileStorage(context)
        chats = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), files, FakeQueue(), FakeNotifications(), MessagePreviewer.PLAIN)
        contacts = ContactRepository(db, db.contactDao(), db.conversationDao(), db.messageDao(), db.memoryDao(), db.usageDao(), files, { it }, FakeQueue())
        history = CallHistory(db)
        runBlocking {
            for (id in 1L..2L) {
                db.contactDao().insert(Contact(id = id, name = "联系人$id", avatarColor = 0, providerId = 1, model = "m", createdAt = id, updatedAt = id))
                db.conversationDao().upsert(Conversation(contactId = id))
            }
        }
    }

    @After
    fun tearDown() = db.close()

    private fun audio(extension: String = "m4a"): File = files.newCallFile(extension).apply {
        writeBytes(ByteArray(50) { 1 })
        files.commitAvatar(absolutePath)
    }

    /** 一次已经挂断的通话：用户一句、联系人两句；返回通话消息 id 和音频文件 */
    private suspend fun call(contactId: Long, at: Long, millis: Long = 9_000): Pair<Long, List<File>> {
        val id = db.messageDao().insert(Message(contactId = contactId, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, voiceMillis = millis, createdAt = at))
        val clips = listOf(audio(), audio("mp3"), audio("mp3"))
        db.callDao().insertAll(
            listOf(
                CallTurn(messageId = id, seq = 0, role = Role.USER, text = "周末去爬山吧", audioPath = clips[0].absolutePath, audioMillis = 1_200, createdAt = at + 1_000),
                CallTurn(messageId = id, seq = 1, role = Role.ASSISTANT, text = "好呀", audioPath = clips[1].absolutePath, audioMillis = 800, createdAt = at + 3_000),
                CallTurn(messageId = id, seq = 2, role = Role.ASSISTANT, text = "去哪座山", audioPath = clips[2].absolutePath, audioMillis = 900, createdAt = at + 4_000, status = CallTurnStatus.INTERRUPTED),
            ),
        )
        return id to clips
    }

    private fun callsDir() = File(filesDir, FileStorage.CALLS_DIR)

    @Test
    fun preview_showsCallAndDuration() {
        val ongoing = Message(contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, createdAt = 1)
        assertEquals("[CALL]", MessagePreviewer.PLAIN.preview(ongoing))
        assertEquals("[CALL] 03:21", MessagePreviewer.PLAIN.preview(ongoing.copy(voiceMillis = 201_000)))
    }

    @Test
    fun search_findsCallByTurnText_showsMatchingTurns() = runBlocking {
        db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "爬山累不累", status = MessageStatus.SUCCESS, createdAt = 50))
        val (id, _) = call(1, 100)
        val hits = chats.searchInChat(1, "爬山")
        assertEquals(listOf(id), hits.filter { it.type == MessageType.CALL }.map { it.id })
        assertEquals("搜索结果里显示命中的那句", "周末去爬山吧", hits.single { it.type == MessageType.CALL }.content)
        assertEquals("通话消息本身不存文字", "", db.messageDao().get(id)!!.content)
        val groups = chats.searchMessageGroups("哪座")
        assertEquals(1, groups.single().group.hitCount)
        assertEquals("去哪座山", groups.single().single!!.content)
        assertTrue("没命中的不出现", chats.searchInChat(1, "游泳").isEmpty())
    }

    @Test
    fun deleteCall_removesTurnsAndAudio_ongoingCallCannotBeDeleted() = runBlocking {
        val (id, clips) = call(1, 100)
        chats.deleteMessage(id)
        assertTrue(db.callDao().listAll().isEmpty())
        assertTrue(clips.none { it.exists() })

        val ongoing = db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, createdAt = 500))
        chats.deleteMessage(ongoing)
        assertTrue("正在进行的通话不能删", db.messageDao().get(ongoing) != null)
    }

    @Test
    fun clearDeleteChatAndContact_removeCallAudio() = runBlocking {
        val (_, first) = call(1, 100)
        chats.clearHistory(1)
        assertTrue(first.none { it.exists() })
        assertTrue(db.callDao().listAll().isEmpty())

        val (_, second) = call(1, 200)
        chats.deleteConversation(1)
        assertTrue(second.none { it.exists() })

        val (_, third) = call(2, 300)
        val (_, fourth) = call(1, 400)
        contacts.delete(2)
        assertTrue(third.none { it.exists() })
        assertTrue("别的联系人的通话还在", fourth.all { it.exists() })
        assertEquals(3, db.callDao().listAll().size)

        chats.clearAllHistory()
        assertTrue(fourth.none { it.exists() })
        assertTrue(db.callDao().listAll().isEmpty())
        assertTrue(callsDir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun orphanScan_keepsCallAudioInUse_findsUnused() = runBlocking {
        val (_, clips) = call(1, 100)
        val unused = audio()
        val referenced = db.messageDao().allVoicePaths().toSet()
        assertEquals(listOf(unused.canonicalPath), files.unreferenced(referenced).map { it.canonicalPath })
        assertTrue(clips.all { it.exists() })
        val fresh = files.newCallFile("m4a").apply { writeBytes(ByteArray(10)) }
        assertFalse("还没写库的新文件是固定的，不当成孤立文件", files.unreferenced(referenced).any { it.canonicalPath == fresh.canonicalPath })
    }

    @Test
    fun history_expandsCalls_forRecentAfterAndBefore() = runBlocking {
        db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "通话前", status = MessageStatus.SUCCESS, createdAt = 50))
        call(1, 100)
        db.messageDao().insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "通话后", status = MessageStatus.SUCCESS, createdAt = 20_000))
        assertEquals(listOf("通话前", "周末去爬山吧", "好呀", "去哪座山", "通话后"), history.recent(1, 50).map { it.content })
        // 记忆的进度停在通话中间：后半段接着总结，前半段不再算
        assertEquals(listOf("好呀", "去哪座山", "通话后"), history.after(1, 1_100).map { it.content })
        assertEquals(listOf("通话后"), history.after(1, 4_100).map { it.content })
        // 往前总结：通话里早于起点的那几句
        assertEquals(listOf("通话前", "周末去爬山吧"), history.before(1, 3_000, 50).map { it.content })
        // 没有通话的聊天和直接查一样
        assertEquals(db.messageDao().recent(2, 50), history.recent(2, 50))
    }

    private class FakeNotifications : ReplyNotifications {
        override fun notifyReply(contact: Contact, parts: List<String>, alert: Boolean) = Unit
        override fun cancelReply(contactId: Long) = Unit
    }

    private class FakeQueue : ReplyQueue {
        override fun enqueue(contactId: Long) = Unit
        override fun enqueueDelivery(contactId: Long) = Unit
        override fun cancel(contactId: Long) = Unit
        override suspend fun isActive(contactId: Long) = false
        override fun cancelDelivery(contactId: Long) = Unit
        override suspend fun isDelivering(contactId: Long) = false
    }
}
