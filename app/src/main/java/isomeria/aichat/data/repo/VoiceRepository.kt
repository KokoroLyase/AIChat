package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.core.VoiceSettingsSource
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.voice.AsrRequest
import isomeria.aichat.data.voice.AsrResult
import isomeria.aichat.data.voice.TtsAudio
import isomeria.aichat.data.voice.TtsRequest
import isomeria.aichat.data.voice.VoiceProtocol
import isomeria.aichat.data.voice.VoiceService
import isomeria.aichat.data.voice.WavFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/** 语音合成或识别用不了：还没选来源、选的服务商不在了、没有 Key、地址不对。 */
class VoiceUnavailableException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NO_SOURCE, NO_KEY, BAD_URL }
}

/** 「我 → 模型服务」里一类语音服务商（合成或识别）和现在选用的是哪个。 */
data class VoiceProviders(val providers: List<ProviderConfig>, val selectedId: Long?)

/**
 * 语音合成和识别的服务商（0.7.0）：provider_config 里 kind 为 TTS、ASR 的行，和选用哪个（[VoiceSettings] 的来源）。
 *
 * - 合成和识别分别配置，各自选一个来源；Key 和大语言模型一样经 Keystore 加密，只发到它自己的 Base URL
 * - 选用的来源存成 `provider:{id}`，以后的本机来源（0.7.1）用别的前缀，不改表和备份格式
 * - 用量：识别按音频秒数、合成按字数和音频秒数记，单独分类，不并进 token 统计
 */
