package isomeria.aichat.data.backup

import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 导出文件的格式（zip）：
 *
 *   manifest.json   App 版本、数据格式版本、导出时间
 *   data.json       联系人、会话、消息、服务商配置和设置
 *   avatars/…       头像（联系人和自己的）
 *   images/…        聊天图片
 *
 * data.json 里的头像和图片写成 zip 内的相对路径（如 images/xxx.jpg），恢复时换成本机路径。
 * 格式和数据库表分开定义：以后改表不影响已经导出的文件，改格式时 [BackupFormat.VERSION] +1，
 * 并在 [BackupMigrations] 里加一步旧格式到新格式的转换。
 */

object BackupFormat {
    /** 数据格式版本。改 data.json 的结构时 +1 */
    const val VERSION = 1

    /** manifest 里的应用标识，用来认出智信的备份文件 */
    const val APP_ID = "isomeria.aichat"

    const val MANIFEST = "manifest.json"
    const val DATA = "data.json"
    const val AVATARS_DIR = "avatars/"
    const val IMAGES_DIR = "images/"

    /** zip 内文件名只允许这些字符（不能以点开头），防止「../」之类的路径 */
    val mediaNamePattern = Regex("""^(avatars|images)/[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}$""")

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
    val settings: SettingsDto = SettingsDto(),
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
    val contextCount: Int,
    val splitReplies: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
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
)

@Serializable
data class ProviderDto(
    val id: Long,
    val kind: ProviderKind,
    val provider: String,
    val baseUrl: String,
    val defaultModel: String,
    val enabled: Boolean = true,
    val models: List<ProviderModelDto> = emptyList(),
    /** 只有勾选「包含 API Key」时才有 */
    val apiKey: SecretDto? = null,
)

@Serializable
data class ProviderModelDto(
    val modelId: String,
    val displayName: String,
    val imageInput: Capability = Capability.UNKNOWN,
    val imageInputUserMark: Boolean? = null,
    val sortOrder: Int = 0,
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
)
