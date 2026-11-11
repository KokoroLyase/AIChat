package isomeria.aichat.data.voice

import android.os.Build
import isomeria.aichat.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** 离线识别模型的一个文件：名称、字节数和写死的 SHA-256（下载后按它校验，不对就删除）。 */
data class ModelFile(val name: String, val bytes: Long, val sha256: String)

/**
 * 离线识别用的模型（0.7.1）：SenseVoice Small 转换成 sherpa-onnx 格式后的 int8 量化版，支持中文、粤语、英语、日语、韩语，自带标点。
 *
 * - 原始模型：阿里巴巴通义实验室的 SenseVoice Small（FunASR），FunASR 模型许可：可以使用、复制、修改和分享，
 *   要注明出处和作者、保留模型名称
 * - sherpa-onnx 格式由 k2-fsa 项目转换并发布（Hugging Face `csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17`）
 * - 从魔搭社区（ModelScope）上的副本下载：Hugging Face 在国内打不开；两边文件的 SHA-256 完全相同（2026-11-12 核对），
 *   所以下载后按写死的 SHA-256 校验就能确认拿到的是官方发布的同一份文件
 * - App 不转发、不内置模型，安装包里只有运行程序（sherpa-onnx 和 onnxruntime 的原生库，只打 arm64-v8a）
 */
object OfflineAsrModel {
    const val ID = "sense-voice-zh-en-ja-ko-yue-2024-07-17-int8"

    /** 应用私有目录下放离线识别模型的目录（存储空间页按它统计）；不在导出的媒体目录里，不随备份 */
    const val ROOT = "asr-models"

    const val DIR = "$ROOT/$ID"

    const val BASE_URL = "https://www.modelscope.cn/models/pengzhendong/sherpa-onnx-sense-voice-zh-en-ja-ko-yue/resolve/master/"

    /** 下载时允许跳转到的域名（魔搭社区把大文件跳转到它的 CDN） */
    val ALLOWED_HOSTS = listOf("modelscope.cn")

    /** 先下小的 tokens.txt，再下模型 */
    val FILES = listOf(
        ModelFile("tokens.txt", 315_894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
        ModelFile("model.int8.onnx", 239_233_841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
    )

    val TOTAL_BYTES: Long = FILES.sumOf { it.bytes }

    const val MODEL_FILE = "model.int8.onnx"
    const val TOKENS_FILE = "tokens.txt"
}

/**
 * 离线识别引擎（0.7.1）。真实实现用 sherpa-onnx（[SherpaOfflineAsrEngine]）；JVM 加载不了原生库，测试用假实现。
 */
interface OfflineAsrEngine {
    /** 这台手机能不能跑：64 位 ARM，原生库加载成功 */
    fun supported(): Boolean

    /** 用 [modelDir] 里的模型识别一段 16 位 PCM（[samples] 是 -1～1 的浮点数）。失败时抛 [IOException] */
    suspend fun transcribe(modelDir: File, samples: FloatArray, sampleRate: Int): String
}

/** 把 WAV 里的 16 位 PCM 转成 sherpa-onnx 要的浮点数（纯函数，便于测试）；多声道取第一个声道 */
object PcmSamples {
    fun fromWav(wav: ByteArray): Pair<FloatArray, Int> {
        val info = WavFormat.parse(wav) ?: throw IOException("NotWav")
        if (info.bits != 16) throw IOException("Not16Bit")
        val frame = info.channels * 2
        val start = info.dataOffset.toInt()
        val frames = (minOf(info.dataBytes, (wav.size - start).toLong()) / frame).toInt()
        val buffer = ByteBuffer.wrap(wav, start, frames * frame).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            out[i] = buffer.getShort(start + i * frame) / 32768f
        }
        return out to info.sampleRate
    }
}

/**
 * sherpa-onnx 实现。模型加载要几秒、占几百 MB 内存，所以识别器加载后留 [IDLE_MILLIS]，期间再识别不用重新加载，
 * 之后自动释放。同一时间只识别一段，在后台线程上跑。
 */
@Singleton
class SherpaOfflineAsrEngine @Inject constructor(@ApplicationScope private val scope: CoroutineScope) : OfflineAsrEngine {
    private val lock = Mutex()
    private var recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer? = null
    private var loadedFrom: String? = null
    private var releaseJob: Job? = null

    private val supported: Boolean by lazy {
        "arm64-v8a" in Build.SUPPORTED_ABIS && try {
            System.loadLibrary(LIBRARY)
            true
        } catch (_: Throwable) {
            false
        }
    }

    override fun supported(): Boolean = supported

    override suspend fun transcribe(modelDir: File, samples: FloatArray, sampleRate: Int): String = lock.withLock {
        if (!supported) throw IOException("Unsupported")
        releaseJob?.cancel()
        try {
            withContext(Dispatchers.Default) {
                val engine = load(modelDir)
                val stream = engine.createStream()
                try {
                    stream.acceptWaveform(samples, sampleRate)
                    engine.decode(stream)
                    engine.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // 原生代码的异常（模型文件坏了等）统一当识别失败
            release()
            throw IOException("RecognizeFailed: ${e.javaClass.simpleName}", e)
        } finally {
            releaseJob = scope.launch {
                delay(IDLE_MILLIS)
                lock.withLock { release() }
            }
        }
    }

    private fun load(modelDir: File): com.k2fsa.sherpa.onnx.OfflineRecognizer {
        val key = modelDir.absolutePath
        recognizer?.takeIf { loadedFrom == key }?.let { return it }
        release()
        val model = File(modelDir, OfflineAsrModel.MODEL_FILE)
        val tokens = File(modelDir, OfflineAsrModel.TOKENS_FILE)
        if (!model.isFile || !tokens.isFile) throw IOException("ModelMissing")
        val config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
            featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = com.k2fsa.sherpa.onnx.OfflineModelConfig(
                senseVoice = com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(
                    model = model.absolutePath,
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = tokens.absolutePath,
                numThreads = THREADS,
                debug = false,
                provider = "cpu",
            ),
        )
        return com.k2fsa.sherpa.onnx.OfflineRecognizer(config = config).also {
            recognizer = it
            loadedFrom = key
        }
    }

    private fun release() {
        recognizer?.let { runCatching { it.release() } }
        recognizer = null
        loadedFrom = null
    }

    private companion object {
        const val LIBRARY = "sherpa-onnx-jni"
        const val IDLE_MILLIS = 120_000L
        const val THREADS = 2
    }
}
