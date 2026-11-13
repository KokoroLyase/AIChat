package isomeria.aichat.feature.voice

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.data.voice.ModelDownloadControl
import isomeria.aichat.data.voice.ModelDownloadWork
import isomeria.aichat.data.voice.ModelFailure
import isomeria.aichat.data.voice.ModelState
import isomeria.aichat.data.voice.OfflineAsrEngine
import isomeria.aichat.data.voice.OfflineAsrModel
import isomeria.aichat.data.voice.OfflineModelStore
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.WeActionRow
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeDivider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.LocalVoicePrefs
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.repo.VoiceRepository
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.voice.SystemTts
import isomeria.aichat.data.voice.SystemTtsInfo
import isomeria.aichat.data.voice.SystemTtsStatus
import isomeria.aichat.feature.settings.ChoiceRow
import isomeria.aichat.feature.settings.ConfigActionRow
import isomeria.aichat.ui.components.ErrorNote
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 本机来源的显示名（0.7.1） */
internal fun localSourceLabel(source: VoiceSource.Local): Int = when (source) {
    VoiceSource.SYSTEM_TTS -> R.string.voice_local_system_tts
    else -> R.string.voice_local_offline_asr
}

// ---------- 系统语音（本机） ----------

data class SystemTtsUiState(
    /** null 表示还在检查 */
    val info: SystemTtsInfo? = null,
    val inUse: Boolean = false,
    /** 选的音色；空串是引擎默认 */
    val voice: String = "",
)

@HiltViewModel
class SystemTtsViewModel @Inject constructor(
    settings: SettingsStore,
    private val voices: VoiceRepository,
    private val tts: SystemTts,
    private val prefs: LocalVoicePrefs,
) : ViewModel() {
    private val info = MutableStateFlow<SystemTtsInfo?>(null)
    private val voice = MutableStateFlow(prefs.systemTtsVoice)

    val state: StateFlow<SystemTtsUiState> = combine(info, settings.voiceSettings, voice) { info, _, voice ->
        SystemTtsUiState(info, voices.selectedLocal(ProviderKind.TTS) == VoiceSource.SYSTEM_TTS, voice)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SystemTtsUiState())

    /** 每次回到这一页都重新连接引擎：用户可能刚在系统设置里换了引擎或装了中文语音 */
    fun refresh() {
        viewModelScope.launch { info.value = runCatching { tts.info(refresh = true) }.getOrDefault(SystemTtsInfo(SystemTtsStatus.FAILED)) }
    }

    fun setUse(use: Boolean) = voices.selectLocal(ProviderKind.TTS, VoiceSource.SYSTEM_TTS, use)

    fun setVoice(name: String) {
        prefs.systemTtsVoice = name
        voice.value = name
    }
}

/**
 * 我 → 语音消息 → 语音合成 → 系统语音（本机）（0.7.1）：用手机自带的语音合成引擎，不需要 Key 和地址。
 * 没有引擎、没有不联网的中文音色时开关不能打开，并说明原因
 */
