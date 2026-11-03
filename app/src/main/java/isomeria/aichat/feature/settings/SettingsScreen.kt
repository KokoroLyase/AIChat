package isomeria.aichat.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.runtime.rememberCoroutineScope
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
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.settings.ThemeMode
import isomeria.aichat.di.ApplicationScope
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.UnreadBadge
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeLoading
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import isomeria.aichat.update.ReleaseInfo
import isomeria.aichat.update.UpdateCheckResult
import isomeria.aichat.update.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val notifier: ReplyNotifier,
    private val chats: ChatRepository,
    private val updates: UpdateChecker,
    @ApplicationScope private val appScope: CoroutineScope,
    providers: ProviderRepository,
) : ViewModel() {
    val themeMode = settings.themeMode
    val autoCheckUpdate = settings.autoCheckUpdateFlow

    /** 已知有新版本时，「检查更新」旁显示红点 */
    val newerRelease: StateFlow<ReleaseInfo?> = updates.newer
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
    fun setAutoCheckUpdate(enabled: Boolean) = settings.setAutoCheckUpdate(enabled)

    /** 手动检查更新；有新版本时由全局的更新弹窗提示 */
    suspend fun checkUpdate(): UpdateCheckResult = updates.manualCheck()

    fun clearAllHistory() {
        appScope.launch { chats.clearAllHistory() }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLlm: () -> Unit,
    onOpenUsage: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenExport: () -> Unit,
    onOpenRestore: () -> Unit,
    onOpenCrashLogs: () -> Unit = {},
    onOpenStorage: () -> Unit = {},
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
    val autoCheckUpdate by viewModel.autoCheckUpdate.collectAsStateWithLifecycle()
    val newerRelease by viewModel.newerRelease.collectAsStateWithLifecycle()
    var showTheme by remember { mutableStateOf(false) }
    var showFont by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                value = if (hasKey) stringResource(R.string.llm_api_key_set) else stringResource(R.string.llm_api_key_missing),
                onClick = onOpenLlm,
                showDivider = true,
            )
            WeListRow(
                title = stringResource(R.string.usage_title),
                onClick = onOpenUsage,
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

            SectionHeader(stringResource(R.string.settings_group_data))
            WeListRow(title = stringResource(R.string.storage_title), onClick = onOpenStorage, showDivider = true)
            WeListRow(title = stringResource(R.string.settings_export), onClick = onOpenExport, showDivider = true)
            WeListRow(title = stringResource(R.string.settings_restore), onClick = onOpenRestore, showDivider = true)
            WeListRow(title = stringResource(R.string.settings_clear_all), onClick = { confirmClearAll = true }, showArrow = false)
            FootnoteText(stringResource(R.string.settings_data_notice))

            SectionHeader(stringResource(R.string.settings_group_about))
            WeListRow(title = stringResource(R.string.crash_title), onClick = onOpenCrashLogs, showDivider = true)
            WeListRow(
                title = stringResource(R.string.settings_version),
                value = BuildConfig.VERSION_NAME,
                showArrow = false,
                showDivider = true,
            )
            WeListRow(
                title = stringResource(R.string.settings_check_update),
                showArrow = false,
                value = newerRelease?.let { stringResource(R.string.settings_new_version, it.version) },
                trailing = if (newerRelease != null) {
                    { UnreadBadge(0, dotOnly = true) }
                } else {
                    null
                },
                onClick = {
                    if (!checkingUpdate) {
                        scope.launch {
                            checkingUpdate = true
                            val result = viewModel.checkUpdate()
                            checkingUpdate = false
                            when (result) {
                                is UpdateCheckResult.Available -> Unit // 全局弹窗提示
                                UpdateCheckResult.UpToDate ->
                                    Toast.makeText(context, R.string.update_latest, Toast.LENGTH_SHORT).show()
                                is UpdateCheckResult.Failed -> updateFailed = true
                            }
                        }
                    }
                },
                showDivider = true,
            )
            WeListRow(
                title = stringResource(R.string.settings_auto_update),
                showArrow = false,
                showDivider = true,
                trailing = { WeSwitch(autoCheckUpdate, viewModel::setAutoCheckUpdate) },
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
    if (confirmClearAll) {
        WeDialog(
            message = stringResource(R.string.settings_clear_all_confirm),
            confirmText = stringResource(R.string.action_clear),
            destructive = true,
            onDismiss = { confirmClearAll = false },
            onConfirm = {
                confirmClearAll = false
                viewModel.clearAllHistory()
                Toast.makeText(context, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
            },
        )
    }
    if (checkingUpdate) WeLoading(stringResource(R.string.update_checking))
    if (updateFailed) {
        WeDialog(
            title = stringResource(R.string.update_failed_title),
            message = stringResource(R.string.update_failed_message),
            confirmText = stringResource(R.string.update_open_browser),
            onDismiss = { updateFailed = false },
            onConfirm = {
                updateFailed = false
                open(Intent(Intent.ACTION_VIEW, "https://github.com/${BuildConfig.GITHUB_REPO}/releases/latest".toUri()))
            },
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
