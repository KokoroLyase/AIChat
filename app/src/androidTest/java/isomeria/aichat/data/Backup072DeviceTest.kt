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
 * 真机上恢复 v0.7.2 导出代码生成的明文和整文件加密备份（虚构数据，放在 androidTest assets/backup072）：
 * 真实 Keystore 和 PBKDF2。备份内层 v8，没有通话；语音消息、联系人单独选的语音来源（服务商 id 跟着换）、核心记忆照常恢复；
 * 加密文件里的 Key 用真实 Keystore 重新加密
 */
@RunWith(AndroidJUnit4::class)
class Backup072DeviceTest {
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
        assets.open("backup072/$name").use { input -> target.outputStream().use(input::copyTo) }
        copies += target
    }

    private suspend fun assertRestored() {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        val tts = db.providerDao().listByKind(ProviderKind.TTS).single()
        assertEquals(VoiceSource.provider(tts.id), contacts[0].ttsSource)
        assertEquals(VoiceSource.of(VoiceSource.OFFLINE_ASR), contacts[0].asrSource)
        assertEquals(ReplyType.VOICE, contacts[0].replyType)
        val messages = db.messageDao().listByContact(1)
        assertEquals(listOf("虚构问题", "", "第一条", "我说的语音", "联系人的语音", "第二条"), messages.map { it.content })
        assertTrue(messages.none { it.type == MessageType.CALL })
        assertTrue(messages.all { it.status == MessageStatus.SUCCESS })
        assertEquals(MessageOrigin.PROACTIVE, messages.last().origin)
        assertEquals(listOf(300L, 200L), messages.filter { it.type == MessageType.VOICE }.map { File(it.voicePath!!).length() })
        assertEquals(listOf(true, false), db.memoryDao().listByContact(1).map { it.core })
        val conversation = db.conversationDao().get(1)!!
        assertTrue(conversation.pinned && conversation.muted)
        assertEquals(512L, File(conversation.backgroundPath!!).length())
        assertEquals(VoiceSource.provider(tts.id), settings.v.voice.ttsSource)
    }

    @Test
    fun plainZipFrom072Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-072-plain.zip"))
        assertEquals(8, preview.manifest.formatVersion)
        assertEquals(2, preview.voiceCount)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored()
    }

    @Test
    fun encryptedFileFrom072Restores_keysUnderRealKeystore() = runBlocking {
        val preview = backup.inspect(fixture("aichat-072-encrypted.aichat"), "pw123456".toCharArray())
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored()
        assertEquals("sk-fixture-not-real", cipher.decrypt(db.providerDao().get(1)!!.apiKeyEncrypted!!))
        assertEquals("sk-tts-not-real", cipher.decrypt(db.providerDao().listByKind(ProviderKind.TTS).single().apiKeyEncrypted!!))
        assertFalse(preview.file.exists())
    }

    /** 用 App 自己的代码建服务商的“真实形态”备份（assets/backup072real）：v0.7.2 自己都读不了，0.8.0 在真机上要能恢复 */
    @Test
    fun realisticEncryptedFileFrom072_withVoiceProviders_restores() = runBlocking {
        val target = File(context.cacheDir, "real.aichat").also { f -> assets.open("backup072real/aichat-072-realistic-encrypted.aichat").use { input -> f.outputStream().use(input::copyTo) } }
        val preview = backup.inspect(target, "pw123456".toCharArray())
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        val voice = db.providerDao().listConfigs().filter { it.kind != ProviderKind.LLM }
        assertEquals(3, voice.size)
        assertTrue(voice.all { it.compatibility == "" })
        assertEquals("sk-fake-asr", cipher.decrypt(db.providerDao().listByKind(ProviderKind.ASR).single().apiKeyEncrypted!!))
    }
}
