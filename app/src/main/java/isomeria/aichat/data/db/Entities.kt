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

/**
 * 消息类型。VOICE（0.7.0）：语音消息，文件在 files/voice/，转写文字存在 content（用于上下文、记忆、搜索和「转文字」）。
 *
 * CALL（0.8.0）：一次语音通话，用户发起（role 为 USER），聊天里显示成一条通话消息。content 为空；
 * 每一条说的话在 [CallTurn]（call_turn 表），上下文、记忆和搜索都按条展开。
 * [Message.voiceMillis] 是总时长：通话还在进行时为 0（见 [Message.callOngoing]），挂断时写入（至少 1 毫秒）；
 * [Message.errorCode] 是挂断的原因（[CallEndReason]）
 */
enum class MessageType { TEXT, IMAGE, VOICE, CALL }

/**
 * 通话为什么结束（0.8.0，存在通话消息的 errorCode）。数字写进数据库和备份，不能改。
 */
enum class CallEndReason(val code: Int) {
    /** 用户挂断 */
    HANG_UP(0),

    /** 别的 App 拿走了声音（比如来电） */
    AUDIO_LOST(1),

    /** 连续几轮失败 */
    FAILURES(2),

    /** 到了单次通话的最长时间 */
    TIME_LIMIT(3),

    /** 很久没人说话 */
    IDLE(4),

    /** App 在通话中被关掉，下次启动时补完 */
    INTERRUPTED(5),

    /** 麦克风在通话中出错、读不到声音了 */
    MICROPHONE(6),
    ;

    companion object {
        fun of(code: Int): CallEndReason = entries.firstOrNull { it.code == code } ?: HANG_UP
    }
}

/** 通话里一条话的状态（0.8.0）。 */
enum class CallTurnStatus {
    /** 用户的话识别成功，或联系人的话合成并放出来了 */
    OK,

    /** 用户的话没识别出来：留着录音，没有文字，不进上下文 */
    ASR_FAILED,

    /** 联系人的这句合成失败：文字在通话页显示过，没有音频 */
    TTS_FAILED,

    /** 联系人的这句放到一半被用户打断 */
    INTERRUPTED,
}

/**
 * 消息状态：发送中（等待回复）、成功、失败、待投递。
 *
 * PENDING（0.5.0）：模型已经回复、还没到计划时间的回复消息，createdAt 是计划出现的时间。
 * 不在聊天页、搜索、会话预览、未读里出现，转成 SUCCESS 才算送达；导出时按已送达写入。
 */
enum class MessageStatus { SENDING, SUCCESS, FAILED, PENDING }

enum class ProviderKind { LLM, TTS, ASR }

/** 模型能力：接口报告支持 / 不支持，或接口没说（由用户在设置里标记）。 */
enum class Capability { YES, NO, UNKNOWN }

/**
 * 用量的用途：聊天回复、自动总结记忆，或联系人主动聊天（0.6.0；含 [SKIP] 和失败的请求）。
 *
 * 语音合成 TTS、语音识别 ASR（0.7.0）不按 token 计，借用同一张表但含义不同，统计时单独列出、不并进 token 合计：
 * ASR 的 promptTokens 是识别的音频秒数；TTS 的 promptTokens 是合成的字数、completionTokens 是合成出的音频秒数。
 */
enum class UsagePurpose { CHAT, MEMORY, PROACTIVE, TTS, ASR }

/**
 * 消息来源（0.6.0）：普通消息（用户发的、对用户消息的回复），或联系人主动发来的。
 * 主动消息没有对应的用户消息，不能「重新生成」；其他地方（显示、预览、未读、通知、搜索、上下文、记忆、导出）和普通回复一样。
 */
enum class MessageOrigin { NORMAL, PROACTIVE }

/** 主动聊天的频率（0.6.0）：偶尔 / 适中 / 频繁。 */
enum class ProactiveFrequency { LOW, MEDIUM, HIGH }

