package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.LocalVoicePrefs
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
import isomeria.aichat.data.voice.OfflineAsrEngine
import isomeria.aichat.data.voice.OfflineModelStore
import isomeria.aichat.data.voice.PcmSamples
import isomeria.aichat.data.voice.SystemTts
import isomeria.aichat.data.voice.SystemTtsStatus
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
    /** [UNSUPPORTED]、[NO_MODEL]（0.7.1）：离线识别在这台手机上跑不了、模型还没下好 */
    enum class Reason { NO_SOURCE, NO_KEY, BAD_URL, UNSUPPORTED, NO_MODEL }
}

/**
 * 「我 → 模型服务」里一类语音服务商（合成或识别）和现在选用的是哪个。
 * 选用的是本机来源（0.7.1）时 [selectedId] 为 null，[local] 是那个来源
 */
data class VoiceProviders(val providers: List<ProviderConfig>, val selectedId: Long?, val local: VoiceSource.Local? = null) {
    /** 选用的来源写成字符串（和 [VoiceSettings] 里一样）；没选时是空串 */
    val source: String get() = local?.let(VoiceSource::of) ?: selectedId?.let(VoiceSource::provider).orEmpty()
}

/**
 * 语音合成和识别的服务商（0.7.0）：provider_config 里 kind 为 TTS、ASR 的行，和选用哪个（[VoiceSettings] 的来源）。
 *
 * - 合成和识别分别配置，各自选一个来源；Key 和大语言模型一样经 Keystore 加密，只发到它自己的 Base URL
 * - 选用的来源存成 `provider:{id}`；0.7.1 的本机来源存成 `local:{name}`（[VoiceSource.SYSTEM_TTS]、
 *   [VoiceSource.OFFLINE_ASR]），不改表和备份格式
 * - 用量：识别按音频秒数、合成按字数和音频秒数记，单独分类，不并进 token 统计；本机来源不调用服务商，不记用量
 */
