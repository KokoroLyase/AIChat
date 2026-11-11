package isomeria.aichat.feature.voice

import android.content.ActivityNotFoundException
import android.content.Intent
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
