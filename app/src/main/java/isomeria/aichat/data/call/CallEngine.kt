package isomeria.aichat.data.call

import android.util.Log
import androidx.room.withTransaction
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.Clock
import isomeria.aichat.core.UtteranceDetector
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallEndReason
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.memory.MemoryQueue
import isomeria.aichat.data.reply.DeliveryEngine
import isomeria.aichat.data.reply.ReplyQueue
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.VoiceRepository
import isomeria.aichat.data.repo.VoiceUnavailableException
import isomeria.aichat.data.voice.AudioCodec
import isomeria.aichat.data.voice.CallFocus
import isomeria.aichat.data.voice.CallMicrophone
import isomeria.aichat.data.voice.CallSpeaker
import isomeria.aichat.data.voice.SpeechActivity
import isomeria.aichat.data.voice.SpeechActivityFactory
import isomeria.aichat.data.voice.WavFormat
import isomeria.aichat.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** 通话现在在做什么（0.8.0） */
enum class CallPhase {
    /** 在听，用户还没开口 */
    LISTENING,

    /** 用户正在说 */
    HEARING,

    /** 说完了，正在识别 */
    RECOGNIZING,

    /** 等联系人回复 */
    THINKING,

    /** 联系人在说 */
    SPEAKING,

    /** 别的声音暂时占用（比如导航播报），不听也不放 */
    PAUSED,

    ENDED,
}

/** 通话页上的一行提示 */
enum class CallNotice {
    /** 没识别出来，请再说一遍 */
    ASR_FAILED,

    /** 这一轮的识别来源用不了了 */
    ASR_UNAVAILABLE,

    /** 联系人没回上来（[CallState.noticeReason] 是原因） */
    REPLY_FAILED,

    /** 有几句没合成出来，只显示了文字 */
    TTS_FAILED,

    /** 快到单次通话的最长时间了 */
    TIME_WARNING,
}

/** 字幕：最近一句是谁说的、说了什么 */
data class CallSubtitle(val role: Role, val text: String)

data class CallState(
    val contactId: Long,
    val messageId: Long,
    val startedAt: Long,
    val phase: CallPhase = CallPhase.LISTENING,
    val muted: Boolean = false,
    val speakerphone: Boolean = false,
    val subtitle: CallSubtitle? = null,
    val notice: CallNotice? = null,
    val noticeReason: FailReason? = null,
    /** 开始等回复的时间（显示“对方在想… N 秒”）；没在等时为 0 */
    val thinkingSince: Long = 0,
    /** 用的是 Silero VAD（false 是按音量判断） */
    val modelVad: Boolean = true,
    val endReason: CallEndReason? = null,
)

/** 不能发起通话的原因 */
enum class CallBlock {
    /** 已经在和这个联系人通话（界面直接回到通话页） */
    SAME_CALL,

    /** 正在和别的联系人通话 */
    OTHER_CALL,

    /** 这个联系人还在回上一条消息 */
    BUSY_REPLY,

    /** 没设置语音识别，或全局选的用不了 */
    ASR_UNAVAILABLE,

    /** 这个联系人单独选的语音识别用不了（不换成全局的） */
    CONTACT_ASR_UNAVAILABLE,
    TTS_UNAVAILABLE,
    CONTACT_TTS_UNAVAILABLE,

    /** 联系人的服务商没有 API Key */
    NO_API_KEY,

    /** 联系人的模型配置有问题 */
    MODEL_CONFIG,

    /** 拿不到声音（比如正在打电话） */
    AUDIO_BUSY,

    /** 麦克风打不开 */
    MICROPHONE,
    NO_CONTACT,
}

/** 通话期间的前台服务（麦克风和放音，回到桌面、锁屏也接着通话）；真实实现见 CallService */
interface CallHost {
    fun start(contactId: Long, contactName: String)

    fun stop()

    companion object {
        val NONE = object : CallHost {
            override fun start(contactId: Long, contactName: String) = Unit
            override fun stop() = Unit
        }
    }
}

