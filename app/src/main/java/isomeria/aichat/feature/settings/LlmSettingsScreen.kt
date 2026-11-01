package isomeria.aichat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeButton
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LlmSettingsState(
    val loaded: Boolean = false,
    val baseUrl: String = "",
    val keyInput: String = "",
    val savedKeyMasked: String? = null,
    val defaultModel: String = "",
    val testing: Boolean = false,
    /** 结果提示的字符串资源和参数 */
    val messageRes: Int? = null,
    val messageArg: Int = 0,
    val messageIsError: Boolean = false,
)

@HiltViewModel
class LlmSettingsViewModel @Inject constructor(private val providers: ProviderRepository) : ViewModel() {
    private val _state = MutableStateFlow(LlmSettingsState())
    val state: StateFlow<LlmSettingsState> = _state.asStateFlow()

    val models: StateFlow<List<ProviderModel>> = providers.observeModels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        val config = providers.llm()
        _state.update {
            it.copy(
                loaded = true,
                baseUrl = config.baseUrl,
                savedKeyMasked = providers.maskedKey(config),
                defaultModel = config.defaultModel,
            )
        }
    }

    fun setBaseUrl(value: String) = _state.update { it.copy(baseUrl = value.trim(), messageRes = null) }

    fun setKey(value: String) = _state.update { it.copy(keyInput = value.trim(), messageRes = null) }

    private fun validUrl(url: String) = url.startsWith("https://") || url.startsWith("http://")

    fun save() {
        val s = _state.value
        if (!validUrl(s.baseUrl)) {
            _state.update { it.copy(messageRes = R.string.llm_base_url_invalid, messageIsError = true) }
            return
        }
        viewModelScope.launch {
            providers.save(s.baseUrl, s.keyInput.ifEmpty { null }, s.defaultModel)
            _state.update { it.copy(keyInput = "", messageRes = R.string.llm_saved, messageIsError = false) }
            reload()
        }
    }

    /** 测试连接：成功时保存配置并更新模型列表 */
    fun test() {
        val s = _state.value
        if (!validUrl(s.baseUrl)) {
            _state.update { it.copy(messageRes = R.string.llm_base_url_invalid, messageIsError = true) }
            return
        }
        _state.update { it.copy(testing = true, messageRes = null) }
        viewModelScope.launch {
            try {
                val models = providers.testConnection(s.baseUrl, s.keyInput.ifEmpty { null })
                providers.save(s.baseUrl, s.keyInput.ifEmpty { null }, s.defaultModel)
                _state.update {
                    it.copy(
                        testing = false,
                        keyInput = "",
                        messageRes = R.string.llm_test_ok,
                        messageArg = models.size,
                        messageIsError = false,
                    )
                }
                reload()
            } catch (e: LlmException) {
                val res = when (e.error) {
                    LlmError.AUTH -> R.string.error_auth
                    LlmError.BALANCE -> R.string.error_balance
                    LlmError.RATE_LIMIT -> R.string.error_rate_limit
                    LlmError.BAD_REQUEST -> R.string.error_bad_request
                    LlmError.SERVER, LlmError.NETWORK -> R.string.error_network
                    LlmError.UNKNOWN -> R.string.error_unknown
                }
                _state.update { it.copy(testing = false, messageRes = res, messageIsError = true) }
            }
        }
    }

    fun setDefaultModel(modelId: String) {
        _state.update { it.copy(defaultModel = modelId) }
        viewModelScope.launch { providers.setDefaultModel(modelId) }
    }

    fun clearKey() {
        viewModelScope.launch {
            providers.save(_state.value.baseUrl, "", _state.value.defaultModel)
            reload()
        }
    }

    fun setImageMark(model: ProviderModel, supports: Boolean) {
        viewModelScope.launch { providers.setImageMark(model.providerId, model.modelId, supports) }
    }
}