@Singleton
class VoiceRepository @Inject constructor(
    private val db: AppDatabase,
    private val cipher: SecretCipher,
    private val settings: VoiceSettingsSource,
    private val api: VoiceService,
    private val usage: UsageRepository,
) {
    private val dao get() = db.providerDao()

    fun observe(kind: ProviderKind, selection: Flow<VoiceSettings>): Flow<VoiceProviders> =
        combine(dao.observeByKind(kind), selection) { list, voice -> VoiceProviders(list, selectedId(kind, voice, list)) }

    suspend fun list(kind: ProviderKind): List<ProviderConfig> = dao.listByKind(kind)

    suspend fun config(id: Long): ProviderConfig? = dao.get(id)

    fun apiKey(config: ProviderConfig): String? = config.apiKeyEncrypted?.let(cipher::decrypt)

    fun maskedKey(config: ProviderConfig): String? {
        val key = apiKey(config) ?: return null
        if (key.length <= 8) return "••••••"
        return key.take(4) + "••••••" + key.takeLast(4)
    }

    /** 现在选用的服务商；没选、选的不在了或类型不对时为 null */
    suspend fun selected(kind: ProviderKind): ProviderConfig? {
        val source = VoiceSource.parse(source(kind, settings.voiceSettings())) as? VoiceSource.Provider ?: return null
        return dao.get(source.id)?.takeIf { it.kind == kind }
    }

    /** 现在能不能合成或识别：选了服务商，有 Key，地址合规（不发请求） */
    suspend fun available(kind: ProviderKind): Boolean = runCatching { endpoint(kind) }.isSuccess

    /**
     * 新建一个语音服务商，返回 id。[baseUrl] 要已经过 [BaseUrlRules.normalize]。
     * 这一类里还没有选用的服务商时，新建的直接选用（第一次配置不用再多点一下）
     */
    suspend fun create(kind: ProviderKind, protocol: String, name: String, baseUrl: String, apiKey: String?, model: String, voice: String, presetId: String?): Long {
        require(kind != ProviderKind.LLM && protocol in VoiceProtocol.all)
        val id = dao.insert(
            ProviderConfig(
                kind = kind,
                provider = protocol,
                name = name.trim(),
                baseUrl = baseUrl,
                apiKeyEncrypted = apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt),
                defaultModel = model.trim(),
                presetId = presetId,
                compatibility = "",
                voice = voice.trim(),
            ),
        )
        if (selected(kind) == null) select(kind, id)
        return id
    }

    /** 保存；[apiKey] 为 null 时保持原 Key，为空串时清除 */
    suspend fun save(id: Long, protocol: String, name: String, baseUrl: String, apiKey: String?, model: String, voice: String) {
        val current = dao.get(id) ?: return
        require(protocol in VoiceProtocol.all)
        dao.update(
            current.copy(
                provider = protocol,
                name = name.trim().ifEmpty { current.name },
                baseUrl = baseUrl,
                apiKeyEncrypted = when {
                    apiKey == null -> current.apiKeyEncrypted
                    apiKey.isBlank() -> null
                    else -> cipher.encrypt(apiKey.trim())
                },
                defaultModel = model.trim(),
                voice = voice.trim(),
            ),
        )
    }

    /** 删除；删的是正在用的，这一类就变成没有选 */
    suspend fun delete(id: Long) {
        val config = dao.get(id) ?: return
        db.withTransaction { dao.delete(id) }
        val voice = settings.voiceSettings()
        if (VoiceSource.parse(source(config.kind, voice)) == VoiceSource.Provider(id)) setSource(config.kind, "")
    }

    fun select(kind: ProviderKind, id: Long?) = setSource(kind, id?.let(VoiceSource::provider).orEmpty())

    private fun setSource(kind: ProviderKind, value: String) {
        val voice = settings.voiceSettings()
        settings.setVoiceSettings(
            when (kind) {
                ProviderKind.TTS -> voice.copy(ttsSource = value)
                ProviderKind.ASR -> voice.copy(asrSource = value)
                ProviderKind.LLM -> voice
            },
        )
    }

    /**
     * 合成一段话。音色用联系人自己的（没填用服务商默认），语气和风格要求只在聊天接口带音频的格式里发。
     * 失败时抛 [VoiceUnavailableException] 或 [isomeria.aichat.data.llm.LlmException]
     */
    suspend fun synthesize(contact: Contact, text: String): TtsAudio {
        val (config, endpoint) = endpoint(ProviderKind.TTS)
        val request = TtsRequest(
            model = config.defaultModel,
            text = text,
            voice = contact.ttsVoice?.takeIf { it.isNotBlank() } ?: config.voice,
            style = if (config.provider == VoiceProtocol.CHAT_AUDIO) contact.ttsStyle else "",
        )
        return api.synthesize(config.provider, endpoint, request)
    }

    /** 合成成功后记用量：字数和音频秒数 */
    suspend fun recordSynthesis(contactId: Long, chars: Int, millis: Long) =
        usage.record(contactId, UsagePurpose.TTS, chars, seconds(millis))

    /** 识别一段 16 kHz 单声道 WAV，记用量（服务商报告了时长就用它，否则按 WAV 长度算） */
    suspend fun transcribe(contactId: Long, wav: ByteArray): AsrResult {
        val (config, endpoint) = endpoint(ProviderKind.ASR)
        val result = api.transcribe(config.provider, endpoint, AsrRequest(config.defaultModel, wav))
        val seconds = result.seconds?.let { ceil(it).toInt() } ?: seconds(WavFormat.durationMillis(wav.size.toLong()))
        usage.record(contactId, UsagePurpose.ASR, seconds, 0)
        return result
    }

    private suspend fun endpoint(kind: ProviderKind): Pair<ProviderConfig, LlmEndpoint> {
        val config = selected(kind) ?: throw VoiceUnavailableException(VoiceUnavailableException.Reason.NO_SOURCE)
        val url = BaseUrlRules.normalize(config.baseUrl, BuildConfig.DEBUG) ?: throw VoiceUnavailableException(VoiceUnavailableException.Reason.BAD_URL)
        val key = apiKey(config)?.takeIf { it.isNotEmpty() } ?: throw VoiceUnavailableException(VoiceUnavailableException.Reason.NO_KEY)
        return config to LlmEndpoint(url, key)
    }

    private fun source(kind: ProviderKind, voice: VoiceSettings) = when (kind) {
        ProviderKind.TTS -> voice.ttsSource
        ProviderKind.ASR -> voice.asrSource
        ProviderKind.LLM -> ""
    }

    private fun selectedId(kind: ProviderKind, voice: VoiceSettings, list: List<ProviderConfig>): Long? =
        (VoiceSource.parse(source(kind, voice)) as? VoiceSource.Provider)?.id?.takeIf { id -> list.any { it.id == id } }

    private fun seconds(millis: Long): Int = ((millis + 999) / 1000).toInt()
}
