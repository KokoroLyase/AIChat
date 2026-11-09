package isomeria.aichat.data.backup

import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.settings.ThemeMode
import javax.inject.Inject

/** 随备份导出和恢复的设置项。 */
data class AppSettingsSnapshot(
    val themeMode: ThemeMode,
    val fontSize: FontSize,
    val nickname: String,
    /** 本机头像文件的绝对路径 */
    val avatarPath: String?,
    val notifyEnabled: Boolean,
    val notifyPreview: Boolean,
    val autoCheckUpdate: Boolean,
    /** 回复偏好（0.5.0） */
    val replyPreferences: ReplyPreferences = ReplyPreferences(),
)

/** 导出和恢复读写设置的入口；单元测试里换成内存实现（MMKV 需要原生库）。 */
interface BackupSettingsAccess {
    fun read(): AppSettingsSnapshot
    fun write(snapshot: AppSettingsSnapshot)
}

class SettingsStoreBackupAccess @Inject constructor(private val store: SettingsStore) : BackupSettingsAccess {
    override fun read() = AppSettingsSnapshot(
        themeMode = store.themeMode.value,
        fontSize = store.fontSize.value,
        nickname = store.nickname.value,
        avatarPath = store.avatarPath.value,
        notifyEnabled = store.notifyEnabled.value,
        notifyPreview = store.notifyPreview.value,
        autoCheckUpdate = store.autoCheckUpdate,
        replyPreferences = store.replyPreferences.value,
    )

    override fun write(snapshot: AppSettingsSnapshot) {
        store.setThemeMode(snapshot.themeMode)
        store.setFontSize(snapshot.fontSize)
        store.setNickname(snapshot.nickname)
        store.setAvatarPath(snapshot.avatarPath)
        store.setNotifyEnabled(snapshot.notifyEnabled)
        store.setNotifyPreview(snapshot.notifyPreview)
        store.setAutoCheckUpdate(snapshot.autoCheckUpdate)
        store.setReplyPreferences(snapshot.replyPreferences)
    }
}
