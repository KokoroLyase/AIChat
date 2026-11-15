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
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 0.9.2 发文件的导出与恢复（备份 v11）：原文件放在 zip 的 chat_files/ 下、保留扩展名，解析结果放在 parsed/ 下，跟着「聊天记录」；
 * 文件解析服务跟着「模型配置」，覆盖恢复时备份里没有就保留本机的（0.9.1 及更早的备份）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackupFileTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private lateinit var backup: BackupManager
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:") else null
    }
    private val settings = object : BackupSettingsAccess {
        var v = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true)
        override fun read() = v
        override fun write(snapshot: AppSettingsSnapshot) { v = snapshot }
    }

    @Before
    fun setUp() {
        listOf("avatars", "images", "background", "voice", "calls", "chat_files", "parsed", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).build()
        files = FileStorage(context)
        backup = BackupManager(context, db, files, cipher, settings, object : ReplyControl {
            override fun cancel(contactId: Long) = Unit
            override suspend fun isActive(contactId: Long) = false
            override fun cancelDelivery(contactId: Long) = Unit
            override suspend fun isDelivering(contactId: Long) = false
        })
    }

    @After
    fun tearDown() = db.close()

    private var fileId = 0L

    private suspend fun seed() {
        db.contactDao().insert(Contact(id = 1, name = "甲", avatarColor = 1, providerId = 1, model = "m", createdAt = 10, updatedAt = 10))
        db.conversationDao().upsert(Conversation(contactId = 1, lastMessageTime = 100, lastMessagePreview = "[文件] 报告.pdf"))
        val original = files.newMediaFile(isomeria.aichat.data.repo.MediaKind.FILE, "pdf").apply { writeText("%PDF 原文件") }
        val parsed = files.writeParsed("# 第一章\n内容").also(files::commitAvatar)
        fileId = db.messageDao().insert(
            Message(
                contactId = 1, role = Role.USER, type = MessageType.FILE, content = "报告.pdf", filePath = original.path, fileSize = 13,
                fileMime = "application/pdf", parsedPath = parsed, status = MessageStatus.SUCCESS, createdAt = 100,
            ),
        )
        // 还没解析的文件
        val unparsed = files.newMediaFile(isomeria.aichat.data.repo.MediaKind.FILE, "docx").apply { writeText("docx") }
        db.messageDao().insert(
            Message(contactId = 1, role = Role.USER, type = MessageType.FILE, content = "合同", filePath = unparsed.path, fileSize = 4, status = MessageStatus.FAILED, createdAt = 200),
        )
        db.providerDao().insert(
            ProviderConfig(kind = ProviderKind.PARSER, provider = "mineru", name = "MinerU", baseUrl = "https://mineru.net/api/v4", apiKeyEncrypted = "enc:tok", defaultModel = "vlm"),
        )
    }

    private suspend fun export(modules: Set<BackupModule> = BackupModule.ALL): File {
        val file = File(context.cacheDir, "file-backup.zip")
        backup.export(file.outputStream(), null, modules = modules)
        return file
    }

    @Test
    fun export_putsFilesAndParsedText_keepsExtension_parserProviderWithoutToken() = runBlocking {
        seed()
        val zip = export()
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map(ZipEntry::getName)
            assertEquals(listOf("docx", "pdf"), names.filter { it.startsWith(BackupFormat.FILES_DIR) }.map { it.substringAfterLast('.') }.sorted())
            assertEquals(1, names.count { it.startsWith(BackupFormat.PARSED_DIR) })
        }
        val preview = backup.inspect(zip)
        assertEquals(11, preview.manifest.formatVersion)
        assertEquals("恢复页显示的聊天文件个数", 2, preview.fileCount)
        val file = preview.data.messages.single { it.content == "报告.pdf" }
        assertEquals(MessageType.FILE, file.type)
        assertEquals(13L, file.fileSize)
        assertEquals("application/pdf", file.fileMime)
        assertTrue(file.file!!.startsWith(BackupFormat.FILES_DIR))
        assertTrue(file.parsed!!.startsWith(BackupFormat.PARSED_DIR))
        assertNull(preview.data.messages.single { it.content == "合同" }.parsed)
        val parser = preview.data.providers.single { it.kind == ProviderKind.PARSER }
        assertEquals("mineru", parser.provider)
        assertNull("不选导出 Key 时不带 Token", parser.apiKey)
    }

    @Test
    fun withoutChats_noFiles() = runBlocking {
        seed()
        val zip = export(setOf(BackupModule.CONTACTS, BackupModule.PROVIDERS))
        ZipFile(zip).use { z ->
            assertTrue(z.entries().toList().none { it.name.startsWith(BackupFormat.FILES_DIR) || it.name.startsWith(BackupFormat.PARSED_DIR) })
        }
    }

    @Test
    fun overwriteRestore_filesAndParsedTextBack_parserKeptWithLocalToken() = runBlocking {
        seed()
        val zip = export()
        val oldPath = db.messageDao().get(fileId)!!.filePath
        db.messageDao().deleteAll()
        listOf("chat_files", "parsed").forEach { File(context.filesDir, it).deleteRecursively() }
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        val restored = db.messageDao().listAll().single { it.content == "报告.pdf" }
        assertEquals(MessageType.FILE, restored.type)
        assertEquals("%PDF 原文件", File(restored.filePath!!).readText())
        assertTrue(restored.filePath!!.endsWith(".pdf"))
        assertNotEquals("换成了新的文件名", oldPath, restored.filePath)
        assertEquals("# 第一章\n内容", File(restored.parsedPath!!).readText())
        assertEquals(13L, restored.fileSize)
        assertEquals("application/pdf", restored.fileMime)
        // 文件解析服务换成备份里的；备份没带 Token 时保留本机的
        val parser = db.providerDao().listByKind(ProviderKind.PARSER).single()
        assertEquals("enc:tok", parser.apiKeyEncrypted)
    }

    @Test
    fun overwriteRestore_oldBackupWithoutParser_keepsLocalParser() = runBlocking {
        seed()
        val zip = export(BackupModule.ALL)
        // 模拟 0.9.1 及更早的备份：里面没有文件解析服务
        val preview = backup.inspect(zip)
        val old = preview.copy(data = preview.data.copy(providers = preview.data.providers.filter { it.kind != ProviderKind.PARSER }))
        backup.restore(old, RestoreMode.OVERWRITE, null)
        val parser = db.providerDao().listByKind(ProviderKind.PARSER).single()
        assertEquals("enc:tok", parser.apiKeyEncrypted)
        assertEquals("https://mineru.net/api/v4", parser.baseUrl)
    }

    @Test
    fun mergeRestore_addsFileMessageWithItsFiles() = runBlocking {
        seed()
        val zip = export()
        db.messageDao().deleteAll()
        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        val restored = db.messageDao().listAll().filter { it.type == MessageType.FILE }
        assertEquals(setOf("报告.pdf", "合同"), restored.map { it.content }.toSet())
        assertTrue(restored.all { File(it.filePath!!).isFile })
        assertEquals("合并恢复不会多出一个文件解析服务", 1, db.providerDao().listByKind(ProviderKind.PARSER).size)
    }
}
