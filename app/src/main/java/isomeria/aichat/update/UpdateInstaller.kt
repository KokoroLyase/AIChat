package isomeria.aichat.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.BuildConfig
import isomeria.aichat.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 应用内更新的进度。 */
sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState

    /** [total] 未知时为 -1 */
    data class Downloading(val release: ReleaseInfo, val done: Long = 0, val total: Long = -1) : UpdateInstallState

    /** 下载和校验都通过，可以交给系统安装 */
    data class Ready(val release: ReleaseInfo, val apk: File) : UpdateInstallState

    data class Failed(val release: ReleaseInfo, val failure: UpdateFailure) : UpdateInstallState
}

/** 安装前再核对一次安装包，错误的包不用等系统安装器来拒绝。 */
fun interface ApkVerifier {
    /** 包名、版本号和签名都对时返回 true */
    fun verify(apk: File): Boolean
}

/** 用系统的 PackageManager 读 APK 里的包名、版本号和签名证书。 */
class PackageManagerApkVerifier @Inject constructor(@ApplicationContext private val context: Context) : ApkVerifier {
    override fun verify(apk: File): Boolean {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
        if (archive.packageName != context.packageName) return false
        if (archive.longVersionCode <= BuildConfig.VERSION_CODE) return false
        // 个别系统读不出压缩包的签名：这时交给系统安装器检查，它不会让签名不同的包覆盖安装
        val signers = archive.signingInfo?.apkContentsSigners ?: return true
        val own = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners ?: return true
        return signers.toSet() == own.toSet()
    }
}

/**
 * 应用内更新：下载 APK → 用 .sha256 校验 → 核对安装包 → 交给系统安装。
 * 放在应用级协程里，切到后台也继续下载；进程被系统杀掉后要重新下载。
 */
@Singleton
class UpdateInstaller @Inject constructor(
    private val downloader: ApkDownloader,
    private val verifier: ApkVerifier,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    val state: StateFlow<UpdateInstallState> = _state.asStateFlow()

    private var job: Job? = null

    fun start(release: ReleaseInfo) {
        if (_state.value is UpdateInstallState.Downloading) return
        val apkUrl = release.apkUrl
        val sha256Url = release.sha256Url
        if (apkUrl == null || sha256Url == null) {
            _state.value = UpdateInstallState.Failed(release, UpdateFailure.MISSING_ASSET)
            return
        }
        _state.value = UpdateInstallState.Downloading(release)
        job = scope.launch {
            _state.value = try {
                val apk = downloader.download(apkUrl, sha256Url) { done, total ->
                    _state.value = UpdateInstallState.Downloading(release, done, total)
                }
                if (verifier.verify(apk)) {
                    UpdateInstallState.Ready(release, apk)
                } else {
                    apk.delete()
                    UpdateInstallState.Failed(release, UpdateFailure.INVALID_APK)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                UpdateInstallState.Failed(release, e.failure)
            }
        }
    }

    /** 取消下载 */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = UpdateInstallState.Idle
    }

    /** 关掉失败提示，或已经交给系统安装 */
    fun reset() {
        _state.value = UpdateInstallState.Idle
    }

    /** 启动时清掉上次下载的安装包 */
    fun cleanupOldFiles() {
        scope.launch { isomeria.aichat.data.repo.StorageOperations.gate.activity { downloader.cleanup() } }
    }

    companion object {
        /** 拉起系统安装界面的 Intent */
        fun installIntent(context: Context, apk: File): Intent {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            return Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
