package isomeria.aichat.data.backup

import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.db.UsagePurpose
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 导出文件的格式（zip）：
 *
 *   manifest.json   App 版本、数据格式版本、导出时间
 *   data.json       联系人、会话、消息、服务商配置（每个服务商各自的模型和可选的 Key）、记忆、用量、
 *                   全局记忆（v5 起）和设置；v6 起联系人带主动聊天开关和频率、消息带来源、设置带主动聊天的
 *                   免打扰时段和每日上限。今日已用次数、暂停计数和约定提醒是设备状态，不导出
 *   avatars/…       头像（联系人和自己的）
 *   images/…        聊天图片
 *   background/…    聊天背景（v4 起）
 *
 * data.json 里的头像和图片写成 zip 内的相对路径（如 images/xxx.jpg），恢复时换成本机路径。
 * 格式和数据库表分开定义：以后改表不影响已经导出的文件，改格式时 [BackupFormat.VERSION] +1，
 * 并在 [BackupMigrations] 里加一步旧格式到新格式的转换。
 */

object BackupFormat {
    /** 数据格式版本。改 data.json 的结构时 +1 */
    const val VERSION = 6

    /** manifest 里的应用标识，用来认出智信的备份文件 */
    const val APP_ID = "isomeria.aichat"

    const val MANIFEST = "manifest.json"
    const val DATA = "data.json"
    const val AVATARS_DIR = "avatars/"
    const val IMAGES_DIR = "images/"
    const val BACKGROUNDS_DIR = "background/"

    /** zip 内文件名只允许这些字符（不能以点开头），防止「../」之类的路径 */
    val mediaNamePattern = Regex("""^(avatars|images|background)/[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$""")

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }
}

@Serializable
data class BackupManifest(
    val app: String = BackupFormat.APP_ID,
    val appVersion: String,
    val appVersionCode: Int,
    val formatVersion: Int,
    val exportedAt: Long,
    val includesApiKey: Boolean = false,
    val contactCount: Int = 0,
    val messageCount: Int = 0,
    val imageCount: Int = 0,
)

@Serializable
data class BackupData(
    val contacts: List<ContactDto> = emptyList(),
    val conversations: List<ConversationDto> = emptyList(),
    val messages: List<MessageDto> = emptyList(),
    val providers: List<ProviderDto> = emptyList(),
    val memories: List<MemoryDto> = emptyList(),
    val usage: List<UsageDto> = emptyList(),
    val settings: SettingsDto = SettingsDto(),
    /** 全局记忆的类别、主题、条目（v5 起） */
    val globalCategories: List<GlobalCategoryDto> = emptyList(),
    val globalTopics: List<GlobalTopicDto> = emptyList(),
    val globalItems: List<GlobalItemDto> = emptyList(),
)

@Serializable
data class ContactDto(
    val id: Long,
    val name: String,
    /** zip 内相对路径，如 avatars/xxx.jpg */
    val avatar: String? = null,
    val avatarColor: Int,
    val providerId: Long,
    val model: String,
    val thinking: ThinkingLevel = ThinkingLevel.OFF,
    val temperature: Float,
    val systemPrompt: String = "",
    val contextTokens: Int = Contact.DEFAULT_CONTEXT_TOKENS,
    val splitReplies: Boolean = true,
    val autoMemory: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val thinkingOption: String? = null,
    val memoryModel: String? = null,
    val memoryThinkingOption: String = "off",
    /** 使用全局记忆、使用回复偏好（v5 起；旧备份迁移后默认开启） */
    val useGlobalMemory: Boolean = true,
    val useReplyPreferences: Boolean = true,
    /** 主动聊天开关和频率（v6 起；旧备份迁移后关闭、适中） */
    val proactiveEnabled: Boolean = false,
    val proactiveFrequency: ProactiveFrequency = ProactiveFrequency.MEDIUM,
)

@Serializable
data class ConversationDto(
    val contactId: Long,
    val pinned: Boolean = false,
    val unreadCount: Int = 0,
    val markedUnread: Boolean = false,
    val draft: String = "",
    val lastMessageTime: Long = 0,
    val lastMessagePreview: String = "",
    /** 自动记忆处理到的消息时间；null 表示恢复后再确定起点 */
    val memoryCursor: Long? = null,
    val memoryStart: Long = 0,
    /** 消息免打扰（v4 起） */
    val muted: Boolean = false,
    /** 聊天背景的 zip 内相对路径，如 background/xxx.jpg（v4 起） */
    val background: String? = null,
)

