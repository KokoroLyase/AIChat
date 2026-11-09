package isomeria.aichat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.backup.AppSettingsSnapshot
import isomeria.aichat.data.backup.BackupManager
import isomeria.aichat.data.backup.BackupSettingsAccess
import isomeria.aichat.data.backup.RestoreMode
import isomeria.aichat.data.crypto.KeystoreSecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 真机上恢复 v0.4.2 导出代码生成的明文和整文件加密备份（虚构数据，放在 androidTest assets）：
 * 真实 Keystore 和 PBKDF2；联系人默认使用全局记忆和回复偏好，分条显示、免打扰、聊天背景照常恢复。
 */
@RunWith(AndroidJUnit4::class)
class Backup042DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val cipher = KeystoreSecretCipher()
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupManager
    private val copies = mutableListOf<File>()
    private val local = ReplyPreferences(waitSeconds = 7)
    private val settings = object : BackupSettingsAccess {
        var v = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true, local)
        override fun read() = v
        override fun write(snapshot: AppSettingsSnapshot) { v = snapshot }
    }

    @Before
    fun setUp() {
        require(context.packageName == "isomeria.aichat.debug")
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).build()
        backup = BackupManager(context, db, FileStorage(context), cipher, settings, object : ReplyControl {
            override fun cancel(contactId: Long) = Unit
            override suspend fun isActive(contactId: Long) = false
            override fun cancelDelivery(contactId: Long) = Unit
            override suspend fun isDelivering(contactId: Long) = false
        })
    }

    @After
    fun tearDown() {
        runBlocking {
            db.contactDao().listAll().mapNotNull { it.avatarPath }.forEach { File(it).delete() }
            db.messageDao().allImagePaths().forEach { File(it).delete() }
            db.conversationDao().backgroundPaths().forEach { File(it).delete() }
        }
        db.close()
        copies.forEach { it.delete() }
        backup.snapshotFile.delete()
    }

    private fun fixture(name: String) = File(context.cacheDir, name).also { target ->
        assets.open("backup042/$name").use { input -> target.outputStream().use(input::copyTo) }
        copies += target
    }

    private suspend fun assertRestored() {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        assertTrue(contacts.all { it.useGlobalMemory && it.useReplyPreferences })
        assertEquals(listOf(true, false), contacts.map { it.splitReplies })
        assertEquals(3, db.messageDao().listByContact(1).size)
        val conversation = db.conversationDao().get(1)!!
        assertTrue(conversation.pinned)
        assertTrue(conversation.muted)
        assertEquals(512L, File(conversation.backgroundPath!!).length())
        assertFalse(db.conversationDao().get(2)!!.muted)
        assertEquals(local, settings.v.replyPreferences)
        assertTrue(db.globalMemoryDao().categories().isEmpty())
    }

    @Test
    fun plainZipFrom042Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-042-plain.zip"))
        assertEquals(4, preview.manifest.formatVersion)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored()
    }

    @Test
    fun encryptedFileFrom042Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-042-encrypted.aichat"), "pw123456".toCharArray())
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored()
        assertEquals("sk-fixture-not-real", cipher.decrypt(db.providerDao().get(1)!!.apiKeyEncrypted!!))
        assertFalse(preview.file.exists())
    }
}
