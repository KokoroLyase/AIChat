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
import isomeria.aichat.data.db.ProviderConfig
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * 0.6.0 导出的真实文件（`src/test/resources/backup060`，2026-11-11 在 v0.6.0 的临时工作树里用当时的导出代码和虚构数据生成）：
 * 明文 zip 和整文件加密 .aichat 都能在 0.7.0 恢复。消息都不是语音，联系人的消息发送类型用全局设置；
 * 0.6.0 的主动聊天开关、频率、来源和全局设置照常恢复，设备状态不在备份里；导出时待投递的回复按已送达。
 * 备份里没有语音服务商和语音设置：覆盖恢复保留本机的语音服务商（换了 id，选中的来源跟着换）和语音设置。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Backup060FixtureTest {
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
        listOf("avatars", "images", "background", "voice", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
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
        javaClass.classLoader!!.getResourceAsStream("backup060/$name")!!.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private suspend fun assertRestored(keyRestored: Boolean) {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        assertEquals("0.6.0 的主动聊天开关和频率原样恢复", listOf(true to ProactiveFrequency.HIGH, false to ProactiveFrequency.MEDIUM), contacts.map { it.proactiveEnabled to it.proactiveFrequency })
        assertTrue("消息发送类型用全局设置，音色用服务商默认", contacts.all { it.replyType == null && it.ttsVoice == null && it.ttsStyle == "" })
        assertEquals(listOf(true to false, false to true), contacts.map { it.useGlobalMemory to it.useReplyPreferences })
        assertEquals(0.8f, contacts[0].temperature)
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(contacts[0].avatarPath!!).readBytes().toList())

        val messages = db.messageDao().listByContact(1)
        assertEquals(listOf("虚构问题", "", "第一条", "主动来找你", "第二条"), messages.map { it.content })
        assertTrue("旧消息都不是语音", messages.none { it.type == MessageType.VOICE || it.voicePath != null })
        assertEquals(listOf(MessageOrigin.NORMAL, MessageOrigin.NORMAL, MessageOrigin.NORMAL, MessageOrigin.PROACTIVE, MessageOrigin.PROACTIVE), messages.map { it.origin })
        assertTrue("导出时待投递的回复按已送达", messages.all { it.status == MessageStatus.SUCCESS })
        assertEquals(1024, File(messages.single { it.type == MessageType.IMAGE }.imagePath!!).length())
        assertEquals("虚构思考", messages[2].reasoning)

        val first = db.conversationDao().get(1)!!
        assertTrue(first.pinned && first.muted)
        assertEquals("草稿", first.draft)
        assertEquals("第二条", first.lastMessagePreview)
        assertEquals("暂停计数是设备状态，从 0 开始", 0, first.proactiveUnanswered)
        assertEquals(List(512) { 7.toByte() }, File(first.backgroundPath!!).readBytes().toList())

        assertEquals("虚构记忆", db.memoryDao().listByContact(1).single().content)
        assertEquals(setOf(UsagePurpose.CHAT, UsagePurpose.MEMORY, UsagePurpose.PROACTIVE), db.usageDao().listAll().map { it.purpose }.toSet())
        assertEquals(listOf("住在虚构市"), db.globalMemoryDao().items().map { it.content })
        assertEquals("测试用户", settings.v.nickname)
        assertEquals(ThemeMode.DARK, settings.v.themeMode)
        assertEquals(ReplyPreferences(5, 20, 2, 4, false, 3, 6), settings.v.replyPreferences)
        assertEquals(ProactiveSettings(true, 22 * 60, 8 * 60, 12), settings.v.proactive)
        assertEquals(if (keyRestored) "sk-fixture-not-real" else null, db.providerDao().get(1)!!.apiKeyEncrypted?.let(cipher::decrypt))
        assertEquals("约定提醒不在备份里", 0, db.proactiveDao().reminderCount())
    }

    @Test
    fun plainZipFrom060Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-060-plain.zip"))
        assertEquals(6, preview.manifest.formatVersion)
        assertEquals(600, preview.manifest.appVersionCode)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored(keyRestored = false)
    }

    @Test
    fun encryptedFileFrom060Restores() = runBlocking {
        val preview = backup.inspect(fixture("aichat-060-encrypted.aichat"), "pw123456".toCharArray())
        assertTrue(preview.decryptedTemporary)
        assertEquals(6, preview.manifest.formatVersion)
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored(keyRestored = true)
        assertFalse(preview.file.exists())
    }

    /**
     * 本机已经配了语音服务商并选为来源：覆盖恢复 0.6.0 的备份（只有大语言模型服务商）后，本机的语音服务商还在、Key 还在，
     * 换了 id 不和备份里的服务商撞上，选中的来源跟着换；语音设置（发送类型）保留本机的
     */
    @Test
    fun overwritingWith060Backup_keepsLocalVoiceProvidersAndSettings() = runBlocking {
        val tts = db.providerDao().insert(ProviderConfig(kind = ProviderKind.TTS, provider = "mimo-audio", name = "MiMo 合成", baseUrl = "https://api.xiaomimimo.com/v1",
            apiKeyEncrypted = "enc:sk-tts", defaultModel = "mimo-v2.5-tts", presetId = "mimo", voice = "冰糖"))
        val asr = db.providerDao().insert(ProviderConfig(kind = ProviderKind.ASR, provider = "openai-audio", name = "硅基流动", baseUrl = "https://api.siliconflow.cn/v1",
            apiKeyEncrypted = "enc:sk-asr", defaultModel = "FunAudioLLM/SenseVoiceSmall", presetId = "siliconflow"))
        val local = VoiceSettings(ReplyType.VOICE, VoiceSource.provider(tts), VoiceSource.provider(asr))
        settings.v = settings.v.copy(voice = local)

        backup.restore(backup.inspect(fixture("aichat-060-plain.zip")), RestoreMode.OVERWRITE, null)
        assertRestored(keyRestored = false)

        val voice = db.providerDao().listConfigs().filter { it.kind != ProviderKind.LLM }
        assertEquals(listOf("MiMo 合成", "硅基流动"), voice.map { it.name })
        assertEquals(listOf("sk-tts", "sk-asr"), voice.map { cipher.decrypt(it.apiKeyEncrypted!!) })
        assertEquals("冰糖", voice[0].voice)
        assertEquals(listOf(1L), db.providerDao().llmList().map { it.id })
        assertEquals(ReplyType.VOICE, settings.v.voice.replyType)
        assertEquals(VoiceSource.provider(voice[0].id), settings.v.voice.ttsSource)
        assertEquals(VoiceSource.provider(voice[1].id), settings.v.voice.asrSource)
        assertNotEquals("备份里的服务商 id 不会被语音服务商占掉", 1L, voice[0].id)
    }

    /** 合并 0.6.0 的备份：本机的语音服务商和语音设置都不动 */
    @Test
    fun mergingA060Backup_keepsLocalVoiceProvidersAndSettings() = runBlocking {
        val tts = db.providerDao().insert(ProviderConfig(kind = ProviderKind.TTS, provider = "mimo-audio", baseUrl = "https://api.xiaomimimo.com/v1", defaultModel = "mimo-v2.5-tts"))
        val local = VoiceSettings(ReplyType.TEXT, VoiceSource.provider(tts), "")
        settings.v = settings.v.copy(voice = local)
        backup.restore(backup.inspect(fixture("aichat-060-plain.zip")), RestoreMode.MERGE, null)
        assertEquals(local, settings.v.voice)
        assertEquals(listOf(tts), db.providerDao().listByKind(ProviderKind.TTS).map { it.id })
        assertNull(db.contactDao().listAll().first().replyType)
    }
}
