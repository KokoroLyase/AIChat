package isomeria.aichat.data.db

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * v1 → v2 迁移：用 app/schemas/…/1.json 建出真实的旧库、写入数据，再让 Room 打开。
 * Room 打开时会执行迁移，并按实体定义校验表结构，不一致时直接报错。
 *
 * 真机上还要跑一遍 androidTest 里的 DatabaseTest（系统 SQLite 版本和这里不同）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val file: File get() = context.getDatabasePath(NAME)
    private var room: AppDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(NAME)
        file.parentFile?.mkdirs()
    }

    @After
    fun tearDown() {
        room?.close()
        context.deleteDatabase(NAME)
    }

    /** 按导出的 v1 schema 建库：和 0.2.0 手机上的库结构完全一样 */
    private fun createV1(version: Int = 1, fill: SQLiteDatabase.() -> Unit) {
        val schema = Json.parseToJsonElement(File("schemas/isomeria.aichat.data.db.AppDatabase/$version.json").readText())
            .jsonObject["database"]!!.jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in schema["entities"]!!.jsonArray) {
            val table = entity.jsonObject
            val name = table["tableName"]!!.jsonPrimitive.content
            db.execSQL(table.sql("createSql").replace("\${TABLE_NAME}", name))
            for (index in table["indices"]?.jsonArray.orEmpty()) {
                db.execSQL(index.jsonObject.sql("createSql").replace("\${TABLE_NAME}", name))
            }
        }
        for (statement in schema["setupQueries"]!!.jsonArray) db.execSQL(statement.jsonPrimitive.content)
        db.version = version
        db.fill()
        db.close()
    }

    private fun JsonObject.sql(key: String) = this[key]!!.jsonPrimitive.content

    private fun open(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, NAME)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        .allowMainThreadQueries()
        .build()
        .also { room = it }

    /** 1.json 里 room_master_table 的 identity 要写进去，否则 Room 会把它当成损坏的库 */
    private fun SQLiteDatabase.seedProvider(key: String? = "encrypted-key", baseUrl: String = "https://api.deepseek.com") {
        execSQL(
            "INSERT INTO provider_config (id, kind, provider, baseUrl, apiKeyEncrypted, defaultModel, enabled) " +
                "VALUES (1, 'LLM', 'deepseek', ?, ?, 'deepseek-v4-pro', 1)",
            arrayOf(baseUrl, key),
        )
        execSQL(
            "INSERT INTO provider_model (providerId, modelId, displayName, imageInput, imageInputUserMark, sortOrder) " +
                "VALUES (1, 'deepseek-flash', 'Flash', 'YES', NULL, 0), (1, 'deepseek-v4-pro', 'Pro', 'UNKNOWN', 1, 1)",
        )
    }

    private fun SQLiteDatabase.seedContact(id: Long, name: String, contextCount: Int) {
        execSQL(
            "INSERT INTO contact (id, name, avatarPath, avatarColor, providerId, model, thinking, temperature, " +
                "systemPrompt, contextCount, splitReplies, createdAt, updatedAt) " +
                "VALUES (?, ?, NULL, 123, 1, 'deepseek-flash', 'HIGH', 0.7, '人设', ?, 0, 1000, 2000)",
            arrayOf<Any>(id, name, contextCount),
        )
        execSQL(
            "INSERT INTO conversation (contactId, pinned, unreadCount, markedUnread, draft, lastMessageTime, lastMessagePreview) " +
                "VALUES (?, 1, 2, 0, '草稿', 5000, '预览')",
            arrayOf(id),
        )
    }

    private fun SQLiteDatabase.seedMessage(contactId: Long, role: String, time: Long, prompt: Int = 0, completion: Int = 0) {
        execSQL(
            "INSERT INTO message (contactId, role, type, content, imagePath, imageWidth, imageHeight, reasoning, " +
                "reasoningMillis, status, errorCode, model, promptTokens, completionTokens, replyGroup, createdAt) " +
                "VALUES (?, ?, 'TEXT', '内容', NULL, 0, 0, NULL, 0, 'SUCCESS', 0, NULL, ?, ?, NULL, ?)",
            arrayOf<Any>(contactId, role, prompt, completion, time),
        )
    }

    @Test
    fun existingDeepSeekSettingsAndContacts_areKept() = runBlocking {
        createV1 {
            seedProvider()
            seedContact(1, "小雨", contextCount = 20)
            seedContact(2, "阿哲", contextCount = 2)
            seedContact(3, "夏天", contextCount = 100)
        }
        val db = open()

        // 服务商：id、Base URL、加密的 Key、默认模型都不变，名称是 DeepSeek
        val provider = db.providerDao().llmList().single()
        assertEquals(1L, provider.id)
        assertEquals("DeepSeek", provider.name)
        assertEquals("deepseek", provider.provider)
        assertEquals("https://api.deepseek.com", provider.baseUrl)
        assertEquals("encrypted-key", provider.apiKeyEncrypted)
        assertEquals("deepseek-v4-pro", provider.defaultModel)

        // 模型：能力和用户标记不变，都支持思考
        val models = db.providerDao().models(1)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), models.map { it.modelId })
        assertTrue(models.all { it.supportsThinking })
        assertEquals(Capability.YES, models[0].imageInput)
        assertEquals(true, models[1].imageInputUserMark)
        assertTrue(models.none { it.manual })

        // 联系人：其他字段不变，条数 × 200 换成预算，最低 1000
        val contacts = db.contactDao().listAll()
        assertEquals(listOf(4000, 1000, 20_000), contacts.map { it.contextTokens })
        val first = contacts.first()
        assertEquals("小雨", first.name)
        assertEquals(1L, first.providerId)
        assertEquals(ThinkingLevel.HIGH, first.thinking)
        assertEquals(0.7f, first.temperature)
        assertEquals("人设", first.systemPrompt)
        assertEquals(false, first.splitReplies)
        assertTrue(first.autoMemory)

        // 会话不变；记忆起点留空，不回溯老聊天
        val conversation = db.conversationDao().get(1)!!
        assertEquals(true, conversation.pinned)
        assertEquals("草稿", conversation.draft)
        assertNull(conversation.memoryCursor)
        assertEquals(0L, conversation.memoryStart)
        assertTrue(db.memoryDao().listAll().isEmpty())
    }

    @Test
    fun usageIsBackfilledFromMessages_perLocalDayAndContact() = runBlocking {
        val zone = ZoneId.systemDefault()
        val day1 = LocalDate.of(2026, 10, 30)
        val day2 = day1.plusDays(1)
        fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        createV1 {
            seedProvider()
            seedContact(1, "小雨", 20)
            seedContact(2, "阿哲", 20)
            // 第一天凌晨和深夜各一次，算同一天
            seedMessage(1, "ASSISTANT", at(day1, 0), prompt = 10, completion = 1)
            seedMessage(1, "ASSISTANT", at(day1, 23), prompt = 20, completion = 2)
            // 分条回复的其他几条没有用量，不算请求
            seedMessage(1, "ASSISTANT", at(day1, 23) + 1000)
            seedMessage(1, "USER", at(day1, 22))
            seedMessage(1, "ASSISTANT", at(day2, 8), prompt = 30, completion = 3)
            seedMessage(2, "ASSISTANT", at(day2, 9), prompt = 40, completion = 4)
        }
        val rows = open().usageDao().listAll()
        assertEquals(
            listOf(
                UsageDaily(day1.toEpochDay(), 1, UsagePurpose.CHAT, 30, 3, 2),
                UsageDaily(day2.toEpochDay(), 1, UsagePurpose.CHAT, 30, 3, 1),
                UsageDaily(day2.toEpochDay(), 2, UsagePurpose.CHAT, 40, 4, 1),
            ),
            rows,
        )
    }

    @Test
    fun foreignKeysStillWork_afterMigration() = runBlocking {
        createV1 {
            seedProvider()
            seedContact(1, "小雨", 20)
            seedMessage(1, "USER", 5000)
        }
        val db = open()
        assertEquals(1, db.conversationDao().observeList().first().size)
        db.memoryDao().insert(Memory(contactId = 1, content = "记忆", fromTime = 1, toTime = 2, createdAt = 3, updatedAt = 3))
        db.contactDao().delete(1)
        assertNull(db.conversationDao().get(1))
        assertTrue(db.messageDao().listByContact(1).isEmpty())
        assertTrue(db.memoryDao().listByContact(1).isEmpty())
    }

    @Test
    fun emptyDatabase_migratesCleanly() = runBlocking {
        createV1 { seedProvider(key = null) }
        val db = open()
        assertNull(db.providerDao().llmList().single().apiKeyEncrypted)
        assertTrue(db.usageDao().listAll().isEmpty())
    }

    @Test
    fun v2ToV3_keepsLegacyIdentityKeysAndAllParameters() = runBlocking {
        createV1(version = 2) {
            execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled) VALUES(9,'LLM','openai','旧通用','https://example.com/A/v1','encrypted','m',1)")
            execSQL("INSERT INTO provider_model(providerId,modelId,displayName,imageInput,imageInputUserMark,sortOrder,thinkingInput,thinkingUserMark,manual) VALUES(9,'m','旧模型','UNKNOWN',1,0,'UNKNOWN',1,1)")
            execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt) VALUES(8,'旧联系人',1,9,'m','MAX',0.7,'人设',8000,0,0,10,20)")
        }
        val db = open()
        val p = db.providerDao().get(9)!!
        assertEquals("LEGACY", p.compatibility)
        assertNull(p.presetId)
        assertEquals("encrypted", p.apiKeyEncrypted)
        assertEquals("https://example.com/A/v1", p.baseUrl)
        assertEquals("m", p.defaultModel)
        val model = db.providerDao().model(9, "m")!!
        assertTrue(model.legacy && model.manual && model.supportsImage && model.supportsThinking)
        val contact = db.contactDao().get(8)!!
        assertEquals(ThinkingLevel.MAX, contact.thinking)
        assertEquals(0.7f, contact.temperature)
        assertNull(contact.thinkingOption)
        assertNull(contact.memoryModel)
        assertEquals("off", contact.memoryThinkingOption)
        assertEquals(8000, contact.contextTokens)
        assertFalse(contact.autoMemory)
    }

    @Test
    fun v3ToV4_conversationsDefaultToNotMutedAndNoBackground_otherFieldsKept() = runBlocking {
        createV1(version = 3) {
            execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled,presetId,compatibility) VALUES(1,'LLM','openai','DeepSeek','https://api.deepseek.com','encrypted','',1,'deepseek','DEEPSEEK')")
            execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt,thinkingOption,memoryModel,memoryThinkingOption) VALUES(8,'旧联系人',1,1,'m','OFF',0.4,'人设',6000,0,1,10,20,'high',NULL,'off')")
            execSQL("INSERT INTO conversation(contactId,pinned,unreadCount,markedUnread,draft,lastMessageTime,lastMessagePreview,memoryCursor,memoryStart) VALUES(8,1,4,1,'草稿',99,'预览',50,7)")
        }
        val db = open()
        val conversation = db.conversationDao().get(8)!!
        assertEquals(
            Conversation(
                contactId = 8, pinned = true, unreadCount = 4, markedUnread = true, draft = "草稿", lastMessageTime = 99,
                lastMessagePreview = "预览", memoryCursor = 50, memoryStart = 7, muted = false, backgroundPath = null,
            ),
            conversation,
        )
        // 联系人（决定请求内容的字段）原样保留
        val contact = db.contactDao().get(8)!!
        assertEquals(0.4f, contact.temperature)
        assertEquals("high", contact.thinkingOption)
        assertEquals("人设", contact.systemPrompt)
        assertEquals(6000, contact.contextTokens)
        assertFalse(contact.splitReplies)
        // 新列可以写
        db.conversationDao().setMuted(8, true)
        db.conversationDao().setBackground(8, "/x/background/a.jpg")
        assertEquals(listOf("/x/background/a.jpg"), db.conversationDao().backgroundPaths())
        assertTrue(db.conversationDao().get(8)!!.muted)
    }

    @Test
    fun v4ToV5_oldContactsUseGlobalMemoryAndReplyPreferences_splitRepliesAndRequestFieldsKept() = runBlocking {
        createV1(version = 4) {
            execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled,presetId,compatibility) VALUES(1,'LLM','openai','DeepSeek','https://api.deepseek.com','encrypted','',1,'deepseek','DEEPSEEK')")
            execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt,thinkingOption,memoryModel,memoryThinkingOption) VALUES(8,'分条',1,1,'m','OFF',0.4,'人设',6000,1,1,10,20,'high',NULL,'off'),(9,'不分条',1,1,'m','OFF',1.3,'',4000,0,0,11,21,NULL,'mm','high')")
            execSQL("INSERT INTO conversation(contactId,pinned,unreadCount,markedUnread,draft,lastMessageTime,lastMessagePreview,memoryCursor,memoryStart,muted,backgroundPath) VALUES(8,1,4,1,'草稿',99,'预览',50,7,1,'/bg/8.jpg')")
        }
        val db = open()
        val old = db.contactDao().get(8)!!
        assertTrue(old.useGlobalMemory)
        assertTrue(old.useReplyPreferences)
        assertTrue(old.splitReplies)
        assertEquals(0.4f, old.temperature)
        assertEquals("high", old.thinkingOption)
        assertEquals("人设", old.systemPrompt)
        val other = db.contactDao().get(9)!!
        assertTrue(other.useGlobalMemory && other.useReplyPreferences)
        assertFalse("原先关闭的分条显示保留", other.splitReplies)
        assertFalse(other.autoMemory)
        assertEquals("mm", other.memoryModel)
        // 0.4.2 的会话字段原样保留
        db.conversationDao().get(8)!!.let { assertTrue(it.muted); assertEquals("/bg/8.jpg", it.backgroundPath) }
        // 新表可读写，删除类别时主题和条目一起删
        val dao = db.globalMemoryDao()
        assertTrue(dao.categories().isEmpty())
        val category = dao.insertCategory(GlobalMemoryCategory(name = "个人信息", createdAt = 1, updatedAt = 1))
        val topic = dao.insertTopic(GlobalMemoryTopic(categoryId = category, name = "基本情况", createdAt = 1, updatedAt = 1))
        dao.insertItem(GlobalMemoryItem(topicId = topic, content = "住在杭州", createdAt = 1, updatedAt = 1))
        dao.deleteCategory(category)
        assertTrue(dao.topics().isEmpty() && dao.items().isEmpty())
        // 新建的联系人同样默认开启
        val id = db.contactDao().insert(Contact(name = "新", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1))
        db.contactDao().get(id)!!.let { assertTrue(it.useGlobalMemory && it.useReplyPreferences) }
    }

    /** 0.6.0：升级后主动聊天对已有联系人和新联系人都关闭，旧消息是普通消息，没有暂停状态、额度记录和约定提醒 */
    @Test
    fun v5ToV6_proactiveOffForOldAndNewContacts_oldMessagesAreNormal_otherFieldsKept() = runBlocking {
        createV1(version = 5) {
            execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled,presetId,compatibility) VALUES(1,'LLM','openai','DeepSeek','https://api.deepseek.com','encrypted','',1,'deepseek','DEEPSEEK')")
            execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt,thinkingOption,memoryModel,memoryThinkingOption,useGlobalMemory,useReplyPreferences) VALUES(8,'甲',1,1,'m','OFF',0.4,'人设',6000,1,1,10,20,'high',NULL,'off',0,1),(9,'乙',1,1,'m','OFF',1.3,'',4000,0,0,11,21,NULL,'mm','high',1,0)")
            execSQL("INSERT INTO conversation(contactId,pinned,unreadCount,markedUnread,draft,lastMessageTime,lastMessagePreview,memoryCursor,memoryStart,muted,backgroundPath) VALUES(8,1,4,1,'草稿',99,'预览',50,7,1,'/bg/8.jpg')")
            execSQL("INSERT INTO message(id,contactId,role,type,content,imageWidth,imageHeight,reasoningMillis,status,errorCode,promptTokens,completionTokens,replyGroup,createdAt) VALUES(1,8,'USER','TEXT','在吗',0,0,0,'SUCCESS',0,0,0,NULL,90),(2,8,'ASSISTANT','TEXT','在',0,0,0,'PENDING',0,7,3,'g',99)")
            execSQL("INSERT INTO usage_daily(day,contactId,purpose,promptTokens,completionTokens,requests) VALUES(20000,8,'CHAT',7,3,1)")
        }
        val db = open()
        val contacts = db.contactDao().listAll()
        assertTrue("已有联系人的主动聊天默认关闭", contacts.none { it.proactiveEnabled })
        assertTrue(contacts.all { it.proactiveFrequency == ProactiveFrequency.MEDIUM })
        assertEquals(0, db.contactDao().proactiveCount())
        // 0.5.0 的两个开关和请求相关字段原样保留
        assertEquals(listOf(false, true), contacts.map { it.useGlobalMemory })
        assertEquals(listOf(true, false), contacts.map { it.useReplyPreferences })
        assertEquals(0.4f, contacts[0].temperature)
        assertEquals("high", contacts[0].thinkingOption)
        val messages = db.messageDao().listByContact(8)
        assertTrue(messages.all { it.origin == MessageOrigin.NORMAL })
        assertEquals(listOf(MessageStatus.SUCCESS, MessageStatus.PENDING), messages.map { it.status })
        db.conversationDao().get(8)!!.let {
            assertEquals(0, it.proactiveUnanswered)
            assertEquals(0L, it.lastProactiveAt)
            assertTrue(it.muted)
            assertEquals("/bg/8.jpg", it.backgroundPath)
            assertEquals("草稿", it.draft)
        }
        assertEquals(UsagePurpose.CHAT, db.usageDao().listAll().single().purpose)
        // 新表：没有状态行、没有提醒；可读写，删除联系人时提醒一起删
        val proactive = db.proactiveDao()
        assertNull(proactive.state())
        assertNull(proactive.nextReminderAt())
        proactive.saveState(ProactiveState(day = 20000, used = 3, lastRunAt = 5))
        proactive.saveState(ProactiveState(day = 20001, used = 1, lastRunAt = 6))
        assertEquals(ProactiveState(day = 20001, used = 1, lastRunAt = 6), proactive.state())
        proactive.insertReminder(ProactiveReminder(contactId = 9, dueAt = 500, note = "叫他起床", createdAt = 1))
        assertEquals(500L, proactive.nextReminderAt())
        db.contactDao().delete(9)
        assertNull(proactive.nextReminderAt())
        // 新建的联系人同样默认关闭；用量可以记主动聊天
        val id = db.contactDao().insert(Contact(name = "新", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1))
        assertFalse(db.contactDao().get(id)!!.proactiveEnabled)
        db.usageDao().add(20001, 8, UsagePurpose.PROACTIVE, 5, 2)
        assertEquals(listOf(UsagePurpose.CHAT, UsagePurpose.PROACTIVE), db.usageDao().listAll().map { it.purpose })
    }

    /** 清空或删除聊天时连续未回复次数清零；用户发言用的清零和主动发送的计数互不影响别的字段 */
    @Test
    fun proactiveCounters_incrementResetAndClearWithHistory() = runBlocking {
        createV1(version = 5) {
            execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled,presetId,compatibility) VALUES(1,'LLM','openai','DeepSeek','https://api.deepseek.com',NULL,'',1,'deepseek','DEEPSEEK')")
            execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt,memoryThinkingOption,useGlobalMemory,useReplyPreferences) VALUES(8,'甲',1,1,'m','OFF',1.3,'',4000,1,1,10,20,'off',1,1),(9,'乙',1,1,'m','OFF',1.3,'',4000,1,1,10,20,'off',1,1)")
            execSQL("INSERT INTO conversation(contactId,pinned,unreadCount,markedUnread,draft,lastMessageTime,lastMessagePreview,memoryStart,muted) VALUES(8,1,4,0,'草稿',99,'预览',0,1),(9,1,4,0,'',99,'预览',0,0)")
        }
        val db = open()
        val dao = db.conversationDao()
        dao.addProactiveSent(8, 100)
        dao.addProactiveSent(8, 200)
        dao.setLastProactiveAt(9, 300)
        dao.get(8)!!.let { assertEquals(2, it.proactiveUnanswered); assertEquals(200L, it.lastProactiveAt); assertEquals(4, it.unreadCount) }
        dao.get(9)!!.let { assertEquals(0, it.proactiveUnanswered); assertEquals(300L, it.lastProactiveAt) }
        dao.clearProactiveUnanswered(8)
        dao.get(8)!!.let { assertEquals(0, it.proactiveUnanswered); assertEquals(200L, it.lastProactiveAt); assertEquals("草稿", it.draft); assertTrue(it.muted) }
        dao.addProactiveSent(8, 400)
        dao.addProactiveSent(9, 400)
        dao.clearHistory(8)
        dao.reset(9)
        assertEquals(listOf(0, 0), dao.listAll().map { it.proactiveUnanswered })
        dao.addProactiveSent(8, 500)
        dao.resetAll()
        assertEquals(0, dao.get(8)!!.proactiveUnanswered)
    }

    @Test
    fun conversationResetDropsBackground_clearHistoryKeepsMuteAndBackground() = runBlocking {
        createV1(version = 3) {
            execSQL("INSERT INTO provider_config(id,kind,provider,name,baseUrl,apiKeyEncrypted,defaultModel,enabled,presetId,compatibility) VALUES(1,'LLM','openai','DeepSeek','https://api.deepseek.com',NULL,'',1,'deepseek','DEEPSEEK')")
            execSQL("INSERT INTO contact(id,name,avatarColor,providerId,model,thinking,temperature,systemPrompt,contextTokens,splitReplies,autoMemory,createdAt,updatedAt,memoryThinkingOption) VALUES(8,'甲',1,1,'m','OFF',1.3,'',4000,1,1,10,20,'off'),(9,'乙',1,1,'m','OFF',1.3,'',4000,1,1,10,20,'off')")
            execSQL("INSERT INTO conversation(contactId,pinned,unreadCount,markedUnread,draft,lastMessageTime,lastMessagePreview,memoryStart) VALUES(8,1,4,0,'',99,'预览',0),(9,1,4,0,'',99,'预览',0)")
        }
        val db = open()
        for (id in listOf(8L, 9L)) {
            db.conversationDao().setMuted(id, true)
            db.conversationDao().setBackground(id, "/bg/$id.jpg")
        }
        db.conversationDao().clearHistory(8)
        db.conversationDao().reset(9)
        db.conversationDao().get(8)!!.let { assertTrue(it.muted); assertEquals("/bg/8.jpg", it.backgroundPath) }
        db.conversationDao().get(9)!!.let { assertTrue(it.muted); assertNull(it.backgroundPath); assertFalse(it.pinned) }
        db.conversationDao().setBackground(9, "/bg/9.jpg")
        db.conversationDao().resetAll()
        assertEquals(listOf("/bg/8.jpg", "/bg/9.jpg"), db.conversationDao().backgroundPaths().sorted())
        assertTrue(db.conversationDao().listAll().all { it.muted })
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
