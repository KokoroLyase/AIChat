package isomeria.aichat.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.BuildConfig
import isomeria.aichat.R
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.settings.ThemeMode
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val notifier: ReplyNotifier,
    providers: ProviderRepository,
) : ViewModel() {
    val themeMode = settings.themeMode
    val fontSize = settings.fontSize
    val notifyEnabled = settings.notifyEnabled
    val notifyPreview = settings.notifyPreview
    val hasApiKey: StateFlow<Boolean> = providers.hasApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _canNotify = MutableStateFlow(notifier.canNotify())
    val canNotify: StateFlow<Boolean> = _canNotify

    fun refreshPermission() {
        _canNotify.value = notifier.canNotify()
    }

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)
    fun setFontSize(size: FontSize) = settings.setFontSize(size)
    fun setNotifyEnabled(enabled: Boolean) = settings.setNotifyEnabled(enabled)
    fun setNotifyPreview(enabled: Boolean) = settings.setNotifyPreview(enabled)
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLlm: () -> Unit,
    onOpenLicenses: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = WeTheme.colors
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val notifyEnabled by viewModel.notifyEnabled.collectAsStateWithLifecycle()
    val notifyPreview by viewModel.notifyPreview.collectAsStateWithLifecycle()
    val canNotify by viewModel.canNotify.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    var showTheme by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }

    // 从系统设置回来时刷新通知权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermission()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun open(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // 个别系统没有对应的设置页
        }
    }

    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.settings_title), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SectionHeader(stringResource(R.string.settings_group_models))
            WeListRow(
                title = stringResource(R.string.settings_llm),
                value = if (hasKey) stringResource(R.string.llm_provider_deepseek) else stringResource(R.string.llm_api_key_missing),
                onClick = onOpenLlm,
                showDivider = true,
            )
            WeListRow(
                title = stringResource(R.string.settings_tts),
                value = stringResource(R.string.coming_soon),
                showArrow = false,
                showDivider = true,
                enabled = false,
            )
            WeListRow(
                title = stringResource(R.string.settings_asr),
                value = stringResource(R.string.coming_soon),
                showArrow = false,
                enabled = false,
            )

            SectionHeader(stringResource(R.string.settings_group_general))
            WeListRow(
                title = stringResource(R.string.settings_dark_mode),
                value = stringResource(themeLabel(themeMode)),
                onClick = { showTheme = true },
                showDivider = true,
            )
            WeListRow(
                title = stringResource(R.string.settings_font_size),
                value = stringResource(fontLabel(fontSize)),
                onClick = { showFont = true },
                showDivider = true,
            )
            WeListRow(
                title = stringResource(R.string.settings_notify),
                showArrow = false,
                showDivider = true,
                trailing = { WeSwitch(notifyEnabled, viewModel::setNotifyEnabled) },
            )
            WeListRow(
                title = stringResource(R.string.settings_notify_preview),
                showArrow = false,
                showDivider = true,
                enabled = notifyEnabled,
                trailing = { WeSwitch(notifyPreview, viewModel::setNotifyPreview, enabled = notifyEnabled) },
            )
            if (notifyEnabled && !canNotify) {
                WeListRow(
                    title = stringResource(R.string.settings_notify_permission_off),
                    titleColor = colors.danger,
                    titleStyle = WeTheme.typography.small,
                    onClick = {
                        open(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                    showDivider = true,
                )
            }
            WeListRow(
                title = stringResource(R.string.settings_battery),
                onClick = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            )
            FootnoteText(stringResource(R.string.settings_battery_hint))

            SectionHeader(stringResource(R.string.settings_group_about))
            WeListRow(
                title = stringResource(R.string.settings_version),
                value = BuildConfig.VERSION_NAME,
                showArrow = false,
                showDivider = true,
            )
            WeListRow(
                title = stringResource(R.string.settings_licenses),
                onClick = onOpenLicenses,
                showDivider = true,
            )
            WeListRow(
                title = stringResource(R.string.settings_github),
                value = BuildConfig.GITHUB_REPO,
                onClick = {
                    open(Intent(Intent.ACTION_VIEW, "https://github.com/${BuildConfig.GITHUB_REPO}".toUri()))
                },
            )
            FootnoteText(stringResource(R.string.settings_data_notice))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTheme) {
        WeActionSheet(
            options = ThemeMode.entries.map { mode ->
                SheetOption(stringResource(themeLabel(mode)), selected = mode == themeMode) { viewModel.setThemeMode(mode) }
            },
            onDismiss = { showTheme = false },
        )
    }
    if (showFont) {
        WeActionSheet(
            options = FontSize.entries.map { size ->
                SheetOption(stringResource(fontLabel(size)), selected = size == fontSize) { viewModel.setFontSize(size) }
            },
            onDismiss = { showFont = false },
        )
    }
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun fontLabel(size: FontSize) = when (size) {
    FontSize.SMALL -> R.string.font_small
    FontSize.STANDARD -> R.string.font_standard
    FontSize.LARGE -> R.string.font_large
    FontSize.EXTRA_LARGE -> R.string.font_extra_large
}
