package isomeria.aichat

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.settings.ThemeMode
import isomeria.aichat.navigation.AppNavHost
import isomeria.aichat.ui.theme.AIChatTheme
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsStore

    /** 点通知进入的聊天 */
    private val openChatRequest = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
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
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == ReplyNotifier.ACTION_OPEN_CHAT) {
            val contactId = intent.getLongExtra(ReplyNotifier.EXTRA_CONTACT_ID, -1L)
            if (contactId >= 0) openChatRequest.value = contactId
        }
    }
}
