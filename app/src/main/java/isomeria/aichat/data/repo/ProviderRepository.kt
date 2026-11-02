package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.DeepSeekDefaults
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderDao
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.llm.RemoteModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** 删除服务商的结果。 */
sealed interface DeleteProviderResult {
    data object Deleted : DeleteProviderResult

    /** 还有联系人在用，不能删 */
    data class InUse(val contacts: Int) : DeleteProviderResult

    /** 至少要保留一个服务商 */
    data object LastOne : DeleteProviderResult
}

/** Base URL 的规则：只接受 https（明文会把 Key 和聊天内容暴露在网络上，系统默认也不允许）。 */
object BaseUrlRules {
    /**
     * 规范化（去掉首尾空白和末尾的斜杠）；不合规时返回 null。
     * [allowLocalHttp] 只有 debug 包才打开，用来连本机的模拟接口。
     */
    fun normalize(input: String, allowLocalHttp: Boolean = false): String? {
        val text = input.trim().trimEnd('/')
        val url = text.toHttpUrlOrNull() ?: return null
        val local = url.host == "localhost" || url.host == "127.0.0.1"
        if (url.scheme != "https" && !(allowLocalHttp && local)) return null
        return text
    }

    fun isSameUrl(a: String, b: String): Boolean = a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

    /** 手动添加 DeepSeek 时也用 DeepSeek 的思考字段；其他一律按通用格式 */
    fun protocolOf(baseUrl: String): String =
        if (baseUrl.toHttpUrlOrNull()?.host == DEEPSEEK_HOST) ProviderProtocol.DEEPSEEK else ProviderProtocol.OPENAI

    private const val DEEPSEEK_HOST = "api.deepseek.com"
}