@Singleton
class VoiceRepository @Inject constructor(
    private val db: AppDatabase,
    private val cipher: SecretCipher,
    private val settings: VoiceSettingsSource,
    private val api: VoiceService,
    private val usage: UsageRepository,
    private val systemTts: SystemTts,
    private val localPrefs: LocalVoicePrefs,
    private val offlineAsr: OfflineAsrEngine,
    private val models: OfflineModelStore,
) {
    private val dao get() = db.providerDao()

    fun observe(kind: ProviderKind, selection: Flow<VoiceSettings>): Flow<VoiceProviders> =
        combine(dao.observeByKind(kind), selection) { list, voice -> VoiceProviders(list, selectedId(kind, voice, list), selectedLocal(kind, voice)) }

    suspend fun list(kind: ProviderKind): List<ProviderConfig> = dao.listByKind(kind)

    suspend fun config(id: Long): ProviderConfig? = dao.get(id)

    fun apiKey(config: ProviderConfig): String? = config.apiKeyEncrypted?.let(cipher::decrypt)

    fun maskedKey(config: ProviderConfig): String? {
        val key = apiKey(config) ?: return null
        if (key.length <= 8) return "••••••"
        return key.take(4) + "••••••" + key.takeLast(4)
    }

    /**
     * 这个联系人这一类实际用的来源（0.7.2）：单独选了就用自己的，否则用「我 → 语音消息」里的；[contact] 为 null 时是全局的。
     * 联系人选的来源用不了时不换成全局的：录音和回复内容不发给没给这个联系人选的服务商
     */
    fun sourceFor(kind: ProviderKind, contact: Contact?): String = contactSource(kind, contact) ?: source(kind, settings.voiceSettings())

    /** 联系人单独选的来源；跟随全局时为 null */
    private fun contactSource(kind: ProviderKind, contact: Contact?): String? = when (kind) {
        ProviderKind.TTS -> contact?.ttsSource
        ProviderKind.ASR -> contact?.asrSource
        ProviderKind.LLM -> null
    }?.takeIf { it.isNotBlank() }

    /** 选用的服务商（[contact] 不为 null 时是这个联系人实际用的）；没选、选的不在了或类型不对时为 null */
    suspend fun selected(kind: ProviderKind, contact: Contact? = null): ProviderConfig? = providerOf(kind, sourceFor(kind, contact))

    /** [source] 指的服务商（0.7.2 联系人编辑页按表单里的来源用）；不是服务商、不在了或类型不对时为 null */
    suspend fun providerOf(kind: ProviderKind, source: String): ProviderConfig? {
        val parsed = VoiceSource.parse(source) as? VoiceSource.Provider ?: return null
        return dao.get(parsed.id)?.takeIf { it.kind == kind }
    }

    /** 选用的本机来源（0.7.1；[contact] 同上）；选的是服务商或没选时为 null */
    fun selectedLocal(kind: ProviderKind, contact: Contact? = null): VoiceSource.Local? = localOf(kind, sourceFor(kind, contact))

    /**
     * 能不能合成或识别（不发请求，[contact] 同上）：选了服务商，有 Key，地址合规；
     * 系统语音合成要有引擎和不联网的中文音色；离线识别要这台手机支持、模型已经下好
     */
    suspend fun available(kind: ProviderKind, contact: Contact? = null): Boolean {
        val source = sourceFor(kind, contact)
        return when (localOf(kind, source)) {
            VoiceSource.SYSTEM_TTS -> runCatching { systemTts.info().status == SystemTtsStatus.READY }.getOrDefault(false)
            VoiceSource.OFFLINE_ASR -> offlineAsr.supported() && models.installed()
            null -> runCatching { endpoint(kind, source) }.isSuccess
            else -> false
        }
    }

    /**
     * 有几位联系人单独选了这个来源（0.7.2）：删除服务商、删除离线模型前提示，删除后他们改为跟随全局（[releaseContacts]）
     */
    suspend fun contactsUsing(kind: ProviderKind, source: String): Int = db.contactDao().listAll().count { contactSource(kind, it) == source }

    /** 单独选了 [source] 的联系人改为跟随全局 */
    suspend fun releaseContacts(kind: ProviderKind, source: String) {
        db.withTransaction {
            for (contact in db.contactDao().listAll()) {
                if (contactSource(kind, contact) != source) continue
                db.contactDao().update(
                    when (kind) {
                        ProviderKind.TTS -> contact.copy(ttsSource = null)
                        ProviderKind.ASR -> contact.copy(asrSource = null)
                        ProviderKind.LLM -> contact
                    },
                )
            }
        }
    }

    /**
     * 新建一个语音服务商，返回 id。[baseUrl] 要已经过 [BaseUrlRules.normalize]。
     * 这一类里还没有选用的来源（服务商或本机来源）时，新建的直接选用（第一次配置不用再多点一下）
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
        if (selected(kind) == null && selectedLocal(kind) == null) select(kind, id)
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

    /** 删除；删的是正在用的，这一类就变成没有选；单独选了它的联系人改为跟随全局（0.7.2） */
    suspend fun delete(id: Long) {
        val config = dao.get(id) ?: return
        db.withTransaction {
            dao.delete(id)
            releaseContacts(config.kind, VoiceSource.provider(id))
        }
        val voice = settings.voiceSettings()
        if (VoiceSource.parse(source(config.kind, voice)) == VoiceSource.Provider(id)) setSource(config.kind, "")
    }

    fun select(kind: ProviderKind, id: Long?) = setSource(kind, id?.let(VoiceSource::provider).orEmpty())

    /** 选用本机来源（0.7.1）；[use] 为 false 时，正在用的是它才改成没有选 */
    fun selectLocal(kind: ProviderKind, source: VoiceSource.Local, use: Boolean) {
        when {
            use -> setSource(kind, VoiceSource.of(source))
            selectedLocal(kind) == source -> setSource(kind, "")
        }
    }

    /**
     * 本机来源不能再用时（离线模型删掉了，0.7.2）：全局用的是它就改成没有选，单独选了它的联系人改为跟随全局
     */
    suspend fun releaseLocal(kind: ProviderKind, source: VoiceSource.Local) {
        selectLocal(kind, source, use = false)
        releaseContacts(kind, VoiceSource.of(source))
    }

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
     * 合成一段话。音色用联系人自己的（没填用服务商默认），语气和风格要求只发给支持的格式（[VoiceProtocol.supportsStyle]）。
     * 失败时抛 [VoiceUnavailableException] 或 [isomeria.aichat.data.llm.LlmException]
     */
    suspend fun synthesize(contact: Contact, text: String): TtsAudio {
        val source = sourceFor(ProviderKind.TTS, contact)
        if (localOf(ProviderKind.TTS, source) == VoiceSource.SYSTEM_TTS) {
            // 联系人填的音色是这台手机上的系统音色时用它，否则用「系统语音」页选的（引擎里没有时用默认中文音色）
            // （联系人可能填的是服务商的音色 ID，比如以前给 MiMo 填的，这时不能拿去当系统音色）
            val listed = runCatching { systemTts.info().voices.map { it.name } }.getOrDefault(emptyList())
            val voice = contact.ttsVoice?.takeIf { it.isNotBlank() && it in listed } ?: localPrefs.systemTtsVoice
            val wav = try {
                systemTts.synthesize(text, voice.ifBlank { null })
            } catch (e: java.io.IOException) {
                throw isomeria.aichat.data.llm.LlmException(isomeria.aichat.data.llm.LlmError.UNKNOWN, message = e.message, cause = e)
            }
            return TtsAudio(wav, "wav", billable = false)
        }
        val (config, endpoint) = endpoint(ProviderKind.TTS, source)
        val request = TtsRequest(
            model = config.defaultModel,
            text = text,
            voice = contact.ttsVoice?.takeIf { it.isNotBlank() } ?: config.voice,
            style = if (VoiceProtocol.supportsStyle(config.provider, config.defaultModel)) contact.ttsStyle else "",
        )
        return api.synthesize(config.provider, endpoint, request)
    }

    /** 合成成功后记用量：字数和音频秒数 */
    suspend fun recordSynthesis(contactId: Long, chars: Int, millis: Long) =
        usage.record(contactId, UsagePurpose.TTS, chars, seconds(millis))

    /**
     * 识别一段 16 kHz 单声道 WAV，记用量（服务商报告了时长就用它，否则按 WAV 长度算）。用这个联系人实际的识别来源（0.7.2）。
     * 选用离线识别（0.7.1）时在手机上识别，不发请求、不记用量；识别不出文字时和服务商返回空内容一样算失败
     */
    suspend fun transcribe(contactId: Long, wav: ByteArray): AsrResult {
        val source = sourceFor(ProviderKind.ASR, db.contactDao().get(contactId))
        if (localOf(ProviderKind.ASR, source) == VoiceSource.OFFLINE_ASR) {
            if (!offlineAsr.supported()) throw VoiceUnavailableException(VoiceUnavailableException.Reason.UNSUPPORTED)
            if (!models.installed()) throw VoiceUnavailableException(VoiceUnavailableException.Reason.NO_MODEL)
            val text = try {
                val (samples, rate) = PcmSamples.fromWav(wav)
                offlineAsr.transcribe(models.dir, samples, rate)
            } catch (e: java.io.IOException) {
                throw isomeria.aichat.data.llm.LlmException(isomeria.aichat.data.llm.LlmError.UNKNOWN, message = e.message, cause = e)
            }
            if (text.isBlank()) throw isomeria.aichat.data.llm.LlmException(isomeria.aichat.data.llm.LlmError.UNKNOWN, message = "EmptyResult")
            return AsrResult(text.trim())
        }
        val (config, endpoint) = endpoint(ProviderKind.ASR, source)
        val result = api.transcribe(config.provider, endpoint, AsrRequest(config.defaultModel, wav))
        val seconds = result.seconds?.let { ceil(it).toInt() } ?: seconds(WavFormat.durationMillis(wav.size.toLong()))
        usage.record(contactId, UsagePurpose.ASR, seconds, 0)
        return result
    }

    private suspend fun endpoint(kind: ProviderKind, source: String): Pair<ProviderConfig, LlmEndpoint> {
        val config = providerOf(kind, source) ?: throw VoiceUnavailableException(VoiceUnavailableException.Reason.NO_SOURCE)
        val url = BaseUrlRules.normalize(config.baseUrl, BuildConfig.DEBUG) ?: throw VoiceUnavailableException(VoiceUnavailableException.Reason.BAD_URL)
        val key = apiKey(config)?.takeIf { it.isNotEmpty() } ?: throw VoiceUnavailableException(VoiceUnavailableException.Reason.NO_KEY)
        return config to LlmEndpoint(url, key)
    }

    private fun source(kind: ProviderKind, voice: VoiceSettings) = when (kind) {
        ProviderKind.TTS -> voice.ttsSource
        ProviderKind.ASR -> voice.asrSource
        ProviderKind.LLM -> ""
    }

    private fun selectedLocal(kind: ProviderKind, voice: VoiceSettings): VoiceSource.Local? = localOf(kind, source(kind, voice))

    /** [source] 指的本机来源；合成只认系统语音，识别只认离线识别 */
    fun localOf(kind: ProviderKind, source: String): VoiceSource.Local? {
        val local = VoiceSource.parse(source) as? VoiceSource.Local ?: return null
        // 合成只认系统语音，识别只认离线模型；别的（例如以后版本写进备份的新来源）按没有选处理
        return local.takeIf { (kind == ProviderKind.TTS && it == VoiceSource.SYSTEM_TTS) || (kind == ProviderKind.ASR && it == VoiceSource.OFFLINE_ASR) }
    }

    private fun selectedId(kind: ProviderKind, voice: VoiceSettings, list: List<ProviderConfig>): Long? =
        (VoiceSource.parse(source(kind, voice)) as? VoiceSource.Provider)?.id?.takeIf { id -> list.any { it.id == id } }

    private fun seconds(millis: Long): Int = ((millis + 999) / 1000).toInt()
}
