package isomeria.aichat.data.settings

import android.content.Context
import com.tencent.mmkv.MMKV
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
class SettingsStore @Inject constructor(@ApplicationContext context: Context) : UpdatePrefs {
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

    private val _avatarPath = MutableStateFlow(mmkv.decodeString(KEY_AVATAR, null))
    val avatarPath: StateFlow<String?> = _avatarPath.asStateFlow()

    private val _notifyEnabled = MutableStateFlow(mmkv.decodeBool(KEY_NOTIFY, true))
    val notifyEnabled: StateFlow<Boolean> = _notifyEnabled.asStateFlow()

    private val _notifyPreview = MutableStateFlow(mmkv.decodeBool(KEY_NOTIFY_PREVIEW, true))
    val notifyPreview: StateFlow<Boolean> = _notifyPreview.asStateFlow()

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
    }
}