/** 服务商配置、API Key（加密存放）和模型列表。 */
@Singleton
class ProviderRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: ProviderDao,
    private val cipher: SecretCipher,
    providers: Set<@JvmSuppressWildcards LlmProvider>,
) {
    private val providersById = providers.associateBy { it.id }

    val llmConfigs: Flow<List<ProviderConfig>> = dao.observeLlmList()

    /** 至少有一个服务商配置了 Key */
    val hasApiKey: Flow<Boolean> = llmConfigs.map { list -> list.any { !it.apiKeyEncrypted.isNullOrEmpty() } }
        .distinctUntilChanged()

    fun observeConfig(id: Long): Flow<ProviderConfig?> = dao.observe(id)

    fun observeModels(providerId: Long): Flow<List<ProviderModel>> = dao.observeModels(providerId)

    suspend fun list(): List<ProviderConfig> = dao.llmList()

    /** 新建联系人默认用的服务商：第一个配置了 Key 的，都没有时用第一个 */
    suspend fun defaultLlm(): ProviderConfig {
        val list = dao.llmList()
        return list.firstOrNull { !it.apiKeyEncrypted.isNullOrEmpty() }
            ?: list.firstOrNull()
            ?: error("缺少服务商配置")
    }

    suspend fun config(id: Long): ProviderConfig? = dao.get(id)

    suspend fun models(providerId: Long): List<ProviderModel> = dao.models(providerId)

    suspend fun model(providerId: Long, modelId: String): ProviderModel? = dao.model(providerId, modelId)

    fun provider(config: ProviderConfig): LlmProvider = provider(config.provider)

    private fun provider(protocol: String): LlmProvider =
        providersById[protocol] ?: error("不支持的请求格式：$protocol")

    /** 解密后的 API Key；未配置或无法解密（如换机后）时返回 null */
    fun apiKey(config: ProviderConfig): String? = config.apiKeyEncrypted?.let(cipher::decrypt)

    fun endpoint(config: ProviderConfig): LlmEndpoint? = apiKey(config)?.let { LlmEndpoint(config.baseUrl, it) }

    /** 界面显示用的打码 Key，如 sk-a••••••3f9c */
    fun maskedKey(config: ProviderConfig): String? {
        val key = apiKey(config) ?: return null
        if (key.length <= 8) return "••••••"
        return key.take(4) + "••••••" + key.takeLast(4)
    }

    /** 新增服务商，返回 id。[baseUrl] 需已经过 [BaseUrlRules.normalize] */
    suspend fun create(name: String, baseUrl: String, apiKey: String?): Long = dao.insert(
        ProviderConfig(
            kind = ProviderKind.LLM,
            provider = BaseUrlRules.protocolOf(baseUrl),
            name = name.trim(),
            baseUrl = baseUrl,
            apiKeyEncrypted = apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt),
            defaultModel = "",
        ),
    )

    /**
     * 保存配置。[apiKey] 为 null 时保持原 Key 不变，为空串时清除。
     * 地址改成 DeepSeek 官方时，请求格式也跟着改；其他情况不变（DeepSeek 服务商换成代理地址仍是 DeepSeek 格式）。
     */
    suspend fun save(id: Long, name: String, baseUrl: String, apiKey: String?, defaultModel: String) {
        val current = dao.get(id) ?: return
        val encrypted = when {
            apiKey == null -> current.apiKeyEncrypted
            apiKey.isBlank() -> null
            else -> cipher.encrypt(apiKey.trim())
        }
        val protocol = if (BaseUrlRules.protocolOf(baseUrl) == ProviderProtocol.DEEPSEEK) ProviderProtocol.DEEPSEEK else current.provider
        dao.update(
            current.copy(
                provider = protocol,
                name = name.trim().ifEmpty { current.name },
                baseUrl = baseUrl,
                apiKeyEncrypted = encrypted,
                defaultModel = defaultModel,
            ),
        )
    }

    suspend fun setDefaultModel(id: Long, modelId: String) {
        dao.get(id)?.let { dao.update(it.copy(defaultModel = modelId)) }
    }

    /** 还有联系人在用、或只剩这一个时不能删 */
    suspend fun delete(id: Long): DeleteProviderResult = db.withTransaction {
        val contacts = dao.contactCount(id)
        when {
            contacts > 0 -> DeleteProviderResult.InUse(contacts)
            dao.llmList().size <= 1 -> DeleteProviderResult.LastOne
            else -> {
                dao.deleteModels(id)
                dao.delete(id)
                DeleteProviderResult.Deleted
            }
        }
    }

    /**
     * 拉取模型列表：用给定的 Base URL 和 Key 请求接口。[apiKey] 为空时用 [config] 已保存的 Key。
     * 这一步不改数据库，成功后调用 [applyRemoteModels] 写入。
     */
    suspend fun fetchModels(config: ProviderConfig?, baseUrl: String, apiKey: String?): List<RemoteModel> {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: config?.let(::apiKey)
            ?: throw LlmException(LlmError.AUTH, message = "未填写 API Key")
        val protocol = config?.provider ?: BaseUrlRules.protocolOf(baseUrl)
        return provider(protocol).listModels(LlmEndpoint(baseUrl, key))
    }

    /**
     * 用接口返回的列表更新本地模型：保留用户的标记和手动添加的模型。
     * 还没有默认模型时，用列表里的第一个。
     */
    suspend fun applyRemoteModels(providerId: Long, remote: List<RemoteModel>) {
        if (remote.isEmpty()) return
        db.withTransaction {
            val config = dao.get(providerId) ?: return@withTransaction
            val existing = dao.models(providerId).associateBy { it.modelId }
            val known = if (config.provider == ProviderProtocol.DEEPSEEK) {
                DeepSeekDefaults.knownModels.associateBy { it.modelId }
            } else {
                emptyMap()
            }
            val merged = remote.mapIndexed { index, model ->
                val old = existing[model.id]
                ProviderModel(
                    providerId = providerId,
                    modelId = model.id,
                    displayName = model.name?.takeIf { it.isNotBlank() }
                        ?: known[model.id]?.displayName
                        ?: model.id,
                    imageInput = capability(model.supportsImage) ?: known[model.id]?.imageInput ?: Capability.UNKNOWN,
                    imageInputUserMark = old?.imageInputUserMark,
                    sortOrder = index,
                    thinkingInput = capability(model.supportsThinking) ?: known[model.id]?.thinkingInput ?: Capability.UNKNOWN,
                    thinkingUserMark = old?.thinkingUserMark,
                    manual = false,
                )
            }
            dao.upsertModels(merged)
            dao.deleteModelsExcept(providerId, merged.map { it.modelId })
            if (config.defaultModel.isBlank()) dao.update(config.copy(defaultModel = merged.first().modelId))
        }
    }

    private fun capability(supports: Boolean?): Capability? = when (supports) {
        true -> Capability.YES
        false -> Capability.NO
        null -> null
    }

    /** 手动添加模型：接口没有模型列表时用。没有默认模型时设为默认 */
    suspend fun addManualModel(providerId: Long, modelId: String) {
        val id = modelId.trim()
        if (id.isEmpty()) return
        db.withTransaction {
            val config = dao.get(providerId) ?: return@withTransaction
            if (dao.model(providerId, id) == null) {
                val order = (dao.models(providerId).maxOfOrNull { it.sortOrder } ?: -1) + 1
                dao.upsertModels(listOf(ProviderModel(providerId, id, id, sortOrder = order, manual = true)))
            }
            if (config.defaultModel.isBlank()) dao.update(config.copy(defaultModel = id))
        }
    }

    /** 删除手动添加的模型。删的是默认模型时，默认模型换成列表里剩下的第一个 */
    suspend fun removeModel(providerId: Long, modelId: String) {
        db.withTransaction {
            dao.deleteModel(providerId, modelId)
            val config = dao.get(providerId) ?: return@withTransaction
            if (config.defaultModel == modelId) {
                dao.update(config.copy(defaultModel = dao.models(providerId).firstOrNull()?.modelId.orEmpty()))
            }
        }
    }

    suspend fun setImageMark(providerId: Long, modelId: String, supports: Boolean) {
        dao.setImageMark(providerId, modelId, supports)
    }

    suspend fun setThinkingMark(providerId: Long, modelId: String, supports: Boolean) {
        dao.setThinkingMark(providerId, modelId, supports)
    }

    suspend fun supportsImage(providerId: Long, modelId: String): Boolean =
        dao.model(providerId, modelId)?.supportsImage ?: false

    /** 列表里没有这个模型时：DeepSeek 按支持算（接口默认开启思考），其他按不支持算 */
    suspend fun supportsThinking(config: ProviderConfig, modelId: String): Boolean =
        dao.model(config.id, modelId)?.supportsThinking ?: (config.provider == ProviderProtocol.DEEPSEEK)
}
