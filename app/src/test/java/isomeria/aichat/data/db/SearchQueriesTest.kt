package isomeria.aichat.data.db

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 搜索和清空聊天记录用到的查询 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SearchQueriesTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addCallback(AppDatabase.seedCallback)
            .build()
        for ((id, name) in listOf(1L to "小雨", 2L to "阿哲", 3L to "100%纯")) {
            db.contactDao().insert(Contact(id = id, name = name, avatarColor = 0, providerId = 1, model = "m", createdAt = id, updatedAt = id))
            db.conversationDao().upsert(Conversation(contactId = id, pinned = id == 1L, draft = if (id == 1L) "草稿" else "", lastMessageTime = 9, lastMessagePreview = "x"))
        }
        listOf(
            Triple(1L, "今天打折100%", 10L),
            Triple(1L, "只要100元", 20L),
            Triple(1L, "变量 a_b", 30L),
            Triple(1L, "变量 axb", 40L),
            Triple(2L, "100块", 50L),
            Triple(2L, "晚安", 60L),
        ).forEach { (contactId, content, time) ->
            db.messageDao().insert(
                Message(contactId = contactId, role = Role.USER, type = MessageType.TEXT, content = content, status = MessageStatus.SUCCESS, createdAt = time),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun inChat(contactId: Long, q: String) = db.messageDao().searchInChat(contactId, escapeLike(q)).map { it.content }

    @Test
    fun wildcardsAreLiteral() = runBlocking {
        assertEquals(listOf("今天打折100%"), inChat(1, "100%"))
        assertEquals(listOf("变量 a_b"), inChat(1, "a_b"))
        assertEquals(listOf("100%纯"), db.contactDao().search(escapeLike("100%")).map { it.name })
        // 单独搜 % 和 _ 只找真正含有这个符号的消息
        assertEquals(listOf("今天打折100%"), inChat(1, "%"))
        assertEquals(listOf("变量 a_b"), inChat(1, "_"))
    }

    @Test
    fun searchInChat_newestFirst_onlyThisChat() = runBlocking {
        assertEquals(listOf("只要100元", "今天打折100%"), inChat(1, "100"))
        assertEquals(listOf("100块"), inChat(2, "100"))
    }

    @Test
    fun grouped_countsPerChat_latestHitFirst() = runBlocking {
        val groups = db.messageDao().searchGrouped(escapeLike("100"))
        assertEquals(listOf(2L to 1, 1L to 2), groups.map { it.contactId to it.hitCount })
        assertEquals("阿哲", groups.first().contactName)
        assertEquals(50L, groups.first().lastTime)
    }

    @Test
    fun clearHistory_keepsConversationRow() = runBlocking {
        db.messageDao().deleteByContact(1)
        db.conversationDao().clearHistory(1)
        val conversation = db.conversationDao().get(1)!!
        assertEquals(true, conversation.pinned)
        assertEquals("草稿", conversation.draft)
        assertEquals("", conversation.lastMessagePreview)
        assertEquals(listOf(1L, 2L, 3L), db.conversationDao().observeListOnce())
    }

    @Test
    fun resetAll_removesEveryChatFromList() = runBlocking {
        db.messageDao().deleteAll()
        db.conversationDao().resetAll()
        assertEquals(emptyList<Long>(), db.conversationDao().observeListOnce())
        assertEquals(3, db.contactDao().listAll().size)
    }

    private suspend fun ConversationDao.observeListOnce(): List<Long> =
        observeList().first().map { it.conversation.contactId }.sorted()
}
