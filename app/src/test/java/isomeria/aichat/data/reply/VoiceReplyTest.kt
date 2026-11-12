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
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.ReplyType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.llm.ChatRole
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.llm.OpenAiRequestBuilder
import isomeria.aichat.data.repo.ChatMessageActions
import isomeria.aichat.data.repo.ChatRepository
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

    private val systemTts = isomeria.aichat.data.voice.FakeSystemTts()
    private val localPrefs = isomeria.aichat.data.voice.FakeLocalVoicePrefs()
    private val offlineAsr = isomeria.aichat.data.voice.FakeOfflineAsr()
    private val models by lazy { isomeria.aichat.data.voice.testModelStore(java.io.File(RuntimeEnvironment.getApplication().filesDir, "model-test").apply { deleteRecursively() }) }

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
        val voices = VoiceRepository(db, cipher, settings, api, UsageRepository(db.usageDao()), systemTts, localPrefs, offlineAsr, models)
        pipeline = VoicePipeline(db, voices, codec, FileStorage(context), settings, { h.preferences }, Clock { h.now }, h.random)
        engine = ReplyEngine(
            db = db, contactDao = db.contactDao(), messageDao = db.messageDao(), conversationDao = db.conversationDao(), memoryDao = db.memoryDao(),
            providers = h.providers, usage = UsageRepository(db.usageDao()), nicknames = { h.nickname }, files = FileStorage(context),
            contextBuilder = h.contextBuilder, clock = Clock { h.now }, promptGlobals = GlobalMemoryRepository(db, { h.preferences }, Clock { h.now }),
            preferences = { h.preferences }, random = h.random, sleeper = h.sleeper, delivery = h.delivery, queue = h.queue,
            reminders = h.reminders, voice = pipeline,
        )
        h.voice = pipeline
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

    // ---------- 回文字还是语音 ----------

    private val longReply = listOf("今天加班到很晚，回来路上又下起了大雨", "伞还忘在公司了，只好一路跑回家", "整个人都淋透了，现在刚洗完澡出来", "你吃饭了没有呀，要不要一起点外卖").joinToString("\n")

    private suspend fun setContact(transform: (Contact) -> Contact) = db.contactDao().update(transform(db.contactDao().get(contactId)!!))

    @Test
    fun followMode_voiceIn_voiceOut_synthesizedThenDelivered() = runBlocking {
        sendVoice()
        engine.run(contactId)
        assertEquals("模型只请求一次", 1, h.provider.requests.size)
        replies().single().let {
            assertEquals(MessageType.VOICE, it.type)
            assertEquals("好的\n马上来", it.content)
            assertTrue(File(it.voicePath!!).isFile)
            assertEquals(2_000L, it.voiceMillis)
            assertFalse("对方的语音默认未听", it.voicePlayed)
        }
        assertEquals(listOf("好的\n马上来"), api.tts.map { it.text })
        assertEquals("mimo_default", api.tts.single().voice)
        assertTrue(contactId in h.queue.deliveries)
        h.delivery.run(contactId)
        assertEquals(MessageStatus.SUCCESS, replies().single().status)
        assertEquals("[VOICE]", db.conversationDao().get(contactId)!!.lastMessagePreview)
        db.usageDao().listAll().single { it.purpose == UsagePurpose.TTS }.let {
            assertEquals("合成按字数记", 6, it.promptTokens)
            assertEquals("和秒数", 2, it.completionTokens)
        }
    }

    @Test
    fun followMode_lastWaitingDecides() = runBlocking {
        sendVoice(at = start - 6_000)
        sendText("最后一条是文字")
        engine.run(contactId)
        assertTrue("跟随看最后一条：文字", replies().all { it.type == MessageType.TEXT })
        assertTrue(api.tts.isEmpty())
    }

    @Test
    fun voiceMode_textIn_voiceOut_contactTextOverridesGlobal() = runBlocking {
        settings.value = settings.value.copy(replyType = ReplyType.VOICE)
        sendText("在吗")
        engine.run(contactId)
        assertEquals(MessageType.VOICE, replies().single().type)

        setContact { it.copy(replyType = ReplyType.TEXT) }
        sendText("再来", at = h.now + 1)
        engine.run(contactId)
        assertEquals(MessageType.TEXT, replies().maxBy { it.createdAt }.type)
        assertEquals("联系人设为文字后不再合成", 1, api.tts.size)
    }

    @Test
    fun contactVoiceAndStyle_overrideProviderVoice() = runBlocking {
        setContact { it.copy(replyType = ReplyType.VOICE, ttsVoice = "default_zh", ttsStyle = "温柔一点") }
        sendText("在吗")
        engine.run(contactId)
        assertEquals("default_zh", api.tts.single().voice)
        assertEquals("温柔一点", api.tts.single().style)
    }

    @Test
    fun noTtsAvailable_voiceModeFallsBackToText_noTtsRequest() = runBlocking {
        settings.value = settings.value.copy(replyType = ReplyType.VOICE, ttsSource = "")
        sendVoice()
        engine.run(contactId)
        assertTrue(replies().all { it.type == MessageType.TEXT })
        assertTrue(api.tts.isEmpty())
    }

    /** 0.7.2：联系人单独选的合成来源用不了时回文字，不换成全局的（全局的可以用） */
    @Test
    fun contactOwnTtsUnavailable_voiceModeFallsBackToText_globalNotUsed() = runBlocking {
        val noKey = db.providerDao().insert(ProviderConfig(kind = ProviderKind.TTS, provider = VoiceProtocol.CHAT_AUDIO, name = "没 Key", baseUrl = "https://own.example.com/v1", defaultModel = "m"))
        settings.value = settings.value.copy(replyType = ReplyType.VOICE)
        setContact { it.copy(ttsSource = VoiceSource.provider(noKey)) }
        // 判断这一轮回不回语音时就按联系人自己的来源：用不了就直接回文字，不先写等合成的语音消息
        assertFalse(pipeline.replyAsVoice(db.contactDao().get(contactId)!!, lastWaitingIsVoice = false))
        setContact { it.copy(ttsSource = VoiceSource.provider(ttsId)) }
        assertTrue(pipeline.replyAsVoice(db.contactDao().get(contactId)!!, lastWaitingIsVoice = false))
        setContact { it.copy(ttsSource = VoiceSource.provider(noKey)) }
        sendText("在吗")
        engine.run(contactId)
        assertTrue(replies().all { it.type == MessageType.TEXT })
        assertTrue("回复内容没有发给全局的合成服务商", api.tts.isEmpty())
    }

    @Test
    fun longReply_twoVoices_partialFailure_degradesThatOne_keepsOrder_noSecondModelRequest() = runBlocking {
        h.provider.reply = longReply
        api.ttsFailOn = setOf(2)
        sendVoice()
        engine.run(contactId)
        val rows = replies().sortedWith(compareBy({ it.createdAt }, { it.id }))
        assertEquals(listOf(MessageType.VOICE, MessageType.TEXT), rows.map { it.type })
        assertTrue("失败的那条排在第一条之后", rows[1].createdAt > rows[0].createdAt)
        assertEquals(longReply.replace("\n", ""), rows.joinToString("") { it.content }.replace("\n", ""))
        assertEquals(2, api.tts.size)
        assertEquals(1, h.provider.requests.size)
        assertEquals("只留下合成成功的那个文件", 1, File(context.filesDir, "voice").listFiles()!!.count { it.extension == "mp3" })
    }

    @Test
    fun threeVoices_lastFails_staysLast() = runBlocking {
        val sentence = "这是一句比较长的话，用来测试语音回复分成好几段的情况。"
        h.provider.reply = List(24) { sentence }.joinToString("")
        api.ttsFailOn = setOf(3)
        // 合成要花时间：每次请求时钟往前走 3 秒
        val slow = object : VoiceService by api {
            override suspend fun synthesize(protocol: String, endpoint: LlmEndpoint, request: TtsRequest): TtsAudio {
                h.now += 3_000
                return api.synthesize(protocol, endpoint, request)
            }
        }
        val voices = VoiceRepository(db, cipher, settings, slow, UsageRepository(db.usageDao()), isomeria.aichat.data.voice.FakeSystemTts(), isomeria.aichat.data.voice.FakeLocalVoicePrefs(), offlineAsr, models)
        val slowPipeline = VoicePipeline(db, voices, codec, FileStorage(context), settings, { h.preferences }, Clock { h.now }, h.random)
        settings.value = settings.value.copy(replyType = ReplyType.VOICE)
        sendText("讲个长的")
        engineWith(slowPipeline).run(contactId)
        val rows = replies().sortedWith(compareBy({ it.createdAt }, { it.id }))
        assertEquals(listOf(MessageType.VOICE, MessageType.VOICE, MessageType.TEXT), rows.map { it.type })
        assertEquals(h.provider.reply, rows.joinToString("") { it.content }.replace("\n", ""))
    }

    private fun engineWith(pipeline: VoicePipeline) = ReplyEngine(
        db = db, contactDao = db.contactDao(), messageDao = db.messageDao(), conversationDao = db.conversationDao(), memoryDao = db.memoryDao(),
        providers = h.providers, usage = UsageRepository(db.usageDao()), nicknames = { h.nickname }, files = FileStorage(context),
        contextBuilder = h.contextBuilder, clock = Clock { h.now }, promptGlobals = GlobalMemoryRepository(db, { h.preferences }, Clock { h.now }),
        preferences = { h.preferences }, random = h.random, sleeper = h.sleeper, delivery = h.delivery, queue = h.queue,
        reminders = h.reminders, voice = pipeline,
    )

    @Test
    fun allSynthesisFails_reSplitsAsTextByReplyPreferences_noSecondModelRequest_notRetried() = runBlocking {
        setContact { it.copy(useReplyPreferences = true) }
        h.provider.reply = longReply
        api.ttsFailOn = setOf(1, 2)
        sendVoice()
        engine.run(contactId)
        val rows = replies().sortedWith(compareBy({ it.createdAt }, { it.id }))
        assertTrue(rows.all { it.type == MessageType.TEXT && it.voicePath == null })
        assertEquals("按回复偏好重新拆成一行一条", longReply.split("\n"), rows.map { it.content })
        assertEquals(1, h.provider.requests.size)
        assertTrue(db.messageDao().awaitingSynthesis(contactId).isEmpty())
        // 再跑一次回复任务：不会再合成
        engine.run(contactId)
        assertEquals(2, api.tts.size)
    }

    /** 0.7.2：全部合成失败时，单独设置了回复偏好的联系人按自己的重新拆成文字 */
    @Test
    fun allSynthesisFails_reSplitsByTheContactsOwnPreferences() = runBlocking {
        setContact { it.copy(useReplyPreferences = true, replyPreferences = h.preferences.copy(minCount = 1, maxCount = 1)) }
        h.provider.reply = longReply
        api.ttsFailOn = setOf(1, 2)
        sendVoice()
        engine.run(contactId)
        val rows = replies()
        assertTrue(rows.all { it.type == MessageType.TEXT })
        assertEquals("自己的最多条数是 1：都接在一条里", 1, rows.size)
    }

    @Test
    fun interruptedSynthesis_isResumedByTheNextRun_withoutModelRequest() = runBlocking {
        db.messageDao().insertAll(pipeline.heldRows(db.contactDao().get(contactId)!!, "刚才没说完的", "", 0, 0, start, 0, start))
        assertTrue("等合成的不送达", db.messageDao().duePending(contactId, Long.MAX_VALUE).isEmpty())
        engine.run(contactId)
        assertTrue(h.provider.requests.isEmpty())
        assertEquals(1, api.tts.size)
        assertEquals(MessageStatus.SUCCESS, replies().single().status)
        assertTrue(File(replies().single().voicePath!!).isFile)
    }

    @Test
    fun voicePipelinePresent_butReplyTypeOnly_requestIsIdenticalTo060() = runBlocking {
        sendText("在吗")
        val withoutVoice = OpenAiRequestBuilder.build(h.runAndRewind(contactId)).toString()
        settings.value = settings.value.copy(replyType = ReplyType.VOICE)
        engine.run(contactId)
        val withVoice = OpenAiRequestBuilder.build(h.provider.requests.last()).toString()
        assertEquals("语音模式只改变回复的形式，请求逐字不变", withoutVoice, withVoice)
        assertEquals(MessageType.VOICE, replies().single().type)
    }

    // ---------- 主动消息 ----------

    private suspend fun proactiveReady() {
        setContact { it.copy(proactiveEnabled = true, updatedAt = start - 10 * 3_600_000L) }
        db.conversationDao().upsert(Conversation(contactId = contactId, lastMessageTime = start - 5 * 3_600_000L))
        db.messageDao().insert(Message(contactId = contactId, role = Role.USER, type = MessageType.TEXT, content = "在吗", status = MessageStatus.SUCCESS, createdAt = start - 5 * 3_600_000L))
        h.provider.reply = "在忙吗"
    }

    @Test
    fun proactive_voiceModeSendsVoice() = runBlocking {
        proactiveReady()
        settings.value = settings.value.copy(replyType = ReplyType.VOICE)
        h.proactive.run(force = true)
        val sent = replies().filter { it.origin == MessageOrigin.PROACTIVE }
        assertEquals(MessageType.VOICE, sent.single().type)
        assertTrue(File(sent.single().voicePath!!).isFile)
    }

    @Test
    fun proactive_followModeSendsText() = runBlocking {
        proactiveReady()
        h.proactive.run(force = true)
        val sent = replies().filter { it.origin == MessageOrigin.PROACTIVE }
        assertEquals(MessageType.TEXT, sent.single().type)
        assertTrue(api.tts.isEmpty())
    }

    // ---------- 重新生成和取消 ----------

    private fun chat() = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), FileStorage(context), h.queue, h.notifications)

    @Test
    fun regenerate_deletesTheVoiceFilesOfTheOldReply() = runBlocking {
        h.provider.reply = longReply
        sendVoice()
        engine.run(contactId)
        h.delivery.run(contactId)
        val files = replies().map { File(it.voicePath!!) }
        assertEquals(2, files.size)
        assertTrue(files.all { it.isFile })
        chat().regenerate(contactId)
        assertTrue(replies().isEmpty())
        assertTrue("旧回复的语音文件删掉了", files.none { it.exists() })
        assertTrue(contactId in h.queue.enqueued)
    }

    @Test
    fun cancelReply_whileSynthesizing_deliversAsText() = runBlocking {
        db.messageDao().insertAll(pipeline.heldRows(db.contactDao().get(contactId)!!, "还没合成", "", 0, 0, start, 0, start))
        chat().cancelReply(contactId)
        assertEquals(MessageType.TEXT, replies().single().type)
        assertTrue(contactId in h.queue.deliveries)
        // 已经取消：还在路上的合成结果回来后不会再填进这条（还没送达时也一样）
        assertEquals(0, db.messageDao().finishSynthesis(replies().single().id, "x", 1, start))
        assertEquals(null, replies().single().voicePath)
        h.delivery.run(contactId)
        assertEquals(MessageStatus.SUCCESS, replies().single().status)
    }

    // ---------- 0.7.1 系统语音（本机） ----------

    private fun useSystemTts() {
        settings.value = settings.value.copy(replyType = ReplyType.VOICE, ttsSource = VoiceSource.of(VoiceSource.SYSTEM_TTS))
    }

    @Test
    fun systemTts_voiceReply_compressedToM4a_noUsage_noProviderRequest() = runBlocking {
        useSystemTts()
        localPrefs.systemTtsVoice = "zh-voice-b"
        sendText("在吗")
        engine.run(contactId)
        val reply = replies().single()
        assertEquals(MessageType.VOICE, reply.type)
        assertTrue("WAV 压成 AAC", reply.voicePath!!.endsWith(".m4a"))
        assertTrue(File(reply.voicePath!!).isFile)
        assertEquals("合成的 WAV 不留下", 0, File(context.filesDir, "voice").listFiles()!!.count { it.extension == "wav" })
        assertEquals(listOf("好的\n马上来" to "zh-voice-b"), systemTts.calls)
        assertTrue("不调用语音服务商", api.tts.isEmpty())
        assertTrue("本机合成不记用量", db.usageDao().listAll().none { it.purpose == UsagePurpose.TTS })
        h.delivery.run(contactId)
        assertEquals(MessageStatus.SUCCESS, replies().single().status)
    }

    @Test
    fun systemTts_compressionFails_keepsWav() = runBlocking {
        useSystemTts()
        codec.encodeOk = false
        sendText("在吗")
        engine.run(contactId)
        assertTrue(replies().single().voicePath!!.endsWith(".wav"))
        assertTrue(File(replies().single().voicePath!!).isFile)
        assertEquals("压缩失败的 m4a 不留下", 0, File(context.filesDir, "voice").listFiles()!!.count { it.extension == "m4a" })
    }

    @Test
    fun systemTts_failure_degradesToText_noSecondModelRequest() = runBlocking {
        useSystemTts()
        systemTts.fail = true
        sendText("在吗")
        engine.run(contactId)
        assertTrue(replies().isNotEmpty())
        assertTrue(replies().all { it.type == MessageType.TEXT })
        assertEquals(1, h.provider.requests.size)
        assertTrue(File(context.filesDir, "voice").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun systemTts_withoutChineseVoice_repliesText_withoutTrying() = runBlocking {
        useSystemTts()
        systemTts.info = isomeria.aichat.data.voice.SystemTtsInfo(isomeria.aichat.data.voice.SystemTtsStatus.NO_CHINESE, "引擎")
        sendText("在吗")
        engine.run(contactId)
        assertTrue(replies().all { it.type == MessageType.TEXT })
        assertTrue(systemTts.calls.isEmpty())
    }

    // ---------- 0.7.1 离线识别（本机） ----------

    @Test
    fun offlineAsr_transcribesOnThePhone_thenRequestsModel_noAsrUsage() = runBlocking {
        isomeria.aichat.data.voice.installFakeModel(models)
        settings.value = settings.value.copy(asrSource = VoiceSource.of(VoiceSource.OFFLINE_ASR))
        val (id, wav) = sendVoice()
        engine.run(contactId)
        assertTrue("不发给识别服务商", api.asr.isEmpty())
        assertEquals(models.dir, offlineAsr.calls.single().first)
        assertEquals(16_000, offlineAsr.calls.single().third)
        val message = db.messageDao().get(id)!!
        assertEquals("离线识别的文字", message.content)
        assertTrue(message.voicePath!!.endsWith(".m4a"))
        assertFalse(wav.exists())
        assertEquals("离线识别的文字", h.provider.requests.single().messages.last().plainText())
        assertTrue("本机识别不记用量", db.usageDao().listAll().none { it.purpose == UsagePurpose.ASR })
    }

    @Test
    fun offlineAsr_modelMissing_failsAsUnavailable_noModelRequest() = runBlocking {
        settings.value = settings.value.copy(asrSource = VoiceSource.of(VoiceSource.OFFLINE_ASR))
        val (voice, wav) = sendVoice()
        engine.run(contactId)
        assertEquals(FailReason.ASR_UNAVAILABLE.code, db.messageDao().get(voice)!!.errorCode)
        assertTrue(offlineAsr.calls.isEmpty())
        assertTrue(h.provider.requests.isEmpty())
        assertTrue("识别失败时 WAV 留着，重试再识别", wav.exists())
    }

    @Test
    fun offlineAsr_emptyResult_failsLikeAProviderFailure_retryWorks() = runBlocking {
        isomeria.aichat.data.voice.installFakeModel(models)
        settings.value = settings.value.copy(asrSource = VoiceSource.of(VoiceSource.OFFLINE_ASR))
        offlineAsr.text = "  "
        val (voice, _) = sendVoice()
        engine.run(contactId)
        assertEquals(FailReason.ASR_FAILED.code, db.messageDao().get(voice)!!.errorCode)
        assertTrue(h.provider.requests.isEmpty())
    }
}
