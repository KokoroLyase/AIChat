package isomeria.aichat.data.voice

import java.io.File

/**
 * 录音（0.7.0）：16 kHz 单声道 16 位 PCM，直接写成 WAV（[WavFormat]）。真实实现用 AudioRecord；JVM 测试换成假实现。
 * 同一时间只有一段录音。
 */
interface VoiceRecorder {
    /** 开始录到 [file]（先写长度为 0 的 WAV 头）；麦克风不可用时抛异常 */
    fun start(file: File)

    /** 当前音量 0～1，界面动画用；没在录时为 0 */
    fun level(): Float

    /** 已经录了多久（毫秒） */
    fun elapsedMillis(): Long

    /** 停止并补好 WAV 头，返回录到的时长（毫秒）；没在录时为 0 */
    fun stop(): Long

    /** 停止并删掉这段录音 */
    fun cancel()
}

/** 音频文件的编码和信息；真实实现用 MediaCodec / MediaMetadataRetriever，JVM 测试换成假实现。 */
interface AudioCodec {
    /** 把 [wav] 压成 AAC（m4a）写到 [out]；失败返回 false（调用方继续用 WAV，删掉 [out]） */
    fun encodeAac(wav: File, out: File): Boolean

    /** 音频时长（毫秒）；读不出时为 0 */
    fun durationMillis(file: File): Long
}

/** 播放走哪里：扬声器（默认）或听筒 */
enum class AudioRoute { SPEAKER, EARPIECE }

/**
 * 播放语音（0.7.0）。一次只放一段，开始新的一段前先停掉上一段。真实实现用 MediaPlayer，JVM 测试换成假实现。
 * [onEnd] 在播完（true）或出错、被别的声音打断（false）时回调一次；调用 [stop] 停下的不回调。
 */
interface VoicePlayer {
    fun play(file: File, route: AudioRoute, onEnd: (completed: Boolean) -> Unit)

    /** 从这段的第 [startMillis] 毫秒开始放（0.8.0 通话详情页的暂停后继续、拖进度条）；不支持跳转的实现从头放 */
    fun play(file: File, route: AudioRoute, startMillis: Long, onEnd: (completed: Boolean) -> Unit) = play(file, route, onEnd)

    /** 正在放的这一段放到了第几毫秒；没在放或不知道时为 null */
    fun positionMillis(): Long? = null

    fun stop()
}

/** 后台播放（离开聊天页、回到桌面后接着放）时的前台服务；真实实现见 VoicePlaybackService */
interface BackgroundPlaybackHost {
    fun start()

    fun stop()
}