/** 消息发送类型（0.7.0）：跟随（用户发文字回文字、发语音回语音）/ 文字 / 语音。 */
enum class ReplyType { FOLLOW, TEXT, VOICE }

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
    /** 新配置使用原生选项值；null 保留 0.3.0 枚举的请求语义。 */
    val thinkingOption: String? = null,
    /** null 跟随服务商默认，默认为空时回退聊天模型。 */
    val memoryModel: String? = null,
    @ColumnInfo(defaultValue = "'off'") val memoryThinkingOption: String = "off",
    /** 使用全局记忆（0.5.0）：新旧联系人默认开启；关闭后请求里不带全局记忆 */
    @ColumnInfo(defaultValue = "1") val useGlobalMemory: Boolean = true,
    /** 使用回复偏好（0.5.0）：新旧联系人默认开启；关闭时回到 [splitReplies] 的旧行为 */
    @ColumnInfo(defaultValue = "1") val useReplyPreferences: Boolean = true,
    /** 主动聊天（0.6.0）：新旧联系人默认关闭，用户手动开启后才会在没有操作时调用模型 */
    @ColumnInfo(defaultValue = "0") val proactiveEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "'MEDIUM'") val proactiveFrequency: ProactiveFrequency = ProactiveFrequency.MEDIUM,
    /** 消息发送类型（0.7.0）：null 表示使用「我 → 语音消息」里的全局设置（默认跟随） */
    val replyType: ReplyType? = null,
    /** 语音合成的音色（0.7.0）：null 用语音服务商的默认音色 */
    val ttsVoice: String? = null,
    /** 语气和风格要求（0.7.0），只对 MiMo 这类支持的服务商生效，不会被读出来 */
    @ColumnInfo(defaultValue = "''") val ttsStyle: String = "",
    /**
     * 单独设置的回复偏好（0.7.2）：null 表示跟随「我 → 回复偏好」。只在 [useReplyPreferences] 开着时生效，
     * 关掉再打开时还在
     */
    val replyPreferences: isomeria.aichat.core.ReplyPreferences? = null,
    /**
     * 单独选的语音合成、识别来源（0.7.2）：和全局设置同样的写法（`provider:{id}`、`local:system-tts`、`local:offline-asr`），
     * null 表示跟随「我 → 语音消息」。选的来源用不了时不换成全局的（录音和回复内容不发给没选的服务商）
     */
    val ttsSource: String? = null,
    val asrSource: String? = null,
) {
    companion object {
        /** DeepSeek 官方对「通用对话」建议的温度 */
        const val DEFAULT_TEMPERATURE = 1.3f
        const val DEFAULT_CONTEXT_TOKENS = 4000
        const val MIN_CONTEXT_TOKENS = 1000
        const val MAX_CONTEXT_TOKENS = 200_000
        const val NAME_MAX_LENGTH = 20
        /** 音色 ID 和语气风格要求的长度上限（0.7.0） */
        const val VOICE_MAX_LENGTH = 100
        const val STYLE_MAX_LENGTH = 200
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
    /** 消息免打扰：只不发回复到达通知；未读、预览、生成和记忆照常 */
    @ColumnInfo(defaultValue = "0") val muted: Boolean = false,
    /** 当前聊天背景：应用私有目录 files/background 里的图片；null 用默认背景。只在本机显示，不进模型请求 */
    val backgroundPath: String? = null,
    /** 连续几次主动发送没有得到回复（0.6.0）；到上限后暂停，用户再发言时清零。设备状态，不随备份 */
    @ColumnInfo(defaultValue = "0") val proactiveUnanswered: Int = 0,
    /** 最近一次为这个联系人发起主动请求的时间（含 [SKIP] 和失败）；0 表示没有。设备状态，不随备份 */
    @ColumnInfo(defaultValue = "0") val lastProactiveAt: Long = 0,
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
    /** 来源（0.6.0）：联系人主动发来的是 PROACTIVE */
    @ColumnInfo(defaultValue = "'NORMAL'") val origin: MessageOrigin = MessageOrigin.NORMAL,
    /**
     * 语音文件（0.7.0，files/voice/ 里）。联系人的语音回复在合成完成前为 null：这时它是 PENDING 但不会送达
     * （[isomeria.aichat.data.db.MessageDao.duePending] 不取），合成成功后填上路径才排进投递，失败就改成文字
     */
    val voicePath: String? = null,
    /** 语音时长（毫秒） */
    @ColumnInfo(defaultValue = "0") val voiceMillis: Long = 0,
    /** 联系人的语音是否播放过或转过文字：没有时右侧显示红点 */
    @ColumnInfo(defaultValue = "0") val voicePlayed: Boolean = false,
    /** 是否在语音条下面显示转写文字（「转文字」/「取消转文字」） */
    @ColumnInfo(defaultValue = "0") val voiceShowText: Boolean = false,
) {
    /** 等着合成的语音回复：已经存了文字，还没有语音文件 */
    val awaitingSynthesis: Boolean get() = type == MessageType.VOICE && role == Role.ASSISTANT && voicePath == null

    /** 还在进行（或 App 被关掉、还没补完）的通话（0.8.0）：挂断时才写总时长 */
    val callOngoing: Boolean get() = type == MessageType.CALL && voiceMillis <= 0L
}

/**
 * 通话里的一条话（0.8.0）：用户说的一句，或联系人回复的一句。每句本来就是分开录、分开合成的，文字和音频一一对应。
 *
 * - [createdAt] 是这句开始的时间（绝对时间，晚于通话消息的 createdAt）；在整通里的位置 = createdAt - 通话开始时间
 * - [audioPath] 在 files/calls/ 里：用户的话识别后压成 AAC，联系人的话按合成返回的格式保存；没有时为 null
 * - 上下文、记忆和搜索按 [seq] 顺序展开：用户的话按 user，联系人的话按 assistant；没有文字的（识别失败）跳过
 * - 删除通话消息时一起删（外键级联，调用方另外显式删一遍，不依赖 PRAGMA foreign_keys）
 */
@Entity(
    tableName = "call_turn",
    foreignKeys = [
        ForeignKey(
            entity = Message::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["messageId", "seq"])],
)
data class CallTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val seq: Int,
    val role: Role,
    val text: String = "",
    val audioPath: String? = null,
    val audioMillis: Long = 0,
    val status: CallTurnStatus = CallTurnStatus.OK,
    val createdAt: Long,
)

