package isomeria.aichat.feature.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.BuildConfig
import isomeria.aichat.R
import isomeria.aichat.data.parse.ParseError
import isomeria.aichat.data.parse.ParseException
import isomeria.aichat.data.parse.ParserRepository
import isomeria.aichat.data.repo.BaseUrlRules
import isomeria.aichat.feature.voice.VoiceTextInput
import isomeria.aichat.ui.components.ErrorNote
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.PasswordField
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.WeActionRow
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** “测试连接”的结果（0.9.2） */
enum class ParserTestResult { RUNNING, OK, AUTH, FAILED }

data class ParserSettingsState(
    val loaded: Boolean = false,
    val baseUrl: String = ParserRepository.MINERU_BASE,
    val keyInput: String = "",
    /** 已保存的 Token（打码）；没有时为 null */
    val savedKeyMasked: String? = null,
    val enabled: Boolean = true,
    /** 点了“清除 Token”，保存时清掉 */
    val clearKey: Boolean = false,
    val errorRes: Int? = null,
    val test: ParserTestResult? = null,
    val saved: Boolean = false,
    val dirty: Boolean = false,
)

/**
 * 「设置 → 模型服务 → 文件解析」（0.9.2）：只有 MinerU 一个，填 Token、开关、测试连接。
 * 地址默认是 MinerU 的，可以改（debug 包允许本机的 http，接模拟接口）
 */
@HiltViewModel
class ParserSettingsViewModel @Inject constructor(private val parsers: ParserRepository) : ViewModel() {
    private val _state = MutableStateFlow(ParserSettingsState())
    val state: StateFlow<ParserSettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val config = parsers.config()
            _state.value = ParserSettingsState(
                loaded = true,
                baseUrl = config?.baseUrl ?: ParserRepository.MINERU_BASE,
                savedKeyMasked = config?.let(parsers::maskedKey),
                enabled = config?.enabled ?: true,
            )
        }
    }

    fun setBaseUrl(v: String) = _state.update { it.copy(baseUrl = v.trim(), errorRes = null, test = null, dirty = true) }
    fun setKey(v: String) = _state.update { it.copy(keyInput = v.trim(), clearKey = false, errorRes = null, test = null, dirty = true) }
    fun setEnabled(v: Boolean) = _state.update { it.copy(enabled = v, dirty = true) }
    fun clearKey() = _state.update { it.copy(keyInput = "", savedKeyMasked = null, clearKey = true, test = null, dirty = true) }
    fun consumeSaved() = _state.update { it.copy(saved = false) }

    private fun url(): String? = BaseUrlRules.normalize(_state.value.baseUrl, allowLocalHttp = BuildConfig.DEBUG)

    fun save() {
        val s = _state.value
        val url = url() ?: return _state.update { it.copy(errorRes = R.string.llm_base_url_invalid) }
        viewModelScope.launch {
            parsers.save(apiKey = if (s.clearKey) "" else s.keyInput.ifEmpty { null }, enabled = s.enabled, baseUrl = url)
            val config = parsers.config()
            _state.update {
                it.copy(keyInput = "", clearKey = false, savedKeyMasked = config?.let(parsers::maskedKey), saved = true, dirty = false)
            }
        }
    }

    /** 用页面上的地址和 Token 测试（新填的 Token 还没保存也用它） */
    fun test() {
        val s = _state.value
        val url = url() ?: return _state.update { it.copy(errorRes = R.string.llm_base_url_invalid) }
        if (s.keyInput.isEmpty() && (s.clearKey || s.savedKeyMasked == null)) {
            _state.update { it.copy(test = ParserTestResult.AUTH) }
            return
        }
        _state.update { it.copy(test = ParserTestResult.RUNNING) }
        viewModelScope.launch {
            val result = try {
                parsers.test(s.keyInput.ifEmpty { null }, url)
                ParserTestResult.OK
            } catch (e: CancellationException) {
                throw e
            } catch (e: ParseException) {
                if (e.error == ParseError.AUTH) ParserTestResult.AUTH else ParserTestResult.FAILED
            } catch (e: Exception) {
                ParserTestResult.FAILED
            }
            _state.update { it.copy(test = result) }
        }
    }
}

