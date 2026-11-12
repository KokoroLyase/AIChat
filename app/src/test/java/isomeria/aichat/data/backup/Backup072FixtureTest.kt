package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ReplyType
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
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

/**
 * 0.7.2 导出的真实文件（`src/test/resources/backup072`，2026-11-12 在 v0.7.2 的临时工作树里用当时的导出代码和虚构数据生成）：
 * 明文 zip 和整文件加密 .aichat（带 Key，密码 pw123456）都能在 0.8.0 恢复。备份内层是 v8，没有通话；
 * 语音消息、联系人单独设置的回复偏好和语音来源、核心记忆、语音服务商和语音设置照常恢复，服务商 id 跟着换。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Backup072FixtureTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupManager
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:") else null
    }
    private val settings = object : BackupSettingsAccess {
        var v = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true, ReplyPreferences(), ProactiveSettings())
        override fun read() = v
        override fun write(snapshot: AppSettingsSnapshot) { v = snapshot }
    }

    @Before
    fun setUp() {
        listOf("avatars", "images", "background", "voice", "calls", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
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
        javaClass.classLoader!!.getResourceAsStream("backup072/$name")!!.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private suspend fun assertRestored(keyRestored: Boolean) {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        val first = contacts[0]
        assertEquals(true to ProactiveFrequency.HIGH, first.proactiveEnabled to first.proactiveFrequency)
        assertEquals(ReplyType.VOICE, first.replyType)
        assertEquals("甲的音色", first.ttsVoice)
        assertEquals(7, first.replyPreferences!!.waitSeconds)
        val tts = db.providerDao().listByKind(ProviderKind.TTS).single()
        val asr = db.providerDao().listByKind(ProviderKind.ASR).single()
        assertEquals("单独选的合成服务商换成恢复后的 id", VoiceSource.provider(tts.id), first.ttsSource)
        assertEquals(VoiceSource.of(VoiceSource.OFFLINE_ASR), first.asrSource)
        assertNull(contacts[1].ttsSource)
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(first.avatarPath!!).readBytes().toList())

        val messages = db.messageDao().listByContact(1)
        assertEquals(listOf("虚构问题", "", "第一条", "我说的语音", "联系人的语音", "第二条"), messages.map { it.content })
        assertEquals(listOf(MessageType.TEXT, MessageType.IMAGE, MessageType.TEXT, MessageType.VOICE, MessageType.VOICE, MessageType.TEXT), messages.map { it.type })
        assertTrue("旧备份里没有通话", messages.none { it.type == MessageType.CALL } && db.callDao().listAll().isEmpty())
        assertTrue("导出时待投递的回复按已送达", messages.all { it.status == MessageStatus.SUCCESS })
        assertEquals(MessageOrigin.PROACTIVE, messages.last().origin)
        assertEquals(listOf(300L, 200L), messages.filter { it.type == MessageType.VOICE }.map { File(it.voicePath!!).length() })
        assertEquals(1024, File(messages.single { it.type == MessageType.IMAGE }.imagePath!!).length())

        val conversation = db.conversationDao().get(1)!!
        assertTrue(conversation.pinned && conversation.muted)
        assertEquals("草稿", conversation.draft)
        assertEquals(512L, File(conversation.backgroundPath!!).length())
        val memories = db.memoryDao().listByContact(1)
        assertEquals(listOf("虚构核心记忆" to true, "虚构近期记忆" to false), memories.map { it.content to it.core })
        assertEquals(setOf(UsagePurpose.CHAT, UsagePurpose.TTS, UsagePurpose.ASR), db.usageDao().listAll().map { it.purpose }.toSet())
        assertEquals(listOf("住在虚构市"), db.globalMemoryDao().items().map { it.content })
        assertEquals("测试用户", settings.v.nickname)
        assertEquals(ReplyPreferences(5, 20, 2, 4, false, 3, 6), settings.v.replyPreferences)
        assertEquals(VoiceSettings(ReplyType.FOLLOW, VoiceSource.provider(tts.id), VoiceSource.provider(asr.id)), settings.v.voice)
        assertEquals("虚构音色", tts.voice)
        assertEquals(if (keyRestored) "sk-fixture-not-real" else null, db.providerDao().get(1)!!.apiKeyEncrypted?.let(cipher::decrypt))
        assertEquals(if (keyRestored) "sk-tts-not-real" else null, tts.apiKeyEncrypted?.let(cipher::decrypt))
    }

    @Test
    fun plainZipFrom072Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-072-plain.zip"))
        assertEquals(8, preview.manifest.formatVersion)
        assertEquals(702, preview.manifest.appVersionCode)
        assertEquals(2, preview.voiceCount)
        assertEquals(0, preview.callCount)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored(keyRestored = false)
    }

    @Test
    fun encryptedFileFrom072Restores_withKeys() = runBlocking {
        val preview = backup.inspect(fixture("aichat-072-encrypted.aichat"), "pw123456".toCharArray())
        assertTrue(preview.decryptedTemporary)
        assertEquals(8, preview.manifest.formatVersion)
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored(keyRestored = true)
    }

    @Test
    fun mergingA072Backup_intoAnEmptyPhone() = runBlocking {
        backup.restore(backup.inspect(fixture("aichat-072-plain.zip")), RestoreMode.MERGE, null)
        assertEquals(listOf("虚构甲", "虚构乙"), db.contactDao().listAll().map { it.name })
        assertEquals(6, db.messageDao().listByContact(1).size)
    }
}
