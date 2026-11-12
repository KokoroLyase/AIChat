package isomeria.aichat.data.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 通话期间声音焦点的变化（0.8.0） */
enum class CallFocus {
    /** 别的 App 长期拿走了声音（比如来电）：挂断 */
    LOST,

    /** 暂时拿走（比如导航播报、别的 App 的短提示）：先不听也不放 */
    PAUSED,

    /** 还回来了：接着听 */
    RESUMED,
}

/**
 * 通话的声音（0.8.0）：整通电话申请一次声音焦点、切到通话模式，联系人的每一句都从这里放；听筒和免提可以随时切换。
 * 真实实现用 AudioManager + MediaPlayer，JVM 测试换成假实现。可以在任何线程调用。
 */
interface CallSpeaker {
    /** 开始通话的声音；拿不到声音焦点（比如正在打电话）时返回 false。[onFocus] 在主线程回调 */
    fun begin(speakerphone: Boolean, onFocus: (CallFocus) -> Unit): Boolean

    fun setSpeakerphone(speakerphone: Boolean)

    /** 放一句；放完（true）或出错（false）时回调一次。调用 [stopPlayback] 停下的不回调 */
    fun play(file: File, onEnd: (completed: Boolean) -> Unit)

    fun stopPlayback()

    /** 结束：停下、还回声音焦点、恢复普通模式；可以重复调用 */
    fun end()
}

@Singleton
class AndroidCallSpeaker @Inject constructor(@ApplicationContext context: Context) : CallSpeaker {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var onEnd: ((Boolean) -> Unit)? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 在主线程上执行并等结果（MediaPlayer 的回调和声音焦点都在主线程） */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        var result: Result<T>? = null
        val latch = CountDownLatch(1)
        main.post {
            result = runCatching(block)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return (result ?: Result.failure(IllegalStateException("MainThreadTimeout"))).getOrThrow()
    }

    override fun begin(speakerphone: Boolean, onFocus: (CallFocus) -> Unit): Boolean = onMain {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> onFocus(CallFocus.LOST)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onFocus(CallFocus.PAUSED)
                    AudioManager.AUDIOFOCUS_GAIN -> onFocus(CallFocus.RESUMED)
                }
            }, main)
            .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return@onMain false
        focus = request
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        route(speakerphone)
        true
    }

    override fun setSpeakerphone(speakerphone: Boolean) = onMain { route(speakerphone) }

    private fun route(speakerphone: Boolean) {
        val type = if (speakerphone) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        audio.availableCommunicationDevices.firstOrNull { it.type == type }?.let { audio.setCommunicationDevice(it) }
    }

    override fun play(file: File, onEnd: (completed: Boolean) -> Unit) = onMain {
        releasePlayer()
        this.onEnd = onEnd
        val media = MediaPlayer()
        try {
            media.setAudioAttributes(attributes)
            media.setDataSource(file.absolutePath)
            media.setOnCompletionListener { finish(true) }
            media.setOnErrorListener { _, _, _ -> finish(false); true }
            media.prepare()
            media.start()
            player = media
        } catch (_: Exception) {
            media.release()
            finish(false)
        }
    }

    private fun finish(completed: Boolean) {
        val callback = onEnd
        onEnd = null
        releasePlayer()
        callback?.invoke(completed)
    }

    override fun stopPlayback() = onMain {
        onEnd = null
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
    }

    override fun end() = onMain {
        onEnd = null
        releasePlayer()
        // 没开始（没拿到焦点，比如正在打电话）时不动通话模式
        val request = focus ?: return@onMain
        audio.abandonAudioFocusRequest(request)
        focus = null
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }
}
