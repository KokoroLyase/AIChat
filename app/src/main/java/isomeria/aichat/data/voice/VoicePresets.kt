package isomeria.aichat.data.voice

import isomeria.aichat.R
import isomeria.aichat.data.db.ProviderKind

/**
 * 语音服务商的预置（2026-11-11 按官方文档核对）。预置只填新连接，不改已保存的地址。
 *
 * - Xiaomi MiMo：合成 `mimo-v2.5-tts`（官方写“限时免费”，没写截止日期）、识别 `mimo-v2.5-asr`（约 ¥0.5 每小时音频），
 *   聊天接口带音频的格式，地址和聊天预置相同
 * - 硅基流动：识别，标准音频接口，`FunAudioLLM/SenseVoiceSmall`（是否免费以服务商为准）
 *
 * 另有自定义入口，两种格式可选。
 */
data class VoicePreset(
    val id: String,
    val nameRes: Int,
    val protocol: String,
    val baseUrl: String,
    val ttsModel: String?,
    val asrModel: String?,
    val docs: String,
    /** 合成的默认音色 */
    val defaultVoice: String = "",
    /** 可选的预置音色（strings.xml 里的数组），没有时为 0 */
    val voicesRes: Int = 0,
) {
    fun model(kind: ProviderKind): String? = when (kind) {
        ProviderKind.TTS -> ttsModel
        ProviderKind.ASR -> asrModel
        ProviderKind.LLM -> null
    }

    fun supports(kind: ProviderKind): Boolean = model(kind) != null
}

object VoicePresets {
    val MIMO = VoicePreset(
        id = "mimo",
        nameRes = R.string.preset_mimo,
        protocol = VoiceProtocol.CHAT_AUDIO,
        baseUrl = "https://api.xiaomimimo.com/v1",
        ttsModel = "mimo-v2.5-tts",
        asrModel = "mimo-v2.5-asr",
        docs = "https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5",
        defaultVoice = "mimo_default",
        voicesRes = R.array.mimo_tts_voices,
    )

    val SILICONFLOW = VoicePreset(
        id = "siliconflow",
        nameRes = R.string.preset_siliconflow,
        protocol = VoiceProtocol.OPENAI_AUDIO,
        baseUrl = "https://api.siliconflow.cn/v1",
        ttsModel = null,
        asrModel = "FunAudioLLM/SenseVoiceSmall",
        docs = "https://api-docs.siliconflow.cn/docs/api/audio-transcriptions-post",
    )

    val all = listOf(MIMO, SILICONFLOW)

    fun forKind(kind: ProviderKind): List<VoicePreset> = all.filter { it.supports(kind) }

    fun byId(id: String?): VoicePreset? = all.firstOrNull { it.id == id }

    /** MiMo 识别文档的地址（识别页的“官方文档”） */
    const val MIMO_ASR_DOCS = "https://mimo.mi.com/docs/zh-CN/api/audio/Speech-Recognition"

    fun docs(preset: VoicePreset, kind: ProviderKind): String = if (preset == MIMO && kind == ProviderKind.ASR) MIMO_ASR_DOCS else preset.docs
}
