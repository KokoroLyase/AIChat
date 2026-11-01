package isomeria.aichat.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 思考强度：关 / 高 / 最高，对应 DeepSeek 的 thinking 与 reasoning_effort。 */
enum class ThinkingLevel { OFF, HIGH, MAX }

enum class Role { USER, ASSISTANT }

enum class MessageType { TEXT, IMAGE }

/** 消息状态：发送中（等待回复）、成功、失败。 */
enum class MessageStatus { SENDING, SUCCESS, FAILED }

enum class ProviderKind { LLM, TTS, ASR }

/** 模型能力：接口报告支持 / 不支持，或接口没说（由用户在设置里标记）。 */
enum class Capability { YES, NO, UNKNOWN }

/** 联系人。一个联系人对应一个会话。 */
@Entity(tableName = "contact")
data class Contact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 应用私有目录里的头像文件；为空时显示「名称首字 + 色块」 */
    val avatarPath: String? = null,
    /** 默认头像色块，ARGB */
    val avatarColor: Int,
    val providerId: Long,
    val model: String,
    val thinking: ThinkingLevel = ThinkingLevel.OFF,
    val temperature: Float = DEFAULT_TEMPERATURE,
    val systemPrompt: String = "",
    val contextCount: Int = DEFAULT_CONTEXT_COUNT,
    /** 分条显示：回复里有空行时拆成多条气泡依次出现 */
    val splitReplies: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        /** DeepSeek 官方对「通用对话」建议的温度 */
        const val DEFAULT_TEMPERATURE = 1.3f
        const val DEFAULT_CONTEXT_COUNT = 20
        const val NAME_MAX_LENGTH = 20
    }
}

/** 会话：聊天列表直接查这张表。 */
@Entity(
    tableName = "conversation",
    foreignKeys = [
        ForeignKey(
            entity = Contact::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class Conversation(
    @PrimaryKey val contactId: Long,
    val pinned: Boolean = false,
    val unreadCount: Int = 0,
    /** 长按「标为未读」：只显示红点，不显示数字 */
    val markedUnread: Boolean = false,
    val draft: String = "",
    /** 0 表示没有消息，不在聊天列表里显示（有草稿时除外） */
    val lastMessageTime: Long = 0,
    val lastMessagePreview: String = "",
)

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = Contact::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["contactId", "createdAt"]), Index(value = ["status"])],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: Long,
    val role: Role,
    val type: MessageType,
    val content: String = "",
    val imagePath: String? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** 思考内容；分条显示时只存在第一条上 */
    val reasoning: String? = null,
    /** 思考用时（毫秒） */
    val reasoningMillis: Long = 0,
    val status: MessageStatus,
    /** 失败原因，见 [isomeria.aichat.data.repo.FailReason] */
    val errorCode: Int = 0,
    /** 生成这条回复所用的模型 */
    val model: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** 同一次回复拆出的多条消息共用一个分组，用于「重新生成」和上下文拼装 */
    val replyGroup: String? = null,
    val createdAt: Long,
)

/** 服务商配置。API Key 经 Android Keystore 加密后存放，不明文落盘。 */
@Entity(tableName = "provider_config")
data class ProviderConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ProviderKind,
    /** 服务商标识，如 deepseek */
    val provider: String,
    val baseUrl: String,
    val apiKeyEncrypted: String? = null,
    val defaultModel: String,
    val enabled: Boolean = true,
)

/** 服务商的模型列表与能力（是否支持图片输入）。 */
@Entity(
    tableName = "provider_model",
    primaryKeys = ["providerId", "modelId"],
    foreignKeys = [
        ForeignKey(
            entity = ProviderConfig::class,
            parentColumns = ["id"],
            childColumns = ["providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProviderModel(
    val providerId: Long,
    val modelId: String,
    val displayName: String,
    /** 接口报告的图片输入能力 */
    val imageInput: Capability = Capability.UNKNOWN,
    /** 接口读不到时，用户在设置里的标记 */
    val imageInputUserMark: Boolean? = null,
    val sortOrder: Int = 0,
) {
    val supportsImage: Boolean
        get() = when (imageInput) {
            Capability.YES -> true
            Capability.NO -> false
            Capability.UNKNOWN -> imageInputUserMark == true
        }
}

/** 聊天列表的一行：会话 + 联系人的名称和头像。 */
data class ConversationItem(
    @Embedded val conversation: Conversation,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "avatarPath") val avatarPath: String?,
    @ColumnInfo(name = "avatarColor") val avatarColor: Int,
)

/** 搜索结果里的一条聊天记录。 */
data class MessageSearchHit(
    @Embedded val message: Message,
    @ColumnInfo(name = "contactName") val contactName: String,
    @ColumnInfo(name = "contactAvatarPath") val contactAvatarPath: String?,
    @ColumnInfo(name = "contactAvatarColor") val contactAvatarColor: Int,
)
