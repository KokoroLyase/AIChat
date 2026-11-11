package isomeria.aichat.data.reply

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.Clock
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.core.VoiceSettingsSource
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.llm.ChatRole
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.ChatMessageActions
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.GlobalMemoryRepository
import isomeria.aichat.data.repo.UsageRepository
import isomeria.aichat.data.repo.VoiceRepository
import isomeria.aichat.data.voice.AsrRequest
import isomeria.aichat.data.voice.AsrResult
import isomeria.aichat.data.voice.AudioCodec
import isomeria.aichat.data.voice.TtsAudio
import isomeria.aichat.data.voice.TtsRequest
import isomeria.aichat.data.voice.VoiceProtocol
import isomeria.aichat.data.voice.VoiceService
import isomeria.aichat.data.voice.WavFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
import java.time.ZoneId

/**
 * 语音消息在回复流程里（0.7.0）：先识别再请求模型、识别失败不请求、重试再识别；发送类型决定回文字还是语音；
 * 合成成功才投递、失败降级为文字且不重复请求模型；主动消息的语音规则。语音服务和编码都是假的，只用本机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoiceReplyTest {
    private val context = RuntimeEnvironment.getApplication()
    private val start = 1_800_000_000_000L
    private lateinit var db: AppDatabase
    private lateinit var h: ReplyHarness
    private lateinit var engine: ReplyEngine
    private lateinit var pipeline: VoicePipeline
    private var contactId = 0L
    private var ttsId = 0L
    private var asrId = 0L

    private val settings = object : VoiceSettingsSource {
        var value = VoiceSettings()
        override fun voiceSettings() = value
        override fun setVoiceSettings(value: VoiceSettings) { this.value = value }
    }

    private val api = object : VoiceService {
        val tts = mutableListOf<TtsRequest>()
        val asr = mutableListOf<AsrRequest>()
        var asrText = "你好呀"
        var asrFailure: Exception? = null
        /** 第几次合成失败（从 1 数），空表示都成功 */
        var ttsFailOn = emptySet<Int>()
        override suspend fun synthesize(protocol: String, endpoint: LlmEndpoint, request: TtsRequest): TtsAudio {
            tts += request
            if (tts.size in ttsFailOn) throw LlmException(LlmError.RATE_LIMIT, 429)
            return TtsAudio(byteArrayOf(0x49, 0x44, 0x33, 1, 2, 3), "mp3")
        }
        override suspend fun transcribe(protocol: String, endpoint: LlmEndpoint, request: AsrRequest): AsrResult {
            asr += request
            asrFailure?.let { throw it }
            return AsrResult(asrText, 3.0)
        }
    }

    private val codec = object : AudioCodec {
        var encodeOk = true
        override fun encodeAac(wav: File, out: File): Boolean {
            if (!encodeOk) return false
            out.writeBytes(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray())
            return true
        }
        override fun durationMillis(file: File) = 2_000L
    }

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }

    @Before
    fun setUp() {
        listOf("voice").forEach { File(context.filesDir, it).deleteRecursively() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        h = ReplyHarness(context, db, ZoneId.of("Asia/Shanghai"), start)
        runBlocking {
            db.providerDao().insert(ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "测试", baseUrl = "https://example.com/v1", apiKeyEncrypted = "enc:k", defaultModel = "m"))
            db.providerDao().upsertModels(listOf(ProviderModel(1, "m", "M")))
            ttsId = db.providerDao().insert(ProviderConfig(kind = ProviderKind.TTS, provider = VoiceProtocol.CHAT_AUDIO, name = "合成", baseUrl = "https://tts.example.com/v1", apiKeyEncrypted = "enc:t", defaultModel = "mimo-v2.5-tts", voice = "mimo_default"))
            asrId = db.providerDao().insert(ProviderConfig(kind = ProviderKind.ASR, provider = VoiceProtocol.OPENAI_AUDIO, name = "识别", baseUrl = "https://asr.example.com/v1", apiKeyEncrypted = "enc:a", defaultModel = "sense"))
            contactId = db.contactDao().insert(Contact(name = "小雨", avatarColor = 0, providerId = 1, model = "m", systemPrompt = "人设", createdAt = 1, updatedAt = 1, useReplyPreferences = false))
            db.conversationDao().upsert(Conversation(contactId = contactId, lastMessageTime = start - 10_000))
        }
        settings.value = VoiceSettings(ttsSource = VoiceSource.provider(ttsId), asrSource = VoiceSource.provider(asrId))
        val voices = VoiceRepository(db, cipher, settings, api, UsageRepository(db.usageDao()))
        pipeline = VoicePipeline(db, voices, codec, FileStorage(context), settings, { h.preferences }, Clock { h.now }, h.random)
        engine = ReplyEngine(
            db = db, contactDao = db.contactDao(), messageDao = db.messageDao(), conversationDao = db.conversationDao(), memoryDao = db.memoryDao(),
            providers = h.providers, usage = UsageRepository(db.usageDao()), nicknames = { h.nickname }, files = FileStorage(context),
            contextBuilder = h.contextBuilder, clock = Clock { h.now }, promptGlobals = GlobalMemoryRepository(db, { h.preferences }, Clock { h.now }),
            preferences = { h.preferences }, random = h.random, sleeper = h.sleeper, delivery = h.delivery, queue = h.queue,
            reminders = h.reminders, voice = pipeline,
        )
        h.provider.reply = "好的\n\n马上来"
    }

    @After
    fun tearDown() = db.close()

    private val wavBytes = WavFormat.wrap(ByteArray(WavFormat.BYTES_PER_SECOND * 2) { 3 })

    private suspend fun sendVoice(at: Long = start - 5_000): Pair<Long, File> {
        val wav = FileStorage(context).newVoiceFile("wav").apply { writeBytes(wavBytes) }
        FileStorage(context).commitAvatar(wav.absolutePath)
        val id = db.messageDao().insert(Message(contactId = contactId, role = Role.USER, type = MessageType.VOICE, voicePath = wav.absolutePath, voiceMillis = 2_000, voicePlayed = true, status = MessageStatus.SENDING, createdAt = at))
        return id to wav
    }

    private suspend fun sendText(text: String, at: Long = start - 4_000) =
        db.messageDao().insert(Message(contactId = contactId, role = Role.USER, type = MessageType.TEXT, content = text, status = MessageStatus.SENDING, createdAt = at))

    private suspend fun replies() = db.messageDao().listByContact(contactId).filter { it.role == Role.ASSISTANT }

    // ---------- 识别 ----------

    @Test
    fun userVoice_isTranscribedBeforeTheRequest_compressed_wavDeleted() = runBlocking {
        val (id, wav) = sendVoice()
        engine.run(contactId)
        assertEquals(1, api.asr.size)
        assertArrayEquals(wavBytes, api.asr.single().wav)
        assertEquals("sense", api.asr.single().model)
        val message = db.messageDao().get(id)!!
        assertEquals("你好呀", message.content)
        assertEquals(MessageStatus.SUCCESS, message.status)
        assertTrue("识别后压成 AAC", message.voicePath!!.endsWith(".m4a"))
        assertFalse("写库后删掉 WAV", wav.exists())
        val request = h.provider.requests.single()
        assertEquals(ChatRole.USER, request.messages.last().role)
        assertEquals("模型收到的是转写文字", "你好呀", request.messages.last().plainText())
        assertEquals(3L, db.usageDao().listAll().single { it.purpose == UsagePurpose.ASR }.promptTokens)
    }

    @Test
    fun compressionFailure_keepsWav() = runBlocking {
        codec.encodeOk = false
        val (id, wav) = sendVoice()
        engine.run(contactId)
        assertEquals(wav.absolutePath, db.messageDao().get(id)!!.voicePath)
        assertTrue(wav.exists())
        assertEquals(1, h.provider.requests.size)
    }

    @Test
    fun asrFailure_failsTheWholeTurn_noModelRequest_retryTranscribesAgain() = runBlocking {
        val (voice, _) = sendVoice()
        val text = sendText("还有这句")
        api.asrFailure = LlmException(LlmError.SERVER, 500)
        engine.run(contactId)
        assertTrue("识别失败不请求模型", h.provider.requests.isEmpty())
        for (id in listOf(voice, text)) db.messageDao().get(id)!!.let {
            assertEquals(MessageStatus.FAILED, it.status)
            assertEquals(FailReason.ASR_FAILED.code, it.errorCode)
        }
        assertEquals("", db.messageDao().get(voice)!!.content)

        api.asrFailure = null
        assertTrue(ChatMessageActions(db).retry(contactId))
        engine.run(contactId)
        assertEquals(2, api.asr.size)
        assertEquals("你好呀", db.messageDao().get(voice)!!.content)
        assertEquals(1, h.provider.requests.size)
        assertEquals("你好呀\n还有这句", h.provider.requests.single().messages.last().plainText())
    }

    @Test
    fun noAsrConfigured_failsWithUnavailable_noRequests() = runBlocking {
        settings.value = settings.value.copy(asrSource = "")
        val (voice, _) = sendVoice()
        engine.run(contactId)
        assertEquals(FailReason.ASR_UNAVAILABLE.code, db.messageDao().get(voice)!!.errorCode)
        assertTrue(api.asr.isEmpty())
        assertTrue(h.provider.requests.isEmpty())
    }

    @Test
    fun textOnlyTurn_doesNotTouchVoiceServices() = runBlocking {
        sendText("在吗")
        engine.run(contactId)
        assertTrue(api.asr.isEmpty())
        assertTrue("跟随模式、发的是文字：回文字，不合成", api.tts.isEmpty())
        assertTrue(replies().all { it.type == MessageType.TEXT })
    }
}
