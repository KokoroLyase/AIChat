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

/** 用量的用途：聊天回复，或自动总结记忆。 */
enum class UsagePurpose { CHAT, MEMORY }

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
    /** 上下文预算（token）：系统提示词、记忆和聊天记录合计不超过它，超出时丢弃最早的消息 */
    @ColumnInfo(defaultValue = "4000") val contextTokens: Int = DEFAULT_CONTEXT_TOKENS,
    /** 分条显示：回复里有空行时拆成多条气泡依次出现 */
    val splitReplies: Boolean = true,
    /** 自动记忆：较早的聊天自动总结成记忆，会额外消耗 API 额度 */
    @ColumnInfo(defaultValue = "1") val autoMemory: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        /** DeepSeek 官方对「通用对话」建议的温度 */
        const val DEFAULT_TEMPERATURE = 1.3f
        const val DEFAULT_CONTEXT_TOKENS = 4000
        const val MIN_CONTEXT_TOKENS = 1000
        const val MAX_CONTEXT_TOKENS = 200_000
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
    /**
     * 自动记忆已处理到的消息时间：这个时间及更早的消息不再自动总结。
     * null 表示还没确定起点（0.3.0 之前的聊天，或刚重新打开自动记忆），下次检查时设为当前上下文窗口的开头，
     * 这样升级后不会自动回溯总结老聊天
     */
    val memoryCursor: Long? = 0,
    /** 早于这个时间的消息还没总结（只能在记忆页手动「总结更早的聊天」）；0 表示没有 */
    @ColumnInfo(defaultValue = "0") val memoryStart: Long = 0,
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
    /** 请求格式：[ProviderProtocol.DEEPSEEK] 或 [ProviderProtocol.OPENAI] */
    val provider: String,
    /** 显示名称，用户可改 */
    @ColumnInfo(defaultValue = "''") val name: String = "",
    val baseUrl: String,
    val apiKeyEncrypted: String? = null,
    val defaultModel: String,
    val enabled: Boolean = true,
)

/** 服务商的模型列表与能力（是否支持图片、是否支持思考）。 */
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
    /** 用户在设置里的标记，优先于接口报告的能力 */
    val imageInputUserMark: Boolean? = null,
    val sortOrder: Int = 0,
    /** 接口报告的思考能力 */
    @ColumnInfo(defaultValue = "UNKNOWN") val thinkingInput: Capability = Capability.UNKNOWN,
    /** 用户在设置里的标记，优先于接口报告的能力 */
    val thinkingUserMark: Boolean? = null,
    /** 手动添加的模型：接口没有模型列表时用，刷新列表时保留 */
    @ColumnInfo(defaultValue = "0") val manual: Boolean = false,
) {
    val supportsImage: Boolean get() = imageInputUserMark ?: (imageInput == Capability.YES)

    val supportsThinking: Boolean get() = thinkingUserMark ?: (thinkingInput == Capability.YES)
}

/**
 * 长期记忆：一段较早的聊天总结成的一条记忆，每次请求都带上。
 * 删除联系人时一起删除；清空聊天记录时保留。
 */
@Entity(
    tableName = "memory",
    foreignKeys = [
        ForeignKey(
            entity = Contact::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["contactId", "fromTime"])],
)
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: Long,
    val content: String,
    /** 这条记忆覆盖的聊天时间范围 */
    val fromTime: Long,
    val toTime: Long,
    /** 用户改过的记忆不会被自动合并 */
    val edited: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 按天、按联系人统计的 token 用量。和消息表分开：清空聊天记录不影响统计。
 * [contactId] 为 [DELETED_CONTACT] 时是已删除联系人的合计。
 */
@Entity(tableName = "usage_daily", primaryKeys = ["day", "contactId", "purpose"])
data class UsageDaily(
    /** 本地日期的 epochDay */
    val day: Long,
    val contactId: Long,
    val purpose: UsagePurpose,
    val promptTokens: Long,
    val completionTokens: Long,
    val requests: Int,
) {
    companion object {
        const val DELETED_CONTACT = 0L
    }
}

/** 聊天列表的一行：会话 + 联系人的名称和头像。 */
data class ConversationItem(
    @Embedded val conversation: Conversation,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "avatarPath") val avatarPath: String?,
    @ColumnInfo(name = "avatarColor") val avatarColor: Int,
)

/** 全局搜索结果里的一个聊天：命中条数和最近一次命中的时间。 */
data class MessageSearchGroup(
    val contactId: Long,
    val hitCount: Int,
    val lastTime: Long,
    val contactName: String,
    val contactAvatarPath: String?,
    val contactAvatarColor: Int,
)

/** 合并恢复时判断「是不是同一条消息」用的键。 */
data class MessageKey(val id: Long, val contactId: Long, val createdAt: Long)
