package isomeria.aichat.feature.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
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
import isomeria.aichat.feature.voice.selectedName
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.settings.ThemeMode
import isomeria.aichat.di.ApplicationScope
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.WeActionRow
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.UnreadBadge
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeLoading
import isomeria.aichat.ui.components.WeSwitch
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
    voices: isomeria.aichat.data.repo.VoiceRepository,
) : ViewModel() {
    /** 正在用的语音合成、识别来源（服务商或 0.7.1 的本机来源） */
    val voiceSources: StateFlow<Pair<isomeria.aichat.data.repo.VoiceProviders?, isomeria.aichat.data.repo.VoiceProviders?>> = kotlinx.coroutines.flow.combine(
        voices.observe(isomeria.aichat.data.db.ProviderKind.TTS, settings.voiceSettings),
        voices.observe(isomeria.aichat.data.db.ProviderKind.ASR, settings.voiceSettings),
    ) { tts, asr -> tts to asr }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to null)

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

/** 「我」页的四个设置入口，每个进入自己的设置页（0.4.2 起代替单独的「设置」页） */
enum class SettingsSection(@androidx.annotation.StringRes val title: Int) {
    MODELS(R.string.settings_group_models),
    GENERAL(R.string.settings_group_general),
    DATA(R.string.settings_group_data),
    ABOUT(R.string.settings_group_about),
}

/** 设置页里要打开的下一级页面 */
data class SettingsNavigation(
    val onOpenLlm: () -> Unit = {},
    val onOpenUsage: () -> Unit = {},
    val onOpenLicenses: () -> Unit = {},
    val onOpenExport: () -> Unit = {},
    val onOpenRestore: () -> Unit = {},
    val onOpenCrashLogs: () -> Unit = {},
    val onOpenStorage: () -> Unit = {},
    val onOpenTts: () -> Unit = {},
    val onOpenAsr: () -> Unit = {},
)

/** 设置页显示用的状态 */
data class SettingsUiState(
    val hasKey: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val fontSize: FontSize = FontSize.STANDARD,
    val notifyEnabled: Boolean = true,
    val notifyPreview: Boolean = true,
    val canNotify: Boolean = true,
    val autoCheckUpdate: Boolean = true,
    /** 已知有新版本时的版本号，「检查更新」旁显示红点 */
    val newerVersion: String? = null,
    val versionName: String = BuildConfig.VERSION_NAME,
    val githubRepo: String = BuildConfig.GITHUB_REPO,
    /** 正在用的语音合成、识别服务商的名称（0.7.0）；没有选时为 null */
    val ttsName: String? = null,
    val asrName: String? = null,
)

/** 设置页里跳转之外的点击和开关 */
data class SettingsActions(
    val onTheme: () -> Unit = {},
    val onFont: () -> Unit = {},
    val onNotifyEnabled: (Boolean) -> Unit = {},
    val onNotifyPreview: (Boolean) -> Unit = {},
    val onNotifyPermission: () -> Unit = {},
    val onBattery: () -> Unit = {},
    val onClearAll: () -> Unit = {},
    val onCheckUpdate: () -> Unit = {},
    val onAutoCheckUpdate: (Boolean) -> Unit = {},
    val onGithub: () -> Unit = {},
)

/**
 * 一栏的设置页：「我」→ 模型服务 / 通用设置 / 数据管理 / 关于 APP。
 * 管状态、弹窗和系统跳转，行由 [SettingsSectionList] 画。各栏的行和 0.4.1 设置页对应分组一一对应。
 */
