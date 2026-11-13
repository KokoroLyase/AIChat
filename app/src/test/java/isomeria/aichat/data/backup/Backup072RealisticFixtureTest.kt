package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import isomeria.aichat.data.voice.VoiceProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * v0.7.2 的代码导出的“真实形态”备份（`src/test/resources/backup072real`，2026-11-13 在 v0.7.2 的临时工作树里生成）：
 * 服务商是用 App 自己的 ProviderRepository.create / VoiceRepository.create 建的（虚构的名字和 Key），
 * 三个大模型服务商、两个语音合成、一个语音识别，和用户实机验证时那份恢复不了的备份同样的形态。
 * 当时在 v0.7.2 里读这份明文文件就失败（InvalidBackupException：内容格式不对），0.8.0 要能读、能恢复。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Backup072RealisticFixtureTest {
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
        javaClass.classLoader!!.getResourceAsStream("backup072real/$name")!!.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private suspend fun assertRestored() {
        val configs = db.providerDao().listConfigs()
        assertEquals(listOf("DEEPSEEK", "MIMO", "MINIMAX"), configs.filter { it.kind == ProviderKind.LLM }.map { it.compatibility })
        val tts = configs.filter { it.kind == ProviderKind.TTS }
        val asr = configs.single { it.kind == ProviderKind.ASR }
        assertEquals(listOf(VoiceProtocol.CHAT_AUDIO, VoiceProtocol.MINIMAX_TTS), tts.map { it.provider })
        assertTrue("语音服务商原样恢复（没有兼容方式）", (tts + asr).all { it.compatibility == "" })
        val first = db.contactDao().listAll().single { it.name == "虚构甲" }
        assertEquals(VoiceSource.provider(tts.single { it.provider == VoiceProtocol.MINIMAX_TTS }.id), first.ttsSource)
        assertEquals(VoiceSource.provider(asr.id), first.asrSource)
        assertEquals(VoiceSource.provider(tts.single { it.provider == VoiceProtocol.CHAT_AUDIO }.id), settings.v.voice.ttsSource)
        val voice = db.messageDao().listByContact(first.id).single { it.voicePath != null }
        assertTrue(File(voice.voicePath!!).isFile)
    }

    @Test
    fun plainFile_thatV072ItselfCouldNotRead_restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-072-realistic-plain.zip"))
        assertEquals(8, preview.manifest.formatVersion)
        assertEquals(6, preview.data.providers.size)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored()
    }

    @Test
    fun encryptedFile_withKeys_restores() = runBlocking {
        val password = "pw123456"
        val preview = backup.inspect(fixture("aichat-072-realistic-encrypted.aichat"), password.toCharArray())
        backup.restore(preview, RestoreMode.OVERWRITE, password.toCharArray())
        assertRestored()
        val keys = db.providerDao().listConfigs().filter { it.kind != ProviderKind.LLM }.map { it.apiKeyEncrypted?.let(cipher::decrypt) }
        assertEquals(listOf("sk-fake-tts", "sk-fake-tts2", "sk-fake-asr"), keys)
    }

    @Test
    fun mergeIntoAPhoneWithData_works() = runBlocking {
        backup.restore(backup.inspect(fixture("aichat-072-realistic-plain.zip")), RestoreMode.MERGE, null)
        assertRestored()
    }
}
