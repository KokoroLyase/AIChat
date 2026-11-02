package isomeria.aichat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.BuildConfig
import isomeria.aichat.R
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.BaseUrlRules
import isomeria.aichat.data.repo.DeleteProviderResult
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.feature.chat.failReasonText
import isomeria.aichat.navigation.NEW_PROVIDER
import isomeria.aichat.navigation.ProviderEditRoute
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeButton
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProviderEditState(
    val loaded: Boolean = false,
    /** null 表示还没保存的新服务商 */
    val providerId: Long? = null,
    val protocol: String = ProviderProtocol.OPENAI,
    val name: String = "",
    val baseUrl: String = "",
    val keyInput: String = "",
    val savedKeyMasked: String? = null,
    val defaultModel: String = "",
    val modelInput: String = "",
    val testing: Boolean = false,
    /** 结果提示的字符串资源和参数 */
    val messageRes: Int? = null,
    val messageArgs: List<Any> = emptyList(),
    val messageIsError: Boolean = false,
    val deleted: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProviderEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val providers: ProviderRepository,
) : ViewModel() {
    private val routeId = savedState.toRoute<ProviderEditRoute>().providerId

    private val _state = MutableStateFlow(ProviderEditState())
    val state: StateFlow<ProviderEditState> = _state.asStateFlow()

    val models: StateFlow<List<ProviderModel>> = _state.map { it.providerId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else providers.observeModels(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            if (routeId == NEW_PROVIDER) _state.update { it.copy(loaded = true) } else reload(routeId)
        }
    }

    private suspend fun reload(id: Long) {
        val config = providers.config(id) ?: return
        _state.update {
            it.copy(
                loaded = true,
                providerId = config.id,
                protocol = config.provider,
                name = config.name,
                baseUrl = config.baseUrl,
                keyInput = "",
                savedKeyMasked = providers.maskedKey(config),
                defaultModel = config.defaultModel,
            )
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value.take(NAME_MAX_LENGTH), messageRes = null) }

    fun setBaseUrl(value: String) = _state.update { it.copy(baseUrl = value.trim(), messageRes = null) }

    fun setKey(value: String) = _state.update { it.copy(keyInput = value.trim(), messageRes = null) }

    fun setModelInput(value: String) = _state.update { it.copy(modelInput = value.trim(), messageRes = null) }

    private fun error(res: Int, vararg args: Any) =
        _state.update { it.copy(messageRes = res, messageArgs = args.toList(), messageIsError = true, testing = false) }

    /** 检查名称和 Base URL，通过时返回规范化后的 Base URL */
    private fun validated(): String? {
        val s = _state.value
        if (s.name.isBlank()) {
            error(R.string.llm_name_required)
            return null
        }
        val url = BaseUrlRules.normalize(s.baseUrl, allowLocalHttp = BuildConfig.DEBUG)
        if (url == null) error(R.string.llm_base_url_invalid)
        return url
    }

    /** 写入数据库，新服务商这时才真正创建；返回 id */
    private suspend fun persist(url: String): Long {
        val s = _state.value
        val id = s.providerId
        if (id == null) return providers.create(s.name, url, s.keyInput.ifEmpty { null })
        providers.save(id, s.name, url, s.keyInput.ifEmpty { null }, s.defaultModel)
        return id
    }

    fun save() {
        val url = validated() ?: return
        viewModelScope.launch {
            val id = persist(url)
            reload(id)
            _state.update { it.copy(messageRes = R.string.llm_saved, messageArgs = emptyList(), messageIsError = false) }
        }
    }

    /** 测试连接：成功时保存配置并更新模型列表 */
    fun test() {
        val url = validated() ?: return
        val s = _state.value
        _state.update { it.copy(testing = true, messageRes = null) }
        viewModelScope.launch {
            try {
                val config = s.providerId?.let { providers.config(it) }
                val remote = providers.fetchModels(config, url, s.keyInput.ifEmpty { null })
                val id = persist(url)
                providers.applyRemoteModels(id, remote)
                reload(id)
                _state.update {
                    it.copy(
                        testing = false,
                        messageRes = R.string.llm_test_ok,
                        messageArgs = listOf(remote.size),
                        messageIsError = false,
                    )
                }
            } catch (e: LlmException) {
                error(failReasonText(FailReason.of(e)))
            }
        }
    }

    fun setDefaultModel(modelId: String) {
        _state.update { it.copy(defaultModel = modelId) }
        _state.value.providerId?.let { id -> viewModelScope.launch { providers.setDefaultModel(id, modelId) } }
    }

    fun clearKey() {
        val id = _state.value.providerId ?: return
        viewModelScope.launch {
            val s = _state.value
            providers.save(id, s.name, s.baseUrl, "", s.defaultModel)
            reload(id)
        }
    }

    fun setImageMark(model: ProviderModel, supports: Boolean) {
        viewModelScope.launch { providers.setImageMark(model.providerId, model.modelId, supports) }
    }

    fun setThinkingMark(model: ProviderModel, supports: Boolean) {
        viewModelScope.launch { providers.setThinkingMark(model.providerId, model.modelId, supports) }
    }

    /** 手动添加模型：接口没有模型列表时用。新服务商先保存 */
    fun addModel() {
        val modelId = _state.value.modelInput
        if (modelId.isEmpty()) return
        val url = validated() ?: return
        viewModelScope.launch {
            val id = persist(url)
            providers.addManualModel(id, modelId)
            reload(id)
            _state.update { it.copy(modelInput = "") }
        }
    }

    fun removeModel(model: ProviderModel) {
        viewModelScope.launch {
            providers.removeModel(model.providerId, model.modelId)
            reload(model.providerId)
        }
    }

    fun delete() {
        val id = _state.value.providerId ?: return
        viewModelScope.launch {
            when (val result = providers.delete(id)) {
                DeleteProviderResult.Deleted -> _state.update { it.copy(deleted = true) }
                is DeleteProviderResult.InUse -> error(R.string.llm_delete_in_use, result.contacts)
                DeleteProviderResult.LastOne -> error(R.string.llm_delete_last)
            }
        }
    }

    private companion object {
        const val NAME_MAX_LENGTH = 30
    }
}