@Composable
fun SettingsSectionScreen(
    section: SettingsSection,
    onBack: () -> Unit,
    navigation: SettingsNavigation,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val notifyEnabled by viewModel.notifyEnabled.collectAsStateWithLifecycle()
    val notifyPreview by viewModel.notifyPreview.collectAsStateWithLifecycle()
    val canNotify by viewModel.canNotify.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val autoCheckUpdate by viewModel.autoCheckUpdate.collectAsStateWithLifecycle()
    val newerRelease by viewModel.newerRelease.collectAsStateWithLifecycle()
    val voiceSources by viewModel.voiceSources.collectAsStateWithLifecycle()
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

    SettingsSectionContent(
        section = section,
        state = SettingsUiState(
            hasKey = hasKey,
            themeMode = themeMode,
            fontSize = fontSize,
            notifyEnabled = notifyEnabled,
            notifyPreview = notifyPreview,
            canNotify = canNotify,
            autoCheckUpdate = autoCheckUpdate,
            newerVersion = newerRelease?.version,
            ttsName = voiceSources.first?.let { isomeria.aichat.feature.voice.voiceSourceName(it.selectedName(), it.local) },
            asrName = voiceSources.second?.let { isomeria.aichat.feature.voice.voiceSourceName(it.selectedName(), it.local) },
        ),
        onBack = onBack,
        navigation = navigation,
        actions = SettingsActions(
            onTheme = { showTheme = true },
            onFont = { showFont = true },
            onNotifyEnabled = viewModel::setNotifyEnabled,
            onNotifyPreview = viewModel::setNotifyPreview,
            onNotifyPermission = {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
            onBattery = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            onClearAll = { confirmClearAll = true },
            onCheckUpdate = {
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
            onAutoCheckUpdate = viewModel::setAutoCheckUpdate,
            onGithub = { open(Intent(Intent.ACTION_VIEW, "https://github.com/${BuildConfig.GITHUB_REPO}".toUri())) },
        ),
    )

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

/** 一栏设置页的外观：顶栏标题是栏名，下面是这一栏的行 */
@Composable
fun SettingsSectionContent(
    section: SettingsSection,
    state: SettingsUiState,
    onBack: () -> Unit,
    navigation: SettingsNavigation,
    actions: SettingsActions,
) {
    Column(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) {
        WeTopBar(title = stringResource(section.title), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            SettingsSectionList(section, state, navigation, actions)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 一栏里的行，和 0.4.1 设置页的对应分组一致 */
@Composable
fun SettingsSectionList(section: SettingsSection, state: SettingsUiState, navigation: SettingsNavigation, actions: SettingsActions) {
    val colors = WeTheme.colors
    when (section) {
        SettingsSection.MODELS -> {
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_llm),
                value = if (state.hasKey) stringResource(R.string.llm_api_key_set) else stringResource(R.string.llm_api_key_missing),
                onClick = navigation.onOpenLlm,
                showDivider = true,
            )
            WeListRow(titleMaxLines = 2, title = stringResource(R.string.usage_title), onClick = navigation.onOpenUsage, showDivider = true)
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_tts),
                value = state.ttsName ?: stringResource(R.string.voice_source_unset),
                onClick = navigation.onOpenTts,
                showDivider = true,
            )
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_asr),
                value = state.asrName ?: stringResource(R.string.voice_source_unset),
                onClick = navigation.onOpenAsr,
            )
        }
        SettingsSection.GENERAL -> {
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_dark_mode),
                value = stringResource(themeLabel(state.themeMode)),
                onClick = actions.onTheme,
                showDivider = true,
            )
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_font_size),
                value = stringResource(fontLabel(state.fontSize)),
                onClick = actions.onFont,
                showDivider = true,
            )
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_notify),
                showArrow = false,
                showDivider = true,
                trailing = { WeSwitch(state.notifyEnabled, actions.onNotifyEnabled) },
            )
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_notify_preview),
                showArrow = false,
                showDivider = true,
                enabled = state.notifyEnabled,
                trailing = { WeSwitch(state.notifyPreview, actions.onNotifyPreview, enabled = state.notifyEnabled) },
            )
            if (state.notifyEnabled && !state.canNotify) {
                WeListRow(
                titleMaxLines = 2,
                    title = stringResource(R.string.settings_notify_permission_off),
                    titleColor = colors.danger,
                    titleStyle = WeTheme.typography.small,
                    onClick = actions.onNotifyPermission,
                    showDivider = true,
                )
            }
            WeListRow(titleMaxLines = 2, title = stringResource(R.string.settings_battery), onClick = actions.onBattery)
            FootnoteText(stringResource(R.string.settings_battery_hint))
        }
        SettingsSection.DATA -> {
            WeListRow(titleMaxLines = 2, title = stringResource(R.string.storage_title), onClick = navigation.onOpenStorage, showDivider = true)
            WeListRow(titleMaxLines = 2, title = stringResource(R.string.settings_export), onClick = navigation.onOpenExport, showDivider = true)
            WeListRow(titleMaxLines = 2, title = stringResource(R.string.settings_restore), onClick = navigation.onOpenRestore, showDivider = true)
            WeActionRow(stringResource(R.string.settings_clear_all), onClick = actions.onClearAll, danger = true)
            FootnoteText(stringResource(R.string.settings_data_notice))
        }
        SettingsSection.ABOUT -> {
            WeListRow(titleMaxLines = 2, title = stringResource(R.string.crash_title), onClick = navigation.onOpenCrashLogs, showDivider = true)
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_version),
                value = state.versionName,
                showArrow = false,
                showDivider = true,
            )
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_check_update),
                showArrow = false,
                value = state.newerVersion?.let { stringResource(R.string.settings_new_version, it) },
                trailing = if (state.newerVersion != null) {
                    { UnreadBadge(0, dotOnly = true) }
                } else {
                    null
                },
                onClick = actions.onCheckUpdate,
                showDivider = true,
            )
            WeListRow(
                titleMaxLines = 2,
                title = stringResource(R.string.settings_auto_update),
                showArrow = false,
                showDivider = true,
                trailing = { WeSwitch(state.autoCheckUpdate, actions.onAutoCheckUpdate) },
            )
            WeListRow(titleMaxLines = 2, title = stringResource(R.string.settings_licenses), onClick = navigation.onOpenLicenses, showDivider = true)
            WeListRow(titleMaxLines = 2, title = stringResource(R.string.settings_github), value = state.githubRepo, onClick = actions.onGithub)
        }
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
