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
import isomeria.aichat.data.repo.GlobalMemoryRepository
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.UsageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
        val request = harness.runAndRewind(contactId)
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

    // ---------- 0.5.0：新能力默认开启时的请求内容 ----------

    private fun systemOf(contactId: Long): String? = runBlocking {
        harness.runAndRewind(contactId).messages.firstOrNull { it.role == isomeria.aichat.data.llm.ChatRole.SYSTEM }?.plainText()
    }

    private suspend fun globalItem(content: String, category: String = "个人信息", topic: String = "基本情况") {
        val repo = GlobalMemoryRepository(db, { harness.preferences }, Clock { now })
        val c = db.globalMemoryDao().categories().firstOrNull { it.name == category }?.id ?: repo.addCategory(category)
        val t = db.globalMemoryDao().topicsOf(c).firstOrNull { it.name == topic }?.id ?: repo.addTopic(c, topic)
        repo.addItem(t, content)
    }

    private val time = "现在是 2026年11月1日 星期日 23:40。对方的昵称是「阿哲」。"
    private val style = "回复方式：每次 1～5 条，每条 14 字左右，用换行分隔。"

    @Test
    fun newContactWithoutPersona_readsGlobalMemory_andReplyStyle_byDefault() = runBlocking {
        globalItem("住在杭州")
        globalItem("关掉的", topic = "其他")
        db.globalMemoryDao().items().first { it.content == "关掉的" }.let { db.globalMemoryDao().updateItems(listOf(it.copy(enabled = false))) }
        assertEquals("全局记忆：\n[个人信息 / 基本情况]\n- 住在杭州\n\n$style\n\n$time", systemOf(3))
    }

    @Test
    fun eachSwitchRemovesOnlyItsPart_andAllOffMatchesGolden() = runBlocking {
        globalItem("住在杭州")
        val contact = db.contactDao().get(2)!!
        db.contactDao().update(contact.copy(useGlobalMemory = false))
        val noGlobal = systemOf(2)!!
        db.contactDao().update(contact.copy(useReplyPreferences = false))
        val noStyle = systemOf(2)!!
        val memory = "下面是你对之前聊天的记忆，自然地参考就好，不要主动提到这份记忆：\n- [2026年11月1日] 喜欢拿铁"
        assertEquals("$memory\n\n$style\n\n$time", noGlobal)
        assertEquals("全局记忆：\n[个人信息 / 基本情况]\n- 住在杭州\n\n$memory\n\n$time", noStyle)
        harness.allOff(2)
        assertEquals(GOLDEN_GENERIC, requestBody(2))
    }

    @Test
    fun upgradedLegacyContact_requestChangesWhileOn_andReturnsToGoldenWhenOff() = runBlocking {
        globalItem("住在杭州")
        val on = requestBody(1)
        assertTrue(on != GOLDEN_DEEPSEEK && on.contains("全局记忆：") && on.contains("回复方式："))
        harness.allOff(1)
        assertEquals(GOLDEN_DEEPSEEK, requestBody(1))
    }

    // ---------- 0.6.0：主动聊天关闭时，请求内容和 0.5.0 逐字一致 ----------

    /** 期望值是在 0.6.0 改动请求构造之前（提交 7b3cd1c，只改了版本号），用同一套数据跑 0.5.0 的代码得到的 */
    @Test
    fun defaultsOn_proactiveOff_matches050() {
        runBlocking { globalItem("住在杭州") }
        assertEquals(GOLDEN_050_DEEPSEEK, requestBody(1))
        assertEquals(GOLDEN_050_GENERIC, requestBody(2))
        assertEquals(GOLDEN_050_EMPTY, requestBody(3))
    }

    @Test
    fun proactiveOn_addsOnlyTheReminderHintBeforeTheTime_andOffReturnsTo050() = runBlocking {
        globalItem("住在杭州")
        for (id in listOf(1L, 2L, 3L)) db.contactDao().update(db.contactDao().get(id)!!.copy(proactiveEnabled = true))
        val hint = ReplyHarness.REMINDER_HINT.replace("\"", "\\\"")
        fun withHint(golden: String) = golden.replace("\\n\\n现在是 2026年11月1日", "\\n\\n$hint\\n\\n现在是 2026年11月1日")
        assertEquals(withHint(GOLDEN_050_DEEPSEEK), requestBody(1))
        assertEquals(withHint(GOLDEN_050_GENERIC), requestBody(2))
        assertEquals(withHint(GOLDEN_050_EMPTY), requestBody(3))
        // 全部 0.5.0 新能力关闭、只开主动聊天：没有人设的联系人也会有系统提示词（提示 + 时间）
        harness.allOff(3)
        assertEquals("${ReplyHarness.REMINDER_HINT}\n\n$time", systemOf(3))
        for (id in listOf(1L, 2L, 3L)) db.contactDao().update(db.contactDao().get(id)!!.copy(proactiveEnabled = false))
        assertEquals(GOLDEN_050_DEEPSEEK, requestBody(1))
        assertEquals(GOLDEN_050_GENERIC, requestBody(2))
        assertEquals(GOLDEN_EMPTY, requestBody(3))
    }

    /** 全局记忆远超上下文上限：聊天记录被挤掉，但这次等待回复的消息一定带上，回复照常写入 */
    @Test
    fun hugeGlobalMemory_chatStillSendsWaitingMessage() = runBlocking {
        repeat(600) { globalItem("一条很长的全局记忆内容，用来把上下文挤满。") }
        harness.engine.run(1)
        val request = harness.provider.requests.last()
        assertEquals(listOf("SYSTEM", "USER"), request.messages.map { it.role.name })
        assertEquals("[图片]\n看看这张", request.messages.last().plainText())
        assertTrue(db.messageDao().sendingIds(1).isEmpty())
        assertTrue(db.messageDao().listByContact(1).any { it.role == Role.ASSISTANT && it.content == "好的" })
    }

    companion object {
        const val GOLDEN_DEEPSEEK = """{"model":"deepseek-chat","messages":[{"role":"system","content":"你是小雨，正在用微信和对方聊天。\n想分几条发时，用空行隔开。\n\n现在是 2026年11月1日 星期日 23:40。对方的昵称是「阿哲」。"},{"role":"user","content":"在吗"},{"role":"assistant","content":"在呀\n\n怎么啦"},{"role":"user","content":"[图片]\n看看这张"}],"stream":true,"stream_options":{"include_usage":true},"thinking":{"type":"enabled"},"reasoning_effort":"high"}"""
        const val GOLDEN_GENERIC = """{"model":"gpt-x","messages":[{"role":"system","content":"下面是你对之前聊天的记忆，自然地参考就好，不要主动提到这份记忆：\n- [2026年11月1日] 喜欢拿铁\n\n现在是 2026年11月1日 星期日 23:40。对方的昵称是「阿哲」。"},{"role":"user","content":"早"}],"stream":true,"stream_options":{"include_usage":true},"temperature":0.7}"""
        const val GOLDEN_EMPTY = """{"model":"gpt-x","messages":[{"role":"user","content":"hi"}],"stream":true,"stream_options":{"include_usage":true},"temperature":1.3}"""
        const val GOLDEN_050_DEEPSEEK = """{"model":"deepseek-chat","messages":[{"role":"system","content":"全局记忆：\n[个人信息 / 基本情况]\n- 住在杭州\n\n你是小雨，正在用微信和对方聊天。\n想分几条发时，用空行隔开。\n\n回复方式：每次 1～5 条，每条 14 字左右，用换行分隔。\n\n现在是 2026年11月1日 星期日 23:40。对方的昵称是「阿哲」。"},{"role":"user","content":"在吗"},{"role":"assistant","content":"在呀\n怎么啦"},{"role":"user","content":"[图片]\n看看这张"}],"stream":true,"stream_options":{"include_usage":true},"thinking":{"type":"enabled"},"reasoning_effort":"high"}"""
        const val GOLDEN_050_GENERIC = """{"model":"gpt-x","messages":[{"role":"system","content":"全局记忆：\n[个人信息 / 基本情况]\n- 住在杭州\n\n下面是你对之前聊天的记忆，自然地参考就好，不要主动提到这份记忆：\n- [2026年11月1日] 喜欢拿铁\n\n回复方式：每次 1～5 条，每条 14 字左右，用换行分隔。\n\n现在是 2026年11月1日 星期日 23:40。对方的昵称是「阿哲」。"},{"role":"user","content":"早"}],"stream":true,"stream_options":{"include_usage":true},"temperature":0.7}"""
        const val GOLDEN_050_EMPTY = """{"model":"gpt-x","messages":[{"role":"system","content":"全局记忆：\n[个人信息 / 基本情况]\n- 住在杭州\n\n回复方式：每次 1～5 条，每条 14 字左右，用换行分隔。\n\n现在是 2026年11月1日 星期日 23:40。对方的昵称是「阿哲」。"},{"role":"user","content":"hi"}],"stream":true,"stream_options":{"include_usage":true},"temperature":1.3}"""
    }
}

