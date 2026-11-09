package isomeria.aichat.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import isomeria.aichat.R
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.ContextLabels
import isomeria.aichat.core.IcuPinyinConverter
import isomeria.aichat.core.PinyinConverter
import isomeria.aichat.core.TimeLabels
import isomeria.aichat.data.backup.BackupSettingsAccess
import isomeria.aichat.data.backup.SettingsStoreBackupAccess
import isomeria.aichat.data.crypto.KeystoreSecretCipher
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.ContactDao
import isomeria.aichat.data.db.ConversationDao
import isomeria.aichat.data.db.MIGRATION_1_2
import isomeria.aichat.data.db.MIGRATION_2_3
import isomeria.aichat.data.db.MIGRATION_3_4
import isomeria.aichat.data.db.MemoryDao
import isomeria.aichat.data.db.MessageDao
import isomeria.aichat.data.db.ProviderDao
import isomeria.aichat.data.db.UsageDao
import isomeria.aichat.data.llm.DeepSeekProvider
import isomeria.aichat.data.llm.GenericOpenAiProvider
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.memory.MemoryLabels
import isomeria.aichat.data.memory.NicknameSource
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.reply.ReplyScheduler
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.reply.ReplyNotifications
import isomeria.aichat.data.reply.ReplyQueue
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.update.ApkVerifier
import isomeria.aichat.update.GitHubUpdateSource
import isomeria.aichat.update.PackageManagerApkVerifier
import isomeria.aichat.update.UpdatePrefs
import isomeria.aichat.update.UpdateSource
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .addCallback(AppDatabase.seedCallback)
            .build()

    @Provides fun contactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides fun conversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides fun messageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides fun providerDao(db: AppDatabase): ProviderDao = db.providerDao()

    @Provides fun memoryDao(db: AppDatabase): MemoryDao = db.memoryDao()

    @Provides fun usageDao(db: AppDatabase): UsageDao = db.usageDao()

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * 模型请求用的 HTTP 客户端。思考时间可能很长，读超时放宽；
     * 不跟随重定向，保证 API Key 只发送到用户配置的 Base URL。
     */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Provides
    @Singleton
    fun contextBuilder(@ApplicationContext context: Context): ContextBuilder {
        val res = context.resources
        return ContextBuilder(
            ContextLabels(
                namePlaceholder = res.getString(R.string.prompt_name_placeholder),
                personaPlaceholderPrefix = res.getString(R.string.prompt_persona_placeholder_prefix),
                timeLine = res.getString(R.string.prompt_time_line),
                nicknameLine = res.getString(R.string.prompt_nickname_line),
                weekdays = res.getStringArray(R.array.weekdays).toList(),
                imagePlaceholder = res.getString(R.string.preview_image),
                memoryHeader = res.getString(R.string.prompt_memory_header),
                memoryItem = res.getString(R.string.prompt_memory_item),
            ),
        )
    }

    @Provides
    @Singleton
    fun memoryLabels(@ApplicationContext context: Context): MemoryLabels {
        val res = context.resources
        return MemoryLabels(
            summaryPrompt = res.getString(R.string.memory_summary_prompt),
            mergePrompt = res.getString(R.string.memory_merge_prompt),
            defaultMyName = res.getString(R.string.memory_default_my_name),
            imagePlaceholder = res.getString(R.string.preview_image),
        )
    }

    @Provides
    @Singleton
    fun timeFormatter(@ApplicationContext context: Context): ChatTimeFormatter {
        val res = context.resources
        return ChatTimeFormatter(
            TimeLabels(
                yesterday = res.getString(R.string.time_yesterday),
                weekdays = res.getStringArray(R.array.weekdays).toList(),
                monthDay = res.getString(R.string.time_month_day),
                yearMonthDay = res.getString(R.string.time_year_month_day),
            ),
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun secretCipher(impl: KeystoreSecretCipher): SecretCipher

    @Binds abstract fun pinyin(impl: IcuPinyinConverter): PinyinConverter

    @Binds @IntoSet abstract fun deepSeek(impl: DeepSeekProvider): LlmProvider

    @Binds @IntoSet abstract fun genericOpenAi(impl: GenericOpenAiProvider): LlmProvider

    @Binds abstract fun nicknameSource(impl: SettingsStore): NicknameSource

    @Binds abstract fun replyControl(impl: ReplyScheduler): ReplyControl

    @Binds abstract fun replyQueue(impl: ReplyScheduler): ReplyQueue

    @Binds abstract fun replyNotifications(impl: ReplyNotifier): ReplyNotifications

    @Binds abstract fun backupSettings(impl: SettingsStoreBackupAccess): BackupSettingsAccess

    @Binds abstract fun updatePrefs(impl: SettingsStore): UpdatePrefs

    @Binds abstract fun updateSource(impl: GitHubUpdateSource): UpdateSource

    @Binds abstract fun apkVerifier(impl: PackageManagerApkVerifier): ApkVerifier
}
