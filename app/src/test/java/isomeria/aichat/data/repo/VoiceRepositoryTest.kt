package isomeria.aichat.data.repo

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.core.VoiceSettingsSource
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.voice.SystemTtsInfo
import isomeria.aichat.data.voice.SystemTtsStatus
import isomeria.aichat.data.voice.AsrRequest
import isomeria.aichat.data.voice.AsrResult
import isomeria.aichat.data.voice.TtsAudio
import isomeria.aichat.data.voice.TtsRequest
import isomeria.aichat.data.voice.VoiceProtocol
import isomeria.aichat.data.voice.VoiceService
import isomeria.aichat.data.voice.WavFormat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 语音服务商的配置和选用：分别选合成和识别、删掉正在用的、没配置时不可用、联系人的音色和语气、用量单独分类。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var voices: VoiceRepository
    private val systemTts = isomeria.aichat.data.voice.FakeSystemTts()
    private val localPrefs = isomeria.aichat.data.voice.FakeLocalVoicePrefs()
    private val offlineAsr = isomeria.aichat.data.voice.FakeOfflineAsr()
    private val models by lazy { isomeria.aichat.data.voice.testModelStore(java.io.File(RuntimeEnvironment.getApplication().filesDir, "model-test").apply { deleteRecursively() }) }

    private val settings = object : VoiceSettingsSource {
        val flow = MutableStateFlow(VoiceSettings())
        override fun voiceSettings() = flow.value
        override fun setVoiceSettings(value: VoiceSettings) { flow.value = value }
    }
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }
    private val api = object : VoiceService {
        val tts = mutableListOf<Triple<String, LlmEndpoint, TtsRequest>>()
        val asr = mutableListOf<Pair<String, AsrRequest>>()
        var seconds: Double? = null
        override suspend fun synthesize(protocol: String, endpoint: LlmEndpoint, request: TtsRequest): TtsAudio {
            tts += Triple(protocol, endpoint, request)
            return TtsAudio(byteArrayOf(1), "mp3")
        }
        override suspend fun transcribe(protocol: String, endpoint: LlmEndpoint, request: AsrRequest): AsrResult {
            asr += protocol to request
            return AsrResult("好", seconds)
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).addCallback(AppDatabase.seedCallback).allowMainThreadQueries().build()
        voices = VoiceRepository(db, cipher, settings, api, UsageRepository(db.usageDao()), systemTts, localPrefs, offlineAsr, models)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun tts(name: String = "MiMo", key: String? = "sk-1", protocol: String = VoiceProtocol.CHAT_AUDIO, voice: String = "mimo_default") =
        voices.create(ProviderKind.TTS, protocol, name, "https://api.xiaomimimo.com/v1", key, "mimo-v2.5-tts", voice, "mimo")

    private val contact = Contact(id = 7, name = "甲", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1)

    @Test
    fun firstProviderOfAKindIsSelected_secondIsNot_kindsAreSeparate() = runBlocking {
        val first = tts()
        val second = tts("另一个")
        assertEquals(first, voices.selected(ProviderKind.TTS)!!.id)
        assertNull("合成和识别分别选", voices.selected(ProviderKind.ASR))
        val asr = voices.create(ProviderKind.ASR, VoiceProtocol.OPENAI_AUDIO, "硅基", "https://api.siliconflow.cn/v1", "sk-2", "FunAudioLLM/SenseVoiceSmall", "", "siliconflow")
        assertEquals(VoiceSettings(ttsSource = VoiceSource.provider(first), asrSource = VoiceSource.provider(asr)), settings.flow.value)
        voices.select(ProviderKind.TTS, second)
        assertEquals(second, voices.selected(ProviderKind.TTS)!!.id)
        val observed = voices.observe(ProviderKind.TTS, settings.flow).first()
        assertEquals(listOf(first, second), observed.providers.map { it.id })
        assertEquals(second, observed.selectedId)
        assertEquals("大语言模型服务商不受影响", listOf(1L), db.providerDao().llmList().map { it.id })
        assertEquals(listOf(first, second), voices.list(ProviderKind.TTS).map { it.id })
    }

    @Test
    fun deletingSelectedProvider_clearsSelection_deletingOtherKeepsIt() = runBlocking {
        val first = tts()
        val second = tts("另一个")
        voices.delete(second)
        assertEquals(first, voices.selected(ProviderKind.TTS)!!.id)
        voices.delete(first)
        assertNull(voices.selected(ProviderKind.TTS))
        assertEquals("", settings.flow.value.ttsSource)
        assertFalse(voices.available(ProviderKind.TTS))
    }

    @Test
    fun unavailableReasons_noSourceNoKeyBadUrl() = runBlocking {
        suspend fun reason(): VoiceUnavailableException.Reason? = try {
            voices.synthesize(contact, "x"); null
        } catch (e: VoiceUnavailableException) { e.reason }
        assertEquals(VoiceUnavailableException.Reason.NO_SOURCE, reason())
        val id = tts(key = null)
        assertEquals(VoiceUnavailableException.Reason.NO_KEY, reason())
        val config = db.providerDao().get(id)!!
        db.providerDao().update(config.copy(apiKeyEncrypted = "enc:sk", baseUrl = "http://evil.example.com"))
        assertEquals("只用 https", VoiceUnavailableException.Reason.BAD_URL, reason())
        db.providerDao().update(config.copy(apiKeyEncrypted = "enc:sk"))
        assertTrue(voices.available(ProviderKind.TTS))
        // 选用的 id 是大语言模型服务商：类型不对也算没有选
        settings.setVoiceSettings(settings.flow.value.copy(ttsSource = VoiceSource.provider(1)))
        assertEquals(VoiceUnavailableException.Reason.NO_SOURCE, reason())
        assertTrue(api.tts.isEmpty())
    }

    @Test
    fun synthesize_usesContactVoiceOrProviderDefault_styleOnlyForChatAudio() = runBlocking {
        val chat = tts(voice = "mimo_default")
        voices.synthesize(contact.copy(ttsVoice = "苏打", ttsStyle = "慢慢说"), "你好")
        voices.synthesize(contact.copy(ttsVoice = " ", ttsStyle = ""), "再见")
        assertEquals(listOf("苏打", "mimo_default"), api.tts.map { it.third.voice })
        assertEquals("慢慢说", api.tts[0].third.style)
        assertEquals("mimo-v2.5-tts", api.tts[0].third.model)
        assertEquals("sk-1", api.tts[0].second.apiKey)
        val standard = tts("标准", protocol = VoiceProtocol.OPENAI_AUDIO, voice = "alloy")
        voices.select(ProviderKind.TTS, standard)
        voices.synthesize(contact.copy(ttsStyle = "慢慢说"), "标准接口")
        assertEquals("标准接口没有语气要求", "", api.tts.last().third.style)
        assertEquals(VoiceProtocol.OPENAI_AUDIO, api.tts.last().first)
        assertTrue(chat > 0)
    }

    @Test
    fun usage_recordedAsAsrSecondsAndTtsCharsSeparately() = runBlocking {
        voices.create(ProviderKind.ASR, VoiceProtocol.CHAT_AUDIO, "MiMo", "https://api.xiaomimimo.com/v1", "sk", "mimo-v2.5-asr", "", "mimo")
        val wav = WavFormat.wrap(ByteArray(WavFormat.BYTES_PER_SECOND * 2 + 100))
        voices.transcribe(7, wav)
        api.seconds = 4.2
        voices.transcribe(7, wav)
        voices.recordSynthesis(7, chars = 12, millis = 2_500)
        val rows = db.usageDao().listAll().associateBy { it.purpose }
        assertEquals("没报告时长时按 WAV 算（向上取整），报告了用它", 3L + 5L, rows[UsagePurpose.ASR]!!.promptTokens)
        assertEquals(2, rows[UsagePurpose.ASR]!!.requests)
        assertEquals(12L, rows[UsagePurpose.TTS]!!.promptTokens)
        assertEquals(3L, rows[UsagePurpose.TTS]!!.completionTokens)
        assertEquals(setOf(UsagePurpose.ASR, UsagePurpose.TTS), rows.keys)
    }

    @Test
    fun saveKeepsKeyUnlessReplaced_emptyClears() = runBlocking {
        val id = tts()
        voices.save(id, VoiceProtocol.OPENAI_AUDIO, "改名", "https://x.example.com/v1", null, "tts-2", "v")
        db.providerDao().get(id)!!.let {
            assertEquals("enc:sk-1", it.apiKeyEncrypted)
            assertEquals(VoiceProtocol.OPENAI_AUDIO, it.provider)
            assertEquals("tts-2", it.defaultModel)
            assertEquals("v", it.voice)
        }
        voices.save(id, VoiceProtocol.OPENAI_AUDIO, "改名", "https://x.example.com/v1", "", "tts-2", "v")
        assertNull(db.providerDao().get(id)!!.apiKeyEncrypted)
        try {
            voices.create(ProviderKind.TTS, "deepseek", "x", "https://x", null, "m", "", null)
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------- 0.7.1 系统语音（本机） ----------

    @Test
    fun systemTts_selection_isSeparateFromProviders_newProviderDoesNotTakeOver() = runBlocking {
        voices.selectLocal(ProviderKind.TTS, VoiceSource.SYSTEM_TTS, true)
        assertEquals("local:system-tts", settings.flow.value.ttsSource)
        assertEquals(VoiceSource.SYSTEM_TTS, voices.selectedLocal(ProviderKind.TTS))
        assertNull(voices.selected(ProviderKind.TTS))
        val id = tts()
        assertEquals("已经选用本机时，新建的服务商不自动选用", VoiceSource.SYSTEM_TTS, voices.selectedLocal(ProviderKind.TTS))
        voices.observe(ProviderKind.TTS, settings.flow).first().let {
            assertNull(it.selectedId)
            assertEquals(VoiceSource.SYSTEM_TTS, it.local)
        }
        voices.select(ProviderKind.TTS, id)
        voices.selectLocal(ProviderKind.TTS, VoiceSource.SYSTEM_TTS, false)
        assertEquals("关掉没在用的本机来源不影响服务商", id, voices.selected(ProviderKind.TTS)!!.id)
        voices.selectLocal(ProviderKind.TTS, VoiceSource.SYSTEM_TTS, true)
        voices.selectLocal(ProviderKind.TTS, VoiceSource.SYSTEM_TTS, false)
        assertEquals("", settings.flow.value.ttsSource)
    }

    @Test
    fun systemTts_availableFollowsEngine_wrongKindOrUnknownLocalIsNone() = runBlocking {
        voices.selectLocal(ProviderKind.TTS, VoiceSource.SYSTEM_TTS, true)
        assertTrue(voices.available(ProviderKind.TTS))
        systemTts.info = SystemTtsInfo(SystemTtsStatus.NO_CHINESE, "引擎")
        assertFalse(voices.available(ProviderKind.TTS))
        systemTts.info = SystemTtsInfo(SystemTtsStatus.NO_ENGINE)
        assertFalse(voices.available(ProviderKind.TTS))
        // 识别的本机来源写在合成里、以后版本的新来源：都按没有选处理
        settings.setVoiceSettings(VoiceSettings(ttsSource = "local:offline-asr", asrSource = "local:future-engine"))
        assertNull(voices.selectedLocal(ProviderKind.TTS))
        assertNull(voices.selectedLocal(ProviderKind.ASR))
        assertFalse(voices.available(ProviderKind.TTS))
        assertFalse(voices.available(ProviderKind.ASR))
    }

    @Test
    fun systemTts_synthesize_usesContactVoiceThenPrefs_notBillable_failureIsLlmException() = runBlocking {
        tts()
        voices.selectLocal(ProviderKind.TTS, VoiceSource.SYSTEM_TTS, true)
        localPrefs.systemTtsVoice = "zh-voice-b"
        val audio = voices.synthesize(contact, "你好")
        assertEquals("wav", audio.extension)
        assertFalse("本机合成不记用量", audio.billable)
        assertTrue(WavFormat.isWav(audio.bytes))
        voices.synthesize(contact.copy(ttsVoice = "zh-voice-a"), "再见")
        // 联系人填的是服务商的音色 ID（不在系统音色里）：用「系统语音」页选的，不是引擎默认
        voices.synthesize(contact.copy(ttsVoice = "mimo_default"), "服务商音色")
        localPrefs.systemTtsVoice = ""
        voices.synthesize(contact, "空")
        assertEquals(
            listOf("你好" to "zh-voice-b", "再见" to "zh-voice-a", "服务商音色" to "zh-voice-b", "空" to null),
            systemTts.calls,
        )
        assertTrue("不调用服务商", api.tts.isEmpty())
        systemTts.fail = true
        try {
            voices.synthesize(contact, "失败")
            fail()
        } catch (e: LlmException) {
            assertEquals(LlmError.UNKNOWN, e.error)
        }
    }

    // ---------- 0.7.1 离线识别（本机） ----------

    @Test
    fun offlineAsr_availableNeedsSupportAndModel_transcribeHasNoUsage() = runBlocking {
        voices.selectLocal(ProviderKind.ASR, VoiceSource.OFFLINE_ASR, true)
        assertEquals("local:offline-asr", settings.flow.value.asrSource)
        assertFalse("还没下模型", voices.available(ProviderKind.ASR))
        suspend fun reason(): VoiceUnavailableException.Reason? = try {
            voices.transcribe(7, WavFormat.wrap(ByteArray(3_200))); null
        } catch (e: VoiceUnavailableException) { e.reason }
        assertEquals(VoiceUnavailableException.Reason.NO_MODEL, reason())
        isomeria.aichat.data.voice.installFakeModel(models)
        assertTrue(voices.available(ProviderKind.ASR))
        offlineAsr.supported = false
        assertFalse("手机不支持", voices.available(ProviderKind.ASR))
        assertEquals(VoiceUnavailableException.Reason.UNSUPPORTED, reason())
        offlineAsr.supported = true
        val result = voices.transcribe(7, WavFormat.wrap(ByteArray(3_200)))
        assertEquals("离线识别的文字", result.text)
        assertEquals("0.1 秒 = 1600 个采样", 1_600, offlineAsr.calls.single().second)
        assertTrue(api.asr.isEmpty())
        assertTrue(db.usageDao().listAll().isEmpty())
    }

    @Test
    fun offlineAsr_engineFailureOrEmpty_isLlmException() = runBlocking {
        isomeria.aichat.data.voice.installFakeModel(models)
        voices.selectLocal(ProviderKind.ASR, VoiceSource.OFFLINE_ASR, true)
        for (setup in listOf<() -> Unit>({ offlineAsr.fail = true }, { offlineAsr.fail = false; offlineAsr.text = "" })) {
            setup()
            try {
                voices.transcribe(7, WavFormat.wrap(ByteArray(320)))
                fail()
            } catch (e: LlmException) {
                assertEquals(LlmError.UNKNOWN, e.error)
            }
        }
    }
}