/** 用内存数据库、固定时钟和记录请求的假服务商拼出一个真实的 [ReplyEngine]。 */
internal class ReplyHarness(context: android.content.Context, val db: AppDatabase, zone: ZoneId, var now: Long) {
    val provider = RecordingProvider()
    val notifications = RecordingNotifications()
    val memoryQueue = mutableListOf<Long>()
    val activeChat = ActiveChatTracker()
    val queue = RecordingQueue()
    var nickname = "阿哲"

    /** 假装等完，时钟往前走；投递循环和「稍等几秒」都经过它 */
    val sleeper = isomeria.aichat.core.Sleeper { sleeps += it; now += it }

    val delivery by lazy {
        DeliveryEngine(db, ReplyAlerts(db.conversationDao(), notifications, activeChat), activeChat, { memoryQueue += it }, queue, Clock { now }, sleeper)
    }

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
            globalMemoryHeader = "全局记忆：",
            globalMemoryTopic = "[%1\$s / %2\$s]",
            globalMemoryItem = "- %1\$s",
            replyStyle = "回复方式：每次 %1\$s 条，每条 %2\$d 字左右，用换行分隔。",
            countRange = "%1\$d～%2\$d",
            reminderHint = REMINDER_HINT,
        ),
        zone,
    )

    // ---------- 0.6.0 主动聊天 ----------

    val control = RecordingControl()
    var proactiveSettings = isomeria.aichat.core.ProactiveSettings()

    /** 掷概率用的随机数：小于概率就触发。默认 0，只要概率大于 0 就触发 */
    var roll = 0.0
    val random = object : kotlin.random.Random() {
        override fun nextBits(bitCount: Int) = 0
        override fun nextDouble() = roll
    }

    val proactiveLabels = ProactiveLabels(
        instruction = "【主动】现在是 %1\$s，%2\$s。不想找对方只输出 [SKIP]。",
        reminderInstruction = "【约定】现在是 %1\$s。约定 %2\$s：%3\$s。",
        time = "%1\$d年%2\$d月%3\$d日 %4\$s %5\$02d:%6\$02d",
        weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"),
        sinceMinutes = "上一条消息在 %1\$d 分钟前",
        sinceHours = "上一条消息在 %1\$d 小时前",
        sinceDays = "上一条消息在 %1\$d 天前",
        noHistory = "还没有聊过",
        reminderNoNote = "按说好的做",
        reminderKeyword = "提醒",
    )

    val reminders = ProactiveReminders(db, control, { zone }, proactiveLabels)

    /** 每个联系人最近一次主动请求的结果（真实实现存在 MMKV 里） */
    val results = mutableMapOf<Long, Pair<Long, isomeria.aichat.core.ProactiveResult>>()
    private val resultLog = object : isomeria.aichat.core.ProactiveResultLog {
        override fun record(contactId: Long, at: Long, result: isomeria.aichat.core.ProactiveResult) {
            results[contactId] = at to result
        }

        override fun last(contactId: Long) = results[contactId]
    }

    val proactive by lazy {
        ProactiveEngine(
            db = db,
            providers = providers,
            usage = UsageRepository(db.usageDao()),
            nicknames = { nickname },
            files = FileStorage(context),
            contextBuilder = contextBuilder,
            clock = Clock { now },
            promptGlobals = GlobalMemoryRepository(db, { preferences }, Clock { now }),
            preferences = { preferences },
            settings = { proactiveSettings },
            random = random,
            activeChat = activeChat,
            queue = queue,
            reminders = reminders,
            labels = proactiveLabels,
            zone = { zone },
            results = resultLog,
            voice = voice,
        )
    }

    /** 0.7.0 语音：主动聊天用的语音流水线，要在第一次用 [proactive] 之前设好 */
    var voice: VoicePipeline? = null

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
        nicknames = { nickname },
        files = FileStorage(context),
        contextBuilder = contextBuilder,
        clock = Clock { now },
        promptGlobals = GlobalMemoryRepository(db, { preferences }, Clock { now }),
        preferences = { preferences },
        random = random,
        sleeper = sleeper,
        delivery = delivery,
        queue = queue,
        reminders = reminders,
    )

    var preferences = isomeria.aichat.core.RHYTHM_TEST_PREFERENCES

    /** 「稍等几秒」请求的等待；假装等完，时钟往前走 */
    val sleeps = mutableListOf<Long>()

    /** 跑一次回复，返回发出的请求；然后把这个联系人的消息还原，下一次请求看到的历史和这次一样 */
    suspend fun runAndRewind(contactId: Long): ChatRequest {
        val before = db.messageDao().listByContact(contactId)
        val count = provider.requests.size
        engine.run(contactId)
        check(provider.requests.size == count + 1) { "这次没有发出请求" }
        db.messageDao().deleteByContact(contactId)
        db.messageDao().insertAll(before)
        return provider.requests.last()
    }

    companion object {
        const val REMINDER_HINT = "约定时间：写 [提醒 年-月-日 时:分 事由]。"
    }

    /** 关掉这个联系人的全部 0.5.0 新能力 */
    fun allOff(contactId: Long) = runBlocking {
        val contact = db.contactDao().get(contactId)!!
        db.contactDao().update(contact.copy(useGlobalMemory = false, useReplyPreferences = false))
    }
}