@Composable
fun ParserSettingsScreen(onBack: () -> Unit, viewModel: ParserSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            Toast.makeText(context, R.string.llm_saved, Toast.LENGTH_SHORT).show()
            viewModel.consumeSaved()
        }
    }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    ImeBackHandler()
    ParserSettingsContent(
        state = state,
        onBack = { if (state.dirty) confirmDiscard = true else onBack() },
        actions = ParserSettingsActions(
            onSave = viewModel::save, onBaseUrl = viewModel::setBaseUrl, onKey = viewModel::setKey,
            onClearKey = viewModel::clearKey, onEnabled = viewModel::setEnabled, onTest = viewModel::test,
        ),
    )
    if (confirmDiscard) {
        WeDialog(
            message = stringResource(R.string.contact_discard_title),
            confirmText = stringResource(R.string.contact_discard),
            destructive = true,
            onDismiss = { confirmDiscard = false },
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
        )
    }
}

data class ParserSettingsActions(
    val onSave: () -> Unit = {},
    val onBaseUrl: (String) -> Unit = {},
    val onKey: (String) -> Unit = {},
    val onClearKey: () -> Unit = {},
    val onEnabled: (Boolean) -> Unit = {},
    val onTest: () -> Unit = {},
)

@Composable
fun ParserSettingsContent(state: ParserSettingsState, onBack: () -> Unit, actions: ParserSettingsActions) {
    val colors = WeTheme.colors
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(title = stringResource(R.string.parser_title), onBack = onBack) {
            WeSmallButton(stringResource(R.string.action_save), actions.onSave, enabled = state.loaded)
        }
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FootnoteText(stringResource(R.string.parser_intro))
            SectionHeader(stringResource(R.string.parser_mineru))
            FormRow(stringResource(R.string.llm_base_url), showDivider = true) {
                VoiceTextInput(state.baseUrl, actions.onBaseUrl, ParserRepository.MINERU_BASE, KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            }
            FormRow(stringResource(R.string.parser_token)) {
                PasswordField(state.keyInput, actions.onKey, stringResource(R.string.parser_token_hint))
            }
            state.errorRes?.let { ErrorNote(stringResource(it)) }
            if (state.savedKeyMasked != null) {
                if (state.keyInput.isEmpty()) FootnoteText(stringResource(R.string.llm_api_key_saved, state.savedKeyMasked))
                WeActionRow(stringResource(R.string.parser_token_clear), onClick = actions.onClearKey, danger = true)
            }
            val uri = LocalUriHandler.current
            WeListRow(stringResource(R.string.provider_docs), onClick = { uri.openUri(ParserRepository.MINERU_DOCS) })

            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.parser_test),
                value = when (state.test) {
                    ParserTestResult.RUNNING -> stringResource(R.string.parser_test_running)
                    ParserTestResult.OK -> stringResource(R.string.parser_test_ok)
                    ParserTestResult.AUTH -> stringResource(R.string.parser_test_auth)
                    ParserTestResult.FAILED -> stringResource(R.string.parser_test_failed)
                    null -> null
                },
                onClick = actions.onTest.takeIf { state.test != ParserTestResult.RUNNING },
                showArrow = false,
            )

            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.parser_enabled),
                showArrow = false,
                trailing = { WeSwitch(state.enabled, actions.onEnabled, label = stringResource(R.string.parser_enabled)) },
            )
            FootnoteText(stringResource(R.string.parser_privacy))
            FootnoteText(stringResource(R.string.parser_security_notice))
            Spacer(Modifier.height(24.dp))
        }
    }
}
