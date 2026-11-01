package isomeria.aichat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本地数据库。每次改表：版本号 +1、写迁移脚本、用导出的 schema 文件（app/schemas）做迁移测试。
 */
@Database(
    entities = [Contact::class, Conversation::class, Message::class, ProviderConfig::class, ProviderModel::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun providerDao(): ProviderDao

    companion object {
        const val NAME = "aichat.db"

        /** 首次建库时写入 DeepSeek 服务商和已知模型；模型列表之后以接口拉取的为准。 */
        val seedCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT INTO provider_config (id, kind, provider, baseUrl, apiKeyEncrypted, defaultModel, enabled)
                    VALUES (1, 'LLM', '${DeepSeekDefaults.PROVIDER}', '${DeepSeekDefaults.BASE_URL}', NULL,
                            '${DeepSeekDefaults.DEFAULT_MODEL}', 1)
                    """.trimIndent(),
                )
                DeepSeekDefaults.knownModels.forEachIndexed { index, model ->
                    db.execSQL(
                        """
                        INSERT INTO provider_model (providerId, modelId, displayName, imageInput, imageInputUserMark, sortOrder)
                        VALUES (1, ?, ?, ?, NULL, ?)
                        """.trimIndent(),
                        arrayOf<Any>(model.modelId, model.displayName, model.imageInput.name, index),
                    )
                }
            }
        }
    }
}

/** DeepSeek 默认值（2026-11 对照官方文档 api-docs.deepseek.com 核对）。 */
object DeepSeekDefaults {
    const val PROVIDER = "deepseek"
    const val BASE_URL = "https://api.deepseek.com"
    /** DeepSeek V4.1 Flash：支持思考和图片输入 */
    const val DEFAULT_MODEL = "deepseek-flash"
    const val PROVIDER_ID = 1L

    val knownModels = listOf(
        ProviderModel(PROVIDER_ID, "deepseek-flash", "DeepSeek V4.1 Flash", Capability.YES),
        ProviderModel(PROVIDER_ID, "deepseek-v4-pro", "DeepSeek V4 Pro", Capability.NO),
    )
}