@Serializable
data class MessageDto(
    val id: Long,
    val contactId: Long,
    val role: Role,
    val type: MessageType,
    val content: String = "",
    /** zip 内相对路径，如 images/xxx.jpg */
    val image: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val reasoning: String? = null,
    val reasoningMillis: Long = 0,
    val status: MessageStatus,
    val errorCode: Int = 0,
    val model: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val replyGroup: String? = null,
    val createdAt: Long,
    /** 来源（v6 起；旧备份里都是普通消息） */
    val origin: MessageOrigin = MessageOrigin.NORMAL,
)

@Serializable
data class ProviderDto(
    val id: Long,
    val kind: ProviderKind,
    val provider: String,
    val name: String = "",
    val baseUrl: String,
    val defaultModel: String,
    val enabled: Boolean = true,
    val models: List<ProviderModelDto> = emptyList(),
    /** 只有勾选「包含 API Key」时才有 */
    val apiKey: SecretDto? = null,
    val presetId: String? = null,
    val compatibility: String = "LEGACY",
)

@Serializable
data class ProviderModelDto(
    val modelId: String,
    val displayName: String,
    val imageInput: Capability = Capability.UNKNOWN,
    val imageInputUserMark: Boolean? = null,
    val sortOrder: Int = 0,
    val thinkingInput: Capability = Capability.UNKNOWN,
    val thinkingUserMark: Boolean? = null,
    val manual: Boolean = false,
    val legacy: Boolean = true,
    val reportedConfig: String? = null,
    val userConfig: String? = null,
    val compatibilityOverride: String? = null,
    val missingFromList: Boolean = false,
)

@Serializable
data class MemoryDto(
    val id: Long,
    val contactId: Long,
    val content: String,
    val fromTime: Long,
    val toTime: Long,
    val edited: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class GlobalCategoryDto(
    val id: Long,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class GlobalTopicDto(
    val id: Long,
    val categoryId: Long,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class GlobalItemDto(
    val id: Long,
    val topicId: Long,
    val content: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 回复偏好（v5 起）。缺字段时用默认值 */
@Serializable
data class ReplyPreferencesDto(
    val waitSeconds: Int = ReplyPreferences.DEFAULT_WAIT_SECONDS,
    val targetLength: Int = ReplyPreferences.DEFAULT_TARGET_LENGTH,
    val minCount: Int = ReplyPreferences.DEFAULT_MIN_COUNT,
    val maxCount: Int = ReplyPreferences.DEFAULT_MAX_COUNT,
    val stripPunctuation: Boolean = true,
    val minIntervalSeconds: Int = ReplyPreferences.DEFAULT_MIN_INTERVAL,
    val maxIntervalSeconds: Int = ReplyPreferences.DEFAULT_MAX_INTERVAL,
)

/** 主动聊天的全局设置（v6 起）。缺字段时用默认值 */
@Serializable
data class ProactiveSettingsDto(
    val quietEnabled: Boolean = true,
    val quietStartMinutes: Int = ProactiveSettings.DEFAULT_QUIET_START,
    val quietEndMinutes: Int = ProactiveSettings.DEFAULT_QUIET_END,
    val dailyLimit: Int = ProactiveSettings.DEFAULT_DAILY_LIMIT,
)

/** 一天、一个联系人的用量。contactId 为 0 时是已删除联系人的合计 */
@Serializable
data class UsageDto(
    val day: Long,
    val contactId: Long,
    val purpose: UsagePurpose = UsagePurpose.CHAT,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val requests: Int = 0,
)

/**
 * 加密后的 API Key。
 *
 * - [SCHEME_PASSWORD]：导出文件用。密钥由用户设的密码经 PBKDF2 派生，AES-256-GCM 加密
 * - [SCHEME_KEYSTORE]：恢复前在本机私有目录做的自动备份用，直接存 Keystore 密文，只能在本机解开
 */
@Serializable
data class SecretDto(
    val scheme: String,
    val iterations: Int = 0,
    val salt: String? = null,
    val iv: String? = null,
    val data: String,
) {
    companion object {
        const val SCHEME_PASSWORD = "pbkdf2-sha256/aes-256-gcm"
        const val SCHEME_KEYSTORE = "android-keystore"
    }
}

/** 随备份迁移的设置。上次检查更新时间、忽略的版本、是否申请过通知权限这些和设备有关的不导出。 */
@Serializable
data class SettingsDto(
    val themeMode: String? = null,
    val fontSize: String? = null,
    val nickname: String? = null,
    /** zip 内相对路径 */
    val avatar: String? = null,
    val notifyEnabled: Boolean? = null,
    val notifyPreview: Boolean? = null,
    val autoCheckUpdate: Boolean? = null,
    /** 回复偏好（v5 起）；没有时恢复保留本机的 */
    val replyPreferences: ReplyPreferencesDto? = null,
    /** 主动聊天的免打扰时段和每日上限（v6 起）；没有时恢复保留本机的 */
    val proactive: ProactiveSettingsDto? = null,
)
