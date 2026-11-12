package isomeria.aichat.data.voice

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 内置的官方音色列表（0.7.2，2026-11-12 按各家官方文档整理，数据在 resources 里的 builtin-voices.json）。
 * 可能不全，服务商新出的音色可以在输入框里手填 ID。
 *
 * - MiMo：mimo-v2.5-tts 的 9 个预置音色
 * - 火山引擎：豆包语音合成 2.0 的音色（资源 ID seed-tts-2.0；1.0 音色和复刻音色不在这里）
 * - 百炼：qwen-audio-3.1-tts-flash 的中文、英文、方言和其他系统音色（小语种音色没有列）
 * - 硅基流动：CosyVoice2-0.5B 的 8 个预置音色，ID 前面带模型名
 * - 智谱：glm-tts 的 7 个系统音色（复刻的音色用 Key 从服务商取）
 * - OpenRouter：没有取音色的接口，按模型名给 Gemini TTS 的 30 个、OpenAI 的 13 个，别的模型手填
 */
object BuiltInVoices {
    @Serializable
    private data class Entry(val id: String, val name: String, val detail: String = "", val group: String = "")

    @Serializable
    private data class Lists(
        val mimo: List<Entry> = emptyList(),
        val volcengine: List<Entry> = emptyList(),
        val qwen: List<Entry> = emptyList(),
        val siliconflow: List<Entry> = emptyList(),
        val zhipu: List<Entry> = emptyList(),
        val gemini: List<Entry> = emptyList(),
        val openai: List<Entry> = emptyList(),
    )

    private val lists: Lists by lazy {
        val text = requireNotNull(BuiltInVoices::class.java.getResourceAsStream(RESOURCE)) { "missing $RESOURCE" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString(Lists.serializer(), text)
    }

    private fun List<Entry>.options() = map { VoiceOption(it.id, it.name, it.detail, it.group) }

    val MIMO: List<VoiceOption> by lazy { lists.mimo.options() }
    val VOLCENGINE: List<VoiceOption> by lazy { lists.volcengine.options() }
    val QWEN: List<VoiceOption> by lazy { lists.qwen.options() }
    val ZHIPU: List<VoiceOption> by lazy { lists.zhipu.options() }
    val GEMINI: List<VoiceOption> by lazy { lists.gemini.options() }
    val OPENAI: List<VoiceOption> by lazy { lists.openai.options() }

    /** 这个来源的内置列表；没有内置列表时为 null */
    fun forTarget(target: VoiceTarget): List<VoiceOption>? = when {
        target.systemTts -> null
        target.protocol == VoiceProtocol.VOLC_TTS -> VOLCENGINE
        target.protocol == VoiceProtocol.QWEN_TTS -> QWEN
        target.protocol == VoiceProtocol.ZHIPU_TTS -> ZHIPU
        target.protocol == VoiceProtocol.OPENROUTER_TTS -> openRouter(target.model)
        target.presetId == VoicePresets.MIMO.id && target.protocol == VoiceProtocol.CHAT_AUDIO -> MIMO
        target.presetId == VoicePresets.SILICONFLOW.id && target.protocol == VoiceProtocol.OPENAI_AUDIO -> siliconFlow(target.model)
        else -> null
    }

    /** OpenRouter：Gemini TTS 和 OpenAI 的模型有内置列表，别的模型没有（空列表，页面提示手填） */
    fun openRouter(model: String): List<VoiceOption> {
        val id = model.trim().lowercase()
        return when {
            id.startsWith("google/gemini") && id.contains("tts") -> GEMINI
            id.startsWith("openai/") -> OPENAI
            else -> emptyList()
        }
    }

    /** 硅基流动 CosyVoice2 的预置音色：ID 是「模型名:音色名」，换了模型也跟着换前缀 */
    fun siliconFlow(model: String): List<VoiceOption> {
        val prefix = model.trim().ifEmpty { VoicePresets.SILICONFLOW.tts!!.model }
        return lists.siliconflow.map { VoiceOption("$prefix:${it.id}", it.name, it.detail, it.group) }
    }

    private const val RESOURCE = "/isomeria/aichat/data/voice/builtin-voices.json"
}