/**
 * 语音通话（0.8.0），整个 App 同一时间只有一通。
 *
 * 一轮：在听 → 用户说完一段（[UtteranceDetector]）→ 识别（这个联系人实际的识别来源）→ 请求联系人回复（[CallReplier]，
 * 凑满一句就合成）→ 按顺序播放每一句（播放的同时合成下一句）→ 再听。联系人说话时不听（半双工），可以点「打断」。
 *
 * - 每说完一句、每放一句都写一条话（[CallRecords]），挂断时写总时长；App 在通话中被关掉时，下次启动按最后一条话补完
 * - 失败（识别、回复、合成、断网）都提示后回到在听，不自动重试；连续 [MAX_FAILURES] 轮失败自动挂断
 * - 单次最长 [MAX_CALL_MILLIS]（提前 5 分钟提示），[IDLE_MILLIS] 没人说话自动挂断
 * - 别的 App 长期拿走声音（来电）时挂断，暂时拿走时先停下
 * - 挂断时取消进行中的识别、回复、合成，已得到的保留；麦克风、放音、前台服务都关掉；还没放的句子的音频删掉
 */
@Singleton
class CallEngine @Inject constructor(
    private val db: AppDatabase,
    private val records: CallRecords,
    private val voices: VoiceRepository,
    private val providers: ProviderRepository,
    private val replier: CallReplier,
    private val codec: AudioCodec,
    private val files: FileStorage,
    private val microphone: CallMicrophone,
    private val activity: SpeechActivityFactory,
    private val speaker: CallSpeaker,
    private val host: CallHost,
    private val delivery: DeliveryEngine,
    private val replies: ReplyQueue,
    private val memoryQueue: MemoryQueue,
    private val clock: Clock,
    private val tracker: CallTracker,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<CallState?>(null)

    /** 现在的通话；没有通话时为 null，挂断后留着最后的样子（[CallPhase.ENDED]）直到下一通 */
    val state: StateFlow<CallState?> = _state

    private val lock = Mutex()

    @Volatile private var session: Session? = null

    fun inCall(contactId: Long): Boolean = session?.contactId == contactId

    /** 正在进行的通话消息；没有时为 null（启动恢复时跳过它） */
    fun activeMessageId(): Long? = session?.messageId

    /** 发起前检查（不改任何东西）：能打返回 null */
    suspend fun check(contactId: Long): CallBlock? {
        session?.let { return if (it.contactId == contactId) CallBlock.SAME_CALL else CallBlock.OTHER_CALL }
        val contact = db.contactDao().get(contactId) ?: return CallBlock.NO_CONTACT
        val messages = db.messageDao()
        if (messages.hasSending(contactId) || messages.awaitingSynthesis(contactId).isNotEmpty() || replies.isActive(contactId)) return CallBlock.BUSY_REPLY
        if (!voices.available(ProviderKind.ASR, contact)) {
            return if (contact.asrSource.isNullOrBlank()) CallBlock.ASR_UNAVAILABLE else CallBlock.CONTACT_ASR_UNAVAILABLE
        }
        if (!voices.available(ProviderKind.TTS, contact)) {
            return if (contact.ttsSource.isNullOrBlank()) CallBlock.TTS_UNAVAILABLE else CallBlock.CONTACT_TTS_UNAVAILABLE
        }
        try {
            providers.snapshot(contactId, memory = false)
        } catch (e: LlmException) {
            return if (e.error == LlmError.AUTH) CallBlock.NO_API_KEY else CallBlock.MODEL_CONFIG
        }
        return null
    }

    /**
     * 发起通话。要在通话页还在前台时调用（麦克风类的前台服务只能在前台启动），调用前已经有麦克风权限。
     * 能打时返回 null，否则返回原因，什么也不改
     */
    suspend fun start(contactId: Long, speakerphone: Boolean = false): CallBlock? = lock.withLock {
        check(contactId)?.let { return@withLock it }
        val session = Session(contactId, speakerphone)
        if (!speaker.begin(speakerphone) { focus -> session.inputs.trySend(Input.Focus(focus)) }) return@withLock CallBlock.AUDIO_BUSY
        session.vad = activity.create()
        try {
            microphone.start(
                onFrame = { frame, count -> if (session.acceptingFrames) session.inputs.trySend(Input.Frame(frame.copyOf(count))) },
                // 麦克风中途出错：读不到声音了，挂断（不然会一直“在听”，直到很久没人说话）
                onError = { session.inputs.trySend(Input.End(CallEndReason.MICROPHONE)) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            speaker.end()
            session.vad.release()
            return@withLock CallBlock.MICROPHONE
        }
        // 声音和麦克风都拿到了才动聊天：已经生成、等着送达的回复先送达，通话接在它们后面
        try {
            deliverPendingNow(contactId)
        } catch (e: Throwable) {
            microphone.stop()
            speaker.end()
            session.vad.release()
            throw e
        }
        val now = clock.now()
        session.startedAt = now
        session.lastActivity = now
        session.messageId = try {
            records.start(contactId, now)
        } catch (e: Throwable) {
            microphone.stop()
            speaker.end()
            session.vad.release()
            throw e
        }
        // 先建好通话的任务再登记：别处这时要挂断并等收尾（删聊天、恢复数据），一定等得到
        session.job = scope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) { session.run() }
        this.session = session
        tracker.enter(contactId) { hangUpAndWait() }
        _state.value = CallState(contactId, session.messageId, now, speakerphone = speakerphone, modelVad = session.vad !is isomeria.aichat.data.voice.EnergySpeechActivity)
        host.start(contactId, db.contactDao().get(contactId)?.name.orEmpty())
        session.job?.start()
        null
    }

    fun hangUp() {
        session?.inputs?.trySend(Input.End(CallEndReason.HANG_UP))
    }

    /** 挂断并等收尾完成（删聊天、恢复数据前经 [CallTracker] 调用） */
    suspend fun hangUpAndWait() {
        val current = session ?: return
        current.inputs.trySend(Input.End(CallEndReason.HANG_UP))
        current.job?.join()
    }

    /** 「说完了」：马上把这段发出去 */
    fun finishSpeaking() {
        session?.inputs?.trySend(Input.FinishNow)
    }

    /** 「打断」：联系人在说时停下，丢掉还没放的句子，回到在听 */
    fun interrupt() {
        session?.inputs?.trySend(Input.Interrupt)
    }

    fun setMuted(muted: Boolean) {
        session?.inputs?.trySend(Input.Mute(muted))
    }

    fun setSpeakerphone(on: Boolean) {
        val current = session ?: return
        speaker.setSpeakerphone(on)
        _state.update { it?.copy(speakerphone = on) }
        current.speakerphone = on
    }

    /** 关掉提示 */
    fun dismissNotice() {
        _state.update { it?.copy(notice = null, noticeReason = null) }
    }

    /** 启动时补完 App 在通话中被关掉的通话 */
    suspend fun recoverInterrupted() {
        records.recoverInterrupted(session?.messageId)
    }

    /** 已经生成、等着送达的回复现在送达（按原来的顺序） */
    private suspend fun deliverPendingNow(contactId: Long) {
        val now = clock.now()
        db.withTransaction { db.messageDao().advancePending(contactId, now) }
        delivery.deliverDueAndNotify(contactId)
    }

    private sealed interface Input {
        class Frame(val pcm: ShortArray) : Input
        data object Tick : Input
        data object FinishNow : Input
        data object Interrupt : Input
        class Mute(val on: Boolean) : Input
        class Focus(val focus: CallFocus) : Input
        class TurnDone(val index: Int, val ok: Boolean) : Input
        class End(val reason: CallEndReason) : Input
    }

    /** 一个说完的、还没放的句子：合成好的音频（失败时为 null） */
    private class Clip(val text: String, val file: File?, val millis: Long)

    private inner class Session(val contactId: Long, @Volatile var speakerphone: Boolean) {
        val inputs = Channel<Input>(Channel.UNLIMITED)
        lateinit var vad: SpeechActivity
        val detector by lazy { UtteranceDetector(vad::probability) }
        var messageId = 0L
        var startedAt = 0L
        var lastActivity = 0L
        var job: Job? = null
        var turn: Job? = null
        var failures = 0
        var muted = false
        var paused = false
        var warned = false
        var turnIndex = 0

        /** 在听：没静音、没暂停、没在处理一轮 */
        @Volatile var acceptingFrames = true

        /** 正在放的那一句（打断时改成被打断） */
        @Volatile var playingTurn: isomeria.aichat.data.db.CallTurn? = null

        /** 合成好了还没放的音频：打断、挂断时删掉 */
        val unplayed = java.util.Collections.synchronizedSet(LinkedHashSet<File>())

        suspend fun run() {
            val ticker = scope.launch {
                while (isActive) {
                    delay(TICK_MILLIS)
                    inputs.trySend(Input.Tick)
                }
            }
            var reason = CallEndReason.HANG_UP
            try {
                loop@ for (input in inputs) {
                    when (input) {
                        is Input.Frame -> if (acceptingFrames) handle(detector.accept(input.pcm))
                        Input.FinishNow -> if (acceptingFrames) handle(detector.finishNow())
                        Input.Tick -> limit()?.let { reason = it; break@loop }
                        is Input.Mute -> {
                            muted = input.on
                            _state.update { it?.copy(muted = input.on) }
                            listen()
                        }
                        Input.Interrupt -> if (_state.value?.phase == CallPhase.SPEAKING) {
                            turn?.cancelAndJoin()
                            turn = null
                            finishTurn(ok = true)
                        }
                        is Input.Focus -> when (input.focus) {
                            CallFocus.LOST -> { reason = CallEndReason.AUDIO_LOST; break@loop }
                            CallFocus.PAUSED -> {
                                paused = true
                                turn?.cancelAndJoin()
                                turn = null
                                listen()
                            }
                            CallFocus.RESUMED -> {
                                paused = false
                                listen()
                            }
                        }
                        // 被打断、暂停时取消掉的那一轮不算（它可能已经算完、晚一步才送到这里）
                        is Input.TurnDone -> if (input.index == turnIndex && turn != null) {
                            turn = null
                            if (finishTurn(input.ok)) { reason = CallEndReason.FAILURES; break@loop }
                        }
                        is Input.End -> { reason = input.reason; break@loop }
                    }
                }
            } finally {
                ticker.cancel()
                withContext(NonCancellable) { close(reason) }
            }
        }

        /** 一轮结束；连续失败太多时返回 true（挂断） */
        private fun finishTurn(ok: Boolean): Boolean {
            failures = if (ok) 0 else failures + 1
            lastActivity = clock.now()
            if (failures >= MAX_FAILURES) return true
            listen()
            return false
        }

        /** 按现在的状态决定听不听；回到在听时从头判断 */
        private fun listen() {
            val listening = !muted && !paused && turn == null
            if (listening != acceptingFrames || !listening) {
                detector.reset()
                vad.reset()
            }
            acceptingFrames = listening
            microphone.setListening(listening)
            _state.update { current ->
                current?.copy(
                    phase = when {
                        paused -> CallPhase.PAUSED
                        turn != null -> current.phase
                        else -> CallPhase.LISTENING
                    },
                    thinkingSince = if (turn == null) 0 else current.thinkingSince,
                )
            }
        }

        private fun limit(): CallEndReason? {
            val now = clock.now()
            if (now - startedAt >= MAX_CALL_MILLIS) return CallEndReason.TIME_LIMIT
            if (!warned && now - startedAt >= MAX_CALL_MILLIS - WARN_BEFORE_MILLIS) {
                warned = true
                _state.update { it?.copy(notice = CallNotice.TIME_WARNING, noticeReason = null) }
            }
            val idle = turn == null && _state.value?.phase != CallPhase.HEARING
            if (idle && now - lastActivity >= IDLE_MILLIS) return CallEndReason.IDLE
            return null
        }

        private fun handle(events: List<UtteranceDetector.Event>) {
            for (event in events) {
                when (event) {
                    UtteranceDetector.Event.Started -> {
                        lastActivity = clock.now()
                        _state.update { it?.copy(phase = CallPhase.HEARING) }
                    }
                    UtteranceDetector.Event.Discarded -> _state.update { it?.copy(phase = CallPhase.LISTENING) }
                    is UtteranceDetector.Event.Utterance -> {
                        // 半双工：处理这一轮期间不听
                        acceptingFrames = false
                        microphone.setListening(false)
                        val endedAt = clock.now()
                        val index = ++turnIndex
                        turn = scope.launch(Dispatchers.IO) {
                            val ok = try {
                                runTurn(index, event.pcm, endedAt)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 意外的错误（比如写库失败）当这一轮失败，通话不卡住；不记录异常内容
                                debugLog("turn failed unexpectedly: ${e.javaClass.simpleName}")
                                false
                            }
                            inputs.trySend(Input.TurnDone(index, ok))
                        }
                    }
                }
            }
        }

        /** 一轮：识别、请求回复、合成和播放；返回这一轮算不算成功 */
        private suspend fun runTurn(index: Int, pcm: ShortArray, endedAt: Long): Boolean {
            val millis = pcm.size * 1000L / WavFormat.SAMPLE_RATE
            val speechAt = (endedAt - millis).coerceAtLeast(startedAt)
            _state.update { it?.copy(phase = CallPhase.RECOGNIZING, notice = null, noticeReason = null) }
            val wav = WavFormat.wrap(toBytes(pcm))
            var failure: FailReason? = null
            val text = try {
                voices.transcribe(contactId, wav).text.trim()
            } catch (e: CancellationException) {
                throw e
            } catch (_: VoiceUnavailableException) {
                failure = FailReason.ASR_UNAVAILABLE
                ""
            } catch (e: LlmException) {
                failure = FailReason.of(e)
                ""
            } catch (_: Exception) {
                failure = FailReason.ASR_FAILED
                ""
            }
            val recognizedAt = clock.now()
            val audio = saveUserAudio(wav)
            if (text.isEmpty()) {
                records.addTurn(messageId, Role.USER, "", audio?.absolutePath, millis, CallTurnStatus.ASR_FAILED, speechAt)
                audio?.let { files.commitAvatar(it.absolutePath) }
                _state.update {
                    it?.copy(
                        notice = if (failure == FailReason.ASR_UNAVAILABLE) CallNotice.ASR_UNAVAILABLE else CallNotice.ASR_FAILED,
                        noticeReason = failure,
                    )
                }
                return false
            }
            records.addTurn(messageId, Role.USER, text, audio?.absolutePath, millis, CallTurnStatus.OK, speechAt)
            audio?.let { files.commitAvatar(it.absolutePath) }
            records.userSpoke(contactId)
            _state.update { it?.copy(phase = CallPhase.THINKING, thinkingSince = clock.now(), subtitle = CallSubtitle(Role.USER, text)) }

            val contact = db.contactDao().get(contactId) ?: return false
            var played = 0
            var failedClips = 0
            var firstAudioAt = 0L
            val result = try {
                coroutineScope {
                    val texts = Channel<String>(Channel.UNLIMITED)
                    val clips = Channel<Clip>(CLIP_BUFFER)
                    val reply = async {
                        try {
                            Result.success(replier.reply(contactId) { texts.send(it) })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        } finally {
                            texts.close()
                        }
                    }
                    launch {
                        try {
                            for (sentence in texts) clips.send(synthesize(contact, sentence))
                        } finally {
                            clips.close()
                        }
                    }
                    for (clip in clips) {
                        if (play(clip)) {
                            played++
                            if (firstAudioAt == 0L) firstAudioAt = clock.now()
                        } else {
                            failedClips++
                        }
                    }
                    reply.await()
                }
            } finally {
                withContext(NonCancellable) {
                    // 打断或挂断：正在放的那句记成被打断，还没放的音频删掉
                    playingTurn?.let { records.updateTurn(it.copy(status = CallTurnStatus.INTERRUPTED)) }
                    playingTurn = null
                    synchronized(unplayed) {
                        unplayed.forEach { files.delete(it.absolutePath) }
                        unplayed.clear()
                    }
                }
            }
            timing(index, endedAt, recognizedAt, result.getOrNull()?.firstContentAt ?: 0L, firstAudioAt)
            val error = result.exceptionOrNull()
            if (played == 0 && failedClips == 0) {
                // 没回上来：请求失败，或者回复里没有能读的字
                val why = when (error) {
                    null -> FailReason.EMPTY_REPLY
                    is LlmException -> FailReason.of(error)
                    else -> FailReason.UNKNOWN
                }
                _state.update { it?.copy(notice = CallNotice.REPLY_FAILED, noticeReason = why) }
                return false
            }
            if (failedClips > 0) _state.update { it?.copy(notice = CallNotice.TTS_FAILED, noticeReason = null) }
            return played > 0 || failedClips == 0
        }

        /** 合成一句；失败时音频为 null（只显示文字） */
        private suspend fun synthesize(contact: isomeria.aichat.data.db.Contact, text: String): Clip {
            val audio = try {
                voices.synthesize(contact, text)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return Clip(text, null, 0)
            }
            var file = files.newCallFile(audio.extension)
            unplayed += file
            try {
                file.writeBytes(audio.bytes)
                if (audio.extension.equals("wav", ignoreCase = true)) {
                    val m4a = files.newCallFile("m4a")
                    unplayed += m4a
                    if (codec.encodeAac(file, m4a) && m4a.length() > 0) {
                        unplayed -= file
                        files.delete(file.absolutePath)
                        file = m4a
                    } else {
                        unplayed -= m4a
                        files.delete(m4a.absolutePath)
                    }
                }
            } catch (e: Exception) {
                unplayed -= file
                files.delete(file.absolutePath)
                return Clip(text, null, 0)
            }
            val millis = codec.durationMillis(file).takeIf { it > 0 } ?: (text.length * 250L).coerceIn(1_000, 60_000)
            if (audio.billable) voices.recordSynthesis(contactId, text.length, millis)
            return Clip(text, file, millis)
        }

        /** 放一句，写一条话；合成失败的这句只写文字。返回有没有放出来 */
        private suspend fun play(clip: Clip): Boolean {
            val now = clock.now()
            val file = clip.file
            if (file == null) {
                records.addTurn(messageId, Role.ASSISTANT, clip.text, null, 0, CallTurnStatus.TTS_FAILED, now)
                _state.update { it?.copy(subtitle = CallSubtitle(Role.ASSISTANT, clip.text)) }
                return false
            }
            val id = records.addTurn(messageId, Role.ASSISTANT, clip.text, file.absolutePath, clip.millis, CallTurnStatus.OK, now)
            unplayed -= file
            files.commitAvatar(file.absolutePath)
            playingTurn = isomeria.aichat.data.db.CallTurn(id = id, messageId = messageId, seq = 0, role = Role.ASSISTANT, text = clip.text, audioPath = file.absolutePath, audioMillis = clip.millis, createdAt = now)
            _state.update { it?.copy(phase = CallPhase.SPEAKING, thinkingSince = 0, subtitle = CallSubtitle(Role.ASSISTANT, clip.text)) }
            val completed = suspendCancellableCoroutine { continuation ->
                speaker.play(file) { done -> if (continuation.isActive) continuation.resume(done) }
                continuation.invokeOnCancellation { speaker.stopPlayback() }
            }
            val turn = playingTurn
            playingTurn = null
            if (!completed) {
                // 放不出来（文件格式放不了、播放器出错）：和合成失败一样，只留文字，音频删掉
                turn?.let { records.updateTurn(it.copy(audioPath = null, audioMillis = 0, status = CallTurnStatus.TTS_FAILED)) }
                files.delete(file.absolutePath)
                return false
            }
            delay(SENTENCE_GAP_MILLIS)
            return true
        }

        /** 用户这一句的录音：写 WAV 再压成 AAC（压不了就留 WAV）；写不了时为 null */
        private fun saveUserAudio(wav: ByteArray): File? {
            val raw = files.newCallFile("wav")
            return try {
                raw.writeBytes(wav)
                val m4a = files.newCallFile("m4a")
                if (codec.encodeAac(raw, m4a) && m4a.length() > 0) {
                    files.delete(raw.absolutePath)
                    m4a
                } else {
                    files.delete(m4a.absolutePath)
                    raw
                }
            } catch (_: Exception) {
                files.delete(raw.absolutePath)
                null
            }
        }

        /** 挂断：停下所有东西，写总时长和原因 */
        private suspend fun close(reason: CallEndReason) {
            acceptingFrames = false
            inputs.close()
            turn?.cancelAndJoin()
            turn = null
            runCatching { microphone.stop() }
            runCatching { speaker.end() }
            runCatching { vad.release() }
            runCatching { host.stop() }
            records.finish(messageId, clock.now() - startedAt, reason)
            session = null
            tracker.leave()
            _state.update { it?.copy(phase = CallPhase.ENDED, endReason = reason, thinkingSince = 0) }
            if (db.contactDao().get(contactId)?.autoMemory == true) memoryQueue.enqueue(contactId)
        }

        /** debug、perf 包里按段记下每一轮的耗时（不记内容），判断延迟用 */
        private fun timing(index: Int, endedAt: Long, recognizedAt: Long, firstContentAt: Long, firstAudioAt: Long) {
            if (!BuildConfig.TEST_TOOLS) return
            fun gap(from: Long, to: Long) = if (from > 0 && to > 0) (to - from).toString() else "-"
            Log.i(
                TIMING_TAG,
                "turn=$index vad=${if (_state.value?.modelVad == true) "silero" else "energy"} endSilence=$END_SILENCE_MILLIS " +
                    "asr=${gap(endedAt, recognizedAt)} firstToken=${gap(recognizedAt, firstContentAt)} " +
                    "firstAudio=${gap(firstContentAt, firstAudioAt)} total=${gap(endedAt, firstAudioAt)}",
            )
        }
    }

    /** 只在 debug 包输出；不记录内容 */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private fun toBytes(pcm: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) buffer.putShort(sample)
        return buffer.array()
    }

    companion object {
        const val MAX_FAILURES = 3
        const val MAX_CALL_MILLIS = 60 * 60_000L
        const val WARN_BEFORE_MILLIS = 5 * 60_000L
        const val IDLE_MILLIS = 5 * 60_000L
        const val TICK_MILLIS = 1_000L
        const val SENTENCE_GAP_MILLIS = 300L
        const val END_SILENCE_MILLIS = 800L
        private const val CLIP_BUFFER = 2
        const val TIMING_TAG = "CallTiming"
        private const val TAG = "CallEngine"
    }
}
