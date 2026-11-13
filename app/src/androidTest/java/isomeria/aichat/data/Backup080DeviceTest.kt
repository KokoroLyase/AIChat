package isomeria.aichat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.backup.AppSettingsSnapshot
import isomeria.aichat.data.backup.BackupManager
import isomeria.aichat.data.backup.BackupModule
import isomeria.aichat.data.backup.BackupSettingsAccess
import isomeria.aichat.data.backup.RestoreMode
import isomeria.aichat.data.crypto.KeystoreSecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderKind
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
 * 真机上恢复 v0.8.0 导出代码生成的明文和整文件加密备份（虚构数据，放在 androidTest assets/backup080）：
 * 真实 Keystore 和 PBKDF2。备份内层 v9，有通话（每一句和录音），没有收藏、引用和译文；
 * 加密文件里的 Key 用真实 Keystore 重新加密
 */
@RunWith(AndroidJUnit4::class)
class Backup080DeviceTest {
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
            db.callDao().listAll().mapNotNull { it.audioPath }.forEach { File(it).delete() }
            db.conversationDao().backgroundPaths().forEach { File(it).delete() }
        }
        db.close()
        copies.forEach { it.delete() }
        backup.snapshotFile.delete()
    }

    private fun fixture(name: String) = File(context.cacheDir, name).also { target ->
        assets.open("backup080/$name").use { input -> target.outputStream().use(input::copyTo) }
        copies += target
    }

    private suspend fun assertRestored() {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        val tts = db.providerDao().listByKind(ProviderKind.TTS).single()
        assertEquals(VoiceSource.provider(tts.id), contacts[0].ttsSource)
        val messages = db.messageDao().listByContact(1)
        assertEquals(listOf("虚构问题", "", "第一条", "我说的语音", "联系人的语音", "", "第二条"), messages.map { it.content })
        assertTrue(messages.all { it.status == MessageStatus.SUCCESS })
        assertTrue(messages.all { it.quoteId == null && it.translation == null })
        assertEquals(listOf(300L, 200L), messages.filter { it.type == MessageType.VOICE }.map { File(it.voicePath!!).length() })
        val turns = db.callDao().turns(messages.single { it.type == MessageType.CALL }.id)
        assertEquals(listOf(CallTurnStatus.OK, CallTurnStatus.ASR_FAILED, CallTurnStatus.OK, CallTurnStatus.TTS_FAILED), turns.map { it.status })
        assertEquals(listOf(120L, 110L, 90L, null), turns.map { turn -> turn.audioPath?.let { File(it).length() } })
        val conversation = db.conversationDao().get(1)!!
        assertTrue(conversation.pinned && conversation.muted)
        assertEquals(512L, File(conversation.backgroundPath!!).length())
        assertTrue(db.favoriteDao().listAll().isEmpty())
    }

    @Test
    fun plainZipFrom080Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-080-plain.zip"))
        assertEquals(9, preview.manifest.formatVersion)
        assertEquals(1, preview.callCount)
        assertFalse(BackupModule.FAVORITES in preview.modules)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored()
    }

    @Test
    fun encryptedFileFrom080Restores_keysUnderRealKeystore() = runBlocking {
        val preview = backup.inspect(fixture("aichat-080-encrypted.aichat"), "pw123456".toCharArray())
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored()
        assertEquals("sk-fixture-not-real", cipher.decrypt(db.providerDao().get(1)!!.apiKeyEncrypted!!))
        assertEquals("sk-tts-not-real", cipher.decrypt(db.providerDao().listByKind(ProviderKind.TTS).single().apiKeyEncrypted!!))
        assertEquals("sk-asr-not-real", cipher.decrypt(db.providerDao().listByKind(ProviderKind.ASR).single().apiKeyEncrypted!!))
        assertFalse(preview.file.exists())
    }
}
