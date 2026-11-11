package isomeria.aichat.core

import isomeria.aichat.data.db.ReplyType

/**
 * 语音消息的全局设置（0.7.0，「我 → 语音消息」）。
 *
 * - [replyType]：联系人的消息发送类型的全局默认，联系人编辑页可以单独指定（用户 2026-11-11 决定：全局默认加联系人覆盖）
 * - [ttsSource]、[asrSource]：语音合成、语音识别用哪个来源，见 [VoiceSource]；空串表示还没有选
 */
data class VoiceSettings(
    val replyType: ReplyType = ReplyType.FOLLOW,
    val ttsSource: String = "",
    val asrSource: String = "",
)

interface VoiceSettingsSource {
    fun voiceSettings(): VoiceSettings
}

/**
 * 语音合成或识别的来源。存成字符串（MMKV 和备份里都是这样），以后加新的来源不用改表和备份格式：
 *
 * - `provider:{id}`：「我 → 模型服务」里配置的语音服务商（provider_config 的一行，kind 为 TTS 或 ASR）
 * - 0.7.1 起还有本机来源（系统语音合成、离线识别模型），没有 Key 和地址，用别的前缀表示
 *
 * 认不出的值（例如更新的 App 写进备份的新来源）按“没有选”处理。
 */
sealed interface VoiceSource {
    data class Provider(val id: Long) : VoiceSource

    /** 本机运行的来源（0.7.1 起），[name] 区分是哪一种 */
    data class Local(val name: String) : VoiceSource

    companion object {
        private const val PROVIDER = "provider:"
        private const val LOCAL = "local:"

        fun parse(value: String): VoiceSource? = when {
            value.startsWith(PROVIDER) -> value.removePrefix(PROVIDER).toLongOrNull()?.takeIf { it > 0 }?.let(::Provider)
            value.startsWith(LOCAL) -> value.removePrefix(LOCAL).takeIf { it.isNotBlank() }?.let(::Local)
            else -> null
        }

        fun of(source: VoiceSource): String = when (source) {
            is Provider -> PROVIDER + source.id
            is Local -> LOCAL + source.name
        }

        fun provider(id: Long): String = of(Provider(id))
    }
}

/**
 * 这一轮回复发文字还是语音（纯函数，便于测试）。
 *
 * - [contactType] 为空时用全局默认 [globalType]
 * - 跟随模式：这次等待回复的消息里最后一条是语音就回语音，否则回文字；主动消息没有可以跟随的消息，回文字（用户 2026-11-11 决定）
 * - 语音模式：回语音，主动消息也是（用户 2026-11-11 决定）
 * - 没有可用的语音合成来源时一律回文字
 */
object ReplyTypeRule {
    fun voiceReply(contactType: ReplyType?, globalType: ReplyType, lastWaitingIsVoice: Boolean?, ttsAvailable: Boolean): Boolean {
        if (!ttsAvailable) return false
        return when (contactType ?: globalType) {
            ReplyType.TEXT -> false
            ReplyType.VOICE -> true
            ReplyType.FOLLOW -> lastWaitingIsVoice == true
        }
    }
}
