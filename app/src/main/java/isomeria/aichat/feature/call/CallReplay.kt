package isomeria.aichat.feature.call

import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.voice.AudioRoute
import isomeria.aichat.data.voice.VoicePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 正在放哪一条；[all] 为 true 是在放完整通话（放完接着放下一条） */
data class ReplayState(val turnId: Long, val all: Boolean, val startedAt: Long)

/**
 * 通话详情页的播放（0.8.0）。完整通话不生成整段文件：按顺序接着放各条的音频，每条之间停 [GAP_MILLIS]；
 * 没有音频的条（识别失败时录音也丢了、合成失败）跳过。进度按各条时长累加（[timeline]）。
 * 在主线程上调用；[scope] 用主线程。
 */
class CallReplay(
    private val player: VoicePlayer,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<ReplayState?>(null)
    val state: StateFlow<ReplayState?> = _state

    private var turns: List<CallTurn> = emptyList()
    private var token = 0L
    private var next: Job? = null

    fun setTurns(turns: List<CallTurn>) {
        this.turns = turns
    }

    /** 能放的条（有音频文件的），按顺序 */
    fun playable(): List<CallTurn> = turns.filter { turn -> turn.audioPath?.let(::File)?.isFile == true }

    /** 完整通话的总时长：能放的各条加起来，再加上条与条之间的间隔 */
    fun totalMillis(): Long = playable().let { list -> list.sumOf { it.audioMillis } + GAP_MILLIS * (list.size - 1).coerceAtLeast(0) }

    /** 某一条在完整通话里从哪里开始 */
    fun offsetOf(turnId: Long): Long {
        var offset = 0L
        for (turn in playable()) {
            if (turn.id == turnId) return offset
            offset += turn.audioMillis + GAP_MILLIS
        }
        return 0
    }

    /** 完整通话里 [position] 落在哪一条（拖动进度条时用）；没有能放的时为 null */
    fun turnAt(position: Long): CallTurn? {
        val list = playable()
        var offset = 0L
        for (turn in list) {
            if (position < offset + turn.audioMillis + GAP_MILLIS) return turn
            offset += turn.audioMillis + GAP_MILLIS
        }
        return list.lastOrNull()
    }

    /** 现在放到完整通话的哪里 */
    fun position(): Long {
        val current = _state.value ?: return 0
        val turn = playable().firstOrNull { it.id == current.turnId } ?: return 0
        return offsetOf(current.turnId) + (now() - current.startedAt).coerceIn(0, turn.audioMillis)
    }

    /** 点某一条：放这一条，再点停止 */
    fun toggleTurn(turnId: Long) {
        if (_state.value?.turnId == turnId && _state.value?.all == false) {
            stop()
            return
        }
        play(turnId, all = false)
    }

    /** 播放完整通话：从 [fromTurnId]（没有时从头）开始放到最后 */
    fun playAll(fromTurnId: Long? = null) {
        val first = fromTurnId ?: playable().firstOrNull()?.id ?: return
        play(first, all = true)
    }

    fun stop() {
        token++
        next?.cancel()
        next = null
        player.stop()
        _state.value = null
    }

    private fun play(turnId: Long, all: Boolean) {
        val turn = playable().firstOrNull { it.id == turnId } ?: return
        token++
        next?.cancel()
        player.stop()
        val current = token
        _state.value = ReplayState(turnId, all, now())
        player.play(File(turn.audioPath!!), AudioRoute.SPEAKER) { completed ->
            scope.launch {
                if (current != token) return@launch
                val following = if (all && completed) playable().dropWhile { it.id != turnId }.drop(1).firstOrNull() else null
                if (following == null) {
                    _state.value = null
                    return@launch
                }
                next = scope.launch {
                    delay(GAP_MILLIS)
                    if (current == token) play(following.id, all = true)
                }
            }
        }
    }

    companion object {
        const val GAP_MILLIS = 300L
    }
}
