package isomeria.aichat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import isomeria.aichat.core.ReplyPreferences
import kotlinx.serialization.json.Json
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地数据库。每次改表：版本号 +1、写迁移脚本、用导出的 schema 文件（app/schemas）做迁移测试。
 */
@Database(
    entities = [
        Contact::class,
        Conversation::class,
        Message::class,
        ProviderConfig::class,
        ProviderModel::class,
        Memory::class,
        UsageDaily::class,
        GlobalMemoryCategory::class,
        GlobalMemoryTopic::class,
        GlobalMemoryItem::class,
        ProactiveState::class,
        ProactiveReminder::class,
    ],
    version = 8,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class AppDatabase : RoomDatabase() {
    /** 恢复数据使正在等待网络的旧记忆任务失效；瞬时保护，不参与备份。 */
    val restoreGeneration = java.util.concurrent.atomic.AtomicLong(0)
    val restoreInProgress = java.util.concurrent.atomic.AtomicInteger(0)
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao
    abstract fun memoryDao(): MemoryDao
    abstract fun usageDao(): UsageDao
    abstract fun globalMemoryDao(): GlobalMemoryDao
    abstract fun proactiveDao(): ProactiveDao

    companion object {
        const val NAME = "aichat.db"

        /** 首次建库只写入连接预置，不写入任何具体模型。 */
        val seedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT INTO provider_config (id, kind, provider, name, baseUrl, apiKeyEncrypted, defaultModel, enabled, presetId, compatibility)
                    VALUES (1, 'LLM', '${ProviderProtocol.OPENAI}', '${DeepSeekDefaults.NAME}', '${DeepSeekDefaults.BASE_URL}', NULL,
                            '', 1, 'deepseek', 'DEEPSEEK')
                    """.trimIndent(),
                )

            }
        }
    }
}

/** 服务商的请求格式（provider_config.provider）。 */
object ProviderProtocol {
    /** DeepSeek：思考用 thinking 和 reasoning_effort（高 / 最高），关闭必须显式传 */
    const val DEEPSEEK = "deepseek"

    /** 其他兼容 OpenAI 格式的服务商：只用通用字段 */
    const val OPENAI = "openai"
}

/** DeepSeek 默认值（2026-11 对照官方文档 api-docs.deepseek.com 核对）。 */
object DeepSeekDefaults {
    const val PROVIDER = ProviderProtocol.DEEPSEEK
    const val NAME = "DeepSeek"
    const val BASE_URL = "https://api.deepseek.com"
    const val PROVIDER_ID = 1L
}

/** 存成 JSON 的列（0.7.2：联系人单独设置的回复偏好）。读不出时当作没有设置（跟随全局），不让整行读取失败 */
class DbConverters {
    @TypeConverter
    fun replyPreferencesToJson(value: ReplyPreferences?): String? = value?.let { json.encodeToString(ReplyPreferences.serializer(), it) }

    @TypeConverter
    fun replyPreferencesFromJson(value: String?): ReplyPreferences? =
        value?.let { runCatching { json.decodeFromString(ReplyPreferences.serializer(), it).normalized() }.getOrNull() }

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