/**
 * 主动聊天的设备状态（0.6.0），只有一行：今日已用的调用次数和最近一次检查的时间。
 * 不随备份导出，覆盖恢复也不动它。
 */
@Entity(tableName = "proactive_state")
data class ProactiveState(
    @PrimaryKey val id: Int = ID,
    /** [used] 是哪一天的（本地日期的 epochDay）；换了一天就从 0 算 */
    val day: Long = 0,
    val used: Int = 0,
    /** 最近一次检查（周期任务、回到前台或约定提醒）的时间；0 表示还没有运行过 */
    val lastRunAt: Long = 0,
) {
    companion object {
        const val ID = 1
    }
}

/**
 * 用户和联系人约好的时间（0.6.0）：开启主动聊天的联系人在回复里用隐藏标记记下，到点后发起一次主动请求。
 * 触发后删除；关闭主动聊天、清空或删除聊天、删除联系人时一起删除。设备状态，不随备份。
 */
@Entity(
    tableName = "proactive_reminder",
    foreignKeys = [
        ForeignKey(
            entity = Contact::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["contactId"]), Index(value = ["dueAt"])],
)
data class ProactiveReminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactId: Long,
    val dueAt: Long,
    /** 约定的事由，模型写的一句话 */
    val note: String = "",
    val createdAt: Long,
) {
    companion object {
        const val NOTE_MAX_LENGTH = 100
    }
}

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
    val presetId: String? = null,
    /** 协议与品牌分开；legacy 保留旧 provider 的编码语义。 */
    @ColumnInfo(defaultValue = "'LEGACY'") val compatibility: String = "LEGACY",
    /** 语音合成服务商的默认音色（0.7.0，只对 kind 为 TTS 的有意义） */
    @ColumnInfo(defaultValue = "''") val voice: String = "",
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
    @ColumnInfo(defaultValue = "1") val legacy: Boolean = true,
    val reportedConfig: String? = null,
    val userConfig: String? = null,
    val compatibilityOverride: String? = null,
    @ColumnInfo(defaultValue = "0") val missingFromList: Boolean = false,
) {
    val supportsImage: Boolean get() = imageInputUserMark ?: (imageInput == Capability.YES)

    val supportsThinking: Boolean get() = if (legacy) thinkingUserMark ?: (thinkingInput == Capability.YES) else
        isomeria.aichat.data.llm.ModelRules.effective(this).thinking?.mode?.let {
            it == isomeria.aichat.data.llm.ThinkingMode.SWITCH || it == isomeria.aichat.data.llm.ThinkingMode.ALWAYS
        } ?: (thinkingUserMark ?: (thinkingInput == Capability.YES))
}

/**
 * 长期记忆（0.7.2 起分两层）：核心记忆（[core]，每个联系人最多一条）和近期记忆（一段较早的聊天总结成的一条）。
 * 每次请求带上核心记忆和预算内最新的近期记忆。删除联系人时一起删除；清空聊天记录时保留。
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
    /** 用户改过的近期记忆不会被整理进核心记忆 */
    val edited: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * 核心记忆（0.7.2）：每个联系人最多一条，记着现在仍然成立的重要事实，近期记忆超出上限时由模型更新。
     * false 是近期记忆：按时间的一段段总结
     */
    @ColumnInfo(defaultValue = "0") val core: Boolean = false,
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
