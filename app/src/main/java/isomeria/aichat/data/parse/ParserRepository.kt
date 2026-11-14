package isomeria.aichat.data.parse

import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.llm.LlmEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 还没设置文件解析服务，或它关着、没有 Token（0.9.2） */
class ParserUnavailableException : Exception("ParserUnavailable")

/**
 * 文件解析服务的配置（0.9.2）：存在 provider_config 里，kind 为 PARSER，只有一个，目前只有 MinerU。
 * Token 和别的服务商的 Key 一样用 Keystore 加密；备份跟着「模型配置」走，Token 跟着“导出 API Key”的选择。
 */
@Singleton
class ParserRepository @Inject constructor(
    private val db: AppDatabase,
    private val cipher: SecretCipher,
    private val parser: DocumentParser,
) {
    private val dao get() = db.providerDao()

    fun observe(): Flow<ProviderConfig?> = dao.observeByKind(ProviderKind.PARSER).map { it.firstOrNull() }

    suspend fun config(): ProviderConfig? = dao.listByKind(ProviderKind.PARSER).firstOrNull()

    /** 能不能用：有配置、开着、有 Token */
    suspend fun available(): Boolean = config()?.let { it.enabled && !apiKey(it).isNullOrBlank() } ?: false

    fun apiKey(config: ProviderConfig): String? = config.apiKeyEncrypted?.let(cipher::decrypt)

    fun maskedKey(config: ProviderConfig): String? {
        val key = apiKey(config) ?: return null
        if (key.length <= 8) return "••••••"
        return key.take(4) + "••••••" + key.takeLast(4)
    }

    /**
     * 保存：没有配置时新建。[apiKey] 为 null 时保留原来的 Token，空字符串表示清掉
     */
    suspend fun save(apiKey: String?, enabled: Boolean, baseUrl: String = MINERU_BASE) {
        val url = baseUrl.trim().ifEmpty { MINERU_BASE }
        val old = config()
        val encrypted = when {
            apiKey == null -> old?.apiKeyEncrypted
            apiKey.isBlank() -> null
            else -> cipher.encrypt(apiKey.trim())
        }
        if (old == null) {
            dao.insert(
                ProviderConfig(
                    kind = ProviderKind.PARSER,
                    provider = MINERU,
                    name = MINERU_NAME,
                    baseUrl = url,
                    apiKeyEncrypted = encrypted,
                    defaultModel = MineruApi.MODEL_VERSION,
                    enabled = enabled,
                    presetId = MINERU,
                ),
            )
        } else {
            dao.update(old.copy(baseUrl = url, apiKeyEncrypted = encrypted, enabled = enabled))
        }
    }

    /** “测试连接”：[apiKey] 为 null 时用已保存的 Token */
    suspend fun test(apiKey: String?, baseUrl: String = MINERU_BASE) {
        val key = apiKey?.trim()?.takeIf { it.isNotEmpty() } ?: config()?.let(::apiKey) ?: throw ParseException(ParseError.AUTH)
        parser.test(LlmEndpoint(baseUrl.trim().ifEmpty { MINERU_BASE }, key))
    }

    /** 解析一个文件；没有可用的配置时抛 [ParserUnavailableException]，解析失败时抛 [ParseException] */
    suspend fun parse(file: File, name: String, imageLabel: String): String {
        val config = config()?.takeIf { it.enabled } ?: throw ParserUnavailableException()
        val key = apiKey(config)?.takeIf { it.isNotBlank() } ?: throw ParserUnavailableException()
        return parser.parse(LlmEndpoint(config.baseUrl, key), file, name, imageLabel)
    }

    companion object {
        const val MINERU = "mineru"
        const val MINERU_NAME = "MinerU"
        const val MINERU_BASE = "https://mineru.net/api/v4"
        const val MINERU_DOCS = "https://mineru.net/apiManage/docs"
    }
}