/** 一个服务商：名称、API Key、Base URL、默认模型、模型列表（可标记是否支持图片和思考）、测试连接。 */
@Composable
fun ProviderEditScreen(onBack: () -> Unit, viewModel: ProviderEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    ImeBackHandler()
    ProviderEditContent(
        state = state,
        models = models,
        onBack = onBack,
        onSave = viewModel::save,
        onNameChange = viewModel::setName,
        onKeyChange = viewModel::setKey,
        onClearKey = viewModel::clearKey,
        onBaseUrlChange = viewModel::setBaseUrl,
        onDefaultModel = viewModel::setDefaultModel,
        onImageMark = viewModel::setImageMark,
        onThinkingMark = viewModel::setThinkingMark,
        onRemoveModel = viewModel::removeModel,
        onModelInput = viewModel::setModelInput,
        onAddModel = viewModel::addModel,
        onTest = viewModel::test,
        onDelete = viewModel::delete,
    )
}

@Composable
fun ProviderEditContent(
    state: ProviderEditState,
    models: List<ProviderModel>,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onNameChange: (String) -> Unit,
    onKeyChange: (String) -> Unit,
    onClearKey: () -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onDefaultModel: (String) -> Unit,
    onImageMark: (ProviderModel, Boolean) -> Unit,
    onThinkingMark: (ProviderModel, Boolean) -> Unit,
    onRemoveModel: (ProviderModel) -> Unit,
    onModelInput: (String) -> Unit,
    onAddModel: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    var showKey by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(
            title = stringResource(if (state.providerId == null) R.string.llm_add_provider else R.string.llm_edit_provider),
            onBack = onBack,
        ) {
            WeSmallButton(stringResource(R.string.action_save), onSave, enabled = state.loaded)
        }
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SectionHeader(stringResource(R.string.llm_provider))
            FormRow(stringResource(R.string.llm_name)) {
                TextInput(state.name, onNameChange, stringResource(R.string.llm_name_hint))
            }

            SectionHeader(stringResource(R.string.llm_base_url))
            FormRow(stringResource(R.string.llm_base_url)) {
                TextInput(
                    state.baseUrl,
                    onBaseUrlChange,
                    stringResource(R.string.llm_base_url_hint),
                    KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            }
            FootnoteText(stringResource(R.string.llm_base_url_note))

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
                                // 不换行：打码的 Key 是一整串，放不下时会整体换到第二行，被裁掉后看不到
                                softWrap = false,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = state.keyInput,
                            onValueChange = onKeyChange,
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
                    onClick = onClearKey,
                    showArrow = false,
                )
            }
            if (state.protocol == ProviderProtocol.DEEPSEEK) FootnoteText(stringResource(R.string.llm_get_key))

            Spacer(Modifier.height(16.dp))
            WeButton(
                text = stringResource(if (state.testing) R.string.llm_testing else R.string.llm_test),
                onClick = onTest,
                enabled = !state.testing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            )
            state.messageRes?.let { res ->
                Text(
                    stringResource(res, *state.messageArgs.toTypedArray()),
                    style = WeTheme.typography.small,
                    color = if (state.messageIsError) colors.danger else colors.brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(dimens.horizontalPadding),
                )
            }

            SectionHeader(stringResource(R.string.llm_default_model))
            WeListRow(
                title = stringResource(R.string.llm_default_model),
                value = models.firstOrNull { it.modelId == state.defaultModel }?.displayName
                    ?: state.defaultModel.ifEmpty { stringResource(R.string.llm_no_model) },
                onClick = { showModelSheet = true },
                enabled = models.isNotEmpty(),
            )
            FootnoteText(stringResource(R.string.llm_default_model_hint))

            SectionHeader(stringResource(R.string.llm_models))
            models.forEachIndexed { index, model ->
                ModelRow(
                    model = model,
                    showDivider = index != models.lastIndex,
                    onImageMark = { onImageMark(model, it) },
                    onThinkingMark = { onThinkingMark(model, it) },
                    onRemove = { onRemoveModel(model) },
                )
            }
            if (models.isNotEmpty()) WeDivider(startIndent = dimens.horizontalPadding)
            FormRow(stringResource(R.string.llm_add_model)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TextInput(
                            state.modelInput,
                            onModelInput,
                            stringResource(R.string.llm_add_model_hint),
                            KeyboardOptions(autoCorrectEnabled = false),
                        )
                    }
                    WeSmallButton(stringResource(R.string.action_add), onAddModel, enabled = state.modelInput.isNotEmpty())
                }
            }
            FootnoteText(stringResource(R.string.llm_models_hint))

            if (state.providerId != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.listBackground)
                        .clickable { confirmDelete = true }
                        .heightIn(min = dimens.listRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.llm_delete_provider), style = WeTheme.typography.listTitle, color = colors.danger)
                }
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
                    onDefaultModel(model.modelId)
                }
            },
            onDismiss = { showModelSheet = false },
        )
    }
    if (confirmDelete) {
        WeDialog(
            message = stringResource(R.string.llm_delete_confirm, state.name),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }
}

