package isomeria.aichat.feature.chat

import isomeria.aichat.data.voice.VoiceRecorder
import isomeria.aichat.ui.components.RecordMode
import isomeria.aichat.ui.components.RecordZone
import isomeria.aichat.ui.components.RecordingUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 聊天页的录音（0.7.0）：按住说话发语音、上滑取消或转文字、麦克风按钮录音转文字。从 ViewModel 拆出来，JVM 测试用假录音器。
 *
 * - 最长 [MAX_RECORD_MILLIS]，到时间按原来的意图结束（发语音或转文字）；不到 [MIN_RECORD_MILLIS] 提示太短、不发
 * - 发语音：录音文件交给 [send]（写库、排回复任务，回复任务先识别再请求模型）
 * - 转文字：识别后把文字交给界面放进输入框，录音不保留；失败时提示
 * - 取消、太短、转文字结束后都删掉录音文件
 */
class VoiceRecording(
    private val recorder: VoiceRecorder,
    private val newVoiceFile: () -> File,
    private val newTempFile: () -> File,
    private val discard: (File) -> Unit,
    private val send: suspend (File, Long) -> Unit,
    private val transcribe: suspend (ByteArray) -> String,
    private val emit: suspend (ChatEvent) -> Unit,
    private val uiScope: CoroutineScope,
    private val workScope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RecordingUi?>(null)
    val state: StateFlow<RecordingUi?> = _state

    private val _transcribing = MutableStateFlow(false)
    val transcribing: StateFlow<Boolean> = _transcribing

    private var file: File? = null
    private var ticker: Job? = null

    /** 开始录音；已经在录或麦克风打不开时返回 false */
    fun start(mode: RecordMode, toggled: Boolean): Boolean {
        if (_state.value != null) return false
        val target = if (mode == RecordMode.VOICE_MESSAGE) newVoiceFile() else newTempFile()
        try {
            recorder.start(target)
        } catch (e: Exception) {
            discard(target)
            uiScope.launch { emit(ChatEvent.MicUnavailable) }
            return false
        }
        file = target
        _state.value = RecordingUi(mode, toggled)
        ticker = uiScope.launch {
            while (true) {
                delay(TICK_MILLIS)
                val elapsed = recorder.elapsedMillis()
                _state.update { it?.copy(elapsedMillis = elapsed, level = recorder.level()) }
                if (elapsed >= MAX_RECORD_MILLIS) {
                    finish(RecordZone.SEND)
                    break
                }
            }
        }
        return true
    }

    fun setZone(zone: RecordZone) = _state.update { it?.copy(zone = zone) }

    /** 结束录音；[zone] 是松开时手指的位置或点的按钮。转文字模式下“发送”就是完成 */
    fun finish(zone: RecordZone) {
        val current = _state.value ?: return
        val recorded = file ?: return
        _state.value = null
        file = null
        val running = ticker
        ticker = null
        if (zone == RecordZone.CANCEL) {
            recorder.cancel()
            discard(recorded)
            running?.cancel()
            return
        }
        val millis = recorder.stop()
        running?.cancel()
        if (millis < MIN_RECORD_MILLIS) {
            discard(recorded)
            uiScope.launch { emit(ChatEvent.VoiceTooShort) }
            return
        }
        if (zone == RecordZone.TO_TEXT || current.mode == RecordMode.TO_TEXT) {
            _transcribing.value = true
            workScope.launch {
                try {
                    emit(ChatEvent.VoiceText(transcribe(recorded.readBytes())))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(ChatEvent.VoiceTextFailed)
                } finally {
                    discard(recorded)
                    _transcribing.value = false
                }
            }
        } else {
            workScope.launch { send(recorded, millis) }
        }
    }

    /** 离开聊天页时还在录：丢掉，不发送 */
    fun abandon() {
        if (_state.value == null) return
        finish(RecordZone.CANCEL)
    }

    companion object {
        private const val TICK_MILLIS = 100L
    }
}

/** 录音最短 1 秒、最长 60 秒 */
internal const val MIN_RECORD_MILLIS = 1_000L
internal const val MAX_RECORD_MILLIS = 60_000L
