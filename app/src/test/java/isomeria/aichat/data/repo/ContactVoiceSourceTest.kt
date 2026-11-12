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
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.voice.AsrRequest
import isomeria.aichat.data.voice.AsrResult
import isomeria.aichat.data.voice.TtsAudio
import isomeria.aichat.data.voice.TtsRequest
import isomeria.aichat.data.voice.VoiceProtocol
import isomeria.aichat.data.voice.VoiceService
import isomeria.aichat.data.voice.WavFormat
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * 0.7.2 联系人单独选语音合成、识别来源：用联系人自己的，跟随时用全局的；自己的用不了时不换成全局的；
 * 删掉服务商或离线模型时，单独选了它的联系人改为跟随全局。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ContactVoiceSourceTest {
    private lateinit var db: AppDatabase
    private lateinit var voices: VoiceRepository
    private val systemTts = isomeria.aichat.data.voice.FakeSystemTts()
    private val offlineAsr = isomeria.aichat.data.voice.FakeOfflineAsr()
    private val models by lazy { isomeria.aichat.data.voice.testModelStore(java.io.File(RuntimeEnvironment.getApplication().filesDir, "model-test-contact").apply { deleteRecursively() }) }

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
        val tts = mutableListOf<Pair<String, LlmEndpoint>>()
        val asr = mutableListOf<Pair<String, LlmEndpoint>>()
        override suspend fun synthesize(protocol: String, endpoint: LlmEndpoint, request: TtsRequest): TtsAudio {
            tts += protocol to endpoint
            return TtsAudio(byteArrayOf(1), "mp3")
        }
        override suspend fun transcribe(protocol: String, endpoint: LlmEndpoint, request: AsrRequest): AsrResult {
            asr += protocol to endpoint
            return AsrResult("好")
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).addCallback(AppDatabase.seedCallback).allowMainThreadQueries().build()
        voices = VoiceRepository(db, cipher, settings, api, UsageRepository(db.usageDao()), systemTts, isomeria.aichat.data.voice.FakeLocalVoicePrefs(), offlineAsr, models)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun provider(kind: ProviderKind, protocol: String, url: String, key: String? = "sk") =
        voices.create(kind, protocol, url, url, key, "m", "", null)

    private suspend fun contact(name: String, tts: String? = null, asr: String? = null): Contact {
        val id = db.contactDao().insert(Contact(name = name, avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1, ttsSource = tts, asrSource = asr))
        return db.contactDao().get(id)!!
    }

    private val wav = WavFormat.wrap(ByteArray(3200))

    @Test
    fun contactOwnSourceWins_followUsesGlobal() = runBlocking {
        val global = provider(ProviderKind.TTS, VoiceProtocol.OPENAI_AUDIO, "https://global.test")
        val own = provider(ProviderKind.TTS, VoiceProtocol.CHAT_AUDIO, "https://own.test")
        assertEquals("第一个服务商成了全局的", VoiceSource.provider(global), settings.flow.value.ttsSource)
        val following = contact("跟随")
        val alone = contact("单独", tts = VoiceSource.provider(own))

        voices.synthesize(alone, "你好")
        voices.synthesize(following, "你好")
        assertEquals(listOf("https://own.test", "https://global.test"), api.tts.map { it.second.baseUrl })
        assertEquals(own, voices.selected(ProviderKind.TTS, alone)!!.id)
        assertEquals(global, voices.selected(ProviderKind.TTS, following)!!.id)
        assertEquals(global, voices.selected(ProviderKind.TTS)!!.id)

        // 单独选系统语音：在手机上合成，不发请求
        val system = contact("系统语音", tts = VoiceSource.of(VoiceSource.SYSTEM_TTS))
        assertFalse(voices.synthesize(system, "你好").billable)
        assertEquals(2, api.tts.size)
        assertEquals(VoiceSource.SYSTEM_TTS, voices.selectedLocal(ProviderKind.TTS, system))
    }

    @Test
    fun contactOwnSourceUnavailable_doesNotFallBackToGlobal() = runBlocking {
        provider(ProviderKind.TTS, VoiceProtocol.OPENAI_AUDIO, "https://global.test")
        val noKey = provider(ProviderKind.TTS, VoiceProtocol.CHAT_AUDIO, "https://own.test", key = null)
        val alone = contact("单独", tts = VoiceSource.provider(noKey))
        assertTrue("全局的可以用", voices.available(ProviderKind.TTS))
        assertFalse("联系人自己的没有 Key", voices.available(ProviderKind.TTS, alone))
        try {
            voices.synthesize(alone, "你好")
            fail("没有 Key 不能合成")
        } catch (e: VoiceUnavailableException) {
            assertEquals(VoiceUnavailableException.Reason.NO_KEY, e.reason)
        }
        assertTrue("回复内容没有发给全局的服务商", api.tts.isEmpty())
    }

    @Test
    fun transcribe_usesThisContactsAsrSource() = runBlocking {
        provider(ProviderKind.ASR, VoiceProtocol.OPENAI_AUDIO, "https://global.test")
        val own = provider(ProviderKind.ASR, VoiceProtocol.CHAT_AUDIO, "https://own.test")
        val alone = contact("单独", asr = VoiceSource.provider(own))
        val following = contact("跟随")
        voices.transcribe(alone.id, wav)
        voices.transcribe(following.id, wav)
        assertEquals(listOf(VoiceProtocol.CHAT_AUDIO, VoiceProtocol.OPENAI_AUDIO), api.asr.map { it.first })
        // 单独选了离线识别但模型没下：不换成全局的服务商
        val offline = contact("离线", asr = VoiceSource.of(VoiceSource.OFFLINE_ASR))
        try {
            voices.transcribe(offline.id, wav)
            fail("模型没下好")
        } catch (e: VoiceUnavailableException) {
            assertEquals(VoiceUnavailableException.Reason.NO_MODEL, e.reason)
        }
        assertEquals(2, api.asr.size)
        assertFalse(voices.available(ProviderKind.ASR, offline))
        assertTrue(voices.available(ProviderKind.ASR, following))
    }

    @Test
    fun deletingProvider_contactsThatChoseItFollowGlobal_othersUntouched() = runBlocking {
        val global = provider(ProviderKind.TTS, VoiceProtocol.OPENAI_AUDIO, "https://global.test")
        val doomed = provider(ProviderKind.TTS, VoiceProtocol.CHAT_AUDIO, "https://doomed.test")
        val asr = provider(ProviderKind.ASR, VoiceProtocol.OPENAI_AUDIO, "https://asr.test")
        val a = contact("甲", tts = VoiceSource.provider(doomed), asr = VoiceSource.provider(asr))
        val b = contact("乙", tts = VoiceSource.provider(global))
        val c = contact("丙", tts = VoiceSource.of(VoiceSource.SYSTEM_TTS))
        assertEquals(1, voices.contactsUsing(ProviderKind.TTS, VoiceSource.provider(doomed)))

        voices.delete(doomed)
        assertNull("改为跟随全局", db.contactDao().get(a.id)!!.ttsSource)
        assertEquals("识别来源不受影响", VoiceSource.provider(asr), db.contactDao().get(a.id)!!.asrSource)
        assertEquals(VoiceSource.provider(global), db.contactDao().get(b.id)!!.ttsSource)
        assertEquals(VoiceSource.of(VoiceSource.SYSTEM_TTS), db.contactDao().get(c.id)!!.ttsSource)
        assertEquals(0, voices.contactsUsing(ProviderKind.TTS, VoiceSource.provider(doomed)))
    }

    @Test
    fun releasingOfflineAsr_globalBecomesUnset_contactsFollowGlobal() = runBlocking {
        val asr = provider(ProviderKind.ASR, VoiceProtocol.OPENAI_AUDIO, "https://asr.test")
        voices.selectLocal(ProviderKind.ASR, VoiceSource.OFFLINE_ASR, true)
        val offline = contact("离线", asr = VoiceSource.of(VoiceSource.OFFLINE_ASR))
        val other = contact("服务商", asr = VoiceSource.provider(asr))
        assertEquals(1, voices.contactsUsing(ProviderKind.ASR, VoiceSource.of(VoiceSource.OFFLINE_ASR)))

        voices.releaseLocal(ProviderKind.ASR, VoiceSource.OFFLINE_ASR)
        assertEquals("", settings.flow.value.asrSource)
        assertNull(db.contactDao().get(offline.id)!!.asrSource)
        assertEquals(VoiceSource.provider(asr), db.contactDao().get(other.id)!!.asrSource)
    }
}
