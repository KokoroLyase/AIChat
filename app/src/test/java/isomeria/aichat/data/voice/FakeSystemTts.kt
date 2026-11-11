package isomeria.aichat.data.voice

import isomeria.aichat.core.LocalVoicePrefs
import java.io.IOException

/** 测试用的系统语音合成：按 [info] 报告状态，合成时记下文字和音色，返回一段固定的 WAV（或按 [fail] 失败）。 */
class FakeSystemTts(
    var info: SystemTtsInfo = SystemTtsInfo(SystemTtsStatus.READY, "Fake engine", listOf(SystemTtsVoice("zh-voice-a", "zh-CN"), SystemTtsVoice("zh-voice-b", "zh-CN"))),
    var fail: Boolean = false,
) : SystemTts {
    val calls = mutableListOf<Pair<String, String?>>()
    var infoCalls = 0

    override suspend fun info(refresh: Boolean): SystemTtsInfo {
        infoCalls++
        return info
    }

    override suspend fun synthesize(text: String, voice: String?): ByteArray {
        calls += text to voice
        if (fail || info.status != SystemTtsStatus.READY) throw IOException("fake failure")
        // 0.2 秒、24 kHz 单声道 16 位：和录音的 16 kHz 不同，检查按文件头算时长
        val pcm = ByteArray(24_000 * 2 / 5)
        return WavFormat.wrap(pcm).also { patchRate(it, 24_000) }
    }

    private fun patchRate(wav: ByteArray, rate: Int) {
        fun int(at: Int, v: Int) = repeat(4) { wav[at + it] = ((v shr (8 * it)) and 0xFF).toByte() }
        int(24, rate)
        int(28, rate * 2)
    }
}

class FakeLocalVoicePrefs(override var systemTtsVoice: String = "") : LocalVoicePrefs

/** 测试用的离线识别：按 [supported] 报告能不能跑，识别时记下模型目录和采样数，返回 [text]（或按 [fail] 失败）。 */
class FakeOfflineAsr(var supported: Boolean = true, var text: String = "离线识别的文字", var fail: Boolean = false) : OfflineAsrEngine {
    val calls = mutableListOf<Triple<java.io.File, Int, Int>>()

    override fun supported() = supported

    override suspend fun transcribe(modelDir: java.io.File, samples: FloatArray, sampleRate: Int): String {
        calls += Triple(modelDir, samples.size, sampleRate)
        if (fail) throw IOException("fake failure")
        return text
    }
}

/** 测试用的模型目录：[root] 下的模型文件和 [OfflineAsrModel] 同名，大小用 [files] 里写的（测试不下载 230 MB） */
fun testModelStore(
    root: java.io.File,
    files: List<ModelFile> = listOf(ModelFile("tokens.txt", 3, ""), ModelFile("model.int8.onnx", 5, "")),
    downloader: ModelDownloader = ModelDownloader(okhttp3.OkHttpClient(), "https://example.invalid/", { false }),
    freeBytes: (java.io.File) -> Long = { Long.MAX_VALUE },
) = OfflineModelStore(root, downloader, files, freeBytes)

/** 把 [store] 的模型文件按写的大小放好，相当于下载完成 */
fun installFakeModel(store: OfflineModelStore, files: List<ModelFile> = listOf(ModelFile("tokens.txt", 3, ""), ModelFile("model.int8.onnx", 5, ""))) {
    store.dir.mkdirs()
    files.forEach { java.io.File(store.dir, it.name).writeBytes(ByteArray(it.bytes.toInt())) }
    store.refresh()
}
