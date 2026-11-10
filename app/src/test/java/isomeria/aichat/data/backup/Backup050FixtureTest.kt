package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.ProactiveState
import isomeria.aichat.data.db.UsagePurpose
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 0.5.0 导出的真实文件（`src/test/resources/backup050`，在 v0.5.0 的临时工作树里用当时的导出代码和虚构数据生成）：
 * 明文 zip 和整文件加密 .aichat 都能在 0.6.0 恢复。联系人的主动聊天关闭、频率适中，消息都是普通消息，
 * 主动聊天的全局设置保留本机的；0.5.0 的全局记忆、两个开关和回复偏好照常恢复，导出时待投递的回复按已送达。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Backup050FixtureTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupManager
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:") else null
    }
    private val localProactive = ProactiveSettings(quietEnabled = false, dailyLimit = 7)
    private val settings = object : BackupSettingsAccess {
        var v = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true, ReplyPreferences(), localProactive)
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
        javaClass.classLoader!!.getResourceAsStream("backup050/$name")!!.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private suspend fun assertRestored(keyRestored: Boolean) {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        assertTrue("0.5.0 备份的联系人主动聊天关闭", contacts.none { it.proactiveEnabled })
        assertTrue(contacts.all { it.proactiveFrequency == ProactiveFrequency.MEDIUM })
        assertEquals("没有联系人开启，不会注册后台任务", 0, db.contactDao().proactiveCount())
        assertEquals("0.5.0 的两个开关原样恢复", listOf(true to false, false to true), contacts.map { it.useGlobalMemory to it.useReplyPreferences })
        assertEquals(listOf(true, false), contacts.map { it.splitReplies })
        assertEquals(0.8f, contacts[0].temperature)
        assertEquals(6000, contacts[0].contextTokens)
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(contacts[0].avatarPath!!).readBytes().toList())

        val messages = db.messageDao().listByContact(1)
        assertEquals(listOf("虚构问题", "", "第一条", "第二条", "第三条"), messages.map { it.content })
        assertTrue("旧消息都是普通消息", messages.all { it.origin == MessageOrigin.NORMAL })
        assertTrue("导出时待投递的回复按已送达", messages.all { it.status == MessageStatus.SUCCESS })
        assertEquals(1024, File(messages.single { it.type == MessageType.IMAGE }.imagePath!!).length())
        assertEquals("虚构思考", messages[2].reasoning)

        val first = db.conversationDao().get(1)!!
        assertTrue(first.pinned && first.muted)
        assertEquals("草稿", first.draft)
        assertEquals("第三条", first.lastMessagePreview)
        assertEquals(0, first.proactiveUnanswered)
        assertEquals(0L, first.lastProactiveAt)
        assertEquals(List(512) { 7.toByte() }, File(first.backgroundPath!!).readBytes().toList())
        assertEquals(1, db.conversationDao().get(2)!!.unreadCount)

        assertEquals("虚构记忆", db.memoryDao().listByContact(1).single().content)
        assertEquals(listOf(UsagePurpose.CHAT, UsagePurpose.MEMORY), db.usageDao().listAll().map { it.purpose })
        assertEquals(listOf("个人信息"), db.globalMemoryDao().categories().map { it.name })
        assertEquals(mapOf("住在虚构市" to true, "关掉的一条" to false), db.globalMemoryDao().items().associate { it.content to it.enabled })

        assertEquals("测试用户", settings.v.nickname)
        assertEquals(ThemeMode.DARK, settings.v.themeMode)
        assertEquals(ReplyPreferences(5, 20, 2, 4, false, 3, 6), settings.v.replyPreferences)
        assertEquals("备份里没有主动聊天设置，保留本机的", localProactive, settings.v.proactive)
        assertEquals(if (keyRestored) "sk-fixture-not-real" else null, db.providerDao().get(1)!!.apiKeyEncrypted?.let(cipher::decrypt))
        assertEquals(0, db.proactiveDao().reminderCount())
    }

    @Test
    fun plainZipFrom050Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-050-plain.zip"))
        assertEquals(5, preview.manifest.formatVersion)
        assertEquals(500, preview.manifest.appVersionCode)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored(keyRestored = false)
    }

    @Test
    fun encryptedFileFrom050Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-050-encrypted.aichat"), "pw123456".toCharArray())
        assertTrue(preview.decryptedTemporary)
        assertEquals(5, preview.manifest.formatVersion)
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored(keyRestored = true)
        assertFalse(preview.file.exists())
    }

    /** 本机已经开了主动聊天、用过额度：合并 0.5.0 的备份不改本机的开关和额度，新加进来的联系人是关闭的 */
    @Test
    fun mergingA050Backup_keepsLocalProactiveState_addedContactsAreOff() = runBlocking {
        backup.restore(backup.inspect(fixture("aichat-050-plain.zip")), RestoreMode.OVERWRITE, null)
        db.contactDao().update(db.contactDao().get(1)!!.copy(proactiveEnabled = true, proactiveFrequency = ProactiveFrequency.HIGH))
        db.messageDao().deleteByContact(2)
        db.contactDao().delete(2)
        db.proactiveDao().saveState(ProactiveState(day = 20_700, used = 6, lastRunAt = 9))
        db.conversationDao().addProactiveSent(1, 9)

        backup.restore(backup.inspect(fixture("aichat-050-plain.zip")), RestoreMode.MERGE, null)

        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        assertEquals(listOf(true, false), contacts.map { it.proactiveEnabled })
        assertEquals(ProactiveFrequency.HIGH, contacts[0].proactiveFrequency)
        assertEquals(ProactiveState(day = 20_700, used = 6, lastRunAt = 9), db.proactiveDao().state())
        assertEquals(1, db.conversationDao().get(1)!!.proactiveUnanswered)
        assertEquals(localProactive, settings.v.proactive)
    }
}