@Composable
private fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = WeTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (value.isEmpty()) {
            Text(
                hint,
                style = WeTheme.typography.listTitle,
                color = colors.textSecondary,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.brand),
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 一个模型：名称、模型 ID，下面两个可点的标记「图片」「思考」，手动添加的可以移除。 */
@Composable
private fun ModelRow(
    model: ProviderModel,
    showDivider: Boolean,
    onImageMark: (Boolean) -> Unit,
    onThinkingMark: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(Modifier.fillMaxWidth().background(colors.listBackground)) {
        Column(
            Modifier.padding(horizontal = dimens.horizontalPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(model.displayName, style = WeTheme.typography.listTitle, color = colors.textPrimary)
            if (model.displayName != model.modelId) {
                Text(model.modelId, style = WeTheme.typography.listTime, color = colors.textTertiary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                MarkChip(stringResource(R.string.llm_mark_image), model.supportsImage) { onImageMark(!model.supportsImage) }
                MarkChip(stringResource(R.string.llm_mark_thinking), model.supportsThinking) { onThinkingMark(!model.supportsThinking) }
                if (model.manual) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.llm_remove_model),
                        style = WeTheme.typography.small,
                        color = colors.danger,
                        modifier = Modifier.clickable(onClick = onRemove).padding(4.dp),
                    )
                }
            }
        }
        if (showDivider) WeDivider(startIndent = dimens.horizontalPadding)
    }
}

/** 可点的标记：支持时绿字，不支持时灰字 */
@Composable
private fun MarkChip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = WeTheme.colors
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (on) colors.brand.copy(alpha = 0.14f) else colors.pageBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (on) R.string.llm_mark_on else R.string.llm_mark_off, label),
            style = WeTheme.typography.listTime,
            color = if (on) colors.brand else colors.textTertiary,
        )
    }
}

/** 截图用的示例数据 */
internal val previewProviderState = ProviderEditState(
    loaded = true,
    providerId = 2,
    name = "OpenRouter",
    baseUrl = "https://openrouter.ai/api/v1",
    savedKeyMasked = "sk-o••••••3f9c",
    defaultModel = "gpt-example",
)

internal val previewProviderModels = listOf(
    ProviderModel(2, "gpt-example", "Example GPT", isomeria.aichat.data.db.Capability.YES, thinkingInput = isomeria.aichat.data.db.Capability.YES),
    ProviderModel(2, "local-model", "local-model", manual = true),
)

@Composable
internal fun ProviderEditSample() = ProviderEditContent(
    previewProviderState, previewProviderModels, {}, {}, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {}, {}, {},
)
