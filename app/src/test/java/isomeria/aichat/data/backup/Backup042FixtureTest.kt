package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.GlobalMemoryCategory
import isomeria.aichat.data.db.MessageStatus
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
 * 0.4.2 导出的真实文件（`src/test/resources/backup042`，在 v0.4.2 的临时工作树里用当时的导出代码和虚构数据生成）：
 * 明文 zip 和整文件加密 .aichat 都能在 0.5.0 恢复。联系人「使用全局记忆」「使用回复偏好」默认开启，分条显示原样保留；
 * 0.4.2 的免打扰和聊天背景照常恢复；备份里没有全局记忆和回复偏好。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Backup042FixtureTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupManager
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:") else null
    }
    private val localPreferences = ReplyPreferences(waitSeconds = 7, maxCount = 2)
    private val settings = object : BackupSettingsAccess {
        var v = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true, localPreferences)
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
            override fun cancelDelivery(contactId: Long) = Unit
            override suspend fun isDelivering(contactId: Long) = false
        })
    }

    @After
    fun tearDown() = db.close()

    private fun fixture(name: String): File {
        val target = File(context.cacheDir, name)
        javaClass.classLoader!!.getResourceAsStream("backup042/$name")!!.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private suspend fun assertRestored(keyRestored: Boolean) {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        assertTrue("旧备份的联系人默认使用全局记忆和回复偏好", contacts.all { it.useGlobalMemory && it.useReplyPreferences })
        assertEquals("分条显示原样保留", listOf(true, false), contacts.map { it.splitReplies })
        assertEquals(0.8f, contacts[0].temperature)
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(contacts[0].avatarPath!!).readBytes().toList())
        val messages = db.messageDao().listByContact(1)
        assertEquals(3, messages.size)
        assertTrue(messages.all { it.status == MessageStatus.SUCCESS })
        assertEquals(1024, File(messages.single { it.type == MessageType.IMAGE }.imagePath!!).length())
        val first = db.conversationDao().get(1)!!
        assertTrue(first.pinned)
        assertEquals("草稿", first.draft)
        assertTrue("0.4.2 的免打扰照常恢复", first.muted)
        assertEquals(List(512) { 7.toByte() }, File(first.backgroundPath!!).readBytes().toList())
        db.conversationDao().get(2)!!.let { assertFalse(it.muted); assertNull(it.backgroundPath); assertEquals(1, it.unreadCount) }
        assertEquals("虚构记忆", db.memoryDao().listByContact(1).single().content)
        assertEquals(10L, db.usageDao().listAll().single().promptTokens)
        assertEquals("测试用户", settings.v.nickname)
        assertEquals("备份里没有回复偏好，保留本机的", localPreferences, settings.v.replyPreferences)
        assertEquals(if (keyRestored) "sk-fixture-not-real" else null, db.providerDao().get(1)!!.apiKeyEncrypted?.let(cipher::decrypt))
    }

    @Test
    fun plainZipFrom042Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-042-plain.zip"))
        assertEquals(4, preview.manifest.formatVersion)
        assertEquals(402, preview.manifest.appVersionCode)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored(keyRestored = false)
        assertEquals(ThemeMode.DARK, settings.v.themeMode)
        assertTrue(db.globalMemoryDao().categories().isEmpty())
    }

    @Test
    fun encryptedFileFrom042Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-042-encrypted.aichat"), "pw123456".toCharArray())
        assertTrue(preview.decryptedTemporary)
        assertEquals(4, preview.manifest.formatVersion)
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored(keyRestored = true)
        assertFalse(preview.file.exists())
    }

    @Test
    fun mergingA042Backup_keepsLocalGlobalMemoryAndReplyPreferences() = runBlocking {
        db.globalMemoryDao().insertCategory(GlobalMemoryCategory(name = "本机的类别", createdAt = 1, updatedAt = 1))
        backup.restore(backup.inspect(fixture("aichat-042-plain.zip")), RestoreMode.MERGE, null)
        assertEquals(listOf("本机的类别"), db.globalMemoryDao().categories().map { it.name })
        assertEquals(localPreferences, settings.v.replyPreferences)
        val contacts = db.contactDao().listAll()
        assertTrue(contacts.all { it.useGlobalMemory && it.useReplyPreferences })
        assertEquals(listOf(true, false), contacts.map { it.splitReplies })
        assertTrue(db.conversationDao().get(1)!!.muted)
    }
}