/** 设置 → 模型服务 → 大语言模型：服务商、API Key、Base URL、默认模型、测试连接。 */
@Composable
fun LlmSettingsScreen(onBack: () -> Unit, viewModel: LlmSettingsViewModel = hiltViewModel()) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val state by viewModel.state.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }

    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(title = stringResource(R.string.settings_llm), onBack = onBack) {
            WeSmallButton(stringResource(R.string.action_save), viewModel::save, enabled = state.loaded)
        }
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SectionHeader(stringResource(R.string.llm_provider))
            WeListRow(
                title = stringResource(R.string.llm_provider_deepseek),
                showArrow = false,
                trailing = {
                    Text("✓", style = WeTheme.typography.listTitle, color = colors.brand)
                },
            )

            SectionHeader(stringResource(R.string.llm_api_key))
            FormRow(stringResource(R.string.llm_api_key), showDivider = state.savedKeyMasked != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (state.keyInput.isEmpty()) {
                            Text(
                                state.savedKeyMasked?.let { stringResource(R.string.llm_api_key_saved, it) }
                                    ?: stringResource(R.string.llm_api_key_hint),
                                style = WeTheme.typography.listTitle,
                                color = colors.textSecondary,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = state.keyInput,
                            onValueChange = viewModel::setKey,
                            singleLine = true,
                            textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
                            cursorBrush = SolidColor(colors.brand),
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Icon(
                        if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        null,
                        tint = colors.inputIcon,
                        modifier = Modifier.size(22.dp).clickable { showKey = !showKey },
                    )
                }
            }
            if (state.savedKeyMasked != null) {
                WeListRow(
                    title = stringResource(R.string.llm_api_key_clear),
                    titleColor = colors.danger,
                    onClick = viewModel::clearKey,
                    showArrow = false,
                )
            }
            FootnoteText(stringResource(R.string.llm_get_key))

            SectionHeader(stringResource(R.string.llm_base_url))
            FormRow(stringResource(R.string.llm_base_url)) {
                BasicTextField(
                    value = state.baseUrl,
                    onValueChange = viewModel::setBaseUrl,
                    singleLine = true,
                    textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.brand),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                )
            }

            SectionHeader(stringResource(R.string.llm_default_model))
            WeListRow(
                title = stringResource(R.string.llm_default_model),
                value = models.firstOrNull { it.modelId == state.defaultModel }?.displayName ?: state.defaultModel,
                onClick = { showModelSheet = true },
            )
            FootnoteText(stringResource(R.string.llm_default_model_hint))

            SectionHeader(stringResource(R.string.llm_models))
            models.forEachIndexed { index, model ->
                ModelRow(model, showDivider = index != models.lastIndex, onMark = { viewModel.setImageMark(model, it) })
            }
            FootnoteText(stringResource(R.string.llm_models_hint))

            Spacer(Modifier.height(16.dp))
            WeButton(
                text = stringResource(if (state.testing) R.string.llm_testing else R.string.llm_test),
                onClick = viewModel::test,
                enabled = !state.testing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            )
            state.messageRes?.let { res ->
                Text(
                    if (res == R.string.llm_test_ok) stringResource(res, state.messageArg) else stringResource(res),
                    style = WeTheme.typography.small,
                    color = if (state.messageIsError) colors.danger else colors.brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(dimens.horizontalPadding),
                )
            }
            FootnoteText(stringResource(R.string.llm_security_notice))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showModelSheet) {
        WeActionSheet(
            title = stringResource(R.string.llm_default_model),
            options = models.map { model ->
                SheetOption(model.displayName, selected = model.modelId == state.defaultModel, subtitle = model.modelId) {
                    viewModel.setDefaultModel(model.modelId)
                }
            },
            onDismiss = { showModelSheet = false },
        )
    }
}

@Composable
private fun ModelRow(model: ProviderModel, showDivider: Boolean, onMark: (Boolean) -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(Modifier.fillMaxWidth().background(colors.listBackground)) {
        Row(
            Modifier.padding(horizontal = dimens.horizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(model.displayName, style = WeTheme.typography.listTitle, color = colors.textPrimary)
                Text(
                    when (model.imageInput) {
                        Capability.YES -> "${model.modelId} · ${stringResource(R.string.llm_model_image_yes)}"
                        Capability.NO -> "${model.modelId} · ${stringResource(R.string.llm_model_image_no)}"
                        Capability.UNKNOWN -> "${model.modelId} · ${stringResource(R.string.llm_model_image_unknown)}"
                    },
                    style = WeTheme.typography.listTime,
                    color = colors.textTertiary,
                )
            }
            if (model.imageInput == Capability.UNKNOWN) {
                WeSwitch(model.imageInputUserMark == true, onMark)
            }
        }
        if (showDivider) {
            isomeria.aichat.ui.components.WeDivider(startIndent = dimens.horizontalPadding)
        }
    }
}
