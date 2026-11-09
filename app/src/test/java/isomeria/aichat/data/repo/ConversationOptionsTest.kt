package isomeria.aichat.data.repo

import android.app.Application
import android.content.ContextWrapper
import androidx.core.graphics.createBitmap
import androidx.room.Room
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.reply.ActiveChatTracker
import isomeria.aichat.data.reply.ReplyAlerts
import isomeria.aichat.data.reply.ReplyNotifications
import isomeria.aichat.data.reply.ReplyQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.4.2 会话的免打扰和聊天背景：通知抑制、背景文件的保存/更换/恢复默认、删除语义、孤立文件识别。
 * 用内存数据库、真实文件目录；回复任务和系统通知换成记录调用的假实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationOptionsTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var filesDir: File
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private lateinit var chats: ChatRepository
    private lateinit var contacts: ContactRepository
    private val notifications = FakeNotifications()
    private val queue = FakeQueue()
    private val activeChat = ActiveChatTracker()

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        val cacheDir = temporary.newFolder("cache")
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = this@ConversationOptionsTest.filesDir
            override fun getCacheDir() = cacheDir
        }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).addCallback(AppDatabase.seedCallback)
            .allowMainThreadQueries().build()
        files = FileStorage(context)
        chats = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), files, queue, notifications)
        contacts = ContactRepository(
            db, db.contactDao(), db.conversationDao(), db.messageDao(), db.memoryDao(), db.usageDao(), files, { it }, queue,
        )
        runBlocking {
            for (id in 1L..2L) {
                db.contactDao().insert(Contact(id = id, name = "联系人$id", avatarColor = 0, providerId = 1, model = "m", createdAt = id, updatedAt = id))
                db.conversationDao().upsert(Conversation(contactId = id, lastMessageTime = 10, lastMessagePreview = "旧"))
            }
        }
    }

    @After
    fun tearDown() = db.close()

    private fun backgroundDir() = File(filesDir, FileStorage.BACKGROUND_DIR)

    /** 相册选图后解码出的位图（系统解码器在单元测试里不可用，解码和压缩在设备测试里覆盖） */
    private fun picked(width: Int = 400, height: Int = 300): suspend () -> StoredImage = {
        files.saveBackground(createBitmap(width, height).apply { eraseColor(0xFF336699.toInt()) })
    }

    // ---------- 消息免打扰 ----------

    @Test
    fun muted_suppressesOnlyThatContactsReplyNotification() = runBlocking {
        val alerts = ReplyAlerts(db.conversationDao(), notifications, activeChat)
        chats.setMuted(1, true)
        val one = db.contactDao().get(1)!!
        val two = db.contactDao().get(2)!!
        alerts.afterReply(one, listOf("在吗"))
        alerts.afterReply(two, listOf("你好"))
        assertEquals(listOf(2L), notifications.notified)
        // 开启时收起已弹出的通知
        assertEquals(listOf(1L), notifications.cancelled)

        chats.setMuted(1, false)
        alerts.afterReply(one, listOf("在吗"))
        assertEquals("关闭后通知恢复", listOf(2L, 1L), notifications.notified)
    }

    @Test
    fun viewingChat_stillSuppressesNotification_whetherOrNotMuted() = runBlocking {
        val alerts = ReplyAlerts(db.conversationDao(), notifications, activeChat)
        activeChat.enter(2)
        alerts.afterReply(db.contactDao().get(2)!!, listOf("你好"))
        assertTrue(notifications.notified.isEmpty())
    }

    @Test
    fun muted_doesNotTouchUnreadPreviewOrPin() = runBlocking {
        db.conversationDao().addUnread(1, 3)
        chats.setPinned(1, true)
        chats.setMuted(1, true)
        val conversation = db.conversationDao().get(1)!!
        assertEquals(3, conversation.unreadCount)
        assertEquals("旧", conversation.lastMessagePreview)
        assertTrue(conversation.pinned)
        assertTrue(conversation.muted)
    }

    // ---------- 聊天背景 ----------

    @Test
    fun setBackground_compressesIntoBackgroundDir_andReplacingDeletesOldFile() = runBlocking {
        chats.setBackground(1, picked())
        val first = File(db.conversationDao().get(1)!!.backgroundPath!!)
        assertEquals(backgroundDir().canonicalPath, first.parentFile!!.canonicalPath)
        val decoded = first.readBytes().let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        assertEquals(400, decoded.width)
        assertTrue("写库后不再固定", StorageOperations.pinnedPaths().none { it == first.canonicalPath })

        chats.setBackground(1, picked(800, 600))
        val second = File(db.conversationDao().get(1)!!.backgroundPath!!)
        assertNotEquals(first, second)
        assertFalse("更换后删除旧背景", first.exists())
        assertTrue(second.exists())
        assertEquals(listOf(second.name), backgroundDir().list()!!.toList())
    }

    @Test
    fun clearBackground_restoresDefaultAndDeletesFile() = runBlocking {
        chats.setBackground(1, picked())
        val file = File(db.conversationDao().get(1)!!.backgroundPath!!)
        chats.clearBackground(1)
        assertNull(db.conversationDao().get(1)!!.backgroundPath)
        assertFalse(file.exists())
        chats.clearBackground(1) // 没有背景时什么也不做
    }

    @Test
    fun failedImport_leavesNoFileAndKeepsOldBackground() = runBlocking {
        chats.setBackground(1, picked())
        val old = db.conversationDao().get(1)!!.backgroundPath
        val result = runCatching { chats.setBackground(1) { throw java.io.IOException("decode failed") } }
        assertTrue(result.isFailure)
        assertEquals(old, db.conversationDao().get(1)!!.backgroundPath)
        assertEquals(1, backgroundDir().list()!!.size)
    }

    @Test
    fun clearHistory_keepsMuteAndBackground() = runBlocking {
        chats.setMuted(1, true)
        chats.setBackground(1, picked())
        db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "x", status = MessageStatus.SUCCESS, createdAt = 20))
        val path = db.conversationDao().get(1)!!.backgroundPath!!
        chats.clearHistory(1)
        val conversation = db.conversationDao().get(1)!!
        assertTrue(conversation.muted)
        assertEquals(path, conversation.backgroundPath)
        assertTrue(File(path).exists())
        assertTrue(db.messageDao().listByContact(1).isEmpty())
    }

    @Test
    fun clearAllHistory_keepsMuteAndBackground() = runBlocking {
        chats.setMuted(2, true)
        chats.setBackground(2, picked())
        val path = db.conversationDao().get(2)!!.backgroundPath!!
        chats.clearAllHistory()
        assertTrue(db.conversationDao().get(2)!!.muted)
        assertEquals(path, db.conversationDao().get(2)!!.backgroundPath)
        assertTrue(File(path).exists())
    }

    @Test
    fun deleteConversation_deletesBackgroundFile_keepsOtherChats() = runBlocking {
        chats.setBackground(1, picked())
        chats.setBackground(2, picked())
        val one = File(db.conversationDao().get(1)!!.backgroundPath!!)
        val two = File(db.conversationDao().get(2)!!.backgroundPath!!)
        chats.deleteConversation(1)
        assertNull(db.conversationDao().get(1)!!.backgroundPath)
        assertFalse("删除会话时删除背景文件", one.exists())
        assertTrue(two.exists())
        assertEquals(two.path, db.conversationDao().get(2)!!.backgroundPath)
    }

    @Test
    fun deleteContact_deletesBackgroundFile() = runBlocking {
        chats.setBackground(1, picked())
        val file = File(db.conversationDao().get(1)!!.backgroundPath!!)
        contacts.delete(1)
        assertFalse(file.exists())
        assertNull(db.conversationDao().get(1))
        assertEquals(listOf(1L), queue.cancelled)
    }

    @Test
    fun orphanScan_keepsBackgroundInUse_findsUnusedBackground() = runBlocking {
        chats.setBackground(1, picked())
        val used = File(db.conversationDao().get(1)!!.backgroundPath!!)
        val orphan = File(backgroundDir(), "orphan.jpg").apply { writeText("x") }
        val refs = mediaReferences(
            db.contactDao().avatarPaths(), db.messageDao().allImagePaths(), null, db.conversationDao().backgroundPaths(),
        )
        assertEquals(listOf(orphan.canonicalPath), files.unreferenced(refs).map { it.canonicalPath })
        files.deleteUnreferenced(refs)
        assertTrue(used.exists())
        assertFalse(orphan.exists())
    }

    @Test
    fun missingBackgroundFile_isTreatedAsNoBackground() = runBlocking {
        chats.setBackground(1, picked())
        val file = File(db.conversationDao().get(1)!!.backgroundPath!!)
        file.delete()
        assertNull(chatBackgroundFile(db.conversationDao().get(1)!!.backgroundPath))
        assertNull(chatBackgroundFile(null))
        chats.setBackground(1, picked())
        assertTrue(chatBackgroundFile(db.conversationDao().get(1)!!.backgroundPath)!!.isFile)
    }

    private class FakeNotifications : ReplyNotifications {
        val notified = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        override fun notifyReply(contact: Contact, parts: List<String>) { notified += contact.id }
        override fun cancelReply(contactId: Long) { cancelled += contactId }
    }

    private class FakeQueue : ReplyQueue {
        val cancelled = mutableListOf<Long>()
        override fun enqueue(contactId: Long) = Unit
        override fun cancel(contactId: Long) { cancelled += contactId }
        override suspend fun isActive(contactId: Long) = false
    }
}
