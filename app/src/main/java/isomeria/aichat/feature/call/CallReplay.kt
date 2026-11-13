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

/**
 * 正在放（或停在）哪一条、这一条的第几毫秒。
 * [all] 为 true 是在放完整通话（放完接着放下一条），false 是单独放这一条；
 * [paused] 时停在这一条的第 [from] 毫秒，不在放；否则从 [startedAt] 那一刻起从第 [from] 毫秒往后放。
 */
data class ReplayState(
    val turnId: Long,
    val all: Boolean,
    val paused: Boolean = false,
    val from: Long = 0,
    val startedAt: Long = 0,
)

/**
 * 通话详情页的播放（0.8.0）。完整通话不生成整段文件：按顺序接着放各条的音频，每条之间停 [GAP_MILLIS]；
 * 没有音频的条（识别失败时录音也丢了、合成失败）跳过。进度按各条时长累加。
 *
 * 完整通话可以暂停、从停下的地方接着放；拖进度条拖到哪里就从哪里放（暂停中或还没放时只移到那里）。
 * 被别的声音打断（来电、别的 App 放声音）时停在当时的位置，不从头开始。全部放完后回到开头。
 * 单独点一条只放这一条，再点停止。在主线程上调用；[scope] 用主线程。
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

    /** 完整通话里 [position] 落在哪一条；没有能放的时为 null */
    fun turnAt(position: Long): CallTurn? = locate(position)?.first

    /** 完整通话里 [position] 落在哪一条、这一条的第几毫秒（落在条与条之间的空档时算下一条的开头） */
    fun locate(position: Long): Pair<CallTurn, Long>? {
        val list = playable()
        var offset = 0L
        for ((index, turn) in list.withIndex()) {
            val end = offset + turn.audioMillis
            if (position < end) return turn to (position - offset).coerceAtLeast(0)
            if (position < end + GAP_MILLIS) return (list.getOrNull(index + 1) ?: turn).let { it to if (it === turn) turn.audioMillis else 0L }
            offset = end + GAP_MILLIS
        }
        return list.lastOrNull()?.let { it to it.audioMillis }
    }

    /** 现在放到（或停在）完整通话的哪里；没在放时是 0 */
    fun position(): Long {
        val current = _state.value ?: return 0
        val turn = playable().firstOrNull { it.id == current.turnId } ?: return 0
        return offsetOf(turn.id) + inTurn(current, turn)
    }

    /** 这一条放到了第几毫秒：在放时优先问播放器，问不到时按开始放的时间推算 */
    private fun inTurn(current: ReplayState, turn: CallTurn): Long {
        val millis = if (current.paused) current.from else player.positionMillis() ?: (current.from + now() - current.startedAt)
        return millis.coerceIn(0, turn.audioMillis)
    }

    /** 顶部的播放/暂停：没在放（或在单独放某一条）时从头放完整通话，在放时暂停，暂停中接着放 */
    fun toggleAll() {
        val current = _state.value
        when {
            current == null || !current.all -> playable().firstOrNull()?.let { play(it.id, all = true, from = 0) }
            current.paused -> play(current.turnId, all = true, from = current.from)
            else -> pause()
        }
    }

    /** 完整通话停在现在的位置（离开页面、回到桌面时也调用）；单独放的一条直接停止 */
    fun pause() {
        val current = _state.value ?: return
        if (current.paused) return
        if (!current.all) {
            stop()
            return
        }
        _state.value = pausedAt(current)
        token++
        next?.cancel()
        next = null
        player.stop()
    }

    /** 拖进度条到 [position]：在放时从那里接着放；暂停中或还没放时只移到那里 */
    fun seekTo(position: Long) {
        val (turn, from) = locate(position.coerceAtLeast(0)) ?: return
        val current = _state.value
        if (current != null && current.all && !current.paused) {
            play(turn.id, all = true, from = from)
            return
        }
        token++
        next?.cancel()
        next = null
        player.stop()
        _state.value = ReplayState(turn.id, all = true, paused = true, from = from)
    }

    /** 点某一条：只放这一条，再点停止 */
    fun toggleTurn(turnId: Long) {
        val current = _state.value
        if (current != null && current.turnId == turnId && !current.all && !current.paused) {
            stop()
            return
        }
        play(turnId, all = false, from = 0)
    }

    fun stop() {
        token++
        next?.cancel()
        next = null
        player.stop()
        _state.value = null
    }

    /** 停在 [current] 现在的位置；一条正好放完、在等下一条时停在下一条的开头 */
    private fun pausedAt(current: ReplayState): ReplayState {
        val list = playable()
        val turn = list.firstOrNull { it.id == current.turnId } ?: return current.copy(paused = true)
        val at = inTurn(current, turn)
        val following = list.dropWhile { it.id != turn.id }.drop(1).firstOrNull()
        return if (at >= turn.audioMillis && following != null) {
            ReplayState(following.id, all = true, paused = true, from = 0)
        } else {
            current.copy(paused = true, from = at)
        }
    }

    private fun play(turnId: Long, all: Boolean, from: Long) {
        val turn = playable().firstOrNull { it.id == turnId } ?: return
        token++
        next?.cancel()
        next = null
        player.stop()
        val current = token
        val start = from.coerceIn(0, turn.audioMillis)
        _state.value = ReplayState(turnId, all, paused = false, from = start, startedAt = now())
        player.play(File(turn.audioPath!!), AudioRoute.SPEAKER, start) { completed ->
            scope.launch {
                if (current != token) return@launch
                val state = _state.value ?: return@launch
                if (!completed) {
                    // 被打断或出错：完整通话停在这里，之后可以接着放；单独的一条就结束
                    _state.value = if (all) pausedAt(state) else null
                    return@launch
                }
                val following = if (all) playable().dropWhile { it.id != turnId }.drop(1).firstOrNull() else null
                if (following == null) {
                    _state.value = null
                    return@launch
                }
                // 两条之间的空档：进度条停在这一条的末尾
                _state.value = state.copy(from = turn.audioMillis, startedAt = now())
                next = scope.launch {
                    delay(GAP_MILLIS)
                    if (current == token) play(following.id, all = true, from = 0)
                }
            }
        }
    }

    companion object {
        const val GAP_MILLIS = 300L
    }
}
