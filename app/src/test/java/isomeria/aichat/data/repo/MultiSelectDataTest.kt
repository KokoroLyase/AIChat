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
 * 0.9.0 多选在数据层：批量删除和单条删除同样的规则（通话连同每一条话、媒体文件在事务后删、会话预览刷新一次），
 * 等待回复中、待投递、正在进行的通话不删也不能选；复制按时间每条一行，通话展开成完整内容。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MultiSelectDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var filesDir: File
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private lateinit var chats: ChatRepository

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        val cacheDir = temporary.newFolder("cache")
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = this@MultiSelectDataTest.filesDir
            override fun getCacheDir() = cacheDir
        }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).allowMainThreadQueries().build()
        files = FileStorage(context)
        chats = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), files, FakeQueue(), FakeNotifications(), MessagePreviewer.PLAIN)
        runBlocking {
            for (id in 1L..2L) {
                db.contactDao().insert(Contact(id = id, name = "小林$id", avatarColor = 0, providerId = 1, model = "m", createdAt = id, updatedAt = id))
                db.conversationDao().upsert(Conversation(contactId = id))
            }
        }
    }

    @After
    fun tearDown() = db.close()

    private fun file(kind: MediaKind, extension: String): File = files.newMediaFile(kind, extension).apply { writeBytes(ByteArray(20) { 1 }) }

    private suspend fun insert(message: Message): Long = db.messageDao().insert(message)

    private fun text(contactId: Long, at: Long, content: String, status: MessageStatus = MessageStatus.SUCCESS, role: Role = Role.USER) =
        Message(contactId = contactId, role = role, type = MessageType.TEXT, content = content, status = status, createdAt = at)

    private class Seeded(val ids: Map<String, Long>, val files: Map<String, File>)

    private suspend fun seed(): Seeded {
        val image = file(MediaKind.IMAGE, "jpg")
        val voice = file(MediaKind.VOICE, "m4a")
        val clips = listOf(file(MediaKind.CALL, "m4a"), file(MediaKind.CALL, "mp3"))
        val ongoingClip = file(MediaKind.CALL, "m4a")
        val ids = linkedMapOf(
            "text" to insert(text(1, 100, "第一行\n第二行")),
            "image" to insert(Message(contactId = 1, role = Role.USER, type = MessageType.IMAGE, imagePath = image.absolutePath, imageWidth = 10, imageHeight = 10, status = MessageStatus.SUCCESS, createdAt = 200)),
            "voice" to insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "语音转写", voicePath = voice.absolutePath, voiceMillis = 1_500, status = MessageStatus.SUCCESS, createdAt = 300)),
            "call" to insert(Message(contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, voiceMillis = 9_000, createdAt = 400)),
            "failed" to insert(text(1, 500, "发送失败的", MessageStatus.FAILED)),
            "ongoing" to insert(Message(contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, voiceMillis = 0, createdAt = 600)),
            "sending" to insert(text(1, 700, "等待回复中", MessageStatus.SENDING)),
            "pending" to insert(text(1, 800, "待投递", MessageStatus.PENDING, Role.ASSISTANT)),
            "other" to insert(text(2, 150, "别的聊天")),
        )
        db.callDao().insertAll(
            listOf(
                CallTurn(messageId = ids.getValue("call"), seq = 0, role = Role.USER, text = "在吗", audioPath = clips[0].absolutePath, audioMillis = 900, createdAt = 1_000),
                CallTurn(messageId = ids.getValue("call"), seq = 1, role = Role.USER, text = "", audioPath = null, status = CallTurnStatus.ASR_FAILED, createdAt = 2_000),
                CallTurn(messageId = ids.getValue("call"), seq = 2, role = Role.ASSISTANT, text = "在呢", audioPath = clips[1].absolutePath, audioMillis = 800, createdAt = 3_000),
                CallTurn(messageId = ids.getValue("ongoing"), seq = 0, role = Role.USER, text = "还在打", audioPath = ongoingClip.absolutePath, audioMillis = 500, createdAt = 4_000),
            ),
        )
        return Seeded(ids, mapOf("image" to image, "voice" to voice, "clip0" to clips[0], "clip1" to clips[1], "ongoing" to ongoingClip))
    }

    @Test
    fun selectable_excludesWaitingPendingAndOngoingCall() = runBlocking {
        val seeded = seed()
        val all = seeded.ids.values.toList()
        val expected = listOf("text", "image", "voice", "call", "failed", "other").map(seeded.ids::getValue).toSet()
        assertEquals(expected, chats.selectableIds(all))
        assertFalse(isSelectable(db.messageDao().get(seeded.ids.getValue("ongoing"))!!))
        assertTrue(isSelectable(db.messageDao().get(seeded.ids.getValue("failed"))!!))
    }

    @Test
    fun deleteMessages_sameRulesAsSingleDelete_filesAndCallTurns_previewRefreshed() = runBlocking {
        val seeded = seed()
        db.conversationDao().setLastMessage(1, 800, "旧预览")
        val deleted = chats.deleteMessages(seeded.ids.values.filter { it != seeded.ids.getValue("other") })
        assertEquals(5, deleted)
        val left = db.messageDao().listByContact(1).map { it.content }
        assertEquals("等待回复中、待投递、正在进行的通话留下", setOf("", "等待回复中", "待投递"), left.toSet())
        assertEquals(3, left.size)
        // 媒体文件和通话音频都删了；正在进行的通话的音频还在
        assertFalse(seeded.files.getValue("image").exists())
        assertFalse(seeded.files.getValue("voice").exists())
        assertFalse(seeded.files.getValue("clip0").exists())
        assertFalse(seeded.files.getValue("clip1").exists())
        assertTrue(seeded.files.getValue("ongoing").exists())
        assertEquals(listOf("还在打"), db.callDao().listAll().map { it.text })
        // 会话预览按剩下的最后一条可见消息刷新（待投递的不可见）
        assertEquals("等待回复中", db.conversationDao().get(1)!!.lastMessagePreview)
        assertEquals(700L, db.conversationDao().get(1)!!.lastMessageTime)
        // 别的聊天不受影响
        assertEquals(listOf("别的聊天"), db.messageDao().listByContact(2).map { it.content })
    }

    @Test
    fun deleteMessages_everythingVisibleGone_previewCleared() = runBlocking {
        val first = insert(text(1, 100, "一"))
        val second = insert(text(1, 200, "二"))
        db.conversationDao().setLastMessage(1, 200, "二")
        assertEquals(2, chats.deleteMessages(listOf(first, second, 999)))
        assertEquals("", db.conversationDao().get(1)!!.lastMessagePreview)
        assertEquals(0L, db.conversationDao().get(1)!!.lastMessageTime)
        assertEquals(0, chats.deleteMessages(emptyList()))
    }

    @Test
    fun deleteMessages_manyIds_inChunks() = runBlocking {
        val ids = (1..1_200).map { insert(text(1, it.toLong(), "第$it 条")) }
        assertEquals(1_200, chats.deleteMessages(ids))
        assertTrue(db.messageDao().listByContact(1).isEmpty())
    }

    @Test
    fun copyText_oneLineEach_callExpanded_waitingIncluded_pendingSkipped() = runBlocking {
        val seeded = seed()
        val ids = listOf("pending", "call", "voice", "image", "text", "sending").map(seeded.ids::getValue)
        val lines = chats.copyText(1, ids).lines()
        assertEquals("第一行 第二行", lines[0])
        assertEquals("[图片]", lines[1])
        assertEquals("语音转写", lines[2])
        assertTrue(lines[3], lines[3].startsWith("语音通话 ") && lines[3].endsWith(" · 00:09"))
        assertEquals(listOf("我：在吗", "我：（没有识别出文字）", "小林1：在呢"), lines.subList(4, 7))
        assertEquals("单条复制本来就能复制等待回复中的文字", "等待回复中", lines[7])
        assertEquals("待投递的回复还看不到，不复制", 8, lines.size)
        assertNull(lines.firstOrNull { it == "待投递" })
    }

    @Test
    fun copyText_singleCall_fullContent() = runBlocking {
        val seeded = seed()
        val lines = chats.copyText(1, listOf(seeded.ids.getValue("call"))).lines()
        assertEquals(4, lines.size)
        assertTrue(Regex("""语音通话 \d{4}年\d{1,2}月\d{1,2}日 \d{2}:\d{2} · 00:09""").matches(lines[0]))
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
