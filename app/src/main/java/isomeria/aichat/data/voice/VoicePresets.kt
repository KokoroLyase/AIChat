package isomeria.aichat.data.voice

import isomeria.aichat.R
import isomeria.aichat.data.db.ProviderKind

/**
 * 预置服务商在语音合成或识别里的一种用法：请求格式、地址、模型（火山是资源 ID）、文档和默认音色。
 * [priceRes] 是这一项的价格说明，没有时为 0
 */
data class VoicePresetEndpoint(
    val protocol: String,
    val baseUrl: String,
    val model: String,
    val docs: String,
    val defaultVoice: String = "",
    val priceRes: Int = 0,
)

/**
 * 语音服务商的预置（2026-11-12 按官方文档核对）。预置只填新连接，不改已保存的地址；预置的请求格式不能改。
 *
 * - 火山引擎（豆包）：合成用豆包语音合成 2.0（资源 ID seed-tts-2.0），识别用录音文件识别 2.0 极速版（volc.bigasr.auc_turbo），
 *   只支持新版控制台的 API Key
 * - 阿里云百炼（千问）：合成 qwen-audio-3.1-tts-flash，识别 qwen3-asr-flash。地址要换成自己的业务空间专属地址
 *   （2026-09-30 起共享地址 dashscope.aliyuncs.com 不再加新功能，新模型的文档只给业务空间地址）
 * - Xiaomi MiMo：合成 `mimo-v2.5-tts`（官方写“限时免费”）、识别 `mimo-v2.5-asr`，聊天接口带音频的格式
 * - MiniMax：只有合成，speech-2.8-hd
 * - Fish Audio：只有合成，s2.1-pro；服务器在海外
 * - 硅基流动：合成 CosyVoice2-0.5B、识别 SenseVoiceSmall，标准音频接口
 *
 * 另有自定义入口，两种通用格式可选。
 */
data class VoicePreset(
    val id: String,
    val nameRes: Int,
    val tts: VoicePresetEndpoint? = null,
    val asr: VoicePresetEndpoint? = null,
) {
    fun endpoint(kind: ProviderKind): VoicePresetEndpoint? = when (kind) {
        ProviderKind.TTS -> tts
        ProviderKind.ASR -> asr
        ProviderKind.LLM -> null
    }

    fun supports(kind: ProviderKind): Boolean = endpoint(kind) != null
}

object VoicePresets {
    /** 百炼地址里要换成业务空间 ID 的部分 */
    const val WORKSPACE_PLACEHOLDER = "{WorkspaceId}"

    private const val VOLC_BASE = "https://openspeech.bytedance.com/api/v3"
    private const val QWEN_HOST = "https://$WORKSPACE_PLACEHOLDER.cn-beijing.maas.aliyuncs.com"
    private const val MIMO_BASE = "https://api.xiaomimimo.com/v1"
    private const val SILICONFLOW_BASE = "https://api.siliconflow.cn/v1"

    val VOLCENGINE = VoicePreset(
        id = "volcengine",
        nameRes = R.string.preset_volcengine,
        tts = VoicePresetEndpoint(
            VoiceProtocol.VOLC_TTS, VOLC_BASE, "seed-tts-2.0", "https://docs.volcengine.com/docs/6561/1598757",
            defaultVoice = "zh_female_vv_uranus_bigtts", priceRes = R.string.voice_price_volcengine,
        ),
        asr = VoicePresetEndpoint(
            VoiceProtocol.VOLC_ASR, VOLC_BASE, "volc.bigasr.auc_turbo", "https://docs.volcengine.com/docs/doubao-voice/large-model-recording-file-lite-recognition-api",
            priceRes = R.string.voice_price_volcengine,
        ),
    )

