package isomeria.aichat.data.voice

import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** 怎么放：点语音条（扬声器）、长按菜单的听筒播放、后台播放、连续朗读 */
enum class PlayMode { NORMAL, EARPIECE, BACKGROUND, CONTINUOUS }

/** 正在放哪一条 */
data class PlaybackState(val messageId: Long, val contactId: Long, val mode: PlayMode)

/** 播放要用到的消息读写；真实实现是 ChatRepository */
interface VoiceQueueSource {
    suspend fun message(id: Long): Message?

    /** 某条之后的语音消息，按时间正序 */
    suspend fun voiceAfter(message: Message): List<Message>

    suspend fun markVoicePlayed(id: Long)
}

/**
 * 语音消息的播放（0.7.0），整个 App 只有一个。界面线程上调用；[scope] 用主线程。
 *
 * - 点语音条：放这一条，再点停止；点别的语音换成那一条
 * - 放的是对方没听过的语音时，放完接着放后面下一条没听过的对方语音
 * - 连续朗读：从这条开始把后面的语音（双方的）依次放完
 * - 听筒播放：这一段（和接着放的）走听筒
 * - 后台播放：离开聊天页、回到桌面也接着放，期间显示前台服务通知；其余方式离开聊天页就停
 * - 开始放就去掉红点；文件不在了返回 false，界面提示
 */
class VoicePlayback(
    private val player: VoicePlayer,
    private val source: VoiceQueueSource,
    private val host: BackgroundPlaybackHost,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PlaybackState?>(null)
    val state: StateFlow<PlaybackState?> = _state

    /** 每放一段加一；回调回来时不是当前这一段就不理 */
    private var token = 0L
    private var hostRunning = false

    /** 点语音条：正在放这条就停下，否则从这条开始放。返回 false 表示文件不在了 */
    fun toggle(message: Message): Boolean {
        if (_state.value?.messageId == message.id) {
            stop()
            return true
        }
        return play(message, PlayMode.NORMAL)
    }

    /** 从 [message] 开始放；返回 false 表示文件不在了（不影响正在放的） */
    fun play(message: Message, mode: PlayMode): Boolean {
        val file = playableFile(message) ?: return false
        player.stop()
        val current = ++token
        _state.value = PlaybackState(message.id, message.contactId, mode)
        if (mode == PlayMode.BACKGROUND) {
            if (!hostRunning) host.start()
            hostRunning = true
        } else {
            stopHost()
        }
        val firstListen = !message.voicePlayed
        if (firstListen) scope.launch { source.markVoicePlayed(message.id) }
        player.play(file, if (mode == PlayMode.EARPIECE) AudioRoute.EARPIECE else AudioRoute.SPEAKER) { completed ->
            scope.launch { onEnd(current, message, mode, completed, firstListen) }
        }
        return true
    }

    fun stop() {
        token++
        player.stop()
        _state.value = null
        stopHost()
    }

    /** 离开聊天页：后台播放的接着放，其余的停下 */
    fun leaveChat(contactId: Long) {
        val current = _state.value ?: return
        if (current.contactId == contactId && current.mode != PlayMode.BACKGROUND) stop()
    }

    /** 删掉了正在放的那条（或清空了聊天）：停下 */
    fun onDeleted(messageId: Long) {
        if (_state.value?.messageId == messageId) stop()
    }

    fun onContactCleared(contactId: Long) {
        if (_state.value?.contactId == contactId) stop()
    }

    private suspend fun onEnd(current: Long, message: Message, mode: PlayMode, completed: Boolean, firstListen: Boolean) {
        if (current != token) return
        val next = if (completed) next(message, mode, firstListen) else null
        if (next == null || !play(next, mode)) {
            if (current == token) stop()
        }
    }

    private suspend fun next(message: Message, mode: PlayMode, firstListen: Boolean): Message? {
        val after = source.voiceAfter(message).filter { playableFile(it) != null }
        return when {
            mode == PlayMode.CONTINUOUS -> after.firstOrNull()
            message.role == Role.ASSISTANT && firstListen -> after.firstOrNull { it.role == Role.ASSISTANT && !it.voicePlayed }
            else -> null
        }
    }

    private fun stopHost() {
        if (hostRunning) host.stop()
        hostRunning = false
    }

    private fun playableFile(message: Message): File? = message.voicePath?.let(::File)?.takeIf { it.isFile }
}
