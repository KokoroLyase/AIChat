package isomeria.aichat.data.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.BuildConfig
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 通话里一直开着的麦克风（0.8.0）：16 kHz 单声道 16 位 PCM，一段一段交给调用方。真实实现用 AudioRecord（通话用的录音来源，
 * 机型自带的回声消除和降噪能用就打开），JVM 测试换成假实现。
 *
 * [setListening] 为 false 时（联系人在说话、识别和等回复期间、静音）调用方不用这些声音；测试包里的预置音频这时停在原处，
 * 等下一次开始听再接着往下放，这样“说话—静音—说话”的预置音频能一轮一轮地用。
 */
interface CallMicrophone {
    /** 开始采集；[onFrame] 在采集线程上回调。麦克风不可用时抛异常 */
    fun start(onFrame: (ShortArray, Int) -> Unit)

    fun setListening(listening: Boolean)

    /** 停止并释放；可以重复调用 */
    fun stop()
}

/**
 * 一个窗口（16 kHz 下 [windowSamples] 个采样）里是人在说话的概率，0～1（0.8.0）。
 * 真实实现是 Silero VAD（[SileroSpeechActivity]）；跑不了 sherpa-onnx 的手机和 JVM 测试用按音量的简单判断（[EnergySpeechActivity]）
 */
interface SpeechActivity {
    val windowSamples: Int

    /** [window] 是 -1～1 的浮点数，长度是 [windowSamples] */
    fun probability(window: FloatArray): Float

    /** 从头开始（换一轮、静音之后） */
    fun reset()

    fun release()
}

/** 每次通话建一个 [SpeechActivity]，挂断时释放 */
fun interface SpeechActivityFactory {
    fun create(): SpeechActivity
}

/**
 * 按音量的简单判断：跑不了 Silero VAD 的手机（不是 64 位 ARM、原生库加载不了）用它。
 * “底噪”从第一个窗口的音量开始，比它小就马上降下来，比它大就慢慢升上去（约 6 秒跟上一个持续的环境声，几秒的说话不会把它抬得太高）；
 * 比底噪高 [MARGIN_DB] 分贝开始算说话，高 [MARGIN_DB] + [RANGE_DB] 分贝算确定在说话；太小的声音（低于 [MIN_DB]）一律不算。
 * 抗噪声比 Silero 差，环境声忽大忽小时可能误判，这时靠一段最长 25 秒和「说完了」
 */
class EnergySpeechActivity(override val windowSamples: Int = 512) : SpeechActivity {
    private var floor: Double? = null

    override fun probability(window: FloatArray): Float {
        var sum = 0.0
        for (v in window) sum += v.toDouble() * v
        val rms = sqrt(sum / window.size.coerceAtLeast(1))
        val db = if (rms <= 1e-9) -120.0 else 20 * log10(rms)
        val previous = floor ?: db
        val current = if (db < previous) db else previous + (db - previous) * RISE
        floor = current
        if (db < MIN_DB) return 0f
        return ((db - current - MARGIN_DB) / RANGE_DB).coerceIn(0.0, 1.0).toFloat()
    }

    override fun reset() {
        // 底噪留着：换一轮时环境没变
    }

    override fun release() = Unit

    private companion object {
        const val MIN_DB = -50.0
        const val MARGIN_DB = 10.0
        const val RANGE_DB = 10.0
        const val RISE = 0.005
    }
}

/**
 * Silero VAD（MIT，Silero Team；k2-fsa 转换成 sherpa-onnx 用的 ONNX 格式），模型 `silero_vad.onnx` 随安装包放在 assets 里，
 * 不联网。只用它算每个窗口的概率，怎么切分由 [isomeria.aichat.core.UtteranceDetector] 决定
 */
class SileroSpeechActivity(assets: android.content.res.AssetManager) : SpeechActivity {
    override val windowSamples: Int = WINDOW

    private val vad = com.k2fsa.sherpa.onnx.Vad(
        assets,
        com.k2fsa.sherpa.onnx.VadModelConfig(
            sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                model = MODEL_ASSET,
                threshold = 0.5f,
                minSilenceDuration = 0.8f,
                minSpeechDuration = 0.3f,
                windowSize = WINDOW,
                maxSpeechDuration = 25f,
            ),
            sampleRate = 16_000,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        ),
    )

    override fun probability(window: FloatArray): Float = vad.compute(window)

    override fun reset() = vad.reset()

    override fun release() = vad.release()

    companion object {
        /** assets 里的模型文件名；构建时下载并校验（app/build.gradle.kts 的 prepareVadAsset） */
        const val MODEL_ASSET = "silero_vad.onnx"
        const val WINDOW = 512
    }
}

