package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.core.VoiceSettingsSource
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ReplyType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatRequest
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.llm.RemoteModel
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.UsageRepository
import isomeria.aichat.data.repo.VoiceRepository
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import isomeria.aichat.data.voice.AsrRequest
import isomeria.aichat.data.voice.AsrResult
import isomeria.aichat.data.voice.TtsAudio
import isomeria.aichat.data.voice.TtsRequest
import isomeria.aichat.data.voice.VoiceProtocol
import isomeria.aichat.data.voice.VoiceService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
 * 用 App 自己建数据的代码（[ProviderRepository.create]、[VoiceRepository.create]）造出和真实手机上一样形态的数据，
 * 导出再恢复。之前的备份测试直接写数据库，语音服务商的兼容方式用了默认值，和 App 实际写的空串不一样，
 * 所以没发现带语音服务商的备份恢复不了（用户实机验证时发现）。
 *
 * 形态参照那份真实备份（只用了种类、格式和数量，没有用里面的内容）：三个大模型服务商（DeepSeek、MiMo、MiniMax 预设），
 * 两个语音合成（MiMo 聊天接口带音频、MiniMax），一个语音识别（MiMo）；联系人单独选了语音来源；有语音消息和通话。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackupRealisticDataTest {
    private val context = RuntimeEnvironment.getApplication()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:") else null
    }
    private val noLlm = object : LlmProvider {
        override val id = "openai"
        override suspend fun listModels(endpoint: LlmEndpoint): List<RemoteModel> = emptyList()
        override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = emptyFlow()
    }
    private val noVoice = object : VoiceService {
        override suspend fun synthesize(protocol: String, endpoint: LlmEndpoint, request: TtsRequest) = TtsAudio(byteArrayOf(1), "mp3")
        override suspend fun transcribe(protocol: String, endpoint: LlmEndpoint, request: AsrRequest) = AsrResult("", null)
    }

    /** 一台手机：数据库、设置、App 自己的仓库和备份 */
    private inner class Phone {
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).allowMainThreadQueries().build()
        var snapshot = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true, ReplyPreferences(), ProactiveSettings())
        val settingsAccess = object : BackupSettingsAccess {
            override fun read() = snapshot
            override fun write(snapshot: AppSettingsSnapshot) { this@Phone.snapshot = snapshot }
        }
        val voiceSettings = object : VoiceSettingsSource {
            override fun voiceSettings() = snapshot.voice
            override fun setVoiceSettings(value: VoiceSettings) { snapshot = snapshot.copy(voice = value) }
        }
        val providers = ProviderRepository(db, db.providerDao(), cipher, setOf(noLlm))
        val voices = VoiceRepository(
            db, cipher, voiceSettings, noVoice, UsageRepository(db.usageDao()),
            isomeria.aichat.data.voice.FakeSystemTts(), isomeria.aichat.data.voice.FakeLocalVoicePrefs(), isomeria.aichat.data.voice.FakeOfflineAsr(),
            isomeria.aichat.data.voice.testModelStore(File(context.filesDir, "model-test").apply { deleteRecursively() }),
        )
        val backup = BackupManager(context, db, FileStorage(context), cipher, settingsAccess, object : ReplyControl {
            override fun cancel(contactId: Long) = Unit
            override suspend fun isActive(contactId: Long) = false
            override fun cancelDelivery(contactId: Long) = Unit
            override suspend fun isDelivering(contactId: Long) = false
        })
    }

    private val phones = mutableListOf<Phone>()
    private fun phone() = Phone().also(phones::add)

    @Before
    fun setUp() {
        listOf("avatars", "images", "background", "voice", "calls", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    @After
    fun tearDown() = phones.forEach { it.db.close() }

    private class Ids(val mimo: Long, val minimax: Long, val ttsMimo: Long, val ttsMinimax: Long, val asrMimo: Long)

    /** 和真实手机一样的配置：通过 App 自己的代码建服务商，联系人单独选了语音来源，有语音消息和通话 */
    private suspend fun Phone.seedLikeARealPhone(): Ids {
        val mimo = providers.create("Xiaomi MiMo", "https://api.xiaomimimo.com/v1", "sk-mimo", presetId = "mimo", compatibility = "MIMO")
        providers.addManualModel(mimo, "mimo-v2.5")
        val minimax = providers.create("MiniMax", "https://api.minimaxi.com/v1", "sk-minimax", presetId = "minimax", compatibility = "MINIMAX")
        providers.addManualModel(minimax, "MiniMax-M2")
        val ttsMimo = voices.create(ProviderKind.TTS, VoiceProtocol.CHAT_AUDIO, "MiMo", "https://api.xiaomimimo.com/v1", "sk-tts", "mimo-v2.5-tts", "茉莉", "mimo")
        val ttsMinimax = voices.create(ProviderKind.TTS, VoiceProtocol.MINIMAX_TTS, "MiniMax", "https://api.minimaxi.com/v1", "sk-tts2", "speech-02", "female-1", "minimax")
        val asrMimo = voices.create(ProviderKind.ASR, VoiceProtocol.CHAT_AUDIO, "MiMo", "https://api.xiaomimimo.com/v1", "sk-asr", "mimo-v2.5", "", "mimo")
        db.contactDao().insert(
            Contact(
                id = 1, name = "甲", avatarColor = 1, providerId = mimo, model = "mimo-v2.5", createdAt = 10, updatedAt = 10,
                replyType = ReplyType.VOICE, ttsSource = VoiceSource.provider(ttsMinimax), asrSource = VoiceSource.provider(asrMimo),
            ),
        )
        db.contactDao().insert(Contact(id = 2, name = "乙", avatarColor = 2, providerId = minimax, model = "MiniMax-M2", createdAt = 11, updatedAt = 11))
        db.conversationDao().upsert(Conversation(contactId = 1, lastMessageTime = 400, lastMessagePreview = "通话"))
        db.conversationDao().upsert(Conversation(contactId = 2, lastMessageTime = 100, lastMessagePreview = "在吗"))
        val voice = FileStorage(context).newVoiceFile("mp3").apply { writeBytes(ByteArray(100) { 3 }) }
        val callAudio = FileStorage(context).newMediaFile(isomeria.aichat.data.repo.MediaKind.CALL, "m4a").apply { writeBytes(ByteArray(80) { 4 }) }
        FileStorage(context).commitAvatar(voice.absolutePath)
        FileStorage(context).commitAvatar(callAudio.absolutePath)
        db.messageDao().insertAll(
            listOf(
                Message(contactId = 2, role = Role.USER, type = MessageType.TEXT, content = "在吗", status = MessageStatus.SUCCESS, createdAt = 100),
                Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "语音", voicePath = voice.absolutePath, voiceMillis = 2_000, status = MessageStatus.SUCCESS, createdAt = 200),
                Message(id = 50, contactId = 1, role = Role.USER, type = MessageType.CALL, voiceMillis = 61_000, status = MessageStatus.SUCCESS, createdAt = 300),
            ),
        )
        db.callDao().insertAll(
            listOf(
                CallTurn(messageId = 50, seq = 0, role = Role.USER, text = "喂", audioPath = callAudio.absolutePath, audioMillis = 900, createdAt = 300),
                CallTurn(messageId = 50, seq = 1, role = Role.ASSISTANT, text = "你好", status = CallTurnStatus.TTS_FAILED, createdAt = 301),
            ),
        )
        voices.select(ProviderKind.TTS, ttsMimo)
        return Ids(mimo, minimax, ttsMimo, ttsMinimax, asrMimo)
    }

    private suspend fun Phone.exportTo(name: String, password: CharArray? = null): File {
        val file = File(context.cacheDir, name)
        backup.export(file.outputStream(), password)
        return file
    }

    @Test
    fun voiceProvidersAreStoredWithoutCompatibility_likeTheApp() = runBlocking {
        val source = phone()
        source.seedLikeARealPhone()
        val voice = source.db.providerDao().listConfigs().filter { it.kind != ProviderKind.LLM }
        assertEquals("App 建的语音服务商没有兼容方式；以后改了这里，备份恢复也要跟着看", listOf("", "", ""), voice.map { it.compatibility })
    }

    @Test
    fun plainBackup_overwriteOntoAFreshPhone_restoresEverything() = runBlocking {
        val source = phone()
        source.seedLikeARealPhone()
        val file = source.exportTo("realistic-plain.zip")
        val target = phone()
        val preview = target.backup.inspect(file)
        assertEquals(6, preview.data.providers.size)
        target.backup.restore(preview, RestoreMode.OVERWRITE, null)

        val configs = target.db.providerDao().listConfigs()
        assertEquals(listOf("DEEPSEEK", "MIMO", "MINIMAX"), configs.filter { it.kind == ProviderKind.LLM }.map { it.compatibility })
        val tts = configs.filter { it.kind == ProviderKind.TTS }
        val asr = configs.single { it.kind == ProviderKind.ASR }
        assertEquals(listOf(VoiceProtocol.CHAT_AUDIO, VoiceProtocol.MINIMAX_TTS), tts.map { it.provider })
        val first = target.db.contactDao().get(1)!!
        assertEquals("联系人单独选的语音来源换成恢复后的 id", VoiceSource.provider(tts.single { it.provider == VoiceProtocol.MINIMAX_TTS }.id), first.ttsSource)
        assertEquals(VoiceSource.provider(asr.id), first.asrSource)
        assertEquals(VoiceSource.provider(tts.single { it.provider == VoiceProtocol.CHAT_AUDIO }.id), target.snapshot.voice.ttsSource)
        assertTrue("语音服务商能用（选得到）", target.voices.selected(ProviderKind.TTS, first) != null)
        val turns = target.db.callDao().turns(50)
        assertEquals(listOf("喂", "你好"), turns.map { it.text })
        assertTrue(File(turns[0].audioPath!!).isFile)
        assertEquals(3, target.db.messageDao().listByContact(1).size + target.db.messageDao().listByContact(2).size)
    }

    @Test
    fun encryptedWholeFileWithKeys_restoresKeysToo() = runBlocking {
        val source = phone()
        source.seedLikeARealPhone()
        val password = "pw123456".toCharArray()
        val file = source.exportTo("realistic.aichat", password.copyOf())
        val target = phone()
        val preview = target.backup.inspect(file, password.copyOf())
        target.backup.restore(preview, RestoreMode.OVERWRITE, password.copyOf())
        val keys = target.db.providerDao().listConfigs().associate { it.name + it.kind to it.apiKeyEncrypted?.let(cipher::decrypt) }
        assertEquals("sk-tts", keys["MiMo" + ProviderKind.TTS])
        assertEquals("sk-asr", keys["MiMo" + ProviderKind.ASR])
        assertEquals("sk-minimax", keys["MiniMax" + ProviderKind.LLM])
    }

    @Test
    fun merge_ontoAPhoneThatAlreadyHasTheSameVoiceProviders_doesNotDuplicate() = runBlocking {
        val source = phone()
        source.seedLikeARealPhone()
        val file = source.exportTo("realistic-merge.zip")
        val target = phone()
        val local = target.voices.create(ProviderKind.TTS, VoiceProtocol.CHAT_AUDIO, "我的 MiMo", "https://api.xiaomimimo.com/v1", "sk-local", "mimo-v2.5-tts", "苏打", "mimo")
        target.backup.restore(target.backup.inspect(file), RestoreMode.MERGE, null)
        val tts = target.db.providerDao().listConfigs().filter { it.kind == ProviderKind.TTS }
        assertEquals("同一个 MiMo 合成不重复，MiniMax 合成加进来", 2, tts.size)
        assertEquals("本机原来的配置不变", "苏打", tts.single { it.id == local }.voice)
        val merged = target.db.contactDao().listAll().single { it.name == "甲" }
        assertEquals(VoiceSource.provider(tts.single { it.provider == VoiceProtocol.MINIMAX_TTS }.id), merged.ttsSource)
    }

    @Test
    fun exportedAgainAfterRestoring_readsTheSame() = runBlocking {
        val source = phone()
        source.seedLikeARealPhone()
        val middle = phone()
        middle.backup.restore(middle.backup.inspect(source.exportTo("first.zip")), RestoreMode.OVERWRITE, null)
        val second = middle.backup.inspect(middle.exportTo("second.zip"))
        assertEquals(6, second.data.providers.size)
        assertEquals(1, second.callCount)
        assertEquals(1, second.voiceCount)
    }
}