    val QWEN = VoicePreset(
        id = "qwen",
        nameRes = R.string.preset_qwen,
        tts = VoicePresetEndpoint(
            VoiceProtocol.QWEN_TTS, "$QWEN_HOST/api/v1", "qwen-audio-3.1-tts-flash", "https://help.aliyun.com/zh/model-studio/qwen-audio-tts-http-api",
            defaultVoice = "longanhuan_v3.1", priceRes = R.string.voice_price_qwen,
        ),
        asr = VoicePresetEndpoint(
            VoiceProtocol.QWEN_ASR, "$QWEN_HOST/compatible-mode/v1", "qwen3-asr-flash", "https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference",
            priceRes = R.string.voice_price_qwen,
        ),
    )

    val MIMO = VoicePreset(
        id = "mimo",
        nameRes = R.string.preset_mimo,
        tts = VoicePresetEndpoint(
            VoiceProtocol.CHAT_AUDIO, MIMO_BASE, "mimo-v2.5-tts", "https://mimo.mi.com/docs/zh-CN/quick-start/usage-guide/audio/speech-synthesis-v2.5",
            defaultVoice = "mimo_default", priceRes = R.string.voice_price_mimo_tts,
        ),
        asr = VoicePresetEndpoint(
            VoiceProtocol.CHAT_AUDIO, MIMO_BASE, "mimo-v2.5-asr", "https://mimo.mi.com/docs/zh-CN/api/audio/Speech-Recognition",
            priceRes = R.string.voice_price_mimo_asr,
        ),
    )

    val MINIMAX = VoicePreset(
        id = "minimax",
        nameRes = R.string.preset_minimax,
        tts = VoicePresetEndpoint(
            VoiceProtocol.MINIMAX_TTS, "https://api.minimax.cn/v1", "speech-2.8-hd", "https://platform.minimax.cn/docs/api-reference/speech-t2a-http",
            defaultVoice = "male-qn-qingse", priceRes = R.string.voice_price_minimax,
        ),
    )

    val FISH = VoicePreset(
        id = "fish",
        nameRes = R.string.preset_fish,
        tts = VoicePresetEndpoint(
            VoiceProtocol.FISH_TTS, "https://api.fish.audio", "s2.1-pro", "https://docs.fish.audio/api-reference/endpoint/openapi-v1/text-to-speech",
            priceRes = R.string.voice_price_fish,
        ),
    )

    val SILICONFLOW = VoicePreset(
        id = "siliconflow",
        nameRes = R.string.preset_siliconflow,
        tts = VoicePresetEndpoint(
            VoiceProtocol.OPENAI_AUDIO, SILICONFLOW_BASE, "FunAudioLLM/CosyVoice2-0.5B", "https://docs.siliconflow.cn/docs/api/audio-speech-post",
            defaultVoice = "FunAudioLLM/CosyVoice2-0.5B:anna", priceRes = R.string.voice_price_siliconflow_tts,
        ),
        asr = VoicePresetEndpoint(
            VoiceProtocol.OPENAI_AUDIO, SILICONFLOW_BASE, "FunAudioLLM/SenseVoiceSmall", "https://api-docs.siliconflow.cn/docs/api/audio-transcriptions-post",
            priceRes = R.string.voice_price_siliconflow,
        ),
    )

    /** 列表里的顺序（用户 2026-11-12 定的） */
    private val ttsOrder = listOf(VOLCENGINE, QWEN, MIMO, MINIMAX, FISH, SILICONFLOW)
    private val asrOrder = listOf(VOLCENGINE, QWEN, MIMO, SILICONFLOW)

    val all = ttsOrder

    fun forKind(kind: ProviderKind): List<VoicePreset> = when (kind) {
        ProviderKind.TTS -> ttsOrder
        ProviderKind.ASR -> asrOrder
        ProviderKind.LLM -> emptyList()
    }

    fun byId(id: String?): VoicePreset? = all.firstOrNull { it.id == id }

    /** 地址里还留着 {WorkspaceId} 这类要替换的部分 */
    fun hasPlaceholder(url: String): Boolean = isomeria.aichat.data.repo.BaseUrlRules.hasPlaceholder(url)
}
