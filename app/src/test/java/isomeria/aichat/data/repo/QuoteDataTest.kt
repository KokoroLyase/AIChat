package isomeria.aichat.data.repo

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.reply.ReplyNotifications
import isomeria.aichat.data.reply.ReplyQueue
import isomeria.aichat.feature.chat.QuoteDraft
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
 * 0.9.0 引用在数据层：能引用的只有发送成功的文字和有转写的语音（双方的都行）；发文字、语音时存被引用消息的快照；
 * 引用条发一条取走一次；点引用块时原消息还在这个聊天里才跳，删了或是别的聊天的都算不在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class QuoteDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private lateinit var chats: ChatRepository

    @Before
    fun setUp() {
        val filesDir = temporary.newFolder("files")
        val cacheDir = temporary.newFolder("cache")
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = filesDir
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

    private fun message(type: MessageType, content: String = "", status: MessageStatus = MessageStatus.SUCCESS, role: Role = Role.ASSISTANT, contactId: Long = 1, id: Long = 0) =
        Message(id = id, contactId = contactId, role = role, type = type, content = content, status = status, createdAt = 100)

    @Test
    fun canQuote_successfulTextAndTranscribedVoiceOfBothSides() {
        assertTrue(canQuote(message(MessageType.TEXT, "在呀")))
        assertTrue(canQuote(message(MessageType.TEXT, "早", role = Role.USER)))
        assertTrue(canQuote(message(MessageType.VOICE, "语音的转写")))
        assertTrue(canQuote(message(MessageType.VOICE, "我说的", role = Role.USER)))
        assertFalse("没有转写的语音", canQuote(message(MessageType.VOICE, " ")))
        assertFalse(canQuote(message(MessageType.IMAGE)))
        assertFalse(canQuote(message(MessageType.CALL)))
        assertFalse("等待回复中", canQuote(message(MessageType.TEXT, "早", MessageStatus.SENDING, Role.USER)))
        assertFalse("失败", canQuote(message(MessageType.TEXT, "早", MessageStatus.FAILED, Role.USER)))
        assertFalse("待投递", canQuote(message(MessageType.TEXT, "在呀", MessageStatus.PENDING)))
    }

    @Test
    fun sendTextAndVoice_storeTheSnapshot_withoutQuoteAllNull() = runBlocking {
        val quoted = message(MessageType.VOICE, "联系人的语音转写", id = 0).let { it.copy(id = db.messageDao().insert(it)) }
        chats.sendText(1, " 好呀 ", quoted)
        val voiceFile = files.newVoiceFile("wav").apply { writeBytes(ByteArray(10)) }
        chats.sendVoice(1, voiceFile, 1_000, quoted)
        chats.sendText(1, "不引用")
        val sent = db.messageDao().listByContact(1).filter { it.role == Role.USER }.associateBy { it.type to it.content }
        for (key in listOf(MessageType.TEXT to "好呀", MessageType.VOICE to "")) {
            val m = sent.getValue(key)
            assertEquals(quoted.id, m.quoteId)
            assertEquals(Role.ASSISTANT, m.quoteRole)
            assertEquals(MessageType.VOICE, m.quoteType)
            assertEquals("联系人的语音转写", m.quoteText)
        }
        val plain = sent.getValue(MessageType.TEXT to "不引用")
        assertNull(plain.quoteId)
        assertNull(plain.quoteRole)
        assertNull(plain.quoteType)
        assertNull(plain.quoteText)
    }

    @Test
    fun draft_takeOnce_onlyQuotableFromThisChat() {
        val draft = QuoteDraft(contactId = 1)
        val text = message(MessageType.TEXT, "在呀", id = 5)
        draft.start(message(MessageType.IMAGE, id = 6))
        assertNull(draft.state.value)
        draft.start(message(MessageType.TEXT, "别的聊天", contactId = 2, id = 7))
        assertNull(draft.state.value)
        draft.start(text)
        assertEquals(text, draft.state.value)
        assertEquals(text, draft.take())
        assertNull("发出去后引用条收起", draft.state.value)
        assertNull("下一条不再带", draft.take())
        draft.start(text)
        draft.cancel()
        assertNull(draft.take())
    }

    @Test
    fun quoteEntry_onlyWhenOriginalStillInThisChat() = runBlocking {
        val here = db.messageDao().insert(message(MessageType.TEXT, "在呀"))
        val elsewhere = db.messageDao().insert(message(MessageType.TEXT, "别的聊天", contactId = 2))
        val gone = db.messageDao().insert(message(MessageType.TEXT, "删掉的"))
        db.messageDao().delete(gone)
        assertEquals(here, chats.quoteEntry(1, here)?.targetId)
        assertNull(chats.quoteEntry(1, elsewhere))
        assertNull(chats.quoteEntry(1, gone))
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
