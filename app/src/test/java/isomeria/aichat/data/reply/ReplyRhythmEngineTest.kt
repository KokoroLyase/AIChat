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
import isomeria.aichat.data.llm.ChatRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * 回复偏好接进回复流程：稍等几秒、按换行拆条、去句尾标点、按打字间隔排开、第一条扣掉生成时间；
 * 关闭回复偏好时保留旧的分条显示。时钟、随机数和等待都是假的，结果可以逐毫秒核对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReplyRhythmEngineTest {
    private val context = RuntimeEnvironment.getApplication()
    private val start = 1_800_000_000_000L
    private lateinit var db: AppDatabase
    private lateinit var harness: ReplyHarness

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        harness = ReplyHarness(context, db, ZoneId.of("Asia/Shanghai"), start)
        runBlocking {
            db.providerDao().insert(
                ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "测试", baseUrl = "https://example.com/v1", apiKeyEncrypted = "enc:k", defaultModel = "m"),
            )
            db.providerDao().upsertModels(listOf(ProviderModel(1, "m", "M")))
        }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun contact(prefs: Boolean, split: Boolean = true): Long {
        val id = db.contactDao().insert(
            Contact(name = "小雨", avatarColor = 0, providerId = 1, model = "m", systemPrompt = "人设", splitReplies = split, useReplyPreferences = prefs, createdAt = 1, updatedAt = 1),
        )
        db.conversationDao().upsert(Conversation(contactId = id))
        return id
    }

    private suspend fun userMessage(contactId: Long, content: String, at: Long) = db.messageDao().insert(
        Message(contactId = contactId, role = Role.USER, type = MessageType.TEXT, content = content, status = MessageStatus.SENDING, createdAt = at),
    )

    private suspend fun replies(contactId: Long) = db.messageDao().listByContact(contactId).filter { it.role == Role.ASSISTANT }

    @Test
    fun preferencesOn_splitsByLine_stripsTrailingPunctuation_andSpacesByTypingTime() = runBlocking {
        val id = contact(prefs = true)
        userMessage(id, "到哪了", start - 10_000) // 已经等够 3 秒
        harness.provider.reply = "好的。\n\n马上就到，\n你在哪？\n" + "一".repeat(20)
        harness.provider.beforeReply = { harness.now += 1_500 } // 生成用了 1.5 秒
        harness.engine.run(id)
        val finished = start + 1_500
        val rows = replies(id)
        assertEquals(listOf("好的", "马上就到", "你在哪？", "一".repeat(20)), rows.map { it.content })
        // 第一条：3 秒 - 生成的 1.5 秒；之后按字数，最短 3 秒
        assertEquals(listOf(finished + 1_500, finished + 4_500, finished + 7_500, finished + 13_500), rows.map { it.createdAt })
        assertTrue(rows.map { it.replyGroup }.distinct().size == 1)
        assertTrue(harness.sleeps.isEmpty())
    }

    @Test
    fun preferencesOn_longGeneration_firstAppearsImmediately_andTailMergesIntoLast() = runBlocking {
        harness.preferences = harness.preferences.copy(maxCount = 2)
        val id = contact(prefs = true)
        userMessage(id, "在吗", start - 10_000)
        harness.provider.reply = "一。\n二。\n三。"
        harness.provider.beforeReply = { harness.now += 20_000 }
        harness.engine.run(id)
        val rows = replies(id)
        assertEquals(listOf("一", "二\n三"), rows.map { it.content })
        assertEquals(listOf(start + 20_000, start + 23_000), rows.map { it.createdAt })
    }

    @Test
    fun preferencesOn_waitsUntilQuiet_beforeCallingTheModel() = runBlocking {
        val id = contact(prefs = true)
        userMessage(id, "先说一句", start - 5_000)
        userMessage(id, "再补一句", start - 1_000)
        var requestedAt = 0L
        harness.provider.beforeReply = { requestedAt = harness.now }
        harness.engine.run(id)
        // 从最后一条算 3 秒，还差 2 秒；等完才发请求，两条一起回
        assertEquals(listOf(2_000L), harness.sleeps)
        assertEquals(start + 2_000, requestedAt)
        assertEquals("先说一句\n再补一句", harness.provider.requests.single().messages.last().plainText())

        // 等待时间设为 0：不等
        harness.preferences = harness.preferences.copy(waitSeconds = 0)
        userMessage(id, "还在吗", harness.now)
        harness.engine.run(id)
        assertEquals(listOf(2_000L), harness.sleeps)
    }

    @Test
    fun preferencesOff_keepsOldSplitReplies_blankLinesAndOneSecond_noWait() = runBlocking {
        val split = contact(prefs = false, split = true)
        userMessage(split, "在吗", start - 500)
        harness.provider.reply = "好的\n马上来。\n\n真的吗。"
        harness.engine.run(split)
        assertEquals(listOf("好的\n马上来。", "真的吗。"), replies(split).map { it.content })
        assertEquals(listOf(start, start + 1_000), replies(split).map { it.createdAt })

        val single = contact(prefs = false, split = false)
        userMessage(single, "在吗", start - 500)
        harness.engine.run(single)
        assertEquals(listOf("好的\n马上来。\n\n真的吗。"), replies(single).map { it.content })
        assertTrue("关闭回复偏好时不等待", harness.sleeps.isEmpty())
    }

    @Test
    fun history_rejoinsSplitReplyWithNewline_whenPreferencesOn_blankLineWhenOff() = runBlocking {
        val on = contact(prefs = true)
        val off = contact(prefs = false)
        for (id in listOf(on, off)) {
            db.messageDao().insert(Message(contactId = id, role = Role.USER, type = MessageType.TEXT, content = "嗨", status = MessageStatus.SUCCESS, createdAt = start - 9_000))
            db.messageDao().insert(Message(contactId = id, role = Role.ASSISTANT, type = MessageType.TEXT, content = "在呀", status = MessageStatus.SUCCESS, replyGroup = "g$id", createdAt = start - 8_000))
            db.messageDao().insert(Message(contactId = id, role = Role.ASSISTANT, type = MessageType.TEXT, content = "怎么啦", status = MessageStatus.SUCCESS, replyGroup = "g$id", createdAt = start - 7_000))
            userMessage(id, "没事", start - 6_000)
            harness.engine.run(id)
        }
        val (onRequest, offRequest) = harness.provider.requests
        assertEquals("在呀\n怎么啦", onRequest.messages.first { it.role == ChatRole.ASSISTANT }.plainText())
        assertEquals("在呀\n\n怎么啦", offRequest.messages.first { it.role == ChatRole.ASSISTANT }.plainText())
    }
}