internal class RecordingQueue : ReplyQueue {
    val enqueued = mutableListOf<Long>()
    val deliveries = mutableListOf<Long>()
    val cancelled = mutableListOf<Long>()
    val deliveryCancelled = mutableListOf<Long>()
    override fun enqueue(contactId: Long) { enqueued += contactId }
    override fun enqueueDelivery(contactId: Long) { deliveries += contactId }
    val restarts = mutableListOf<Long>()
    override fun restartDelivery(contactId: Long) { restarts += contactId }
    override fun cancel(contactId: Long) { cancelled += contactId }
    override suspend fun isActive(contactId: Long) = false
    override fun cancelDelivery(contactId: Long) { deliveryCancelled += contactId }
    override suspend fun isDelivering(contactId: Long) = false
}

/** 记录主动聊天的后台任务被要求对齐了几次 */
internal class RecordingControl : ProactiveControl {
    var syncs = 0
    override suspend fun sync() {
        syncs++
    }
}

internal class RecordingProvider {
    val requests = mutableListOf<ChatRequest>()
    var lastProtocol = ""
    var reply = "好的\n\n马上来"
    var beforeReply: (suspend () -> Unit)? = null

    /** 不为空时请求失败 */
    var failure: Exception? = null

    fun forProtocol(protocol: String): LlmProvider = object : LlmProvider {
        override val id = protocol
        override suspend fun listModels(endpoint: LlmEndpoint) = emptyList<RemoteModel>()
        override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = flow {
            // 和真实服务商一样先拼请求头：Key 有问题时在这里失败
            endpoint.authorization()
            requests += request
            lastProtocol = protocol
            beforeReply?.invoke()
            failure?.let { throw it }
            emit(ChatEvent.Content(reply))
            emit(ChatEvent.Usage(100, 10))
        }
    }
}

internal class RecordingNotifications : ReplyNotifications {
    /** 联系人、通知内容、是否响铃 */
    val notified = mutableListOf<Triple<Long, List<String>, Boolean>>()
    val cancelled = mutableListOf<Long>()
    override fun notifyReply(contact: Contact, parts: List<String>, alert: Boolean) {
        notified += Triple(contact.id, parts, alert)
    }

    override fun cancelReply(contactId: Long) {
        cancelled += contactId
    }
}
