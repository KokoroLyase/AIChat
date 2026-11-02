package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.BuildConfig
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
import isomeria.aichat.data.llm.*
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        return text
    }

    fun isSameUrl(a: String, b: String): Boolean {
        val left = a.trim().trimEnd('/').toHttpUrlOrNull() ?: return false
        val right = b.trim().trimEnd('/').toHttpUrlOrNull() ?: return false
        return left == right
    }


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

    /** 每个服务商已配置好的模型数量 */
    val usableModelCounts: Flow<Map<Long, Int>> = combine(llmConfigs, dao.observeAllModels()) { configs, models ->
        val byProvider = models.groupBy { it.providerId }
        configs.associate { config -> config.id to byProvider[config.id].orEmpty().count { ModelRules.usable(config, it) } }
    }

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

    fun endpoint(config: ProviderConfig): LlmEndpoint? {
        val url = BaseUrlRules.normalize(config.baseUrl, BuildConfig.DEBUG) ?: throw LlmException(LlmError.CONFIGURATION)
        return apiKey(config)?.let { LlmEndpoint(url, it) }
    }

    /** 界面显示用的打码 Key，如 sk-a••••••3f9c */
    fun maskedKey(config: ProviderConfig): String? {
        val key = apiKey(config) ?: return null
        if (key.length <= 8) return "••••••"
        return key.take(4) + "••••••" + key.takeLast(4)
    }

    /** 新增服务商，返回 id。[baseUrl] 需已经过 [BaseUrlRules.normalize] */
    suspend fun create(name: String, baseUrl: String, apiKey: String?, presetId: String? = null, compatibility: String = "GENERIC"): Long = dao.insert(
        ProviderConfig(
            kind = ProviderKind.LLM,
            provider = ProviderProtocol.OPENAI,
            presetId = presetId,
            compatibility = compatibility,
            name = name.trim(),
            baseUrl = baseUrl,
            apiKeyEncrypted = apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let(cipher::encrypt),
            defaultModel = "",
        ),
    )

    /**
     * 保存配置。[apiKey] 为 null 时保持原 Key 不变，为空串时清除。
     * 仅按用户明确选择修改兼容方案；地址变化不推断品牌或请求格式。
     */
    suspend fun save(id: Long, name: String, baseUrl: String, apiKey: String?, defaultModel: String, compatibility: String? = null) {
        val current = dao.get(id) ?: return
        val encrypted = when {
            apiKey == null -> current.apiKeyEncrypted
            apiKey.isBlank() -> null
            else -> cipher.encrypt(apiKey.trim())
        }
        dao.update(
            current.copy(
                compatibility = compatibility ?: current.compatibility,
                name = name.trim().ifEmpty { current.name },
                baseUrl = baseUrl,
                apiKeyEncrypted = encrypted,
                defaultModel = defaultModel,
            ),
        )
    }

    suspend fun setDefaultModel(id: Long, modelId: String) {
        db.withTransaction {
            val config = dao.get(id) ?: return@withTransaction
            require(modelId.isBlank() || dao.model(id, modelId)?.let { ModelRules.usable(config, it) } == true)
            dao.update(config.copy(defaultModel = modelId))
        }
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
        if (BaseUrlRules.normalize(baseUrl, BuildConfig.DEBUG) == null) throw LlmException(LlmError.CONFIGURATION)
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: config?.let(::apiKey)
            ?: throw LlmException(LlmError.AUTH, message = "未填写 API Key")
        val protocol = config?.provider ?: ProviderProtocol.OPENAI
        return provider(protocol).listModels(LlmEndpoint(baseUrl, key))
    }

    /**
     * 用接口返回的列表更新本地模型：保留用户的标记和手动添加的模型。
     * 不自动选择默认模型；没有返回的受保护项只标记。
     */
    suspend fun applyRemoteModels(providerId: Long, remote: List<RemoteModel>) {
        if (remote.isEmpty()) return
        require(remote.all { it.id.isNotBlank() } && remote.map { it.id }.distinct().size == remote.size)
        db.withTransaction {
            val config = dao.get(providerId) ?: return@withTransaction
            val existing = dao.models(providerId).associateBy { it.modelId }
            val referenced = db.contactDao().listAll().filter { it.providerId == providerId }
                .flatMap { listOf(it.model, it.memoryModel) }.filterNotNull().toSet() + config.defaultModel
            val merged = remote.mapIndexed { index, model ->
                val old = existing[model.id]
                (old ?: ProviderModel(providerId, model.id, model.id, legacy = false)).copy(
                    displayName = model.name?.takeIf { it.isNotBlank() } ?: old?.displayName ?: model.id,
                    imageInput = capability(model.supportsImage) ?: old?.imageInput ?: Capability.UNKNOWN,
                    thinkingInput = capability(model.supportsThinking) ?: old?.thinkingInput ?: Capability.UNKNOWN,
                    reportedConfig = ModelRules.encode(model.config.over(ModelRules.decode(old?.reportedConfig))),
                    sortOrder = index,
                    missingFromList = false,
                )
            }
            val returned = merged.map { it.modelId }.toSet()
            val retained = existing.values.filter { it.modelId !in returned &&
                (it.manual || it.legacy || ModelRules.complete(it) || it.modelId in referenced || it.userConfig != null) }
                .map { it.copy(missingFromList = true) }
            dao.upsertModels(merged + retained)
            dao.deleteModelsExcept(providerId, (merged + retained).map { it.modelId })
        }
    }

    suspend fun remoteConflicts(config: ProviderConfig, remote: List<RemoteModel>): List<ModelReference> {
        val replacements = remote.mapNotNull { r ->
            val old = dao.model(config.id, r.id) ?: return@mapNotNull null
            if (old.legacy) null else old.copy(
                reportedConfig = ModelRules.encode(r.config.over(ModelRules.decode(old.reportedConfig))),
                imageInput = capability(r.supportsImage) ?: old.imageInput,
            )
        }
        return conflicts(config, replacements)
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
            if (dao.get(providerId) == null) return@withTransaction
            val old = dao.model(providerId, id)
            if (old != null) dao.upsertModels(listOf(old.copy(manual = true))) else {
                val order = (dao.models(providerId).maxOfOrNull { it.sortOrder } ?: -1) + 1
                dao.upsertModels(listOf(ProviderModel(providerId, id, id, sortOrder = order, manual = true, legacy = false)))
            }
        }
    }

    /** 返回引用，任何引用都必须先明确解除，不自动换模型。 */
    suspend fun modelReferences(providerId: Long, modelId: String): List<ModelReference> {
        val config = dao.get(providerId) ?: return emptyList()
        return buildList {
            if (config.defaultModel == modelId) add(ModelReference(null, ReferencePurpose.DEFAULT))
            db.contactDao().listAll().filter { it.providerId == providerId }.forEach { c ->
                if (c.model == modelId) add(ModelReference(c.name, ReferencePurpose.CHAT))
                if (memoryModel(c, config) == modelId) add(ModelReference(c.name, ReferencePurpose.MEMORY))
            }
        }
    }

    suspend fun removeModel(providerId: Long, modelId: String): Boolean = db.withTransaction {
        if (modelReferences(providerId, modelId).isNotEmpty()) return@withTransaction false
        dao.deleteModel(providerId, modelId)
        true
    }

    suspend fun saveModel(model: ProviderModel) = dao.upsertModels(listOf(model))

    suspend fun conflicts(config: ProviderConfig, replacement: ProviderModel? = null): List<ModelReference> = conflicts(config, listOfNotNull(replacement))

    suspend fun conflicts(config: ProviderConfig, replacements: List<ProviderModel>): List<ModelReference> {
        val models = dao.models(config.id).associateBy { it.modelId }.toMutableMap()
        replacements.forEach { models[it.modelId] = it }
        return buildList {
            db.contactDao().listAll().filter { it.providerId == config.id }.forEach { c ->
                if (ModelRules.validate(config, models[c.model], c.thinkingOption ?: ModelRules.legacyOption(c.thinking), c.temperature) != null)
                    add(ModelReference(c.name, ReferencePurpose.CHAT, c.id))
                if (ModelRules.validate(config, models[memoryModel(c, config)], c.memoryThinkingOption, 0.3f) != null)
                    add(ModelReference(c.name, ReferencePurpose.MEMORY, c.id))
            }
        }
    }

    /** 和 [saved]（数据库里现在的配置）比较：哪些用途是这次保存才不能用的。原因变了但本来就不能用的，不算新增。 */
    suspend fun conflictChange(saved: ProviderConfig, after: List<ModelReference>): ConflictChange {
        val before = conflicts(saved).toSet()
        val (existing, added) = after.partition { it in before }
        return ConflictChange(added, existing)
    }

    fun memoryModel(contact: Contact, config: ProviderConfig): String = contact.memoryModel ?: config.defaultModel.ifBlank { contact.model }

    /** 原子读取全部配置，Key 只在内存中；网络等待不占数据库事务。 */
    suspend fun snapshot(contactId: Long, memory: Boolean): ModelSnapshot = db.withTransaction {
        val contact = db.contactDao().get(contactId) ?: throw LlmException(LlmError.CONFIGURATION)
        val config = dao.get(contact.providerId) ?: throw LlmException(LlmError.CONFIGURATION)
        val endpoint = endpoint(config) ?: throw LlmException(LlmError.AUTH)
        val modelId = if (memory) memoryModel(contact, config) else contact.model
        val model = dao.model(config.id, modelId)
        val option = if (memory) contact.memoryThinkingOption else contact.thinkingOption ?: ModelRules.legacyOption(contact.thinking)
        val temperature = if (memory) 0.3f else contact.temperature
        val parameters = ModelRules.parameters(config, model, option, temperature)
        val legacyThinking = if (parameters == null) {
            val level = ThinkingLevel.entries.firstOrNull { it.name.equals(option, true) } ?: throw LlmException(LlmError.CONFIGURATION)
            if (memory || supportsThinking(config, modelId)) level else ThinkingLevel.OFF
        } else ThinkingLevel.OFF
        ModelSnapshot(contact, config, endpoint, modelId, model?.supportsImage ?: false, legacyThinking, temperature, parameters)
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

enum class ReferencePurpose { DEFAULT, CHAT, MEMORY }
/** [contactId] 用来区分同名联系人 */
data class ModelReference(val contactName: String?, val purpose: ReferencePurpose, val contactId: Long? = null)
data class ConflictChange(val added: List<ModelReference> = emptyList(), val existing: List<ModelReference> = emptyList())
data class ModelSnapshot(
    val contact: Contact, val config: ProviderConfig, val endpoint: LlmEndpoint, val model: String,
    val supportsImage: Boolean, val thinking: ThinkingLevel, val temperature: Float, val parameters: RequestParameters?,
) {
    fun request(messages: List<ChatMessage>) = ChatRequest(model, messages, thinking, temperature, parameters)
    override fun toString() = "ModelSnapshot(redacted)"
}
