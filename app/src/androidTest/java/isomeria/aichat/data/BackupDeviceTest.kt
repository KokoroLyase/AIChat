package isomeria.aichat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isomeria.aichat.data.backup.AppSettingsSnapshot
import isomeria.aichat.data.backup.BackupManager
import isomeria.aichat.data.backup.BackupSettingsAccess
import isomeria.aichat.data.backup.RestoreMode
import isomeria.aichat.data.backup.WrongPasswordException
import isomeria.aichat.data.crypto.KeystoreSecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Memory
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 真机上的导出与恢复：用真实的 Android Keystore 和 PBKDF2，单元测试里这两样都是假的。
 * 只碰内存数据库；恢复前的自动备份会写到测试包所在 App 的 files/backups，只在 debug 包上跑。
 */
@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cipher = KeystoreSecretCipher()
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupManager

    private val settings = object : BackupSettingsAccess {
        var value = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true)
        override fun read() = value
        override fun write(snapshot: AppSettingsSnapshot) {
            value = snapshot
        }
    }

    private val replies = object : ReplyControl {
        override fun cancel(contactId: Long) = Unit
        override suspend fun isActive(contactId: Long) = false
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.seedCallback)
            .build()
        backup = BackupManager(context, db, FileStorage(context), cipher, settings, replies)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun multiProviderKeys_survivePasswordExportAndRestore() = runBlocking {
        val deepSeek = db.providerDao().llmList().single()
        db.providerDao().update(deepSeek.copy(apiKeyEncrypted = cipher.encrypt("sk-first")))
        val second = db.providerDao().insert(
            ProviderConfig(
                kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "第二家",
                baseUrl = "https://second.example.com/v1", apiKeyEncrypted = cipher.encrypt("sk-second"), defaultModel = "m",
            ),
        )
        val contact = db.contactDao().insert(
            Contact(name = "小雨", avatarColor = 0, providerId = second, model = "m", createdAt = 1, updatedAt = 1),
        )
        db.conversationDao().upsert(Conversation(contactId = contact))
        db.memoryDao().insert(Memory(contactId = contact, content = "记忆", fromTime = 1, toTime = 2, createdAt = 3, updatedAt = 3))
        db.usageDao().add(100, contact, UsagePurpose.CHAT, 10, 2)

        val out = ByteArrayOutputStream()
        backup.export(out, "pw123456".toCharArray())
        val file = File(context.cacheDir, "device-backup-test.zip").apply { writeBytes(out.toByteArray()) }
        val text = out.toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue("明文 Key 不能出现在文件里", !text.contains("sk-first") && !text.contains("sk-second"))

        try {
            // 本机改乱之后恢复
            db.providerDao().update(db.providerDao().get(deepSeek.id)!!.copy(apiKeyEncrypted = null))
            db.memoryDao().deleteAll()
            db.usageDao().deleteAll()
            val preview = backup.inspect(file)
            assertTrue(preview.needsPassword)
            try {
                backup.restore(preview, RestoreMode.OVERWRITE, "wrong-pw".toCharArray())
                fail("密码错误应该失败")
            } catch (_: WrongPasswordException) {
            }
            assertEquals("密码错误时本机数据不变", null, db.providerDao().get(deepSeek.id)!!.apiKeyEncrypted)

            val summary = backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
            assertTrue(summary.apiKeyRestored)

            val encrypted = db.providerDao().get(second)!!.apiKeyEncrypted!!
            assertEquals("sk-first", cipher.decrypt(db.providerDao().get(deepSeek.id)!!.apiKeyEncrypted!!))
            assertEquals("sk-second", cipher.decrypt(encrypted))
            assertNotEquals("写回的是 Keystore 密文", "sk-second", encrypted)
            assertEquals(second, db.contactDao().get(contact)!!.providerId)
            assertEquals("记忆", db.memoryDao().listByContact(contact).single().content)
            assertEquals(10L, db.usageDao().listAll().single().promptTokens)
        } finally {
            file.delete()
            backup.snapshotFile.delete()
        }
    }
}
