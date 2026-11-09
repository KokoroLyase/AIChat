package isomeria.aichat.data.reply

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.Clock
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.ContextLabels
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Memory
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatRequest
import isomeria.aichat.data.llm.DeepSeekRequestBuilder
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.llm.OpenAiRequestBuilder
import isomeria.aichat.data.llm.RemoteModel
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.UsageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 请求内容回归：所有 0.5.0 新能力都关闭时，发给服务商的请求体必须和 0.4.2 逐字一致。
 *
 * 期望值是在 0.5.0 改动系统提示词之前，用同一套数据跑当时的代码（等同 0.4.2，0.4.2 没改请求构造）得到的。
 * 时间、昵称、提示词文字都写死，不依赖 strings.xml 里的模板（0.4.2 改过新联系人模板的文字）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReplyRequestGoldenTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 11, 1, 23, 40).atZone(zone).toInstant().toEpochMilli()
    private lateinit var db: AppDatabase
    private lateinit var harness: ReplyHarness

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        harness = ReplyHarness(context, db, zone, now)
        runBlocking { seed() }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        val providers = db.providerDao()
        providers.insert(
            ProviderConfig(
                id = 1, kind = ProviderKind.LLM, provider = ProviderProtocol.DEEPSEEK, name = "DeepSeek", baseUrl = "https://api.deepseek.com",
                apiKeyEncrypted = "enc:sk-test", defaultModel = "deepseek-chat",
            ),
        )
        providers.upsertModels(listOf(ProviderModel(1, "deepseek-chat", "DeepSeek Chat", thinkingInput = Capability.YES)))
        providers.insert(
            ProviderConfig(
                id = 2, kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "其他", baseUrl = "https://example.com/v1",
                apiKeyEncrypted = "enc:sk-other", defaultModel = "gpt-x",
            ),
        )
        providers.upsertModels(listOf(ProviderModel(2, "gpt-x", "GPT X", imageInput = Capability.YES)))
        db.contactDao().insert(
            Contact(
                id = 1, name = "小雨", avatarColor = 0, providerId = 1, model = "deepseek-chat", thinking = ThinkingLevel.HIGH,
                systemPrompt = "你是{名称}，正在用微信和对方聊天。\n想分几条发时，用空行隔开。\n{在这里写人设：性格、和对方的关系、说话习惯、是否使用 emoji}",
                createdAt = 1, updatedAt = 1,
            ),
        )
        db.contactDao().insert(
            Contact(id = 2, name = "阿哲", avatarColor = 0, providerId = 2, model = "gpt-x", temperature = 0.7f, systemPrompt = "", createdAt = 2, updatedAt = 2),
        )
        db.contactDao().insert(
            Contact(id = 3, name = "空白", avatarColor = 0, providerId = 2, model = "gpt-x", systemPrompt = "", splitReplies = false, createdAt = 3, updatedAt = 3),
        )
        listOf(1L, 2L, 3L).forEach { db.conversationDao().upsert(Conversation(contactId = it)) }
        val m = db.messageDao()
        m.insert(text(1, Role.USER, "在吗", now - 60_000))
        m.insert(text(1, Role.ASSISTANT, "在呀", now - 50_000, group = "g1").copy(reasoning = "思考不回传"))
        m.insert(text(1, Role.ASSISTANT, "怎么啦", now - 49_000, group = "g1"))
        m.insert(
            Message(contactId = 1, role = Role.USER, type = MessageType.IMAGE, imagePath = File(context.filesDir, "images/missing.jpg").path,
                imageWidth = 10, imageHeight = 10, status = MessageStatus.SENDING, createdAt = now - 2_000),
        )
        m.insert(text(1, Role.USER, "看看这张", now - 1_000, status = MessageStatus.SENDING))
        db.memoryDao().insert(Memory(contactId = 2, content = "喜欢拿铁", fromTime = now - 90_000, toTime = now - 80_000, createdAt = now, updatedAt = now))
        m.insert(text(2, Role.USER, "早", now - 1_000, status = MessageStatus.SENDING))
        m.insert(text(3, Role.USER, "hi", now - 1_000, status = MessageStatus.SENDING))
    }

    private fun text(contactId: Long, role: Role, content: String, at: Long, group: String? = null, status: MessageStatus = MessageStatus.SUCCESS) =
        Message(contactId = contactId, role = role, type = MessageType.TEXT, content = content, status = status, replyGroup = group, createdAt = at)

    /** 跑一次回复，返回真实请求构造器拼出的请求体 */
    private fun requestBody(contactId: Long): String = runBlocking {
        harness.engine.run(contactId)
        val request = harness.provider.requests.last()
        val body = if (harness.provider.lastProtocol == ProviderProtocol.DEEPSEEK) DeepSeekRequestBuilder.build(request) else OpenAiRequestBuilder.build(request)
        body.toString()
    }

    @Test
    fun legacyDeepSeek_allNewFeaturesOff_matches042() {
        harness.allOff(1)
        assertEquals(GOLDEN_DEEPSEEK, requestBody(1))
    }

    @Test
    fun genericWithMemory_allNewFeaturesOff_matches042() {
        harness.allOff(2)
        assertEquals(GOLDEN_GENERIC, requestBody(2))
    }

    @Test
    fun emptyPrompt_allNewFeaturesOff_sendsNoSystemMessage_like042() {
        harness.allOff(3)
        assertEquals(GOLDEN_EMPTY, requestBody(3))
    }

    companion object {
        const val GOLDEN_DEEPSEEK = """{"model":"deepseek-chat","messages":[{"role":"system","content":"你是小雨，正在用微信和对方聊天。\n想分几条发时，用空行隔开。\n\n现在是 2026年11月1日 星期日 23:40。对方的昵称是「阿哲」。"},{"role":"user","content":"在吗"},{"role":"assistant","content":"在呀\n\n怎么啦"},{"role":"user","content":"[图片]\n看看这张"}],"stream":true,"stream_options":{"include_usage":true},"thinking":{"type":"enabled"},"reasoning_effort":"high"}"""
        const val GOLDEN_GENERIC = """{"model":"gpt-x","messages":[{"role":"system","content":"下面是你对之前聊天的记忆，自然地参考就好，不要主动提到这份记忆：\n- [2026年11月1日] 喜欢拿铁\n\n现在是 2026年11月1日 星期日 23:40。对方的昵称是「阿哲」。"},{"role":"user","content":"早"}],"stream":true,"stream_options":{"include_usage":true},"temperature":0.7}"""
        const val GOLDEN_EMPTY = """{"model":"gpt-x","messages":[{"role":"user","content":"hi"}],"stream":true,"stream_options":{"include_usage":true},"temperature":1.3}"""
    }
}

