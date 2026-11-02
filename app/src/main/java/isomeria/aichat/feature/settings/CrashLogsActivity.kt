package isomeria.aichat.feature.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import isomeria.aichat.ui.theme.AIChatTheme

/** Launcher shortcut: remains available even when opening the database crashes the main process. */
class CrashLogsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIChatTheme(darkTheme = isSystemInDarkTheme()) {
                Box(Modifier.safeDrawingPadding()) {
                    CrashLogsScreen(onBack = ::finish, viewModel = viewModel(factory = viewModelFactory {
                        initializer { CrashLogsViewModel(applicationContext) }
                    }))
                }
            }
        }
    }
}
