package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 0.4.1 导出的真实文件（`src/test/resources/backup041`，由 v0.4.1 的导出代码用虚构数据生成）：
 * 明文 zip 和整文件加密 .aichat 都能在 0.4.2 恢复，会话默认不免打扰、没有聊天背景，其余内容不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Backup041FixtureTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
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
        listOf("avatars", "images", "background", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).build()
        backup = BackupManager(context, db, FileStorage(context), cipher, settings, object : ReplyControl {
            override fun cancel(contactId: Long) = Unit
            override suspend fun isActive(contactId: Long) = false
        })
    }

    @After
    fun tearDown() = db.close()

    private fun fixture(name: String): File {
        val target = File(context.cacheDir, name)
        javaClass.classLoader!!.getResourceAsStream("backup041/$name")!!.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private suspend fun assertRestored(keyRestored: Boolean) {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        assertEquals(0.8f, contacts[0].temperature)
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(contacts[0].avatarPath!!).readBytes().toList())
        val messages = db.messageDao().listByContact(1)
        assertEquals(3, messages.size)
        assertEquals(1024, File(messages.single { it.type == MessageType.IMAGE }.imagePath!!).length())
        val conversation = db.conversationDao().get(1)!!
        assertTrue(conversation.pinned)
        assertEquals("草稿", conversation.draft)
        assertFalse(conversation.muted)
        assertNull(conversation.backgroundPath)
        assertTrue(db.conversationDao().listAll().none { it.muted || it.backgroundPath != null })
        assertEquals("虚构记忆", db.memoryDao().listByContact(1).single().content)
        assertEquals(10L, db.usageDao().listAll().single().promptTokens)
        assertEquals("测试用户", settings.v.nickname)
        assertEquals(if (keyRestored) "sk-fixture-not-real" else null, db.providerDao().get(1)!!.apiKeyEncrypted?.let(cipher::decrypt))
    }

    @Test
    fun plainZipFrom041Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-041-plain.zip"))
        assertEquals(3, preview.manifest.formatVersion)
        assertEquals(401, preview.manifest.appVersionCode)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored(keyRestored = false)
    }

    @Test
    fun encryptedFileFrom041Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-041-encrypted.aichat"), "pw123456".toCharArray())
        assertTrue(preview.decryptedTemporary)
        assertEquals(3, preview.manifest.formatVersion)
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored(keyRestored = true)
        assertFalse(preview.file.exists())
    }
}