/**
 * 能跑 sherpa-onnx（和离线识别同一个原生库）就用 Silero VAD，否则按音量判断。
 * 模型读不出来、原生代码出错时也退回按音量判断，通话照常能打
 */
@Singleton
class AndroidSpeechActivityFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: OfflineAsrEngine,
) : SpeechActivityFactory {
    override fun create(): SpeechActivity =
        if (engine.supported()) runCatching { SileroSpeechActivity(context.assets) }.getOrElse { EnergySpeechActivity() } else EnergySpeechActivity()
}

/**
 * 用 AudioRecord 一直录（通话的录音来源 VOICE_COMMUNICATION），每次读 [FRAME] 个采样（32 毫秒）。
 * 机型有回声消除、降噪时打开（效果因机型而异；通话是半双工，联系人说话时本来就不听）。
 *
 * 测试包（debug、perf）里应用私有目录有 `files/test-call-input.wav` 时用它代替麦克风：按实际时长放进来，
 * 不在听的时候停在原处，放完后一直是静音。真机测试默认用预置音频、不录手机周围的声音（release 不含这个开关）
 */
@Singleton
class AndroidCallMicrophone @Inject constructor(@ApplicationContext private val context: Context) : CallMicrophone {
    @Volatile private var running = false
    @Volatile private var listening = true
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // 调用方先检查 RECORD_AUDIO
    override fun start(onFrame: (ShortArray, Int) -> Unit) {
        check(!running) { "already running" }
        val injected = File(context.filesDir, TEST_INPUT).takeIf { BuildConfig.TEST_TOOLS && it.isFile }
        val source: Source = if (injected != null) FileSource(injected) { listening } else MicSource()
        source.open()
        running = true
        thread = Thread({
            val buffer = ShortArray(FRAME)
            try {
                while (running) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count > 0) onFrame(buffer, count)
                }
            } finally {
                source.close()
            }
        }, "call-microphone").apply { start() }
    }

    override fun setListening(listening: Boolean) {
        this.listening = listening
    }

    override fun stop() {
        running = false
        thread?.join(2_000)
        thread = null
    }

    private interface Source {
        fun open()
        fun read(buffer: ShortArray): Int
        fun close()
    }

    @SuppressLint("MissingPermission")
    private class MicSource : Source {
        private lateinit var record: AudioRecord
        private var echo: AcousticEchoCanceler? = null
        private var noise: NoiseSuppressor? = null

        override fun open() {
            val min = AudioRecord.getMinBufferSize(WavFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                WavFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, WavFormat.BYTES_PER_SECOND / 2),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("microphone unavailable")
            }
            if (AcousticEchoCanceler.isAvailable()) echo = runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
            if (NoiseSuppressor.isAvailable()) noise = runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
            record.startRecording()
        }

        override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

        override fun close() {
            runCatching { record.stop() }
            echo?.release()
            noise?.release()
            record.release()
        }
    }

    /** 测试包用：按实际时长读预置音频；不在听的时候给静音、文件位置不动；放完后一直是静音 */
    private class FileSource(private val file: File, private val listening: () -> Boolean) : Source {
        private lateinit var input: RandomAccessFile
        private var startedAt = 0L
        private var delivered = 0L

        override fun open() {
            input = RandomAccessFile(file, "r")
            input.seek(WavFormat.HEADER_BYTES.toLong())
            startedAt = System.nanoTime()
        }

        override fun read(buffer: ShortArray): Int {
            val due = (System.nanoTime() - startedAt) / 1_000_000 * WavFormat.SAMPLE_RATE / 1000
            if (delivered + buffer.size > due) Thread.sleep(((delivered + buffer.size - due) * 1000 / WavFormat.SAMPLE_RATE).coerceAtLeast(1))
            delivered += buffer.size
            if (!listening()) {
                buffer.fill(0)
                return buffer.size
            }
            val raw = ByteArray(buffer.size * 2)
            val read = input.read(raw).coerceAtLeast(0)
            val wrapped = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            for (i in buffer.indices) buffer[i] = if (i * 2 + 1 < read) wrapped.getShort(i * 2) else 0
            return buffer.size
        }

        override fun close() = input.close()
    }

    companion object {
        /** 测试包里代替通话麦克风的预置音频（16 kHz 单声道 16 位 WAV，“说话—静音—说话”） */
        const val TEST_INPUT = "test-call-input.wav"
        const val FRAME = 512
    }
}
