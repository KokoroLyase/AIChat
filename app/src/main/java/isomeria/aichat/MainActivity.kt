package isomeria.aichat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.settings.ThemeMode
import isomeria.aichat.navigation.AppNavHost
import isomeria.aichat.ui.components.UpdateDialog
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.update.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var updateChecker: UpdateChecker

    /** 点通知进入的聊天 */
    private val openChatRequest = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            handleIntent(intent)
            // 启动时静默检查更新（24 小时最多一次，失败不提示）
            lifecycleScope.launch { updateChecker.autoCheck() }
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
                        // 0.2.0 先跳到 Release 页面下载，0.3.0 改为应用内下载安装
                        onUpdate = {
                            updateChecker.later()
                            openLink(info.pageUrl)
                        },
                        onLater = updateChecker::later,
                        onIgnore = { updateChecker.ignore(info) },
                    )
                }
            }
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
