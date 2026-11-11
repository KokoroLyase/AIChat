package isomeria.aichat.data.settings

import android.content.Context
import com.tencent.mmkv.MMKV
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ProactiveSettingsSource
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.core.ReplyPreferencesSource
import isomeria.aichat.data.memory.NicknameSource
import isomeria.aichat.update.UpdatePrefs
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 设置里「字体大小」的档位，叠加在系统字体缩放之上。 */
enum class FontSize(val scale: Float) {
    SMALL(0.9f),
    STANDARD(1.0f),
    LARGE(1.12f),
    EXTRA_LARGE(1.25f),
}

/**
 * 设置项，存在 MMKV（腾讯开源的键值存储）。每项都以 StateFlow 暴露，界面直接订阅。
 */
@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) :
    UpdatePrefs, NicknameSource, ReplyPreferencesSource, ProactiveSettingsSource, isomeria.aichat.core.ProactiveResultLog,
    isomeria.aichat.core.VoiceSettingsSource {
    private val mmkv: MMKV

    init {
        MMKV.initialize(context)
        mmkv = MMKV.defaultMMKV()
    }

    private val _themeMode = MutableStateFlow(
        ThemeMode.entries.getOrElse(mmkv.decodeInt(KEY_THEME_MODE, 0)) { ThemeMode.SYSTEM },
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _fontSize = MutableStateFlow(
        FontSize.entries.getOrElse(mmkv.decodeInt(KEY_FONT_SIZE, FontSize.STANDARD.ordinal)) { FontSize.STANDARD },
    )
    val fontSize: StateFlow<FontSize> = _fontSize.asStateFlow()

    private val _nickname = MutableStateFlow(mmkv.decodeString(KEY_NICKNAME, "") ?: "")
    /** 自己的昵称；为空时界面显示默认称呼，请求里不附带昵称 */
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    override fun nickname(): String = _nickname.value

    private val _avatarPath = MutableStateFlow(mmkv.decodeString(KEY_AVATAR, null))
    val avatarPath: StateFlow<String?> = _avatarPath.asStateFlow()

    private val _notifyEnabled = MutableStateFlow(mmkv.decodeBool(KEY_NOTIFY, true))
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled.asStateFlow()

    private val _notifyPreview = MutableStateFlow(mmkv.decodeBool(KEY_NOTIFY_PREVIEW, true))
    val notifyPreview: StateFlow<Boolean> = _notifyPreview.asStateFlow()

    private val _replyPreferences = MutableStateFlow(
        ReplyPreferences(
            waitSeconds = mmkv.decodeInt(KEY_REPLY_WAIT, ReplyPreferences.DEFAULT_WAIT_SECONDS),
            targetLength = mmkv.decodeInt(KEY_REPLY_LENGTH, ReplyPreferences.DEFAULT_TARGET_LENGTH),
            minCount = mmkv.decodeInt(KEY_REPLY_MIN_COUNT, ReplyPreferences.DEFAULT_MIN_COUNT),
            maxCount = mmkv.decodeInt(KEY_REPLY_MAX_COUNT, ReplyPreferences.DEFAULT_MAX_COUNT),
            stripPunctuation = mmkv.decodeBool(KEY_REPLY_STRIP, true),
            minIntervalSeconds = mmkv.decodeInt(KEY_REPLY_MIN_INTERVAL, ReplyPreferences.DEFAULT_MIN_INTERVAL),
            maxIntervalSeconds = mmkv.decodeInt(KEY_REPLY_MAX_INTERVAL, ReplyPreferences.DEFAULT_MAX_INTERVAL),
        ).normalized(),
    )
    /** 回复偏好（0.5.0）：开启「使用回复偏好」的联系人共用这一份 */
    val replyPreferences: StateFlow<ReplyPreferences> = _replyPreferences.asStateFlow()

    override fun replyPreferences(): ReplyPreferences = _replyPreferences.value

    fun setReplyPreferences(value: ReplyPreferences) {
        val next = value.normalized()
        mmkv.encode(KEY_REPLY_WAIT, next.waitSeconds)
        mmkv.encode(KEY_REPLY_LENGTH, next.targetLength)
        mmkv.encode(KEY_REPLY_MIN_COUNT, next.minCount)
        mmkv.encode(KEY_REPLY_MAX_COUNT, next.maxCount)
        mmkv.encode(KEY_REPLY_STRIP, next.stripPunctuation)
        mmkv.encode(KEY_REPLY_MIN_INTERVAL, next.minIntervalSeconds)
        mmkv.encode(KEY_REPLY_MAX_INTERVAL, next.maxIntervalSeconds)
        _replyPreferences.value = next
    }

    private val _proactiveSettings = MutableStateFlow(
        ProactiveSettings(
            quietEnabled = mmkv.decodeBool(KEY_PROACTIVE_QUIET, true),
            quietStartMinutes = mmkv.decodeInt(KEY_PROACTIVE_QUIET_START, ProactiveSettings.DEFAULT_QUIET_START),
            quietEndMinutes = mmkv.decodeInt(KEY_PROACTIVE_QUIET_END, ProactiveSettings.DEFAULT_QUIET_END),
            dailyLimit = mmkv.decodeInt(KEY_PROACTIVE_LIMIT, ProactiveSettings.DEFAULT_DAILY_LIMIT),
        ).normalized(),
    )
    /** 主动聊天的全局设置（0.6.0）：免打扰时段和每日调用上限 */
    val proactiveSettings: StateFlow<ProactiveSettings> = _proactiveSettings.asStateFlow()

    override fun proactiveSettings(): ProactiveSettings = _proactiveSettings.value

    fun setProactiveSettings(value: ProactiveSettings) {
        val next = value.normalized()
        mmkv.encode(KEY_PROACTIVE_QUIET, next.quietEnabled)
        mmkv.encode(KEY_PROACTIVE_QUIET_START, next.quietStartMinutes)
        mmkv.encode(KEY_PROACTIVE_QUIET_END, next.quietEndMinutes)
        mmkv.encode(KEY_PROACTIVE_LIMIT, next.dailyLimit)
        _proactiveSettings.value = next
    }

    private val _voiceSettings = MutableStateFlow(
        isomeria.aichat.core.VoiceSettings(
            replyType = isomeria.aichat.data.db.ReplyType.entries.firstOrNull { it.name == mmkv.decodeString(KEY_VOICE_REPLY_TYPE, null) }
                ?: isomeria.aichat.data.db.ReplyType.FOLLOW,
            ttsSource = mmkv.decodeString(KEY_VOICE_TTS, "") ?: "",
            asrSource = mmkv.decodeString(KEY_VOICE_ASR, "") ?: "",
        ),
    )
    /** 语音消息的全局设置（0.7.0）：消息发送类型的默认值、语音合成和识别用哪个来源 */
    val voiceSettings: StateFlow<isomeria.aichat.core.VoiceSettings> = _voiceSettings.asStateFlow()

    override fun voiceSettings(): isomeria.aichat.core.VoiceSettings = _voiceSettings.value

    fun setVoiceSettings(value: isomeria.aichat.core.VoiceSettings) {
        mmkv.encode(KEY_VOICE_REPLY_TYPE, value.replyType.name)
        mmkv.encode(KEY_VOICE_TTS, value.ttsSource)
        mmkv.encode(KEY_VOICE_ASR, value.asrSource)
        _voiceSettings.value = value
    }

    /** 第一次录音前的说明（录音会发给语音识别服务商）是否已经看过；和设备有关，不随备份 */
    var voiceIntroShown: Boolean
        get() = mmkv.decodeBool(KEY_VOICE_INTRO, false)
        set(value) {
            mmkv.encode(KEY_VOICE_INTRO, value)
        }

    /** 是否已经申请过麦克风权限（拒绝后再点按住说话时显示说明和设置入口） */
    var micPermissionAsked: Boolean
        get() = mmkv.decodeBool(KEY_MIC_ASKED, false)
        set(value) {
            mmkv.encode(KEY_MIC_ASKED, value)
        }

    /** 第一次开启主动聊天时的说明（会自动消耗 API 额度）是否已经看过；和设备有关，不随备份 */
    override fun record(contactId: Long, at: Long, result: isomeria.aichat.core.ProactiveResult) {
        mmkv.encode(KEY_PROACTIVE_RESULT + contactId, "$at:${result.name}")
    }

    override fun last(contactId: Long): Pair<Long, isomeria.aichat.core.ProactiveResult>? {
        val raw = mmkv.decodeString(KEY_PROACTIVE_RESULT + contactId) ?: return null
        val at = raw.substringBefore(':').toLongOrNull() ?: return null
        val result = isomeria.aichat.core.ProactiveResult.entries.firstOrNull { it.name == raw.substringAfter(':') } ?: return null
        return at to result
    }

    var proactiveIntroShown: Boolean
        get() = mmkv.decodeBool(KEY_PROACTIVE_INTRO, false)
        set(value) {
            mmkv.encode(KEY_PROACTIVE_INTRO, value)
        }

    /** 是否已经申请过通知权限（第一次发送消息时申请一次） */
    var notificationPermissionAsked: Boolean
        get() = mmkv.decodeBool(KEY_NOTIFY_ASKED, false)
        set(value) {
            mmkv.encode(KEY_NOTIFY_ASKED, value)
        }

    // 检查更新
    private val _autoCheckUpdate = MutableStateFlow(mmkv.decodeBool(KEY_AUTO_UPDATE, true))
    /** 「自动检查更新」开关，默认开 */
    val autoCheckUpdateFlow: StateFlow<Boolean> = _autoCheckUpdate.asStateFlow()

    override val autoCheckUpdate: Boolean get() = _autoCheckUpdate.value

    fun setAutoCheckUpdate(enabled: Boolean) {
        mmkv.encode(KEY_AUTO_UPDATE, enabled)
        _autoCheckUpdate.value = enabled
    }

    override var lastUpdateCheck: Long
        get() = mmkv.decodeLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) {
            mmkv.encode(KEY_LAST_UPDATE_CHECK, value)
        }

    override var ignoredVersion: String?
        get() = mmkv.decodeString(KEY_IGNORED_VERSION, null)
        set(value) {
            if (value == null) mmkv.removeValueForKey(KEY_IGNORED_VERSION) else mmkv.encode(KEY_IGNORED_VERSION, value)
        }

    /** 表情面板「最近使用」，最近的在前 */
    var recentEmoji: List<String>
        get() = mmkv.decodeString(KEY_RECENT_EMOJI, null)?.split(EMOJI_SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
        set(value) {
            mmkv.encode(KEY_RECENT_EMOJI, value.joinToString(EMOJI_SEPARATOR))
        }

    /** 上次键盘弹出时的高度（px），表情和「+」面板用同样的高度，切换时输入栏不跳动；0 表示还不知道 */
    var keyboardHeightPx: Int
        get() = mmkv.decodeInt(KEY_KEYBOARD_HEIGHT, 0)
        set(value) {
            mmkv.encode(KEY_KEYBOARD_HEIGHT, value)
        }

    fun setThemeMode(mode: ThemeMode) {
        mmkv.encode(KEY_THEME_MODE, mode.ordinal)
        _themeMode.value = mode
    }

    fun setFontSize(size: FontSize) {
        mmkv.encode(KEY_FONT_SIZE, size.ordinal)
        _fontSize.value = size
    }

    fun setNickname(name: String) {
        mmkv.encode(KEY_NICKNAME, name)
        _nickname.value = name
    }

    fun setAvatarPath(path: String?) {
        if (path == null) mmkv.removeValueForKey(KEY_AVATAR) else mmkv.encode(KEY_AVATAR, path)
        _avatarPath.value = path
    }

    fun setNotifyEnabled(enabled: Boolean) {
        mmkv.encode(KEY_NOTIFY, enabled)
        _notifyEnabled.value = enabled
    }

    fun setNotifyPreview(enabled: Boolean) {
        mmkv.encode(KEY_NOTIFY_PREVIEW, enabled)
        _notifyPreview.value = enabled
    }

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_NICKNAME = "my_nickname"
        const val KEY_AVATAR = "my_avatar"
        const val KEY_NOTIFY = "notify_enabled"
        const val KEY_NOTIFY_PREVIEW = "notify_preview"
        const val KEY_NOTIFY_ASKED = "notify_permission_asked"
        const val KEY_AUTO_UPDATE = "auto_check_update"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_IGNORED_VERSION = "ignored_version"
        const val KEY_RECENT_EMOJI = "recent_emoji"
        const val KEY_KEYBOARD_HEIGHT = "keyboard_height_px"
        const val EMOJI_SEPARATOR = " "
        const val KEY_REPLY_WAIT = "reply_wait_seconds"
        const val KEY_REPLY_LENGTH = "reply_target_length"
        const val KEY_REPLY_MIN_COUNT = "reply_min_count"
        const val KEY_REPLY_MAX_COUNT = "reply_max_count"
        const val KEY_REPLY_STRIP = "reply_strip_punctuation"
        const val KEY_REPLY_MIN_INTERVAL = "reply_min_interval"
        const val KEY_REPLY_MAX_INTERVAL = "reply_max_interval"
        const val KEY_PROACTIVE_QUIET = "proactive_quiet_enabled"
        const val KEY_PROACTIVE_QUIET_START = "proactive_quiet_start"
        const val KEY_PROACTIVE_QUIET_END = "proactive_quiet_end"
        const val KEY_PROACTIVE_LIMIT = "proactive_daily_limit"
        const val KEY_PROACTIVE_INTRO = "proactive_intro_shown"
        const val KEY_PROACTIVE_RESULT = "proactive_result_"
        const val KEY_VOICE_REPLY_TYPE = "voice_reply_type"
        const val KEY_VOICE_TTS = "voice_tts_source"
        const val KEY_VOICE_ASR = "voice_asr_source"
        const val KEY_VOICE_INTRO = "voice_intro_shown"
        const val KEY_MIC_ASKED = "mic_permission_asked"
    }
}
