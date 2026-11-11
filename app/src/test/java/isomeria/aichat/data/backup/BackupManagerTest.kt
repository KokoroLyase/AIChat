package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.llm.*
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.GlobalMemoryCategory
import isomeria.aichat.data.db.GlobalMemoryItem
import isomeria.aichat.data.db.GlobalMemoryTopic
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Memory
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.ProactiveReminder
import isomeria.aichat.data.db.ProactiveState
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.db.UsageDaily
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.MediaKind
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 导出与恢复：导出后再恢复，内容完全一致；合并、密码、格式版本、失败回滚。
 * 用内存数据库和真实的文件目录；Keystore 和 MMKV 换成内存实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackupManagerTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private val cipher = FakeCipher()
    private val settings = FakeSettings()
    private val replies = FakeReplies()
    private lateinit var backup: BackupManager

    @Before
    fun setUp() {
        listOf("avatars", "images", "background", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.seedCallback)
            .build()
        files = FileStorage(context)
        backup = BackupManager(context, db, files, cipher, settings, replies)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- 导出 → 覆盖恢复 ----------

    @Test
    fun exportThenOverwrite_restoresIdenticalData() = runBlocking {
        seed()
        val before = dump()
        val zip = export(password = "pw123456")

        // 导出后本机数据被改得面目全非
        scramble()
        assertNotEquals(before, dump())

        val preview = backup.inspect(zip)
        assertTrue(preview.needsPassword)
        assertEquals(2, preview.contactCount)
        assertEquals(4, preview.messageCount)
        assertEquals(1, preview.imageCount)
        val summary = backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())

        assertEquals(before, dump())
        assertTrue(summary.apiKeyRestored)
        assertEquals("sk-real-key", cipher.decrypt(deepSeek().apiKeyEncrypted!!))
        // 覆盖前停掉了等待中的回复
        assertTrue(replies.cancelled.isNotEmpty())
        // 只留下被引用的图片和头像
        assertEquals(referencedFiles(), mediaFiles())
        // 数据库与设置都成功后，立即删除恢复前快照。
        assertFalse(backup.snapshotFile.exists())
    }

    @Test
    fun export_zipLayout_andNoPlainKey() = runBlocking {
        seed()
        val zip = export(password = null)
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map(ZipEntry::getName)
            assertEquals(BackupFormat.MANIFEST, names[0])
            assertEquals(BackupFormat.DATA, names[1])
            assertEquals(2, names.count { it.startsWith(BackupFormat.AVATARS_DIR) })
            assertEquals(1, names.count { it.startsWith(BackupFormat.IMAGES_DIR) })
            assertEquals(1, names.count { it.startsWith(BackupFormat.BACKGROUNDS_DIR) })
            val data = z.getInputStream(z.getEntry(BackupFormat.DATA)).readBytes().toString(Charsets.UTF_8)
            assertFalse("默认不导出 API Key", data.contains("sk-real-key"))
            assertFalse("Keystore 密文也不导出", data.contains(cipher.encrypt("sk-real-key")))
        }
        assertFalse(backup.inspect(zip).manifest.includesApiKey)
    }

    @Test
    fun overwriteWithoutKeyInBackup_keepsLocalKey() = runBlocking {
        seed()
        val zip = export(password = null)
        db.providerDao().update(deepSeek().copy(apiKeyEncrypted = cipher.encrypt("sk-new-local")))
        val summary = backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertFalse(summary.apiKeyRestored)
        assertEquals("sk-new-local", cipher.decrypt(deepSeek().apiKeyEncrypted!!))
    }

    @Test
    fun skippingPassword_restoresEverythingButKey() = runBlocking {
        seed()
        val zip = export(password = "pw123456")
        db.providerDao().update(deepSeek().copy(apiKeyEncrypted = null))
        val summary = backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertFalse(summary.apiKeyRestored)
        assertEquals(null, deepSeek().apiKeyEncrypted)
        assertEquals(2, db.contactDao().listAll().size)
    }

    @Test
    fun wrongPassword_changesNothing() = runBlocking {
        seed()
        val zip = export(password = "pw123456")
        scramble()
        val scrambled = dump()
        try {
            backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, "wrong-pw".toCharArray())
            fail("密码错误应该抛出异常")
        } catch (_: WrongPasswordException) {
        }
        assertEquals(scrambled, dump())
    }

    @Test
    fun sendingMessages_becomeFailedAfterRestore() = runBlocking {
        seed()
        val id = db.messageDao().insert(
            Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "等待中", status = MessageStatus.SENDING, createdAt = 9_000),
        )
        val zip = export(password = null)
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        val restored = db.messageDao().get(id)!!
        assertEquals(MessageStatus.FAILED, restored.status)
        assertEquals(FailReason.INTERRUPTED.code, restored.errorCode)
    }

    /** 0.5.0：待投递的回复按已送达导出，恢复后不会再投递；会话预览和未读按送达后的样子；覆盖恢复前停掉投递任务 */
    @Test
    fun pendingReplies_exportAsDelivered_andOverwriteStopsDelivery() = runBlocking {
        seed()
        val before = db.conversationDao().get(1)!!
        val ids = db.messageDao().insertAll(
            listOf(
                Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "还没送达一", status = MessageStatus.PENDING, replyGroup = "p", createdAt = 9_000),
                Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "还没送达二", status = MessageStatus.PENDING, replyGroup = "p", createdAt = 9_500),
            ),
        )
        val zip = export(password = null)
        val data = backup.inspect(zip).data
        assertTrue(data.messages.none { it.status == MessageStatus.PENDING })
        data.conversations.first { it.contactId == 1L }.let {
            assertEquals("还没送达二", it.lastMessagePreview)
            assertEquals(9_500L, it.lastMessageTime)
            assertEquals(before.unreadCount + 2, it.unreadCount)
        }
        // 本机的会话没被导出改动
        assertEquals(before, db.conversationDao().get(1))

        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertTrue(ids.all { db.messageDao().get(it)!!.status == MessageStatus.SUCCESS })
        assertFalse(db.messageDao().hasPending(1))
        assertEquals("还没送达二", db.conversationDao().get(1)!!.lastMessagePreview)
        assertTrue(replies.deliveryCancelled.containsAll(listOf(1L, 2L)))
    }

    @Test fun v3RoundTripKeepsMemorySelectionOverridesAndManualIdentityAfterIdMerge() = runBlocking {
        seed()
        val config = db.providerDao().get(secondProviderId)!!
        db.providerDao().update(config.copy(presetId = "mimo", compatibility = "MIMO"))
        val model = db.providerDao().model(secondProviderId, "local")!!.copy(legacy = false, missingFromList = true,
            reportedConfig = ModelRules.encode(ModelConfig(chat = true)),
            userConfig = ModelRules.encode(ModelConfig(thinking = ThinkingRule(ThinkingMode.ALWAYS, listOf(NativeEffort("native", "原生档位"))), temperature = TemperatureRule(TemperatureSpec(TemperatureMode.FIXED, fixed = 1f)))),
            compatibilityOverride = ModelRules.encode(CompatibilityOverride(ThinkingField.THINKING_TYPE, EffortField.REASONING_EFFORT, false)))
        db.providerDao().upsertModels(listOf(model))
        db.contactDao().update(db.contactDao().get(2)!!.copy(memoryModel = "local", memoryThinkingOption = "native", thinkingOption = "on"))
        val before = dump()
        val zip = export(password = "pw123456")
        assertEquals(7, backup.inspect(zip).manifest.formatVersion)
        scramble()
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertEquals(before, dump())
        // 相同 id、不同创建时间：导入联系人换 id，记忆模型依然跟随映射后的服务商。
        db.contactDao().update(db.contactDao().get(2)!!.copy(createdAt = 123456, memoryModel = null, memoryThinkingOption = "off"))
        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        val added = db.contactDao().listAll().single { it.name == "阿哲" && it.id != 2L }
        assertEquals("local", added.memoryModel)
        assertEquals("native", added.memoryThinkingOption)
        assertEquals(model, db.providerDao().model(added.providerId, "local"))
    }

    @Test fun pathCaseAndProtocolDifferencesNeverReuseLocalKeys() = runBlocking {
        seed()
        db.providerDao().update(deepSeek().copy(baseUrl = "https://example.com/A/v1"))
        val zip = export(password = null)
        db.providerDao().update(deepSeek().copy(baseUrl = "https://example.com/a/v1", apiKeyEncrypted = cipher.encrypt("local-secret")))
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals(null, deepSeek().apiKeyEncrypted)
        db.providerDao().update(deepSeek().copy(provider = "different-protocol", apiKeyEncrypted = cipher.encrypt("local-secret")))
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals(null, deepSeek().apiKeyEncrypted)
    }

    // ---------- 合并 ----------

    @Test
    fun merge_keepsLocalData_addsOnlyMissing_andDoesNotMixSameIdContacts() = runBlocking {
        seed()
        val zip = export(password = null)

        // 本机：改了联系人 1 的名字、给它多发了一条消息；联系人 2 被删掉，
        // 然后新建了一个联系人恰好也拿到 id 2（创建时间不同，是另一个人）
        db.contactDao().update(db.contactDao().get(1)!!.copy(name = "小雨（本机改名）"))
        db.messageDao().insert(text(contactId = 1, content = "本机新消息", createdAt = 8_000))
        db.contactDao().delete(2)
        db.contactDao().insert(contact(id = 2, name = "另一个人", createdAt = 77_777))
        db.conversationDao().upsert(Conversation(contactId = 2))
        val localSettings = settings.value

        val summary = backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)

        val contacts = db.contactDao().listAll()
        assertEquals(3, contacts.size)
        assertEquals("小雨（本机改名）", contacts.first { it.id == 1L }.name)
        assertEquals("另一个人", contacts.first { it.id == 2L }.name)
        val readded = contacts.first { it.name == "阿哲" }
        assertTrue("同 id 不同人的联系人要换新 id", readded.id > 2)
        assertEquals(1, summary.contactsAdded)

        // 联系人 1 的旧消息已经有了，不重复；本机新消息保留
        val messages1 = db.messageDao().listByContact(1)
        assertEquals(listOf("你好", "嗯嗯", "[图]", "本机新消息"), messages1.map { if (it.type == MessageType.IMAGE) "[图]" else it.content })
        // 阿哲的消息跟着新 id 走，不会混进「另一个人」
        assertEquals(listOf("在吗"), db.messageDao().listByContact(readded.id).map { it.content })
        assertTrue(db.messageDao().listByContact(2).isEmpty())
        assertEquals(1, summary.messagesAdded)
        // 设置保留本机的
        assertEquals(localSettings, settings.value)
    }

    @Test
    fun merge_isIdempotent() = runBlocking {
        seed()
        val zip = export(password = null)
        val before = dump()
        val summary = backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        assertEquals(0, summary.contactsAdded)
        assertEquals(0, summary.messagesAdded)
        assertEquals(before, dump())
    }

    // ---------- 0.5.0 全局记忆、联系人新开关、回复偏好 ----------

    @Test
    fun export_includesGlobalMemorySwitchesAndReplyPreferences() = runBlocking {
        seed()
        val data = backup.inspect(export(password = null)).data
        assertEquals(listOf("个人信息", "工作"), data.globalCategories.map { it.name })
        assertEquals(3, data.globalTopics.size)
        assertEquals(mapOf("住在杭州" to true, "生日 3 月 5 日" to false, "不吃香菜" to true, "做后端开发" to true), data.globalItems.associate { it.content to it.enabled })
        assertEquals(listOf(true to true, false to false), data.contacts.map { it.useGlobalMemory to it.useReplyPreferences })
        assertEquals(ReplyPreferencesDto(5, 20, 2, 4, false, 2, 6), data.settings.replyPreferences)
    }

    @Test
    fun merge_globalMemory_addsMissing_keepsLocal_andKeepsLocalReplyPreferences() = runBlocking {
        seed()
        val zip = export(password = null)
        val dao = db.globalMemoryDao()
        // 本机：改了类别名、删了一条、在同一个主题下加了一条、又新建了一个类别；回复偏好也改过
        val personal = dao.categories().first { it.name == "个人信息" }
        dao.updateCategories(listOf(personal.copy(name = "关于我")))
        val basics = dao.topics().first { it.name == "基本情况" }
        dao.deleteItem(dao.items().first { it.content == "住在杭州" }.id)
        dao.insertItem(GlobalMemoryItem(topicId = basics.id, content = "本机新加的", sortOrder = 5, createdAt = 500, updatedAt = 500))
        dao.insertCategory(GlobalMemoryCategory(name = "本机类别", sortOrder = 2, createdAt = 600, updatedAt = 600))
        val localPrefs = ReplyPreferences(waitSeconds = 1)
        settings.value = settings.value.copy(replyPreferences = localPrefs)

        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)

        // 同一个类别（创建时间相同）保留本机的名字，不重复
        assertEquals(listOf("关于我", "工作", "本机类别"), dao.categories().map { it.name })
        assertEquals(3, dao.topics().size)
        // 本机删掉的那条从备份补回来，接在本机条目后面；其他条目不重复
        assertEquals(listOf("生日 3 月 5 日", "本机新加的", "住在杭州"), dao.itemsOf(basics.id).map { it.content })
        assertEquals(5, dao.items().size)
        assertEquals(localPrefs, settings.value.replyPreferences)

        // 再合并一次不会变
        val again = dao.items()
        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        assertEquals(again, dao.items())
        assertEquals(3, dao.categories().size)
    }

    @Test
    fun merge_fromAnotherPhone_addsSameNamedCategoryAsNew_andRemapsTopicsAndItems() = runBlocking {
        seed()
        val zip = export(password = null)
        // 「另一台手机」：清空全局记忆后自己建了同名类别（创建时间不同），id 正好和备份撞上
        val dao = db.globalMemoryDao()
        dao.deleteAllItems(); dao.deleteAllTopics(); dao.deleteAllCategories()
        val mine = dao.insertCategory(GlobalMemoryCategory(id = 1, name = "个人信息", createdAt = 7_777, updatedAt = 7_777))
        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        val categories = dao.categories()
        assertEquals(listOf("个人信息", "个人信息", "工作"), categories.map { it.name })
        assertEquals(mine, categories.first().id)
        assertTrue(dao.topicsOf(mine).isEmpty())
        // 备份的主题挂到新建的类别下，条目挂到新主题下
        val restored = categories[1]
        assertEquals(listOf("基本情况", "饮食"), dao.topicsOf(restored.id).map { it.name })
        assertEquals(4, dao.items().size)
        assertTrue(dao.items().all { item -> dao.topic(item.topicId) != null })
    }

    @Test
    fun overwrite_replacesGlobalMemory_andOldBackupWithoutItClearsLocal() = runBlocking {
        seed()
        val zip = export(password = null)
        db.globalMemoryDao().insertCategory(GlobalMemoryCategory(name = "后来加的", createdAt = 8_000, updatedAt = 8_000))
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals(listOf("个人信息", "工作"), db.globalMemoryDao().categories().map { it.name })

        // v4 备份没有全局记忆和回复偏好：覆盖时本机全局记忆随其他数据一起清掉，回复偏好保留本机的
        val prefs = settings.value.replyPreferences
        val v4 = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.4.2","appVersionCode":402,"formatVersion":4,"exportedAt":1}""",
            BackupFormat.DATA to """{"contacts":[{"id":1,"name":"小雨","avatarColor":0,"providerId":1,"model":"m","temperature":1.3,"createdAt":1,"updatedAt":1}]}""",
        )
        backup.restore(backup.inspect(v4), RestoreMode.OVERWRITE, null)
        assertTrue(db.globalMemoryDao().categories().isEmpty())
        assertEquals(prefs, settings.value.replyPreferences)
    }

    @Test
    fun overwrite_userChoosesToKeepLocalGlobalMemory_forNewAndOldBackups() = runBlocking {
        seed()
        val zip = export(password = null)
        val dao = db.globalMemoryDao()
        dao.insertCategory(GlobalMemoryCategory(name = "后来加的", sortOrder = 2, createdAt = 8_000, updatedAt = 8_000))
        val local = Triple(dao.categories(), dao.topics(), dao.items())
        // 页面用这些数量决定要不要问、怎么问
        assertEquals(GlobalMemoryCount(3, 4), backup.localGlobalMemory())
        val preview = backup.inspect(zip)
        assertEquals(GlobalMemoryCount(2, 4), preview.globalMemory)

        // 备份里有全局记忆，用户选保留本机的：本机三张表原样不动，其他数据照常换成备份里的
        db.contactDao().insert(Contact(name = "本机新加", avatarColor = 0, providerId = 1, model = "m", createdAt = 9_000, updatedAt = 9_000))
        backup.restore(preview, RestoreMode.OVERWRITE, null, keepGlobalMemory = true)
        assertEquals(local, Triple(dao.categories(), dao.topics(), dao.items()))
        assertTrue(db.contactDao().listAll().none { it.name == "本机新加" })

        // 0.4.2 的备份没有全局记忆：选保留时也不会被清空
        val v4 = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.4.2","appVersionCode":402,"formatVersion":4,"exportedAt":1}""",
            BackupFormat.DATA to """{"contacts":[{"id":1,"name":"小雨","avatarColor":0,"providerId":1,"model":"m","temperature":1.3,"createdAt":1,"updatedAt":1}]}""",
        )
        val old = backup.inspect(v4)
        assertTrue(old.globalMemory.isEmpty)
        backup.restore(old, RestoreMode.OVERWRITE, null, keepGlobalMemory = true)
        assertEquals(local, Triple(dao.categories(), dao.topics(), dao.items()))
        assertEquals(listOf("小雨"), db.contactDao().listAll().map { it.name })
    }

    @Test
    fun v4Backup_contactsDefaultToUsingGlobalMemoryAndReplyPreferences_splitRepliesKept() = runBlocking {
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.4.2","appVersionCode":402,"formatVersion":4,"exportedAt":1}""",
            BackupFormat.DATA to """
                {"contacts":[{"id":1,"name":"分条","avatarColor":0,"providerId":1,"model":"m","temperature":1.3,"splitReplies":true,"createdAt":1,"updatedAt":1},
                             {"id":2,"name":"不分条","avatarColor":0,"providerId":1,"model":"m","temperature":1.3,"splitReplies":false,"createdAt":2,"updatedAt":2}],
                 "conversations":[{"contactId":1,"muted":true}]}
            """.trimIndent(),
        )
        for (mode in RestoreMode.entries) {
            db.contactDao().deleteAll()
            backup.restore(backup.inspect(zip), mode, null)
            val contacts = db.contactDao().listAll()
            assertEquals(mode.name, listOf(true, false), contacts.map { it.splitReplies })
            assertTrue(mode.name, contacts.all { it.useGlobalMemory && it.useReplyPreferences })
            assertTrue(mode.name, db.conversationDao().get(1)!!.muted)
        }
    }

    // ---------- 0.6.0 主动聊天 ----------

    private val customProactive = ProactiveSettings(quietEnabled = false, quietStartMinutes = 22 * 60, quietEndMinutes = 8 * 60 + 30, dailyLimit = 5)

    /** 本机开了主动聊天、发过主动消息、有暂停计数、今日额度和约定提醒 */
    private suspend fun seedProactive() {
        seed()
        db.contactDao().update(db.contactDao().get(1)!!.copy(proactiveEnabled = true, proactiveFrequency = ProactiveFrequency.HIGH))
        db.messageDao().insert(
            text(contactId = 1, content = "在忙吗", createdAt = 5_500).copy(role = Role.ASSISTANT, replyGroup = "p1", origin = MessageOrigin.PROACTIVE),
        )
        db.conversationDao().addProactiveSent(1, 5_500)
        db.conversationDao().addProactiveSent(1, 5_600)
        db.proactiveDao().saveState(ProactiveState(day = 20_000, used = 4, lastRunAt = 5_600))
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = 1, dueAt = 99_000, note = "叫他起床", createdAt = 5_000))
        db.usageDao().add(20_000, 1, UsagePurpose.PROACTIVE, 30, 3)
        settings.value = settings.value.copy(proactive = customProactive)
    }

    @Test
    fun proactive_exportCarriesSwitchFrequencyOriginAndSettings_butNoDeviceState() = runBlocking {
        seedProactive()
        val zip = export(password = null)
        val data = backup.inspect(zip).data
        assertEquals(listOf(true to ProactiveFrequency.HIGH, false to ProactiveFrequency.MEDIUM), data.contacts.map { it.proactiveEnabled to it.proactiveFrequency })
        assertEquals(MessageOrigin.PROACTIVE, data.messages.single { it.content == "在忙吗" }.origin)
        assertTrue(data.messages.filter { it.content != "在忙吗" }.all { it.origin == MessageOrigin.NORMAL })
        assertEquals(ProactiveSettingsDto(false, 22 * 60, 8 * 60 + 30, 5), data.settings.proactive)
        assertTrue(data.usage.any { it.purpose == UsagePurpose.PROACTIVE && it.promptTokens == 30L })
        val raw = ZipFile(zip).use { z -> z.getInputStream(z.getEntry(BackupFormat.DATA)).readBytes().toString(Charsets.UTF_8) }
        for (deviceOnly in listOf("proactiveUnanswered", "lastProactiveAt", "叫他起床", "lastRunAt", "dueAt")) {
            assertFalse("设备状态不该出现在导出文件里：$deviceOnly", raw.contains(deviceOnly))
        }
    }

    @Test
    fun proactive_overwrite_restoresSwitchAndSettings_resetsPauseAndReminders_keepsTodaysQuota() = runBlocking {
        seedProactive()
        val zip = export(password = null)
        // 导出之后本机又变了：关掉开关、改了设置、今天用掉更多、又约了一个提醒
        db.contactDao().update(db.contactDao().get(1)!!.copy(proactiveEnabled = false, proactiveFrequency = ProactiveFrequency.LOW))
        settings.value = settings.value.copy(proactive = ProactiveSettings())
        db.proactiveDao().saveState(ProactiveState(day = 20_001, used = 9, lastRunAt = 8_000))
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = 2, dueAt = 77_000, note = "本机后来约的", createdAt = 7_000))

        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)

        val contact = db.contactDao().get(1)!!
        assertTrue(contact.proactiveEnabled)
        assertEquals(ProactiveFrequency.HIGH, contact.proactiveFrequency)
        assertFalse(db.contactDao().get(2)!!.proactiveEnabled)
        assertEquals(MessageOrigin.PROACTIVE, db.messageDao().listByContact(1).single { it.content == "在忙吗" }.origin)
        assertEquals(customProactive, settings.value.proactive)
        // 暂停计数和最近主动时间从 0 开始；约定提醒不随备份，本机的也清掉（聊天已被覆盖）
        db.conversationDao().get(1)!!.let { assertEquals(0, it.proactiveUnanswered); assertEquals(0L, it.lastProactiveAt) }
        assertEquals(0, db.proactiveDao().reminderCount())
        // 今日已用是这台手机的状态，不被备份改回去
        assertEquals(ProactiveState(day = 20_001, used = 9, lastRunAt = 8_000), db.proactiveDao().state())
    }

    @Test
    fun proactive_merge_keepsLocalSwitchPauseQuotaRemindersAndSettings() = runBlocking {
        seedProactive()
        val zip = export(password = null)
        db.contactDao().update(db.contactDao().get(1)!!.copy(proactiveEnabled = false))
        settings.value = settings.value.copy(proactive = ProactiveSettings(dailyLimit = 3))
        val state = db.proactiveDao().state()

        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)

        assertFalse("同一个联系人保留本机的开关", db.contactDao().get(1)!!.proactiveEnabled)
        assertEquals(ProactiveSettings(dailyLimit = 3), settings.value.proactive)
        assertEquals(2, db.conversationDao().get(1)!!.proactiveUnanswered)
        assertEquals(state, db.proactiveDao().state())
        assertEquals("叫他起床", db.proactiveDao().remindersOf(1).single().note)
        assertEquals(1, db.messageDao().listByContact(1).count { it.origin == MessageOrigin.PROACTIVE })
    }

    /** 0.5.0 及更早的备份：即使文件里写着开启，迁移后也是关闭，消息都是普通消息，主动聊天设置保留本机的 */
    @Test
    fun v5Backup_proactiveStaysOff_evenIfTheFileClaimsOtherwise() = runBlocking {
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.5.0","appVersionCode":500,"formatVersion":5,"exportedAt":1}""",
            BackupFormat.DATA to """
                {"contacts":[{"id":1,"name":"旧","avatarColor":0,"providerId":1,"model":"m","temperature":1.3,"createdAt":1,"updatedAt":1,
                              "proactiveEnabled":true,"proactiveFrequency":"HIGH"}],
                 "conversations":[{"contactId":1}],
                 "messages":[{"id":1,"contactId":1,"role":"ASSISTANT","type":"TEXT","content":"hi","status":"SUCCESS","createdAt":5,"origin":"PROACTIVE"}],
                 "settings":{"nickname":"n","proactive":{"quietEnabled":false,"dailyLimit":200}}}
            """.trimIndent(),
        )
        settings.value = settings.value.copy(proactive = customProactive)
        for (mode in RestoreMode.entries) {
            db.messageDao().deleteAll()
            db.contactDao().deleteAll()
            backup.restore(backup.inspect(zip), mode, null)
            val contact = db.contactDao().listAll().single()
            assertFalse(mode.name, contact.proactiveEnabled)
            assertEquals(mode.name, ProactiveFrequency.MEDIUM, contact.proactiveFrequency)
            assertEquals(mode.name, 0, db.contactDao().proactiveCount())
            assertEquals(mode.name, MessageOrigin.NORMAL, db.messageDao().listAll().single().origin)
            assertEquals(mode.name, customProactive, settings.value.proactive)
        }
    }

    /** 恢复后开启主动聊天的联系人可能变了：按恢复后的数据重新注册或取消后台任务 */
    @Test
    fun restore_resyncsProactiveBackgroundTasks_afterBothModes() = runBlocking {
        var syncs = 0
        val control = object : isomeria.aichat.data.reply.ProactiveControl {
            override suspend fun sync() {
                syncs++
            }
        }
        val withControl = BackupManager(context, db, files, cipher, settings, replies, control)
        seedProactive()
        val zip = export(password = null)
        assertEquals("导出不动后台任务", 0, syncs)
        withControl.restore(withControl.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals(1, syncs)
        withControl.restore(withControl.inspect(export(password = null)), RestoreMode.MERGE, null)
        assertEquals(2, syncs)
    }

    @Test
    fun crafted_proactiveSettings_areNormalized() = runBlocking {
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.6.0","appVersionCode":600,"formatVersion":6,"exportedAt":1}""",
            BackupFormat.DATA to """{"settings":{"proactive":{"quietEnabled":true,"quietStartMinutes":-45,"quietEndMinutes":5000,"dailyLimit":0}}}""",
        )
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals(ProactiveSettings(true, 23 * 60, 11 * 60, 1), settings.value.proactive)
    }

    @Test
    fun crafted_globalMemory_orphansAndOverlongValuesAreCleaned() = runBlocking {
        val long = "长".repeat(GlobalMemoryItem.CONTENT_MAX_LENGTH + 10)
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.5.0","appVersionCode":500,"formatVersion":5,"exportedAt":1}""",
            BackupFormat.DATA to """
                {"globalCategories":[{"id":1,"name":"${"类".repeat(30)}","createdAt":1,"updatedAt":1}],
                 "globalTopics":[{"id":1,"categoryId":1,"name":"主题","createdAt":1,"updatedAt":1},
                                 {"id":2,"categoryId":99,"name":"孤立主题","createdAt":1,"updatedAt":1}],
                 "globalItems":[{"id":1,"topicId":1,"content":"$long","createdAt":1,"updatedAt":1},
                                {"id":2,"topicId":2,"content":"挂在孤立主题下","createdAt":1,"updatedAt":1},
                                {"id":3,"topicId":1,"content":"   ","createdAt":1,"updatedAt":1}],
                 "settings":{"replyPreferences":{"minCount":8,"maxCount":2,"waitSeconds":-5}}}
            """.trimIndent(),
        )
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        val dao = db.globalMemoryDao()
        assertEquals(GlobalMemoryCategory.NAME_MAX_LENGTH, dao.categories().single().name.length)
        assertEquals(listOf("主题"), dao.topics().map { it.name })
        assertEquals(GlobalMemoryItem.CONTENT_MAX_LENGTH, dao.items().single().content.length)
        val prefs = settings.value.replyPreferences
        assertEquals(0, prefs.waitSeconds)
        assertEquals(8, prefs.minCount)
        assertEquals("最多不少于最少", 8, prefs.maxCount)
    }

    // ---------- 多服务商、记忆、用量 ----------

    @Test
    fun export_withPassword_encryptsEveryProviderKey_andNoPlainKeyInFile() = runBlocking {
        seed()
        val zip = export(password = "pw123456")
        val preview = backup.inspect(zip)
        assertTrue(preview.manifest.includesApiKey)
        val secrets = preview.data.providers.map { it.apiKey }
        assertEquals(2, secrets.size)
        assertTrue(secrets.all { it?.scheme == SecretDto.SCHEME_PASSWORD })
        ZipFile(zip).use { z ->
            val data = z.getInputStream(z.getEntry(BackupFormat.DATA)).readBytes().toString(Charsets.UTF_8)
            assertFalse(data.contains("sk-real-key"))
            assertFalse(data.contains("sk-second"))
        }
    }

    @Test
    fun overwrite_restoresBothProviderKeys_andContactBindings() = runBlocking {
        seed()
        val zip = export(password = "pw123456")
        scramble()
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, "pw123456".toCharArray())

        assertEquals("sk-second", cipher.decrypt(db.providerDao().get(secondProviderId)!!.apiKeyEncrypted!!))
        assertEquals(secondProviderId, db.contactDao().get(2)!!.providerId)
        assertEquals("第二家", db.providerDao().get(secondProviderId)!!.name)
        val model = db.providerDao().model(secondProviderId, "vision")!!
        assertEquals(true, model.thinkingUserMark)
        assertTrue(db.providerDao().model(secondProviderId, "local")!!.manual)
    }

    @Test
    fun overwrite_keepsLocalKeyOnlyForSameProvider_notWhenBackupChangesBaseUrl() = runBlocking {
        seed()
        val zip = export(password = null)
        // 本机同一家（Base URL 没变）保留本机的 Key
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals("sk-real-key", cipher.decrypt(deepSeek().apiKeyEncrypted!!))

        // 本机的 Base URL 和备份不一样：不是同一家，本机的 Key 不能发到备份里的地址
        db.providerDao().update(deepSeek().copy(baseUrl = "https://my-own-proxy.example.com"))
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        val restored = deepSeek()
        assertEquals("https://api.deepseek.com/v1", restored.baseUrl)
        assertEquals(null, restored.apiKeyEncrypted)
    }

    @Test
    fun merge_mapsProvidersByProtocolAndUrl_addsNew_fillsMissingKey() = runBlocking {
        seed()
        val zip = export(password = "pw123456")

        // 另一台手机：只有自己的 DeepSeek（同一家，但没有 Key）和一个 id 恰好相同的别的服务商
        db.messageDao().deleteAll()
        db.memoryDao().deleteAll()
        db.usageDao().deleteAll()
        db.conversationDao().deleteAll()
        db.contactDao().deleteAll()
        db.providerDao().deleteModels(secondProviderId)
        db.providerDao().delete(secondProviderId)
        db.providerDao().update(deepSeek().copy(apiKeyEncrypted = null, defaultModel = "本机默认"))
        val localOther = db.providerDao().insert(
            ProviderConfig(
                kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "本机另一家",
                baseUrl = "https://other.example.com/v1", apiKeyEncrypted = cipher.encrypt("sk-local-other"), defaultModel = "x",
            ),
        )

        val summary = backup.restore(backup.inspect(zip), RestoreMode.MERGE, "pw123456".toCharArray())
        assertTrue(summary.apiKeyRestored)

        val providers = db.providerDao().llmList()
        assertEquals(3, providers.size)
        // 同一家：保留本机配置，只补 Key
        assertEquals("本机默认", deepSeek().defaultModel)
        assertEquals("sk-real-key", cipher.decrypt(deepSeek().apiKeyEncrypted!!))
        // 本机另一家不动
        assertEquals("sk-local-other", cipher.decrypt(db.providerDao().get(localOther)!!.apiKeyEncrypted!!))
        // 备份里的第二家作为新服务商加入，联系人跟着它走
        val added = providers.first { it.name == "第二家" }
        assertEquals("sk-second", cipher.decrypt(added.apiKeyEncrypted!!))
        assertTrue(added.id != localOther)
        val azhe = db.contactDao().listAll().first { it.name == "阿哲" }
        assertEquals(added.id, azhe.providerId)
        assertEquals(setOf("vision", "local"), db.providerDao().models(added.id).map { it.modelId }.toSet())
        assertEquals(1L, db.contactDao().listAll().first { it.name == "小雨" }.providerId)
    }

    @Test
    fun merge_doesNotOverwriteLocalKey_withBackupKey() = runBlocking {
        seed()
        val zip = export(password = "pw123456")
        db.providerDao().update(deepSeek().copy(apiKeyEncrypted = cipher.encrypt("sk-mine")))
        val summary = backup.restore(backup.inspect(zip), RestoreMode.MERGE, "pw123456".toCharArray())
        assertFalse(summary.apiKeyRestored)
        assertEquals("sk-mine", cipher.decrypt(deepSeek().apiKeyEncrypted!!))
    }

    @Test
    fun merge_memoriesAndUsage_followContacts_localWins() = runBlocking {
        seed()
        val zip = export(password = null)
        // 本机：联系人 2 被删掉后，新建的联系人恰好也拿到 id 2；联系人 1 同一天的用量变了，多了一条记忆
        db.contactDao().delete(2)
        db.contactDao().insert(contact(id = 2, name = "另一个人", createdAt = 77_777))
        db.conversationDao().upsert(Conversation(contactId = 2))
        db.usageDao().deleteByContact(2)
        db.usageDao().add(20_000, 1, UsagePurpose.CHAT, 1, 1)
        db.memoryDao().insert(Memory(contactId = 1, content = "本机的记忆", fromTime = 9_000, toTime = 9_100, createdAt = 9_200, updatedAt = 9_200))

        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)

        // 记忆：同一条不重复，本机的保留
        assertEquals(listOf("喜欢拿铁", "本机的记忆"), db.memoryDao().listByContact(1).map { it.content })
        // 用量：本机已有的那一天保留本机的（累加之后的数），其他的加入；阿哲的跟着新 id 走
        val usage = db.usageDao().listAll()
        val readded = db.contactDao().listAll().first { it.name == "阿哲" }
        assertEquals(101L, usage.first { it.day == 20_000L && it.contactId == 1L && it.purpose == UsagePurpose.CHAT }.promptTokens)
        assertEquals(50L, usage.first { it.purpose == UsagePurpose.MEMORY }.promptTokens)
        assertEquals(70L, usage.first { it.contactId == readded.id }.promptTokens)
        assertTrue("不会算到「另一个人」头上", usage.none { it.contactId == 2L })
        assertEquals(9L, usage.first { it.contactId == UsageDaily.DELETED_CONTACT }.promptTokens)
    }

    @Test
    fun overwrite_contactWithMissingProvider_fallsBackToFirst() = runBlocking {
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"1","appVersionCode":1,"formatVersion":${BackupFormat.VERSION},"exportedAt":1}""",
            BackupFormat.DATA to """
                {"contacts":[{"id":5,"name":"甲","avatarColor":0,"providerId":77,"model":"m","temperature":1.0,"createdAt":1,"updatedAt":1}],
                 "providers":[{"id":3,"kind":"LLM","provider":"openai","name":"乙","baseUrl":"https://b.example.com/v1","defaultModel":"m"}],
                 "memories":[{"id":1,"contactId":404,"content":"找不到联系人","fromTime":1,"toTime":1,"createdAt":1,"updatedAt":1},
                             {"id":2,"contactId":5,"content":"  ","fromTime":1,"toTime":1,"createdAt":1,"updatedAt":1}],
                 "usage":[{"day":1,"contactId":404,"promptTokens":1},{"day":1,"contactId":5,"promptTokens":-5}]}
            """.trimIndent(),
        )
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals(3L, db.contactDao().get(5)!!.providerId)
        // 新恢复的联系人没有会话时也要建一个；记忆起点留空
        assertEquals(null, db.conversationDao().get(5)!!.memoryCursor)
        // 找不到联系人的、空的记忆和用量都去掉，负数归零
        assertTrue(db.memoryDao().listAll().isEmpty())
        assertEquals(listOf(UsageDaily(1, 5, UsagePurpose.CHAT, 0, 0, 0)), db.usageDao().listAll())
    }

    @Test
    fun v1Backup_restoresIntoCurrentFormat() = runBlocking {
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.2.0","appVersionCode":200,"formatVersion":1,"exportedAt":1}""",
            BackupFormat.DATA to """
                {"contacts":[{"id":1,"name":"小雨","avatarColor":0,"providerId":1,"model":"deepseek-flash","temperature":1.3,
                              "contextCount":50,"createdAt":1,"updatedAt":1}],
                 "conversations":[{"contactId":1,"pinned":true}],
                 "messages":[{"id":1,"contactId":1,"role":"ASSISTANT","type":"TEXT","content":"你好","status":"SUCCESS",
                              "promptTokens":8,"completionTokens":2,"createdAt":1793448000000}],
                 "providers":[{"id":1,"kind":"LLM","provider":"deepseek","baseUrl":"https://api.deepseek.com","defaultModel":"deepseek-flash",
                               "models":[{"modelId":"deepseek-flash","displayName":"Flash","imageInput":"YES"}]}]}
            """.trimIndent(),
        )
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals(10_000, db.contactDao().get(1)!!.contextTokens)
        assertEquals(null, db.conversationDao().get(1)!!.memoryCursor)
        assertEquals("DeepSeek", deepSeek().name)
        assertTrue(db.providerDao().model(1, "deepseek-flash")!!.supportsThinking)
        assertEquals(8L, db.usageDao().listAll().single().promptTokens)
    }

    @Test
    fun v3Backup_restoresWithDefaultMuteAndBackground() = runBlocking {
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.4.1","appVersionCode":401,"formatVersion":3,"exportedAt":1}""",
            BackupFormat.DATA to """
                {"contacts":[{"id":1,"name":"小雨","avatarColor":0,"providerId":1,"model":"deepseek-flash","temperature":1.3,
                              "createdAt":1,"updatedAt":1}],
                 "conversations":[{"contactId":1,"pinned":true,"draft":"草稿"}],
                 "providers":[{"id":1,"kind":"LLM","provider":"openai","baseUrl":"https://api.deepseek.com","defaultModel":"",
                               "compatibility":"DEEPSEEK","presetId":"deepseek"}]}
            """.trimIndent(),
        )
        val preview = backup.inspect(zip)
        assertEquals(3, preview.manifest.formatVersion)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        val conversation = db.conversationDao().get(1)!!
        assertFalse(conversation.muted)
        assertEquals(null, conversation.backgroundPath)
        assertTrue(conversation.pinned)
        assertEquals("草稿", conversation.draft)
    }

    @Test
    fun background_overwriteRemapsPath_andRemovesReplacedLocalBackground() = runBlocking {
        seed()
        val zip = export(password = null)
        val exported = db.conversationDao().get(1)!!.backgroundPath!!
        // 导出后本机换了背景：旧文件已删，新文件只被本机引用
        val replaced = files.newMediaFile(MediaKind.BACKGROUND).apply { writeBytes(byteArrayOf(1)) }
        File(exported).delete()
        db.conversationDao().setBackground(1, replaced.absolutePath)

        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)

        val restored = db.conversationDao().get(1)!!
        assertTrue(restored.muted)
        val path = restored.backgroundPath!!
        assertNotEquals(exported, path)
        assertTrue(File(path).canonicalPath.startsWith(File(context.filesDir, "background").canonicalPath))
        assertEquals(listOf<Byte>(7, 7, 7), File(path).readBytes().toList())
        assertFalse("恢复后不再引用的本机背景会被清理", replaced.exists())
        assertEquals(referencedFiles(), mediaFiles())
    }

    @Test
    fun background_mergeKeepsLocalConversation_andMapsBackgroundForNewContact() = runBlocking {
        seed()
        val zip = export(password = null)
        // 本机：联系人 1 关掉免打扰、去掉背景；删掉后重建一个同 id 的另一个人，备份里的联系人 1 会换新 id
        db.conversationDao().setMuted(1, false)
        val old = db.conversationDao().get(1)!!.backgroundPath!!
        db.conversationDao().setBackground(1, null)
        File(old).delete()
        db.messageDao().deleteByContact(1)
        db.contactDao().delete(1)
        db.contactDao().insert(contact(id = 1, name = "另一个人", createdAt = 99_999))
        db.conversationDao().upsert(Conversation(contactId = 1))

        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)

        val local = db.conversationDao().get(1)!!
        assertFalse(local.muted)
        assertEquals(null, local.backgroundPath)
        val readded = db.contactDao().listAll().first { it.name == "小雨" }
        val conversation = db.conversationDao().get(readded.id)!!
        assertTrue(conversation.muted)
        assertEquals(listOf<Byte>(7, 7, 7), File(conversation.backgroundPath!!).readBytes().toList())
        assertEquals(referencedFiles(), mediaFiles())
    }

    @Test
    fun background_mergeIntoExistingConversation_keepsLocalBackground_andDropsUnusedFile() = runBlocking {
        seed()
        val zip = export(password = null)
        val local = files.newMediaFile(MediaKind.BACKGROUND).apply { writeBytes(byteArrayOf(5)) }
        val exported = db.conversationDao().get(1)!!.backgroundPath!!
        db.conversationDao().setBackground(1, local.absolutePath)
        File(exported).delete()

        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)

        assertEquals(local.absolutePath, db.conversationDao().get(1)!!.backgroundPath)
        assertTrue(local.exists())
        assertEquals("备份里多出来的背景没人用，恢复后清掉", referencedFiles(), mediaFiles())
    }

    @Test
    fun background_nameOutsideBackgroundDir_isIgnored() = runBlocking {
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.4.2","appVersionCode":402,"formatVersion":4,"exportedAt":1}""",
            BackupFormat.DATA to """
                {"contacts":[{"id":1,"name":"小雨","avatarColor":0,"providerId":1,"model":"m","temperature":1.3,"createdAt":1,"updatedAt":1}],
                 "conversations":[{"contactId":1,"muted":true,"background":"images/x.jpg"}]}
            """.trimIndent(),
            "images/x.jpg" to "x",
        )
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        val conversation = db.conversationDao().get(1)!!
        assertTrue(conversation.muted)
        assertEquals(null, conversation.backgroundPath)
        assertTrue(mediaFiles().isEmpty())
    }

    @Test
    fun background_missingFileInZip_restoresWithoutBackground() = runBlocking {
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"0.4.2","appVersionCode":402,"formatVersion":4,"exportedAt":1}""",
            BackupFormat.DATA to """
                {"contacts":[{"id":1,"name":"小雨","avatarColor":0,"providerId":1,"model":"m","temperature":1.3,"createdAt":1,"updatedAt":1}],
                 "conversations":[{"contactId":1,"background":"background/gone.jpg"}]}
            """.trimIndent(),
        )
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals(null, db.conversationDao().get(1)!!.backgroundPath)
    }

    // ---------- 拒绝与回滚 ----------

    @Test
    fun newerFormat_rejected() = runBlocking {
        val zip = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"9.0.0","appVersionCode":90000,"formatVersion":${BackupFormat.VERSION + 1},"exportedAt":1}""",
            BackupFormat.DATA to "{}",
        )
        try {
            backup.inspect(zip)
            fail("更新版本的格式应该被拒绝")
        } catch (e: BackupTooNewException) {
            assertEquals(BackupFormat.VERSION + 1, e.formatVersion)
        }
    }

    @Test
    fun invalidFiles_rejected() = runBlocking {
        val notZip = File(context.cacheDir, "not.zip").apply { writeText("hello") }
        val noManifest = writeZip(BackupFormat.DATA to "{}")
        val otherApp = writeZip(
            BackupFormat.MANIFEST to """{"app":"other.app","appVersion":"1","appVersionCode":1,"formatVersion":1,"exportedAt":1}""",
            BackupFormat.DATA to "{}",
        )
        val brokenData = writeZip(
            BackupFormat.MANIFEST to """{"app":"isomeria.aichat","appVersion":"1","appVersionCode":1,"formatVersion":1,"exportedAt":1}""",
            BackupFormat.DATA to """{"contacts":[{"id":"x"}]}""",
        )
        for (file in listOf(notZip, noManifest, otherApp, brokenData)) {
            try {
                backup.inspect(file)
                fail("${file.name} 应该被拒绝")
            } catch (_: InvalidBackupException) {
            }
        }
    }

    @Test
    fun failureAfterDatabaseCommit_rollsBackFromAutomaticBackup() = runBlocking {
        seed()
        val zip = export(password = null)
        scramble()
        val scrambled = dump()

        settings.failNextWrite = true
        try {
            backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
            fail("写设置失败应该抛出异常")
        } catch (_: IllegalStateException) {
        }
        assertEquals(scrambled, dump())
        assertEquals(referencedFiles(), mediaFiles())
    }

    private suspend fun encryptedExport(includeKeys: Boolean = true): File {
        val output = ByteArrayOutputStream()
        val result = backup.export(output, "pw123456".toCharArray(), includeKeys)
        assertTrue(result.encrypted)
        return File(context.cacheDir, "encrypted-${System.nanoTime()}.aichat").apply { writeBytes(output.toByteArray()) }
    }

    @Test fun encryptedContainerRoundTripRemovesSnapshotAndPlaintextTemporary() = runBlocking {
        seed()
        val expected = dump()
        val source = encryptedExport()
        assertTrue(BackupContainer.isEncrypted(source))
        try { ZipFile(source).close(); fail("Encrypted export cannot be opened as zip") }
        catch (_: java.util.zip.ZipException) { }
        scramble()
        val preview = backup.inspect(source, "pw123456".toCharArray())
        assertTrue(preview.decryptedTemporary)
        assertTrue(preview.file.canonicalPath.startsWith(files.restoreCacheDir.canonicalPath + File.separator))
        assertTrue(preview.file.isFile)
        assertEquals(7, preview.manifest.formatVersion)
        val result = backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertEquals(expected, dump())
        assertFalse(result.cleanupPending)
        assertFalse(preview.file.exists())
        assertFalse(backup.snapshotFile.exists())
        assertTrue(source.exists())
    }

    @Test fun wholeFileCanBeEncryptedWithoutIncludingApiKeys() = runBlocking {
        seed()
        val source = encryptedExport(includeKeys = false)
        val preview = backup.inspect(source, "pw123456".toCharArray())
        assertFalse(preview.manifest.includesApiKey)
        assertTrue(preview.data.providers.all { it.apiKey == null })
        assertTrue(backup.discardPreview(preview))
        assertFalse(preview.file.exists())
    }

    @Test fun wrongOuterPasswordAndMissingPasswordNeverChangeDataOrCreateSnapshot() = runBlocking {
        seed()
        val source = encryptedExport()
        val expected = dump()
        try { backup.inspect(source); fail("Password required") }
        catch (_: EncryptedPasswordRequiredException) { }
        try { backup.inspect(source, "wrong-pw".toCharArray()); fail("Wrong password must fail") }
        catch (_: WrongPasswordException) { }
        assertEquals(expected, dump())
        assertFalse(backup.snapshotFile.exists())
        assertTrue(files.restoreCacheDir.listFiles().orEmpty().none { it.name.startsWith("decrypted-") })
    }

    @Test fun truncatedMultiChunkFileIsRejectedAndPartialPlaintextRemoved() = runBlocking {
        seed()
        val image = files.newMediaFile(MediaKind.IMAGE).apply { writeBytes(ByteArray(200_000) { it.toByte() }) }
        db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.IMAGE, imagePath = image.path,
            status = MessageStatus.SUCCESS, createdAt = 9000))
        val source = encryptedExport()
        source.writeBytes(source.readBytes().dropLast(1).toByteArray())
        val expected = dump()
        try { backup.inspect(source, "pw123456".toCharArray()); fail("Truncation must fail") }
        catch (_: InvalidBackupException) { }
        assertEquals(expected, dump())
        assertFalse(backup.snapshotFile.exists())
        assertTrue(files.restoreCacheDir.listFiles().orEmpty().none { it.name.startsWith("decrypted-") })
    }

    @Test fun settingsFailureRollsBackEncryptedRestoreAndKeepsSnapshotButDeletesPlaintext() = runBlocking {
        seed()
        val source = encryptedExport()
        scramble()
        val expected = dump()
        val preview = backup.inspect(source, "pw123456".toCharArray())
        settings.failNextWrite = true
        try { backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray()); fail("Settings failure must fail") }
        catch (_: IllegalStateException) { }
        assertEquals(expected, dump())
        assertEquals(referencedFiles(), mediaFiles())
        assertTrue(backup.snapshotFile.isFile)
        assertFalse(preview.file.exists())
    }

    @Test fun authenticatedNonZipIsRejectedAndPlaintextRemoved() = runBlocking {
        val source = File(context.cacheDir, "invalid-inner-${System.nanoTime()}.aichat")
        source.outputStream().let { BackupContainer.encrypting(it, "pw123456".toCharArray()) }.use { it.write("not a zip".toByteArray()) }
        try { backup.inspect(source, "pw123456".toCharArray()); fail("Invalid inner zip") }
        catch (_: InvalidBackupException) { }
        assertTrue(files.restoreCacheDir.listFiles().orEmpty().none { it.name.startsWith("decrypted-") })
    }

    @Test fun startupCleanupDeletesOnlyRestoreCacheAndPreservesFailureSnapshot() {
        val stale = File(files.restoreCacheDir, "decrypted-stale.zip").apply { writeText("fixture") }
        val selected = File(files.restoreCacheDir, "selected-stale.backup").apply { writeText("fixture") }
        val snapshot = backup.snapshotFile.apply { writeText("snapshot fixture") }
        FileStorage.clearStaleRestoreCache(context)
        assertFalse(stale.exists()); assertFalse(selected.exists())
        assertTrue(snapshot.exists())
    }

    // ---------- 测试数据 ----------

    private suspend fun seed() {
        val llm = deepSeek()
        db.providerDao().update(llm.copy(apiKeyEncrypted = cipher.encrypt("sk-real-key"), baseUrl = "https://api.deepseek.com/v1"))
        db.providerDao().setImageMark(llm.id, "deepseek-v4-pro", true)

        val avatar = files.newMediaFile(MediaKind.AVATAR).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val myAvatar = files.newMediaFile(MediaKind.AVATAR).apply { writeBytes(byteArrayOf(9, 9)) }
        val image = files.newMediaFile(MediaKind.IMAGE).apply { writeBytes(ByteArray(2048) { it.toByte() }) }
        val background = files.newMediaFile(MediaKind.BACKGROUND).apply { writeBytes(byteArrayOf(7, 7, 7)) }

        db.contactDao().insert(
            contact(id = 1, name = "小雨", createdAt = 1_000).copy(
                avatarPath = avatar.absolutePath,
                thinking = ThinkingLevel.HIGH,
                temperature = 0.7f,
                systemPrompt = "你是小雨\n说话温柔",
                contextTokens = 6000,
                splitReplies = false,
                autoMemory = false,
            ),
        )
        db.contactDao().insert(contact(id = 2, name = "阿哲", createdAt = 2_000).copy(useGlobalMemory = false, useReplyPreferences = false))
        seedGlobalMemory()
        db.conversationDao().upsert(
            Conversation(
                contactId = 1, pinned = true, unreadCount = 2, draft = "还没发出去", lastMessageTime = 5_000,
                lastMessagePreview = "[图片]", memoryCursor = 3_500, memoryStart = 2_000,
                muted = true, backgroundPath = background.absolutePath,
            ),
        )
        db.conversationDao().upsert(Conversation(contactId = 2, markedUnread = true, lastMessageTime = 6_000, lastMessagePreview = "在吗"))
        db.messageDao().insert(text(contactId = 1, content = "你好", createdAt = 3_000))
        db.messageDao().insert(
            text(contactId = 1, content = "嗯嗯", createdAt = 4_000).copy(
                role = Role.ASSISTANT,
                reasoning = "想一想",
                reasoningMillis = 1_234,
                model = "deepseek-flash",
                promptTokens = 10,
                completionTokens = 20,
                replyGroup = "g1",
            ),
        )
        db.messageDao().insert(
            Message(
                contactId = 1, role = Role.USER, type = MessageType.IMAGE, imagePath = image.absolutePath,
                imageWidth = 640, imageHeight = 480, status = MessageStatus.SUCCESS, createdAt = 5_000,
            ),
        )
        db.messageDao().insert(
            text(contactId = 2, content = "在吗", createdAt = 6_000).copy(status = MessageStatus.FAILED, errorCode = FailReason.NETWORK.code),
        )
        // 第二个服务商，带 Key、带手动添加的模型和标记；阿哲用它
        val second = db.providerDao().insert(
            ProviderConfig(
                kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "第二家",
                baseUrl = "https://second.example.com/v1", apiKeyEncrypted = cipher.encrypt("sk-second"), defaultModel = "vision",
            ),
        )
        db.providerDao().upsertModels(
            listOf(
                ProviderModel(second, "vision", "Vision", Capability.YES, thinkingInput = Capability.NO, thinkingUserMark = true),
                ProviderModel(second, "local", "local", manual = true, sortOrder = 1),
            ),
        )
        db.contactDao().update(db.contactDao().get(2)!!.copy(providerId = second, model = "vision"))
        secondProviderId = second

        db.memoryDao().insert(
            Memory(contactId = 1, content = "喜欢拿铁", fromTime = 3_000, toTime = 3_500, edited = true, createdAt = 3_600, updatedAt = 3_700),
        )
        db.usageDao().insertIgnore(
            listOf(
                UsageDaily(20_000, 1, UsagePurpose.CHAT, 100, 10, 3),
                UsageDaily(20_000, 1, UsagePurpose.MEMORY, 50, 5, 1),
                UsageDaily(20_001, 2, UsagePurpose.CHAT, 70, 7, 2),
                UsageDaily(20_001, UsageDaily.DELETED_CONTACT, UsagePurpose.CHAT, 9, 9, 1),
            ),
        )
        settings.value = AppSettingsSnapshot(
            themeMode = ThemeMode.DARK,
            fontSize = FontSize.LARGE,
            nickname = "Isomeria",
            avatarPath = myAvatar.absolutePath,
            notifyEnabled = true,
            notifyPreview = false,
            autoCheckUpdate = false,
            replyPreferences = ReplyPreferences(waitSeconds = 5, targetLength = 20, minCount = 2, maxCount = 4, stripPunctuation = false, minIntervalSeconds = 2, maxIntervalSeconds = 6),
        )
    }

    /** 两个类别；第一个有两个主题，其中一条记忆关闭 */
    private suspend fun seedGlobalMemory() {
        val dao = db.globalMemoryDao()
        val personal = dao.insertCategory(GlobalMemoryCategory(name = "个人信息", sortOrder = 0, createdAt = 100, updatedAt = 101))
        val work = dao.insertCategory(GlobalMemoryCategory(name = "工作", sortOrder = 1, createdAt = 200, updatedAt = 201))
        val basics = dao.insertTopic(GlobalMemoryTopic(categoryId = personal, name = "基本情况", sortOrder = 0, createdAt = 110, updatedAt = 111))
        val food = dao.insertTopic(GlobalMemoryTopic(categoryId = personal, name = "饮食", sortOrder = 1, createdAt = 120, updatedAt = 121))
        val job = dao.insertTopic(GlobalMemoryTopic(categoryId = work, name = "职业", sortOrder = 0, createdAt = 210, updatedAt = 211))
        dao.insertItems(
            listOf(
                GlobalMemoryItem(topicId = basics, content = "住在杭州", sortOrder = 0, createdAt = 130, updatedAt = 131),
                GlobalMemoryItem(topicId = basics, content = "生日 3 月 5 日", enabled = false, sortOrder = 1, createdAt = 132, updatedAt = 133),
                GlobalMemoryItem(topicId = food, content = "不吃香菜", sortOrder = 0, createdAt = 140, updatedAt = 141),
                GlobalMemoryItem(topicId = job, content = "做后端开发", sortOrder = 0, createdAt = 220, updatedAt = 221),
            ),
        )
    }

    /** 把本机数据改乱：删联系人、改名、加消息、换设置、清 Key */
    private suspend fun scramble() {
        db.contactDao().update(db.contactDao().get(1)!!.copy(name = "改过的名字", avatarPath = null))
        db.messageDao().deleteByContact(2)
        db.contactDao().delete(2)
        db.contactDao().insert(contact(id = 5, name = "新联系人", createdAt = 50_000))
        db.conversationDao().upsert(Conversation(contactId = 5))
        db.messageDao().insert(text(contactId = 1, content = "新消息", createdAt = 7_000))
        db.providerDao().update(deepSeek().copy(apiKeyEncrypted = null, baseUrl = "https://example.com"))
        db.providerDao().deleteModels(1)
        db.providerDao().deleteModels(secondProviderId)
        db.providerDao().delete(secondProviderId)
        db.memoryDao().deleteAll()
        db.memoryDao().insert(Memory(contactId = 1, content = "本机后来的记忆", fromTime = 7_000, toTime = 7_000, createdAt = 7_000, updatedAt = 7_000))
        db.usageDao().deleteAll()
        db.usageDao().add(30_000, 1, UsagePurpose.CHAT, 1, 1)
        db.globalMemoryDao().deleteCategory(db.globalMemoryDao().categories().first().id)
        db.globalMemoryDao().insertCategory(GlobalMemoryCategory(name = "本机后来的类别", createdAt = 9_000, updatedAt = 9_000))
        db.contactDao().update(db.contactDao().get(1)!!.copy(useGlobalMemory = false))
        settings.value = settings.value.copy(themeMode = ThemeMode.LIGHT, nickname = "别人", avatarPath = null, replyPreferences = ReplyPreferences())
    }

    private fun contact(id: Long, name: String, createdAt: Long) = Contact(
        id = id, name = name, avatarColor = 0xFF123456.toInt(), providerId = 1, model = "deepseek-flash",
        createdAt = createdAt, updatedAt = createdAt + 1,
    )

    private fun text(contactId: Long, content: String, createdAt: Long) = Message(
        contactId = contactId, role = Role.USER, type = MessageType.TEXT, content = content,
        status = MessageStatus.SUCCESS, createdAt = createdAt,
    )

    /** Legacy regression fixtures use the unchanged inner zip, including password-encrypted SecretDto. */
    private suspend fun export(password: String?): File {
        val out = ByteArrayOutputStream()
        backup.export(out, password?.toCharArray())
        val bytes = if (password == null) out.toByteArray() else ByteArrayOutputStream().also {
            BackupContainer.decrypt(out.toByteArray().inputStream(), it, password.toCharArray())
        }.toByteArray()
        return File(context.cacheDir, "backup-${System.nanoTime()}.zip").apply { writeBytes(bytes) }
    }

    private fun writeZip(vararg entries: Pair<String, String>): File {
        val file = File(context.cacheDir, "crafted-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    /** 数据库和设置的全部内容；文件路径换成文件内容（恢复后文件名会变），Key 换成明文 */
    private data class Dump(
        val contacts: List<Contact>,
        val conversations: List<Conversation>,
        val messages: List<Message>,
        val providers: List<ProviderConfig>,
        val models: List<ProviderModel>,
        val memories: List<Memory>,
        val usage: List<UsageDaily>,
        val settings: AppSettingsSnapshot,
        val globalCategories: List<GlobalMemoryCategory>,
        val globalTopics: List<GlobalMemoryTopic>,
        val globalItems: List<GlobalMemoryItem>,
    )

    private var secondProviderId = 0L

    private suspend fun deepSeek() = db.providerDao().get(1)!!

    private suspend fun dump(): Dump {
        fun content(path: String?) = path?.let { "bytes:" + File(it).readBytes().joinToString(",") }
        val providers = db.providerDao().llmList()
        return Dump(
            contacts = db.contactDao().listAll().map { it.copy(avatarPath = content(it.avatarPath)) },
            conversations = db.conversationDao().listAll().map { it.copy(backgroundPath = content(it.backgroundPath)) },
            messages = db.messageDao().listAll().map { it.copy(imagePath = content(it.imagePath)) },
            providers = providers.map { it.copy(apiKeyEncrypted = it.apiKeyEncrypted?.let(cipher::decrypt)) },
            models = providers.flatMap { db.providerDao().models(it.id) },
            memories = db.memoryDao().listAll(),
            usage = db.usageDao().listAll(),
            settings = settings.value.copy(avatarPath = content(settings.value.avatarPath)),
            globalCategories = db.globalMemoryDao().categories(),
            globalTopics = db.globalMemoryDao().topics(),
            globalItems = db.globalMemoryDao().items(),
        )
    }

    private suspend fun referencedFiles(): Set<String> = buildSet {
        db.contactDao().listAll().mapNotNullTo(this) { it.avatarPath }
        addAll(db.messageDao().allImagePaths())
        addAll(db.conversationDao().backgroundPaths())
        settings.value.avatarPath?.let(::add)
    }.map { File(it).canonicalPath }.toSet()

    private fun mediaFiles(): Set<String> = listOf("avatars", "images", "background")
        .flatMap { File(context.filesDir, it).listFiles().orEmpty().toList() }
        .map { it.canonicalPath }
        .toSet()

    private class FakeCipher : SecretCipher {
        override fun encrypt(plain: String) = "enc:" + plain.reversed()
        override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:").reversed() else null
    }

    private class FakeSettings : BackupSettingsAccess {
        var value = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true)
        var failNextWrite = false

        override fun read() = value

        override fun write(snapshot: AppSettingsSnapshot) {
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("写设置失败")
            }
            value = snapshot
        }
    }

    private class FakeReplies : ReplyControl {
        val cancelled = mutableListOf<Long>()
        val deliveryCancelled = mutableListOf<Long>()
        override fun cancel(contactId: Long) {
            cancelled += contactId
        }

        override suspend fun isActive(contactId: Long) = false

        override fun cancelDelivery(contactId: Long) {
            deliveryCancelled += contactId
        }

        override suspend fun isDelivering(contactId: Long) = false
    }
}
