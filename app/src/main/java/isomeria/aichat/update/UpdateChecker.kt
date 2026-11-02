package isomeria.aichat.update

import isomeria.aichat.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 检查更新用到的设置项（存在 MMKV，见 SettingsStore）。 */
interface UpdatePrefs {
    /** 「自动检查更新」开关 */
    val autoCheckUpdate: Boolean

    /** 上次自动检查的时间 */
    var lastUpdateCheck: Long

    /** 用户点了「忽略此版本」的版本号 */
    var ignoredVersion: String?
}

/** 一个已发布的版本。 */
data class ReleaseInfo(
    /** 去掉 v 前缀的版本号，如 0.2.0 */
    val version: String,
    val tag: String,
    /** 更新说明（已转成纯文本） */
    val notes: String,
    /** Release 页面地址，下载失败时在浏览器里打开它 */
    val pageUrl: String,
    /** APK 下载地址 */
    val apkUrl: String?,
    /** .sha256 校验文件的下载地址 */
    val sha256Url: String? = null,
)

/** 更新来源。GitHub 是第一个实现；以后可以加更新镜像。 */
interface UpdateSource {
    suspend fun latest(): ReleaseInfo
}

sealed interface UpdateCheckResult {
    data class Available(val release: ReleaseInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Failed(val error: Exception) : UpdateCheckResult
}

/**
 * 检查更新：启动时静默检查，24 小时最多一次；设置里可手动检查。
 *
 * - 自动检查失败时不提示，被「忽略此版本」的版本不再弹窗
 * - 手动检查总是请求接口；有新版本时即使被忽略过也弹窗，失败时把错误交给界面显示
 * - 只请求公开接口，不携带任何聊天内容或用户数据
 */
@Singleton
class UpdateChecker(
    private val source: UpdateSource,
    private val prefs: UpdatePrefs,
    private val currentVersion: String,
    private val now: () -> Long,
) {
    @Inject constructor(source: UpdateSource, prefs: UpdatePrefs) :
        this(source, prefs, BuildConfig.VERSION_NAME, System::currentTimeMillis)

    private val mutex = Mutex()

    private val _pending = MutableStateFlow<ReleaseInfo?>(null)
    /** 要弹窗提示的新版本；用户点了「稍后」或「忽略此版本」后清空 */
    val pending: StateFlow<ReleaseInfo?> = _pending.asStateFlow()

    private val _newer = MutableStateFlow<ReleaseInfo?>(null)
    /** 已知的新版本（设置里「检查更新」旁显示红点） */
    val newer: StateFlow<ReleaseInfo?> = _newer.asStateFlow()

    suspend fun autoCheck() = mutex.withLock {
        if (!prefs.autoCheckUpdate) return@withLock
        val time = now()
        val last = prefs.lastUpdateCheck
        // 时间被往回调过（last 在未来）时照常检查
        if (last in (time - CHECK_INTERVAL_MS + 1)..time) return@withLock
        prefs.lastUpdateCheck = time
        val release = try {
            source.latest()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock
        }
        if (isNewerVersion(release.tag, currentVersion)) {
            _newer.value = release
            if (prefs.ignoredVersion != release.version) _pending.value = release
        } else {
            _newer.value = null
        }
    }

    suspend fun manualCheck(): UpdateCheckResult = mutex.withLock {
        val release = try {
            source.latest()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock UpdateCheckResult.Failed(e)
        }
        prefs.lastUpdateCheck = now()
        if (isNewerVersion(release.tag, currentVersion)) {
            _newer.value = release
            _pending.value = release
            UpdateCheckResult.Available(release)
        } else {
            _newer.value = null
            UpdateCheckResult.UpToDate
        }
    }

    /** 「稍后」：这次不再提示，下次启动（24 小时后）还会检查 */
    fun later() {
        _pending.value = null
    }

    /** 「忽略此版本」：这个版本不再自动提示，更新的版本发布后照常提示 */
    fun ignore(release: ReleaseInfo) {
        prefs.ignoredVersion = release.version
        _pending.value = null
    }

    companion object {
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
