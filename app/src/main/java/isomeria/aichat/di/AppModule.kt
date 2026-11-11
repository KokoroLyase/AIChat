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
import isomeria.aichat.data.db.MIGRATION_4_5
import isomeria.aichat.data.db.MIGRATION_5_6
import isomeria.aichat.data.db.MIGRATION_6_7
import isomeria.aichat.data.db.GlobalMemoryDao
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .addCallback(AppDatabase.seedCallback)
            .build()

    @Provides fun contactDao(db: AppDatabase): ContactDao = db.contactDao()

    @Provides fun conversationDao(db: AppDatabase): ConversationDao = db.conversationDao()

    @Provides fun messageDao(db: AppDatabase): MessageDao = db.messageDao()

    @Provides fun providerDao(db: AppDatabase): ProviderDao = db.providerDao()

    @Provides fun memoryDao(db: AppDatabase): MemoryDao = db.memoryDao()

    @Provides fun usageDao(db: AppDatabase): UsageDao = db.usageDao()

    @Provides fun globalMemoryDao(db: AppDatabase): GlobalMemoryDao = db.globalMemoryDao()

    @Provides fun proactiveDao(db: AppDatabase): isomeria.aichat.data.db.ProactiveDao = db.proactiveDao()

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
                globalMemoryHeader = res.getString(R.string.prompt_global_memory_header),
                globalMemoryTopic = res.getString(R.string.prompt_global_memory_topic),
                globalMemoryItem = res.getString(R.string.prompt_global_memory_item),
                replyStyle = res.getString(R.string.prompt_reply_style),
                countRange = res.getString(R.string.prompt_count_range),
                reminderHint = res.getString(R.string.prompt_reminder_hint),
            ),
        )
    }

    @Provides
    @Singleton
    fun proactiveLabels(@ApplicationContext context: Context): isomeria.aichat.data.reply.ProactiveLabels {
        val res = context.resources
        return isomeria.aichat.data.reply.ProactiveLabels(
            instruction = res.getString(R.string.prompt_proactive_instruction),
            reminderInstruction = res.getString(R.string.prompt_proactive_reminder),
            time = res.getString(R.string.prompt_proactive_time),
            weekdays = res.getStringArray(R.array.weekdays).toList(),
            sinceMinutes = res.getString(R.string.prompt_proactive_since_minutes),
            sinceHours = res.getString(R.string.prompt_proactive_since_hours),
            sinceDays = res.getString(R.string.prompt_proactive_since_days),
            noHistory = res.getString(R.string.prompt_proactive_no_history),
            reminderNoNote = res.getString(R.string.prompt_proactive_reminder_no_note),
            reminderKeyword = res.getString(R.string.prompt_reminder_keyword),
        )
    }

    /** 每次用到时取手机当前的时区（用户可能中途改时区） */
    @Provides
    fun zone(): java.time.ZoneId = java.time.ZoneId.systemDefault()


    @Provides
    fun clock(): isomeria.aichat.core.Clock = isomeria.aichat.core.Clock.SYSTEM

    @Provides
    fun random(): kotlin.random.Random = kotlin.random.Random.Default

    @Provides
    fun sleeper(): isomeria.aichat.core.Sleeper = isomeria.aichat.core.Sleeper.DELAY

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

    @Binds abstract fun promptGlobals(impl: isomeria.aichat.data.repo.GlobalMemoryRepository): isomeria.aichat.data.repo.PromptGlobalsSource

    @Binds abstract fun memoryQueue(impl: isomeria.aichat.data.memory.MemoryScheduler): isomeria.aichat.data.memory.MemoryQueue

    @Binds abstract fun replyPreferencesSource(impl: SettingsStore): isomeria.aichat.core.ReplyPreferencesSource

    @Binds abstract fun proactiveSettingsSource(impl: SettingsStore): isomeria.aichat.core.ProactiveSettingsSource

    @Binds abstract fun proactiveResultLog(impl: SettingsStore): isomeria.aichat.core.ProactiveResultLog

    @Binds abstract fun voiceSettingsSource(impl: SettingsStore): isomeria.aichat.core.VoiceSettingsSource

    @Binds abstract fun voiceService(impl: isomeria.aichat.data.voice.VoiceApi): isomeria.aichat.data.voice.VoiceService

    @Binds abstract fun voiceRecorder(impl: isomeria.aichat.data.voice.AndroidVoiceRecorder): isomeria.aichat.data.voice.VoiceRecorder

    @Binds abstract fun audioCodec(impl: isomeria.aichat.data.voice.AndroidAudioCodec): isomeria.aichat.data.voice.AudioCodec

    @Binds abstract fun localVoicePrefs(impl: SettingsStore): isomeria.aichat.core.LocalVoicePrefs

    @Binds abstract fun systemTts(impl: isomeria.aichat.data.voice.AndroidSystemTts): isomeria.aichat.data.voice.SystemTts

    @Binds abstract fun offlineAsr(impl: isomeria.aichat.data.voice.SherpaOfflineAsrEngine): isomeria.aichat.data.voice.OfflineAsrEngine

    @Binds abstract fun replyControl(impl: ReplyScheduler): ReplyControl

    @Binds abstract fun proactiveControl(impl: isomeria.aichat.data.reply.ProactiveScheduler): isomeria.aichat.data.reply.ProactiveControl

    @Binds abstract fun replyQueue(impl: ReplyScheduler): ReplyQueue

    @Binds abstract fun replyNotifications(impl: ReplyNotifier): ReplyNotifications

    @Binds abstract fun messagePreviewer(impl: isomeria.aichat.data.repo.ResourceMessagePreviewer): isomeria.aichat.data.repo.MessagePreviewer

    @Binds abstract fun receivingForeground(impl: ReplyNotifier): isomeria.aichat.data.reply.ReceivingForeground

    @Binds abstract fun backupSettings(impl: SettingsStoreBackupAccess): BackupSettingsAccess

    @Binds abstract fun updatePrefs(impl: SettingsStore): UpdatePrefs

    @Binds abstract fun updateSource(impl: GitHubUpdateSource): UpdateSource

    @Binds abstract fun apkVerifier(impl: PackageManagerApkVerifier): ApkVerifier
}
