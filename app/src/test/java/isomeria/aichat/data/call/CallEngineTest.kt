package isomeria.aichat.data.call

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.Clock
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.ContextLabels
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.core.VoiceSettingsSource
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallEndReason
import isomeria.aichat.data.db.CallTurnStatus
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
import isomeria.aichat.data.reply.ReplyHarness
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.GlobalMemoryRepository
import isomeria.aichat.data.repo.MessagePreviewer
import isomeria.aichat.data.repo.StorageOperations
import isomeria.aichat.data.repo.UsageRepository
import isomeria.aichat.data.repo.VoiceRepository
import isomeria.aichat.data.repo.mediaReferencesOf
import isomeria.aichat.data.voice.AsrRequest
import isomeria.aichat.data.voice.AsrResult
import isomeria.aichat.data.voice.AudioCodec
import isomeria.aichat.data.voice.CallFocus
import isomeria.aichat.data.voice.CallMicrophone
import isomeria.aichat.data.voice.CallSpeaker
import isomeria.aichat.data.voice.SpeechActivity
import isomeria.aichat.data.voice.TtsAudio
import isomeria.aichat.data.voice.TtsRequest
import isomeria.aichat.data.voice.VoiceProtocol
import isomeria.aichat.data.voice.VoiceService
import isomeria.aichat.data.voice.WavFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.8.0 通话流程：麦克风、判断说话（假的概率）、放音、识别、合成和大语言模型都是假的，只用本机。
 * 检查一轮的顺序（识别 → 回复 → 按句合成播放 → 再听）、失败的提示和自动挂断、打断、挂断时的清理、发起前的检查。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CallEngineTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val start = java.time.LocalDateTime.of(2026, 11, 1, 20, 0).atZone(zone).toInstant().toEpochMilli()
    private lateinit var db: AppDatabase
    private lateinit var h: ReplyHarness
    private lateinit var engine: CallEngine
    private lateinit var scope: CoroutineScope
    private var contactId = 0L
    private var ttsId = 0L
    private var asrId = 0L
    private val tracker = CallTracker()
    private val memory = CopyOnWriteArrayList<Long>()

    // ---------- 假的麦克风、判断说话、放音 ----------

    private val mic = object : CallMicrophone {
        @Volatile var onFrame: ((ShortArray, Int) -> Unit)? = null
        @Volatile var onError: (() -> Unit)? = null
        @Volatile var hearing = true
        var fail = false
        var stopped = 0
        override fun start(onFrame: (ShortArray, Int) -> Unit, onError: () -> Unit) {
            if (fail) throw IllegalStateException("microphone unavailable")
            this.onFrame = onFrame
            this.onError = onError
        }
        override fun setListening(listening: Boolean) { hearing = listening }
        override fun stop() { onFrame = null; stopped++ }

        /** 放 [millis] 毫秒“说话”（loud）或静音进麦克风，32 毫秒一段 */
        fun feed(millis: Long, loud: Boolean) {
            var left = (WavFormat.SAMPLE_RATE * millis / 1000).toInt()
            while (left > 0) {
                val n = minOf(512, left)
                onFrame?.invoke(ShortArray(n) { if (loud) 8000 else 0 }, n)
                left -= n
            }
        }

        fun say(millis: Long = 1_000) {
            feed(300, false)
            feed(millis, true)
            feed(1_000, false)
        }
    }

    private var vadReleased = 0
    private val vad = object : SpeechActivity {
        override val windowSamples = 512
        override fun probability(window: FloatArray) = if (window.any { it > 0.1f }) 0.9f else 0.05f
        override fun reset() = Unit
        override fun release() { vadReleased++ }
    }

    private val speaker = object : CallSpeaker {
        var began = 0
        var ended = 0
        var grant = true
        var loud = false
        @Volatile var focus: ((CallFocus) -> Unit)? = null
        val played = CopyOnWriteArrayList<File>()
        /** 为 true 时放音不会自己结束（检查“对方在说”时的打断和挂断） */
        @Volatile var hold = false
        @Volatile var pending: ((Boolean) -> Unit)? = null
        /** 为 true 时放第一句就出错（文件放不了） */
        @Volatile var failFirst = false
        var stops = 0
        override fun begin(speakerphone: Boolean, onFocus: (CallFocus) -> Unit): Boolean {
            if (!grant) return false
            began++
            loud = speakerphone
            focus = onFocus
            return true
        }
        override fun setSpeakerphone(speakerphone: Boolean) { loud = speakerphone }
        override fun play(file: File, onEnd: (completed: Boolean) -> Unit) {
            played += file
            val ok = !(failFirst && played.size == 1)
            if (hold) pending = onEnd else Thread { Thread.sleep(5); onEnd(ok) }.start()
        }
        override fun stopPlayback() { stops++; pending = null }
        override fun end() { ended++ }
    }

    private val host = object : CallHost {
        var running = false
        var name = ""
        override fun start(contactId: Long, contactName: String) { running = true; name = contactName }
        override fun stop() { running = false }
    }

    // ---------- 假的语音服务和编码 ----------

    private val api = object : VoiceService {
        val tts = CopyOnWriteArrayList<TtsRequest>()
        val asr = CopyOnWriteArrayList<AsrRequest>()
        @Volatile var asrText = "周末去爬山吧"
        @Volatile var asrFailure: Exception? = null
        @Volatile var ttsFail: (String) -> Boolean = { false }
        override suspend fun synthesize(protocol: String, endpoint: LlmEndpoint, request: TtsRequest): TtsAudio {
            tts += request
            if (ttsFail(request.text)) throw LlmException(LlmError.RATE_LIMIT, 429)
            return TtsAudio(byteArrayOf(0x49, 0x44, 0x33, 1, 2, 3), "mp3")
        }
        override suspend fun transcribe(protocol: String, endpoint: LlmEndpoint, request: AsrRequest): AsrResult {
            asr += request
            asrFailure?.let { throw it }
            return AsrResult(asrText, 1.0)
        }
    }

    private val codec = object : AudioCodec {
        override fun encodeAac(wav: File, out: File): Boolean {
            out.writeBytes(byteArrayOf(0, 0, 0, 0x20) + "ftypM4A ".toByteArray())
            return true
        }
        override fun durationMillis(file: File) = 1_500L
    }

    private val settings = object : VoiceSettingsSource {
        var value = VoiceSettings()
        override fun voiceSettings() = value
        override fun setVoiceSettings(value: VoiceSettings) { this.value = value }
    }

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }

    private val callHint = "现在是语音通话。"
    private lateinit var contextBuilder: ContextBuilder

    @Before
    fun setUp() {
        File(context.filesDir, FileStorage.CALLS_DIR).deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        h = ReplyHarness(context, db, zone, start)
        h.provider.reply = "好呀。去哪座山？"
        contextBuilder = ContextBuilder(
            ContextLabels(
                namePlaceholder = "{名称}",
                personaPlaceholderPrefix = "{在这里写人设",
                timeLine = "现在是 %1\$d年%2\$d月%3\$d日 %4\$s %5\$02d:%6\$02d。",
                nicknameLine = "对方的昵称是「%1\$s」。",
                weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"),
                imagePlaceholder = "[图片]",
                memoryHeader = "记忆：",
                memoryItem = "- %4\$s",
                replyStyle = "回复方式：每次 %1\$s 条，每条 %2\$d 字左右，用换行分隔。",
                callHint = callHint,
            ),
            zone,
        )
        runBlocking {
            db.providerDao().insert(ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "测试", baseUrl = "https://example.com/v1", apiKeyEncrypted = "enc:k", defaultModel = "m"))
            db.providerDao().upsertModels(listOf(ProviderModel(1, "m", "M")))
            ttsId = db.providerDao().insert(ProviderConfig(kind = ProviderKind.TTS, compatibility = "", provider = VoiceProtocol.CHAT_AUDIO, name = "合成", baseUrl = "https://tts.example.com/v1", apiKeyEncrypted = "enc:t", defaultModel = "mimo-v2.5-tts", voice = "v"))
            asrId = db.providerDao().insert(ProviderConfig(kind = ProviderKind.ASR, compatibility = "", provider = VoiceProtocol.OPENAI_AUDIO, name = "识别", baseUrl = "https://asr.example.com/v1", apiKeyEncrypted = "enc:a", defaultModel = "sense"))
            contactId = db.contactDao().insert(Contact(name = "小雨", avatarColor = 0, providerId = 1, model = "m", systemPrompt = "人设", createdAt = 1, updatedAt = 1, useReplyPreferences = false))
            db.conversationDao().upsert(Conversation(contactId = contactId))
        }
        settings.value = VoiceSettings(ttsSource = VoiceSource.provider(ttsId), asrSource = VoiceSource.provider(asrId))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        build()
    }

    private fun build() {
        val voices = VoiceRepository(
            db, cipher, settings, api, UsageRepository(db.usageDao()),
            isomeria.aichat.data.voice.FakeSystemTts(), isomeria.aichat.data.voice.FakeLocalVoicePrefs(), isomeria.aichat.data.voice.FakeOfflineAsr(),
            isomeria.aichat.data.voice.testModelStore(File(context.filesDir, "model-test").apply { deleteRecursively() }),
        )
        val files = FileStorage(context)
        val replier = CallReplier(
            db, h.providers, UsageRepository(db.usageDao()), { h.nickname }, files, contextBuilder, Clock { h.now },
            GlobalMemoryRepository(db, { h.preferences }, Clock { h.now }), h.reminders,
        )
        engine = CallEngine(
            db = db, records = CallRecords(db, MessagePreviewer.PLAIN), voices = voices, providers = h.providers, replier = replier,
            codec = codec, files = files, microphone = mic, activity = { vad }, speaker = speaker, host = host,
            delivery = h.delivery, replies = h.queue, memoryQueue = { memory += it }, clock = Clock { h.now }, tracker = tracker, scope = scope,
        )
    }

    @After
    fun tearDown() {
        runBlocking { engine.hangUpAndWait() }
        scope.cancel()
        db.close()
    }

    /** 等到条件成立（引擎在别的线程上跑），最多 5 秒 */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("等不到：$what（现在 ${engine.state.value}）")
            Thread.sleep(10)
        }
    }

    private fun phase() = engine.state.value?.phase

    private fun turns() = runBlocking { db.callDao().turns(engine.state.value!!.messageId) }

    private fun startCall(): Long {
        assertNull(runBlocking { engine.start(contactId) })
        return engine.state.value!!.messageId
    }

    private fun callsDir() = File(context.filesDir, FileStorage.CALLS_DIR)

    @Test
    fun oneTurn_recognizesRepliesSentenceBySentence_thenListensAgain() {
        val id = startCall()
        val call = runBlocking { db.messageDao().get(id)!! }
        assertEquals(MessageType.CALL, call.type)
        assertTrue(call.callOngoing)
        assertEquals("[CALL]", runBlocking { db.conversationDao().get(contactId)!!.lastMessagePreview })
        assertTrue(tracker.inCall(contactId) && host.running && speaker.began == 1)
        assertEquals("通知里写联系人名称", "小雨", host.name)
        assertEquals(CallPhase.LISTENING, phase())

        mic.say()
        await("一轮完成") { turns().size == 3 && phase() == CallPhase.LISTENING }
        // 识别：发出去的是 16 kHz WAV
        assertTrue(WavFormat.isWav(api.asr.single().wav))
        val all = turns()
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.ASSISTANT), all.map { it.role })
        assertEquals(listOf("周末去爬山吧", "好呀。", "去哪座山？"), all.map { it.text })
        assertTrue(all.all { it.status == CallTurnStatus.OK && it.audioPath != null && File(it.audioPath!!).parentFile!!.name == FileStorage.CALLS_DIR })
        assertEquals("用户这句压成 AAC", "m4a", File(all[0].audioPath!!).extension)
        // 按句合成、按顺序放
        assertEquals(listOf("好呀。", "去哪座山？"), api.tts.map { it.text })
        assertEquals(all.drop(1).map { File(it.audioPath!!) }, speaker.played.toList())
        // 请求：系统提示词末尾多了通话说明（在时间之前），最后一条是用户刚说的话
        val request = h.provider.requests.single()
        assertTrue(request.messages.first().plainText().contains("$callHint\n\n现在是 2026年11月1日"))
        assertEquals(ChatRole.USER, request.messages.last().role)
        assertEquals("周末去爬山吧", request.messages.last().plainText())
        assertTrue("又在听了", mic.hearing)
        // 用量：识别、合成、聊天都记在这个联系人
        val usage = runBlocking { db.usageDao().listAll() }.map { it.purpose }.toSet()
        assertEquals(setOf(UsagePurpose.ASR, UsagePurpose.TTS, UsagePurpose.CHAT), usage)
        assertTrue("写完库的音频不再固定", all.none { File(it.audioPath!!).canonicalPath in StorageOperations.pinnedPaths() })

        // 第二轮：上一轮的话在上下文里
        h.provider.reply = "那就去西湖边的山。"
        api.asrText = "你定吧"
        mic.say()
        await("第二轮") { turns().size == 5 && phase() == CallPhase.LISTENING }
        assertEquals(
            listOf("周末去爬山吧", "好呀。\n\n去哪座山？", "你定吧"),
            h.provider.requests.last().messages.drop(1).map { it.plainText() },
        )

        h.now += 95_000
        engine.hangUp()
        await("挂断") { phase() == CallPhase.ENDED }
        val ended = runBlocking { db.messageDao().get(id)!! }
        assertEquals(95_000L, ended.voiceMillis)
        assertEquals(CallEndReason.HANG_UP, CallEndReason.of(ended.errorCode))
        assertEquals("[CALL] 01:35", runBlocking { db.conversationDao().get(contactId)!!.lastMessagePreview })
        assertEquals(1, mic.stopped)
        assertEquals(1, speaker.ended)
        assertEquals(1, vadReleased)
        assertFalse(host.running)
        assertFalse(tracker.inCall(contactId))
        assertEquals("挂断后排一次自动记忆", listOf(contactId), memory.toList())
    }

    @Test
    fun tinyBudget_stillSendsWhatTheUserJustSaid() = runBlocking {
        db.contactDao().update(db.contactDao().get(contactId)!!.copy(contextTokens = 1_000, systemPrompt = "很长的人设。".repeat(160)))
        repeat(20) { db.messageDao().insert(Message(contactId = contactId, role = Role.USER, type = MessageType.TEXT, content = "很早以前的话".repeat(10), status = MessageStatus.SUCCESS, createdAt = start - 100_000 + it)) }
        startCall()
        mic.say()
        await("一轮完成") { turns().size == 3 && phase() == CallPhase.LISTENING }
        val request = h.provider.requests.single()
        assertEquals("预算放不下也带上用户刚说的话", "周末去爬山吧", request.messages.last().plainText())
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), request.messages.map { it.role })
    }

    @Test
    fun reminderMarker_isNotSpoken_butSaved() = runBlocking {
        db.contactDao().update(db.contactDao().get(contactId)!!.copy(proactiveEnabled = true))
        h.provider.reply = "好的，明早叫你。\n[提醒 2026-11-02 07:00 叫他起床]"
        startCall()
        mic.say()
        await("一轮完成") { turns().size == 2 && phase() == CallPhase.LISTENING }
        assertEquals(listOf("好的，明早叫你。"), api.tts.map { it.text })
        assertEquals(listOf("周末去爬山吧", "好的，明早叫你。"), turns().map { it.text })
        assertEquals(listOf("叫他起床"), db.proactiveDao().remindersOf(contactId).map { it.note })
    }

    @Test
    fun recognitionFails_keepsTheRecording_noModelRequest_threeInARowHangsUp() {
        val id = startCall()
        api.asrFailure = LlmException(LlmError.NETWORK)
        mic.say()
        await("识别失败") { turns().size == 1 && phase() == CallPhase.LISTENING }
        val failed = turns().single()
        assertEquals(CallTurnStatus.ASR_FAILED, failed.status)
        assertEquals("", failed.text)
        assertTrue("录音留着", File(failed.audioPath!!).isFile)
        assertEquals(CallNotice.ASR_FAILED, engine.state.value!!.notice)
        assertEquals(FailReason.NETWORK, engine.state.value!!.noticeReason)
        assertTrue(h.provider.requests.isEmpty())
        mic.say()
        await("第二次") { turns().size == 2 && phase() == CallPhase.LISTENING }
        mic.say()
        await("连续三次失败自动挂断") { phase() == CallPhase.ENDED }
        assertEquals(CallEndReason.FAILURES, CallEndReason.of(runBlocking { db.messageDao().get(id)!!.errorCode }))
    }

    @Test
    fun replyFails_showsTheReason_successResetsTheCount() {
        startCall()
        h.provider.failure = LlmException(LlmError.BALANCE, 402)
        mic.say()
        await("回复失败") { engine.state.value?.notice == CallNotice.REPLY_FAILED && phase() == CallPhase.LISTENING }
        assertEquals(FailReason.BALANCE, engine.state.value!!.noticeReason)
        assertEquals("用户的话留着，联系人没有话", listOf(Role.USER), turns().map { it.role })
        mic.say()
        await("又失败一次") { turns().size == 2 && phase() == CallPhase.LISTENING }
        h.provider.failure = null
        mic.say()
        await("成功") { turns().size == 5 && phase() == CallPhase.LISTENING }
        // 中间成功过：失败次数从头算，再失败两次也不挂断
        h.provider.failure = LlmException(LlmError.SERVER, 500)
        mic.say()
        await("失败") { turns().size == 6 && phase() == CallPhase.LISTENING }
        mic.say()
        await("再失败") { turns().size == 7 && phase() == CallPhase.LISTENING }
        assertEquals(CallPhase.LISTENING, phase())
    }

    @Test
    fun emptyReply_isAFailedTurn() {
        startCall()
        h.provider.reply = "  "
        mic.say()
        await("空回复") { engine.state.value?.notice == CallNotice.REPLY_FAILED && phase() == CallPhase.LISTENING }
        assertEquals(FailReason.EMPTY_REPLY, engine.state.value!!.noticeReason)
    }

    @Test
    fun oneSentenceFailsToSynthesize_itsTextIsKept_othersPlay() {
        startCall()
        api.ttsFail = { it.startsWith("好呀") }
        mic.say()
        await("一轮完成") { turns().size == 3 && phase() == CallPhase.LISTENING }
        val all = turns()
        assertEquals(listOf(CallTurnStatus.OK, CallTurnStatus.TTS_FAILED, CallTurnStatus.OK), all.map { it.status })
        assertNull(all[1].audioPath)
        assertEquals(1, speaker.played.size)
        assertEquals(CallNotice.TTS_FAILED, engine.state.value!!.notice)
    }

    @Test
    fun sentenceThatCannotBePlayed_keptAsTextOnly_withNotice() {
        startCall()
        speaker.failFirst = true
        mic.say()
        await("一轮完成") { turns().size == 3 && phase() == CallPhase.LISTENING }
        val all = turns()
        assertEquals(listOf(CallTurnStatus.OK, CallTurnStatus.TTS_FAILED, CallTurnStatus.OK), all.map { it.status })
        assertNull("放不出来的那句不留音频", all[1].audioPath)
        assertEquals(0L, all[1].audioMillis)
        assertEquals(CallNotice.TTS_FAILED, engine.state.value!!.notice)
        // 文件也删掉了：目录里只剩记录引用的
        await("音频删掉") { callsDir().listFiles().orEmpty().map { it.canonicalPath }.toSet() == all.mapNotNull { it.audioPath }.map { File(it).canonicalPath }.toSet() }
    }

    /**
     * 0.8.0 发布后报告过“通话中清理孤立文件会删掉还没写进记录的音频”，复核不是问题：通话文件创建时就固定，
     * 写进 call_turn 后才解除，孤立文件检查跳过固定着的文件。这里把这个行为锁住：对方在说第一句、第二句已经合成好排着队时，
     * 跑一遍和「存储空间 → 清理孤立文件」一样的预览和清理，排队的句子不会被列出、也不会被删，之后照常放完、写进记录。
     */
    @Test
    fun orphanCleanupDuringCall_keepsQueuedSentences() {
        startCall()
        h.provider.reply = "第一句。第二句。"
        speaker.hold = true
        mic.say()
        await("第一句在放、第二句已合成") { phase() == CallPhase.SPEAKING && speaker.pending != null && api.tts.size == 2 }
        val queued = callsDir().listFiles().orEmpty().map { it.canonicalPath }.toSet()
        val recorded = turns().mapNotNull { it.audioPath }.map { File(it).canonicalPath }.toSet()
        assertTrue("有还没写进记录的句子", (queued - recorded).isNotEmpty())

        val files = FileStorage(context)
        val references = runBlocking { mediaReferencesOf(db, null) }
        val preview = files.unreferenced(references)
        assertTrue("预览里没有通话的文件：$preview", preview.none { it.parentFile!!.name == FileStorage.CALLS_DIR })
        files.deleteUnreferenced(runBlocking { mediaReferencesOf(db, null) })
        assertTrue("清理后通话的文件都还在", queued.all { File(it).isFile })

        speaker.hold = false
        speaker.pending?.invoke(true)
        await("两句都放完、写进记录") { turns().size == 3 && phase() == CallPhase.LISTENING }
        assertTrue(turns().all { it.audioPath != null && File(it.audioPath!!).isFile })
    }

    @Test
    fun interruptWhileSpeaking_marksTheSentence_dropsTheRest_listensAgain() {
        startCall()
        h.provider.reply = "第一句。第二句。第三句。"
        speaker.hold = true
        mic.say()
        await("对方在说") { phase() == CallPhase.SPEAKING && speaker.pending != null }
        engine.interrupt()
        await("打断后在听") { phase() == CallPhase.LISTENING }
        val all = turns()
        assertEquals(listOf("周末去爬山吧", "第一句。"), all.map { it.text })
        assertEquals(CallTurnStatus.INTERRUPTED, all[1].status)
        assertTrue(speaker.stops >= 1)
        // 已经合成、没放的句子的音频删掉；目录里只剩记录引用的
        await("没放的音频删掉") { callsDir().listFiles().orEmpty().map { it.canonicalPath }.toSet() == all.mapNotNull { it.audioPath }.map { File(it).canonicalPath }.toSet() }
        assertTrue(mic.hearing)
        // 打断不算失败，也不会因为晚到的“这一轮完成”把下一轮弄乱
        speaker.hold = false
        mic.say()
        await("下一轮") { turns().size == 6 && phase() == CallPhase.LISTENING }
    }

    @Test
    fun hangUpWhileThinking_cancelsTheRequest_cleansUp() {
        val id = startCall()
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        h.provider.beforeReply = { waiting.await() }
        mic.say()
        await("对方在想") { phase() == CallPhase.THINKING }
        // 半双工：等回复期间说的话不听
        mic.say()
        Thread.sleep(200)
        assertEquals(1, api.asr.size)
        assertFalse(mic.hearing)
        h.now += 10_000
        runBlocking { tracker.hangUpFor(contactId) }
        assertEquals(CallPhase.ENDED, phase())
        assertEquals(10_000L, runBlocking { db.messageDao().get(id)!!.voiceMillis })
        assertEquals("只有用户的话", listOf(Role.USER), runBlocking { db.callDao().turns(id) }.map { it.role })
        assertTrue(api.tts.isEmpty())
        assertFalse(host.running)
        assertTrue("没有固定着的通话文件", StorageOperations.pinnedPaths().none { it.contains(File.separator + FileStorage.CALLS_DIR + File.separator) })
    }

    @Test
    fun muteIgnoresSpeech_finishNowSendsAtOnce() {
        startCall()
        engine.setMuted(true)
        await("静音") { engine.state.value!!.muted }
        mic.say()
        Thread.sleep(200)
        assertTrue("静音时不识别", api.asr.isEmpty())
        engine.setMuted(false)
        await("取消静音") { !engine.state.value!!.muted }
        mic.feed(300, false)
        mic.feed(1_000, true)
        await("在说") { phase() == CallPhase.HEARING }
        engine.finishSpeaking()
        await("说完了马上发") { api.asr.size == 1 }
    }

    @Test
    fun limits_idleAndMaximumDuration_andFocusLoss() {
        val id = startCall()
        h.now += CallEngine.IDLE_MILLIS
        await("没人说话自动挂断") { phase() == CallPhase.ENDED }
        assertEquals(CallEndReason.IDLE, CallEndReason.of(runBlocking { db.messageDao().get(id)!!.errorCode }))

        val second = startCall()
        h.now += CallEngine.MAX_CALL_MILLIS - CallEngine.WARN_BEFORE_MILLIS
        mic.say() // 有人说话，不算闲着
        await("提前提示") { engine.state.value?.notice == CallNotice.TIME_WARNING || turns().size == 3 }
        h.now += CallEngine.WARN_BEFORE_MILLIS
        await("到了最长时间") { phase() == CallPhase.ENDED }
        assertEquals(CallEndReason.TIME_LIMIT, CallEndReason.of(runBlocking { db.messageDao().get(second)!!.errorCode }))

        val third = startCall()
        speaker.focus!!.invoke(CallFocus.LOST)
        await("来电挂断") { phase() == CallPhase.ENDED }
        assertEquals(CallEndReason.AUDIO_LOST, CallEndReason.of(runBlocking { db.messageDao().get(third)!!.errorCode }))
    }

    @Test
    fun focusPausedThenResumed() {
        startCall()
        speaker.focus!!.invoke(CallFocus.PAUSED)
        await("暂停") { phase() == CallPhase.PAUSED }
        assertFalse(mic.hearing)
        speaker.focus!!.invoke(CallFocus.RESUMED)
        await("接着听") { phase() == CallPhase.LISTENING }
        assertTrue(mic.hearing)
    }

    @Test
    fun check_blocksWithReasons() = runBlocking {
        // 这个联系人还在回上一条
        val waiting = db.messageDao().insert(Message(contactId = contactId, role = Role.USER, type = MessageType.TEXT, content = "在吗", status = MessageStatus.SENDING, createdAt = start))
        assertEquals(CallBlock.BUSY_REPLY, engine.check(contactId))
        db.messageDao().delete(waiting)
        // 单独选的识别来源用不了：不换成全局的
        db.contactDao().update(db.contactDao().get(contactId)!!.copy(asrSource = VoiceSource.of(VoiceSource.OFFLINE_ASR)))
        assertEquals(CallBlock.CONTACT_ASR_UNAVAILABLE, engine.check(contactId))
        db.contactDao().update(db.contactDao().get(contactId)!!.copy(asrSource = null))
        settings.value = settings.value.copy(asrSource = "")
        assertEquals(CallBlock.ASR_UNAVAILABLE, engine.check(contactId))
        settings.value = settings.value.copy(asrSource = VoiceSource.provider(asrId))
        db.contactDao().update(db.contactDao().get(contactId)!!.copy(ttsSource = VoiceSource.provider(999)))
        assertEquals(CallBlock.CONTACT_TTS_UNAVAILABLE, engine.check(contactId))
        db.contactDao().update(db.contactDao().get(contactId)!!.copy(ttsSource = null))
        settings.value = settings.value.copy(ttsSource = "")
        assertEquals(CallBlock.TTS_UNAVAILABLE, engine.check(contactId))
        settings.value = settings.value.copy(ttsSource = VoiceSource.provider(ttsId))
        db.providerDao().update(db.providerDao().get(1)!!.copy(apiKeyEncrypted = null))
        assertEquals(CallBlock.NO_API_KEY, engine.check(contactId))
        db.providerDao().update(db.providerDao().get(1)!!.copy(apiKeyEncrypted = "enc:k"))
        assertNull(engine.check(contactId))
        // 拿不到声音、麦克风打不开：什么也不写
        speaker.grant = false
        assertEquals(CallBlock.AUDIO_BUSY, engine.start(contactId))
        speaker.grant = true
        mic.fail = true
        assertEquals(CallBlock.MICROPHONE, engine.start(contactId))
        assertEquals(1, speaker.ended)
        assertTrue(db.messageDao().listByContact(contactId).none { it.type == MessageType.CALL })
        mic.fail = false
        // 正在通话：同一个人回到通话页，别人不能打
        assertNull(engine.start(contactId))
        assertEquals(CallBlock.SAME_CALL, engine.check(contactId))
        val other = db.contactDao().insert(Contact(name = "阿哲", avatarColor = 0, providerId = 1, model = "m", createdAt = 2, updatedAt = 2))
        assertEquals(CallBlock.OTHER_CALL, engine.check(other))
    }

    @Test
    fun pendingRepliesAreDeliveredBeforeTheCall() = runBlocking {
        db.messageDao().insert(Message(contactId = contactId, role = Role.ASSISTANT, type = MessageType.TEXT, content = "还没送到", status = MessageStatus.PENDING, createdAt = start + 60_000))
        val id = startCall()
        val rows = db.messageDao().listByContact(contactId)
        assertEquals(MessageStatus.SUCCESS, rows.single { it.content == "还没送到" }.status)
        assertTrue("通话接在它后面", rows.last().id == id)
    }

    @Test
    fun cannotStart_pendingRepliesStayWhereTheyWere() = runBlocking {
        val due = start + 60_000
        db.messageDao().insert(Message(contactId = contactId, role = Role.ASSISTANT, type = MessageType.TEXT, content = "还没送到", status = MessageStatus.PENDING, createdAt = due))
        // 拿不到声音、麦克风打不开：没打成，等着送达的回复不提前
        speaker.grant = false
        assertEquals(CallBlock.AUDIO_BUSY, engine.start(contactId))
        speaker.grant = true
        mic.fail = true
        assertEquals(CallBlock.MICROPHONE, engine.start(contactId))
        val row = db.messageDao().listByContact(contactId).single { it.content == "还没送到" }
        assertEquals(MessageStatus.PENDING, row.status)
        assertEquals(due, row.createdAt)
    }

    @Test
    fun microphoneError_endsTheCall() {
        val id = startCall()
        mic.onError!!.invoke()
        await("挂断") { phase() == CallPhase.ENDED }
        assertEquals(CallEndReason.MICROPHONE, engine.state.value!!.endReason)
        val call = runBlocking { db.messageDao().get(id)!! }
        assertEquals(CallEndReason.MICROPHONE, CallEndReason.of(call.errorCode))
        assertTrue(!call.callOngoing)
        assertTrue(mic.stopped >= 1 && speaker.ended >= 1 && !host.running && !tracker.inCall(contactId))
    }

    @Test
    fun recoverInterrupted_finishesCallsLeftOpen_butNotTheActiveOne() = runBlocking {
        val open = db.messageDao().insert(Message(contactId = contactId, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, createdAt = start - 100_000))
        db.callDao().insert(isomeria.aichat.data.db.CallTurn(messageId = open, seq = 0, role = Role.USER, text = "在吗", audioMillis = 2_000, createdAt = start - 97_000))
        val active = startCall()
        engine.recoverInterrupted()
        val recovered = db.messageDao().get(open)!!
        assertEquals(5_000L, recovered.voiceMillis)
        assertEquals(CallEndReason.INTERRUPTED, CallEndReason.of(recovered.errorCode))
        assertTrue(db.messageDao().get(active)!!.callOngoing)
    }
}
