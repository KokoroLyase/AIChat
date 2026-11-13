package isomeria.aichat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.backup.AppSettingsSnapshot
import isomeria.aichat.data.backup.BackupManager
import isomeria.aichat.data.backup.BackupSettingsAccess
import isomeria.aichat.data.backup.RestoreMode
import isomeria.aichat.data.crypto.KeystoreSecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ReplyType
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
 * 真机上恢复 v0.6.0 导出代码生成的明文和整文件加密备份（虚构数据，放在 androidTest assets）：
 * 真实 Keystore 和 PBKDF2；旧消息都不是语音，联系人的消息发送类型用全局设置；0.6.0 的主动聊天开关和频率照常恢复。
 * 本机已经配好的语音服务商（真实 Keystore 加密的 Key）和语音设置在覆盖恢复后保留。
 */
@RunWith(AndroidJUnit4::class)
class Backup060DeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val cipher = KeystoreSecretCipher()
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupManager
    private val copies = mutableListOf<File>()
    private val settings = object : BackupSettingsAccess {
        var v = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true, ReplyPreferences(), ProactiveSettings())
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
            db.messageDao().allVoicePaths().forEach { File(it).delete() }
            db.conversationDao().backgroundPaths().forEach { File(it).delete() }
        }
        db.close()
        copies.forEach { it.delete() }
        backup.snapshotFile.delete()
    }

    private fun fixture(name: String) = File(context.cacheDir, name).also { target ->
        assets.open("backup060/$name").use { input -> target.outputStream().use(input::copyTo) }
        copies += target
    }

    private suspend fun assertRestored() {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        assertEquals(listOf(true to ProactiveFrequency.HIGH, false to ProactiveFrequency.MEDIUM), contacts.map { it.proactiveEnabled to it.proactiveFrequency })
        assertTrue(contacts.all { it.replyType == null && it.ttsVoice == null && it.ttsStyle == "" })
        val messages = db.messageDao().listByContact(1)
        assertEquals(listOf("虚构问题", "", "第一条", "主动来找你", "第二条"), messages.map { it.content })
        assertTrue(messages.none { it.type == MessageType.VOICE || it.voicePath != null })
        assertEquals(MessageOrigin.PROACTIVE, messages[3].origin)
        assertTrue(messages.all { it.status == MessageStatus.SUCCESS })
        assertEquals(1024, File(messages.single { it.type == MessageType.IMAGE }.imagePath!!).length())
        val conversation = db.conversationDao().get(1)!!
        assertTrue(conversation.pinned && conversation.muted)
        assertEquals(512L, File(conversation.backgroundPath!!).length())
        assertEquals(ProactiveSettings(true, 22 * 60, 8 * 60, 12), settings.v.proactive)
    }

    @Test
    fun plainZipFrom060Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-060-plain.zip"))
        assertEquals(6, preview.manifest.formatVersion)
        assertEquals(0, preview.voiceCount)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored()
    }

    @Test
    fun encryptedFileFrom060Restores_keepsLocalVoiceProviderKey() = runBlocking {
        val tts = db.providerDao().insert(
            ProviderConfig(kind = ProviderKind.TTS, compatibility = "", provider = "chat-audio", name = "合成", baseUrl = "https://api.xiaomimimo.com/v1",
                apiKeyEncrypted = cipher.encrypt("sk-tts-not-real"), defaultModel = "mimo-v2.5-tts", presetId = "mimo", voice = "mimo_default"),
        )
        settings.v = settings.v.copy(voice = VoiceSettings(ReplyType.VOICE, VoiceSource.provider(tts), ""))

        val preview = backup.inspect(fixture("aichat-060-encrypted.aichat"), "pw123456".toCharArray())
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored()
        assertEquals("sk-fixture-not-real", cipher.decrypt(db.providerDao().get(1)!!.apiKeyEncrypted!!))
        val voice = db.providerDao().listByKind(ProviderKind.TTS).single()
        assertEquals("sk-tts-not-real", cipher.decrypt(voice.apiKeyEncrypted!!))
        assertEquals(VoiceSource.provider(voice.id), settings.v.voice.ttsSource)
        assertEquals(ReplyType.VOICE, settings.v.voice.replyType)
        assertFalse(preview.file.exists())
    }
}
