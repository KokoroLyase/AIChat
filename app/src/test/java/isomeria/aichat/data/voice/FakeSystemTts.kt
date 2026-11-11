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
