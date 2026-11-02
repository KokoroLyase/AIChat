package isomeria.aichat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.settings.ThemeMode
import isomeria.aichat.navigation.AppNavHost
import isomeria.aichat.ui.components.UpdateDialog
import isomeria.aichat.ui.components.UpdateFailedDialog
import isomeria.aichat.ui.components.UpdateInstallPermissionDialog
import isomeria.aichat.ui.components.UpdateProgressDialog
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.update.UpdateChecker
import isomeria.aichat.update.UpdateInstallState
import isomeria.aichat.update.UpdateInstaller
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var updateChecker: UpdateChecker
    @Inject lateinit var updateInstaller: UpdateInstaller

    /** 点通知进入的聊天 */
    private val openChatRequest = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleIntent(intent)
            // 启动时静默检查更新（24 小时最多一次，失败不提示）
            lifecycleScope.launch { updateChecker.autoCheck() }
            // 上次下载的安装包已经没用了
            if (updateInstaller.state.value == UpdateInstallState.Idle) updateInstaller.cleanupOldFiles()
        }
        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()
            val fontSize by settings.fontSize.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            AIChatTheme(darkTheme = dark, fontScale = fontSize.scale) {
                AppNavHost(
                    openChatRequest = openChatRequest,
                    onOpenChatHandled = { openChatRequest.value = null },
                )
                val release by updateChecker.pending.collectAsStateWithLifecycle()
                release?.let { info ->
                    UpdateDialog(
                        release = info,
                        onUpdate = {
                            updateChecker.later()
                            updateInstaller.start(info)
                        },
                        onLater = updateChecker::later,
                        onIgnore = { updateChecker.ignore(info) },
                    )
                }
                UpdateProgress()
            }
        }
    }

    /** 下载进度、失败提示，以及下载完成后交给系统安装 */
    @Composable
    private fun UpdateProgress() {
        val state by updateInstaller.state.collectAsStateWithLifecycle()
        var needsPermission by remember { mutableStateOf(false) }
        var canInstall by remember { mutableStateOf(packageManager.canRequestPackageInstalls()) }
        // 从系统设置回来时重新检查
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            canInstall = packageManager.canRequestPackageInstalls()
        }
        when (val current = state) {
            is UpdateInstallState.Downloading -> UpdateProgressDialog(current, updateInstaller::cancel)
            is UpdateInstallState.Failed -> UpdateFailedDialog(
                failure = current.failure,
                onOpenBrowser = {
                    updateInstaller.reset()
                    openLink(current.release.pageUrl)
                },
                onDismiss = updateInstaller::reset,
            )
            is UpdateInstallState.Ready -> {
                LaunchedEffect(current, canInstall) {
                    if (canInstall) {
                        needsPermission = false
                        updateInstaller.reset()
                        startInstall(current)
                    } else {
                        needsPermission = true
                    }
                }
                if (needsPermission) {
                    UpdateInstallPermissionDialog(
                        onOpenSettings = {
                            needsPermission = false
                            openInstallPermissionSettings()
                        },
                        onDismiss = {
                            needsPermission = false
                            updateInstaller.reset()
                        },
                    )
                }
            }
            UpdateInstallState.Idle -> Unit
        }
    }

    private fun startInstall(ready: UpdateInstallState.Ready) {
        try {
            startActivity(UpdateInstaller.installIntent(this, ready.apk))
        } catch (_: ActivityNotFoundException) {
            openLink(ready.release.pageUrl)
        }
    }

    private fun openInstallPermissionSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_to_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ReplyNotifier.ACTION_OPEN_CHAT) {
            val contactId = intent.getLongExtra(ReplyNotifier.EXTRA_CONTACT_ID, -1L)
            if (contactId >= 0) openChatRequest.value = contactId
        }
    }
}