@Composable
fun SystemTtsScreen(onBack: () -> Unit, viewModel: SystemTtsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val context = LocalContext.current
    val missing = stringResource(R.string.system_tts_settings_missing)
    SystemTtsContent(
        state = state,
        onBack = onBack,
        onUse = viewModel::setUse,
        onVoice = viewModel::setVoice,
        onOpenSystemSettings = {
            try {
                context.startActivity(Intent(SYSTEM_TTS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
            }
        },
    )
}

/** 系统的「文字转语音输出」设置页；不是公开常量，各家手机大多支持 */
private const val SYSTEM_TTS_SETTINGS = "com.android.settings.TTS_SETTINGS"

@Composable
fun SystemTtsContent(
    state: SystemTtsUiState,
    onBack: () -> Unit,
    onUse: (Boolean) -> Unit,
    onVoice: (String) -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val colors = WeTheme.colors
    val info = state.info
    val ready = info?.status == SystemTtsStatus.READY
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.voice_local_system_tts), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            val useLabel = stringResource(R.string.system_tts_use)
            WeListRow(
                title = useLabel,
                showDivider = true,
                titleMaxLines = 3,
                // 不能用时只允许关掉（之前选用过、后来引擎被卸载的情况）
                trailing = { WeSwitch(state.inUse, onUse, enabled = ready || state.inUse, label = useLabel) },
            )
            // 点开是系统的「文字转语音输出」设置（换引擎、下载语音数据）；值放不下时换到标题下面
            ConfigActionRow(
                title = stringResource(R.string.system_tts_engine),
                value = when {
                    info == null -> stringResource(R.string.system_tts_checking)
                    info.engine.isNullOrBlank() -> stringResource(R.string.system_tts_engine_none)
                    else -> info.engine
                },
                onClick = onOpenSystemSettings,
            )
            when (info?.status) {
                SystemTtsStatus.NO_ENGINE -> ErrorNote(stringResource(R.string.system_tts_status_no_engine))
                SystemTtsStatus.NO_CHINESE -> ErrorNote(stringResource(R.string.system_tts_status_no_chinese))
                SystemTtsStatus.FAILED -> ErrorNote(stringResource(R.string.system_tts_status_failed))
                else -> Unit
            }
            if (ready) {
                GroupSpacer()
                val engineDefault = stringResource(R.string.system_tts_voice_default)
                val options = listOf(engineDefault to "") + info.voices.map { "${it.name}（${it.locale}）" to it.name }
                ChoiceRow(
                    stringResource(R.string.system_tts_voice),
                    options.firstOrNull { it.second == state.voice }?.first ?: engineDefault,
                    options = options,
                    onPick = onVoice,
                )
            }
            FootnoteText(stringResource(R.string.system_tts_note))
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- 离线识别（本机） ----------

data class OfflineAsrUiState(
    /** 这台手机能不能跑离线识别（64 位 ARM、原生库加载成功） */
    val supported: Boolean = true,
    val model: ModelState = ModelState.Missing,
    val work: ModelDownloadWork = ModelDownloadWork.NONE,
    val inUse: Boolean = false,
    val totalBytes: Long = OfflineAsrModel.TOTAL_BYTES,
    /** 单独选了离线识别的联系人数（0.7.2）：删除模型后他们改为跟随全局 */
    val contactsUsing: Int = 0,
) {
    val installed: Boolean get() = model == ModelState.Installed

    /** 正在下载或等着下载（等网络） */
    val busy: Boolean get() = work != ModelDownloadWork.NONE || model is ModelState.Downloading || model == ModelState.Verifying
}

@HiltViewModel
class OfflineAsrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    settings: SettingsStore,
    private val voices: VoiceRepository,
    private val store: OfflineModelStore,
    private val downloads: ModelDownloadControl,
    engine: OfflineAsrEngine,
) : ViewModel() {
    private val supported = engine.supported()

    val state: StateFlow<OfflineAsrUiState> = combine(store.state, downloads.work, settings.voiceSettings) { model, work, _ ->
        OfflineAsrUiState(
            supported, model, work, voices.selectedLocal(ProviderKind.ASR) == VoiceSource.OFFLINE_ASR, store.totalBytes,
            contactsUsing = voices.contactsUsing(ProviderKind.ASR, VoiceSource.of(VoiceSource.OFFLINE_ASR)),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineAsrUiState(supported, store.state.value, totalBytes = store.totalBytes))

    /** 现在用的是不是按流量计费的网络（下载前的提示里提醒） */
    fun metered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    fun download() = downloads.start()

    fun pause() = downloads.cancel()

    fun setUse(use: Boolean) = voices.selectLocal(ProviderKind.ASR, VoiceSource.OFFLINE_ASR, use)

    /** 删除模型（含没下完的部分）；正在用离线识别时改成没有选，单独选了它的联系人改为跟随全局（0.7.2） */
    fun delete() {
        downloads.cancel()
        viewModelScope.launch {
            voices.releaseLocal(ProviderKind.ASR, VoiceSource.OFFLINE_ASR)
            store.delete()
        }
    }
}

data class OfflineAsrActions(
    val onUse: (Boolean) -> Unit = {},
    val onDownload: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val metered: () -> Boolean = { false },
)

/**
 * 我 → 语音消息 → 语音识别 → 离线识别（本机）（0.7.1）：下载识别模型后在手机上识别，录音不离开手机。
 * 下载前写明大小、提示 Wi-Fi，可以暂停和继续，下载后按写死的 SHA-256 校验；模型可以单独删除，不随备份
 */
@Composable
fun OfflineAsrScreen(onBack: () -> Unit, viewModel: OfflineAsrViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OfflineAsrContent(
        state,
        onBack,
        OfflineAsrActions(viewModel::setUse, viewModel::download, viewModel::pause, viewModel::delete, viewModel::metered),
    )
}

/** 和存储空间页（系统的 Formatter）一样按 1 MB = 1,000,000 字节算 */
private fun megabytes(bytes: Long): String = "%.1f".format(java.util.Locale.ROOT, bytes / 1_000_000.0)

@Composable
fun OfflineAsrContent(state: OfflineAsrUiState, onBack: () -> Unit, actions: OfflineAsrActions) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    var confirmDownload by remember { mutableStateOf<Boolean?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val total = megabytes(state.totalBytes)
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.voice_local_offline_asr), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            val useLabel = stringResource(R.string.offline_asr_use)
            WeListRow(
                title = useLabel,
                // 模型没下好或手机不支持时只允许关掉
                trailing = { WeSwitch(state.inUse, actions.onUse, enabled = (state.supported && state.installed) || state.inUse, label = useLabel) },
            )
            when {
                !state.supported -> ErrorNote(stringResource(R.string.offline_asr_unsupported))
                !state.installed -> FootnoteText(stringResource(R.string.offline_asr_need_model))
            }

            SectionHeader(stringResource(R.string.offline_asr_model_section))
            Column(Modifier.fillMaxWidth().background(colors.listBackground).padding(horizontal = dimens.horizontalPadding, vertical = 12.dp)) {
                Text(stringResource(R.string.offline_asr_model_name), style = WeTheme.typography.listTitle, color = colors.textPrimary)
                Text(stringResource(R.string.offline_asr_model_detail, total), style = WeTheme.typography.listTime, color = colors.textTertiary)
                val model = state.model
                val status = when {
                    model == ModelState.Installed -> stringResource(R.string.offline_asr_status_installed)
                    model == ModelState.Verifying -> stringResource(R.string.offline_asr_status_verifying)
                    model is ModelState.Downloading -> stringResource(
                        R.string.offline_asr_status_downloading, megabytes(model.bytes), total, (model.bytes * 100 / state.totalBytes).toInt(),
                    )
                    state.work == ModelDownloadWork.WAITING -> stringResource(R.string.offline_asr_status_waiting)
                    model is ModelState.Partial -> stringResource(R.string.offline_asr_status_partial, megabytes(model.bytes), total)
                    model is ModelState.Failed && model.bytes > 0 -> stringResource(R.string.offline_asr_status_partial, megabytes(model.bytes), total)
                    else -> stringResource(R.string.offline_asr_status_missing)
                }
                Text(status, style = WeTheme.typography.listTime, color = if (state.installed) colors.brandText else colors.textSecondary)
            }
            (state.model as? ModelState.Failed)?.takeIf { !state.busy }?.let { ErrorNote(stringResource(failureText(it.failure))) }
            WeDivider(startIndent = dimens.horizontalPadding)
            val model = state.model
            when {
                state.busy && model != ModelState.Verifying ->
                    WeActionRow(stringResource(R.string.offline_asr_pause), onClick = actions.onPause)
                model == ModelState.Missing ->
                    WeActionRow(stringResource(R.string.offline_asr_download), onClick = { confirmDownload = actions.metered() })
                model is ModelState.Partial || model is ModelState.Failed ->
                    WeActionRow(stringResource(R.string.offline_asr_resume), onClick = { confirmDownload = actions.metered() })
            }
            if (!state.busy && model != ModelState.Missing) {
                GroupSpacer()
                WeActionRow(stringResource(if (state.installed) R.string.offline_asr_delete else R.string.offline_asr_delete_partial), onClick = { confirmDelete = true }, danger = true)
            }
            FootnoteText(stringResource(R.string.offline_asr_note))
            FootnoteText(stringResource(R.string.offline_asr_source_note))
            Spacer(Modifier.height(24.dp))
        }
    }
    confirmDownload?.let { metered ->
        WeDialog(
            title = stringResource(R.string.offline_asr_download_title),
            message = stringResource(R.string.offline_asr_download_message, total) +
                if (metered) "\n\n" + stringResource(R.string.offline_asr_download_metered) else "",
            confirmText = stringResource(R.string.offline_asr_download_confirm),
            onDismiss = { confirmDownload = null },
            onConfirm = {
                confirmDownload = null
                actions.onDownload()
            },
        )
    }
    if (confirmDelete) {
        WeDialog(
            message = stringResource(R.string.offline_asr_delete_confirm) +
                if (state.contactsUsing > 0) "\n\n" + stringResource(R.string.voice_delete_contacts_using, state.contactsUsing) else "",
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
        )
    }
}

private fun failureText(failure: ModelFailure): Int = when (failure) {
    ModelFailure.NETWORK -> R.string.offline_asr_failed_network
    ModelFailure.HTTP -> R.string.offline_asr_failed_http
    ModelFailure.CHECKSUM -> R.string.offline_asr_failed_checksum
    ModelFailure.NO_SPACE -> R.string.offline_asr_failed_space
    ModelFailure.REDIRECT -> R.string.offline_asr_failed_redirect
}
