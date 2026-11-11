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

    /**
     * 每读到一段 PCM 就回调一次（在录音线程上）。0.7.0 不用；给 0.8.0 语音通话判断“一段话说完了没有”留的接口
     */
    var pcmListener: ((ShortArray, Int) -> Unit)?
}

/** 音频文件的编码和信息；真实实现用 MediaCodec / MediaMetadataRetriever，JVM 测试换成假实现。 */
interface AudioCodec {
    /** 把 [wav] 压成 AAC（m4a）写到 [out]；失败返回 false（调用方继续用 WAV，删掉 [out]） */
    fun encodeAac(wav: File, out: File): Boolean

    /** 音频时长（毫秒）；读不出时为 0 */
    fun durationMillis(file: File): Long
}
