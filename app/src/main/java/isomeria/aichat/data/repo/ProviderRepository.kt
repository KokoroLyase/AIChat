package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.DeepSeekDefaults
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderDao
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.llm.RemoteModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 服务商配置、API Key（加密存放）和模型列表。 */
@Singleton
class ProviderRepository @Inject constructor(
    private val db: AppDatabase,
    private val dao: ProviderDao,
    private val cipher: SecretCipher,
    providers: Set<@JvmSuppressWildcards LlmProvider>,
) {
    private val providersById = providers.associateBy { it.id }

    val llmConfig: Flow<ProviderConfig?> = dao.observeLlm()

    val hasApiKey: Flow<Boolean> = llmConfig.map { !it?.apiKeyEncrypted.isNullOrEmpty() }.distinctUntilChanged()

    fun observeModels(providerId: Long = DeepSeekDefaults.PROVIDER_ID): Flow<List<ProviderModel>> =
        dao.observeModels(providerId)

    suspend fun llm(): ProviderConfig = dao.getLlm() ?: error("缺少默认服务商配置")

    suspend fun config(id: Long): ProviderConfig? = dao.get(id)

    suspend fun models(providerId: Long): List<ProviderModel> = dao.models(providerId)

    suspend fun model(providerId: Long, modelId: String): ProviderModel? = dao.model(providerId, modelId)

    fun provider(config: ProviderConfig): LlmProvider =
        providersById[config.provider] ?: error("不支持的服务商：${config.provider}")

    /** 解密后的 API Key；未配置或无法解密（如换机后）时返回 null */
    fun apiKey(config: ProviderConfig): String? = config.apiKeyEncrypted?.let(cipher::decrypt)

    fun endpoint(config: ProviderConfig): LlmEndpoint? = apiKey(config)?.let { LlmEndpoint(config.baseUrl, it) }

    /** 界面显示用的打码 Key，如 sk-a••••••3f9c */
    fun maskedKey(config: ProviderConfig): String? {
        val key = apiKey(config) ?: return null
        if (key.length <= 8) return "••••••"
        return key.take(4) + "••••••" + key.takeLast(4)
    }

    /**
     * 保存配置。[apiKey] 为 null 时保持原 Key 不变，为空串时清除。
     */
    suspend fun save(baseUrl: String, apiKey: String?, defaultModel: String) {
        val current = llm()
        val encrypted = when {
            apiKey == null -> current.apiKeyEncrypted
            apiKey.isBlank() -> null
            else -> cipher.encrypt(apiKey.trim())
        }
        dao.update(
            current.copy(
                baseUrl = baseUrl.trim().trimEnd('/').ifEmpty { DeepSeekDefaults.BASE_URL },
                apiKeyEncrypted = encrypted,
                defaultModel = defaultModel,
            ),
        )
    }

    suspend fun setDefaultModel(modelId: String) {
        dao.update(llm().copy(defaultModel = modelId))
    }

    /**
     * 测试连接：用给定的 Base URL 和 Key 拉取模型列表，成功时写入模型列表。
     * [apiKey] 为 null 时用已保存的 Key。
     */
    suspend fun testConnection(baseUrl: String, apiKey: String?): List<RemoteModel> {
        val config = llm()
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: apiKey(config)
            ?: throw LlmException(LlmError.AUTH, message = "未填写 API Key")
        val remote = provider(config).listModels(LlmEndpoint(baseUrl.trim().ifEmpty { config.baseUrl }, key))
        if (remote.isNotEmpty()) applyRemoteModels(config.id, remote)
        return remote
    }

    /** 用接口返回的模型列表替换本地列表，保留用户对「是否支持图片」的标记 */
    private suspend fun applyRemoteModels(providerId: Long, remote: List<RemoteModel>) {
        db.withTransaction {
            val existing = dao.models(providerId).associateBy { it.modelId }
            val known = DeepSeekDefaults.knownModels.associateBy { it.modelId }
            val merged = remote.mapIndexed { index, model ->
                val old = existing[model.id]
                ProviderModel(
                    providerId = providerId,
                    modelId = model.id,
                    displayName = model.name?.takeIf { it.isNotBlank() }
                        ?: known[model.id]?.displayName
                        ?: model.id,
                    imageInput = when (model.supportsImage) {
                        true -> Capability.YES
                        false -> Capability.NO
                        null -> known[model.id]?.imageInput ?: Capability.UNKNOWN
                    },
                    imageInputUserMark = old?.imageInputUserMark,
                    sortOrder = index,
                )
            }
            dao.upsertModels(merged)
            dao.deleteModelsExcept(providerId, merged.map { it.modelId })
        }
    }

    suspend fun setImageMark(providerId: Long, modelId: String, supports: Boolean) {
        dao.setImageMark(providerId, modelId, supports)
    }

    suspend fun supportsImage(providerId: Long, modelId: String): Boolean =
        dao.model(providerId, modelId)?.supportsImage ?: false
}
