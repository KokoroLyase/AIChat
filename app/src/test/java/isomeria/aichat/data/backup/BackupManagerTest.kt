package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.ThinkingLevel
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
        listOf("avatars", "images", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
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
        assertEquals("sk-real-key", cipher.decrypt(db.providerDao().getLlm()!!.apiKeyEncrypted!!))
        // 覆盖前停掉了等待中的回复
        assertTrue(replies.cancelled.isNotEmpty())
        // 只留下被引用的图片和头像
        assertEquals(referencedFiles(), mediaFiles())
        // 恢复前的自动备份在私有目录里
        assertTrue(backup.snapshotFile.isFile)
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
        db.providerDao().update(db.providerDao().getLlm()!!.copy(apiKeyEncrypted = cipher.encrypt("sk-new-local")))
        val summary = backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertFalse(summary.apiKeyRestored)
        assertEquals("sk-new-local", cipher.decrypt(db.providerDao().getLlm()!!.apiKeyEncrypted!!))
    }

    @Test
    fun skippingPassword_restoresEverythingButKey() = runBlocking {
        seed()
        val zip = export(password = "pw123456")
        db.providerDao().update(db.providerDao().getLlm()!!.copy(apiKeyEncrypted = null))
        val summary = backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertFalse(summary.apiKeyRestored)
        assertEquals(null, db.providerDao().getLlm()!!.apiKeyEncrypted)
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

    // ---------- 测试数据 ----------

    private suspend fun seed() {
        val llm = db.providerDao().getLlm()!!
        db.providerDao().update(llm.copy(apiKeyEncrypted = cipher.encrypt("sk-real-key"), baseUrl = "https://api.deepseek.com/v1"))
        db.providerDao().setImageMark(llm.id, "deepseek-v4-pro", true)

        val avatar = files.newMediaFile(MediaKind.AVATAR).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val myAvatar = files.newMediaFile(MediaKind.AVATAR).apply { writeBytes(byteArrayOf(9, 9)) }
        val image = files.newMediaFile(MediaKind.IMAGE).apply { writeBytes(ByteArray(2048) { it.toByte() }) }

        db.contactDao().insert(
            contact(id = 1, name = "小雨", createdAt = 1_000).copy(
                avatarPath = avatar.absolutePath,
                thinking = ThinkingLevel.HIGH,
                temperature = 0.7f,
                systemPrompt = "你是小雨\n说话温柔",
                contextCount = 30,
                splitReplies = false,
            ),
        )
        db.contactDao().insert(contact(id = 2, name = "阿哲", createdAt = 2_000))
        db.conversationDao().upsert(
            Conversation(contactId = 1, pinned = true, unreadCount = 2, draft = "还没发出去", lastMessageTime = 5_000, lastMessagePreview = "[图片]"),
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
        settings.value = AppSettingsSnapshot(
            themeMode = ThemeMode.DARK,
            fontSize = FontSize.LARGE,
            nickname = "Isomeria",
            avatarPath = myAvatar.absolutePath,
            notifyEnabled = true,
            notifyPreview = false,
            autoCheckUpdate = false,
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
        db.providerDao().update(db.providerDao().getLlm()!!.copy(apiKeyEncrypted = null, baseUrl = "https://example.com"))
        db.providerDao().deleteModels(1)
        settings.value = settings.value.copy(themeMode = ThemeMode.LIGHT, nickname = "别人", avatarPath = null)
    }

    private fun contact(id: Long, name: String, createdAt: Long) = Contact(
        id = id, name = name, avatarColor = 0xFF123456.toInt(), providerId = 1, model = "deepseek-flash",
        createdAt = createdAt, updatedAt = createdAt + 1,
    )

    private fun text(contactId: Long, content: String, createdAt: Long) = Message(
        contactId = contactId, role = Role.USER, type = MessageType.TEXT, content = content,
        status = MessageStatus.SUCCESS, createdAt = createdAt,
    )

    private suspend fun export(password: String?): File {
        val out = ByteArrayOutputStream()
        backup.export(out, password?.toCharArray())
        return File(context.cacheDir, "backup-${System.nanoTime()}.zip").apply { writeBytes(out.toByteArray()) }
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
        val provider: ProviderConfig,
        val models: List<ProviderModel>,
        val settings: AppSettingsSnapshot,
    )

    private suspend fun dump(): Dump {
        fun content(path: String?) = path?.let { "bytes:" + File(it).readBytes().joinToString(",") }
        val llm = db.providerDao().getLlm()!!
        return Dump(
            contacts = db.contactDao().listAll().map { it.copy(avatarPath = content(it.avatarPath)) },
            conversations = db.conversationDao().listAll(),
            messages = db.messageDao().listAll().map { it.copy(imagePath = content(it.imagePath)) },
            provider = llm.copy(apiKeyEncrypted = llm.apiKeyEncrypted?.let(cipher::decrypt)),
            models = db.providerDao().models(llm.id),
            settings = settings.value.copy(avatarPath = content(settings.value.avatarPath)),
        )
    }

    private suspend fun referencedFiles(): Set<String> = buildSet {
        db.contactDao().listAll().mapNotNullTo(this) { it.avatarPath }
        addAll(db.messageDao().allImagePaths())
        settings.value.avatarPath?.let(::add)
    }.map { File(it).canonicalPath }.toSet()

    private fun mediaFiles(): Set<String> = listOf("avatars", "images")
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
        override fun cancel(contactId: Long) {
            cancelled += contactId
        }

        override suspend fun isActive(contactId: Long) = false
    }
}