/** 用内存数据库、固定时钟和记录请求的假服务商拼出一个真实的 [ReplyEngine]。 */
internal class ReplyHarness(context: android.content.Context, val db: AppDatabase, zone: ZoneId, var now: Long) {
    val provider = RecordingProvider()
    val notifications = RecordingNotifications()
    val memoryQueue = mutableListOf<Long>()
    val activeChat = ActiveChatTracker()
    var nickname = "阿哲"

    val contextBuilder = ContextBuilder(
        ContextLabels(
            namePlaceholder = "{名称}",
            personaPlaceholderPrefix = "{在这里写人设",
            timeLine = "现在是 %1\$d年%2\$d月%3\$d日 %4\$s %5\$02d:%6\$02d。",
            nicknameLine = "对方的昵称是「%1\$s」。",
            weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"),
            imagePlaceholder = "[图片]",
            memoryHeader = "下面是你对之前聊天的记忆，自然地参考就好，不要主动提到这份记忆：",
            memoryItem = "- [%1\$d年%2\$d月%3\$d日] %4\$s",
        ),
        zone,
    )

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }

    val providers = ProviderRepository(db, db.providerDao(), cipher, setOf(provider.forProtocol(ProviderProtocol.DEEPSEEK), provider.forProtocol(ProviderProtocol.OPENAI)))

    val engine = ReplyEngine(
        db = db,
        contactDao = db.contactDao(),
        messageDao = db.messageDao(),
        conversationDao = db.conversationDao(),
        memoryDao = db.memoryDao(),
        providers = providers,
        usage = UsageRepository(db.usageDao()),
        memoryScheduler = { memoryQueue += it },
        nicknames = { nickname },
        files = FileStorage(context),
        contextBuilder = contextBuilder,
        alerts = ReplyAlerts(db.conversationDao(), notifications, activeChat),
        activeChat = activeChat,
        clock = Clock { now },
    )

    /** 关掉这个联系人的全部 0.5.0 新能力 */
    fun allOff(contactId: Long) = runBlocking {
        val contact = db.contactDao().get(contactId)!!
        db.contactDao().update(contact.copy(useGlobalMemory = false, useReplyPreferences = false))
    }
}

internal class RecordingProvider {
    val requests = mutableListOf<ChatRequest>()
    var lastProtocol = ""
    var reply = "好的\n\n马上来"
    var beforeReply: (suspend () -> Unit)? = null

    fun forProtocol(protocol: String): LlmProvider = object : LlmProvider {
        override val id = protocol
        override suspend fun listModels(endpoint: LlmEndpoint) = emptyList<RemoteModel>()
        override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            lastProtocol = protocol
            beforeReply?.invoke()
            emit(ChatEvent.Content(reply))
            emit(ChatEvent.Usage(100, 10))
        }
    }
}

internal class RecordingNotifications : ReplyNotifications {
    val notified = mutableListOf<Pair<Long, List<String>>>()
    val cancelled = mutableListOf<Long>()
    override fun notifyReply(contact: Contact, parts: List<String>) {
        notified += contact.id to parts
    }

    override fun cancelReply(contactId: Long) {
        cancelled += contactId
    }
}
