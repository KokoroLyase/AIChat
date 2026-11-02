package isomeria.aichat.feature.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import isomeria.aichat.data.db.*
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.llm.*
import isomeria.aichat.data.repo.ModelReference
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

/** 已保存的连接信息，用来判断有没有未保存的修改 */
data class ProviderSavedForm(val name: String = "", val baseUrl: String = "", val compatibility: String = "GENERIC", val presetId: String? = null)

data class ProviderEditState(
    val loaded: Boolean = false,
    /** null 表示还没保存的新服务商 */
    val providerId: Long? = null,
    val protocol: String = ProviderProtocol.OPENAI,
    val presetId: String? = null,
    val compatibility: String = "GENERIC",
    val conflicts: List<ModelReference> = emptyList(),
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
    val savedForm: ProviderSavedForm = ProviderSavedForm(),
    /** 名称或地址校验失败的次数；每次失败都把页面带回出错的字段 */
    val validationAttempt: Int = 0,
) {
    val dirty: Boolean get() = loaded && (ProviderSavedForm(name, baseUrl, compatibility, presetId) != savedForm || keyInput.isNotEmpty() || modelInput.isNotEmpty())
}

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
                presetId = config.presetId,
                compatibility = config.compatibility,
                name = config.name,
                baseUrl = config.baseUrl,
                keyInput = "",
                savedKeyMasked = providers.maskedKey(config),
                defaultModel = config.defaultModel,
                savedForm = ProviderSavedForm(config.name, config.baseUrl, config.compatibility, config.presetId),
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
            _state.update { it.copy(validationAttempt = it.validationAttempt + 1) }
            return null
        }
        val url = BaseUrlRules.normalize(s.baseUrl, allowLocalHttp = BuildConfig.DEBUG)
        if (url == null) {
            error(R.string.llm_base_url_invalid)
            _state.update { it.copy(validationAttempt = it.validationAttempt + 1) }
        }
        return url
    }

    /** 写入数据库，新服务商这时才真正创建；返回 id */
    private suspend fun persist(url: String, s: ProviderEditState = _state.value): Long {
        val id = s.providerId
        if (id == null) return providers.create(s.name, url, s.keyInput.ifEmpty { null }, s.presetId, s.compatibility)
        providers.save(id, s.name, url, s.keyInput.ifEmpty { null }, s.defaultModel, s.compatibility)
        return id
    }

    private var pending: (suspend () -> Unit)? = null
    private suspend fun review(config: isomeria.aichat.data.db.ProviderConfig?, action: suspend () -> Unit) {
        val conflicts = config?.let { providers.conflicts(it) }.orEmpty()
        if (conflicts.isEmpty()) action() else {
            pending = action
            _state.update { it.copy(conflicts = conflicts, testing = false) }
        }
    }
    fun confirm() { val action = pending ?: return; pending = null; _state.update { it.copy(conflicts = emptyList()) }; viewModelScope.launch { action() } }
    fun dismiss() { pending = null; _state.update { it.copy(conflicts = emptyList()) } }
    fun selectPreset(preset: ProviderPreset?, name: String) = _state.update {
        if (it.providerId != null) it else it.copy(presetId = preset?.id, name = name, baseUrl = preset?.baseUrl.orEmpty(), compatibility = (preset?.compatibility ?: Compatibility.GENERIC).name, messageRes = null)
    }
    fun setCompatibility(value: Compatibility) = _state.update { it.copy(compatibility = value.name) }
    fun save() {
        val url = validated() ?: return
        val s = _state.value
        viewModelScope.launch {
            val config = s.providerId?.let { providers.config(it)?.copy(defaultModel = s.defaultModel, compatibility = s.compatibility) }
            review(config) {
                val id = persist(url, s)
                reload(id)
                _state.update { it.copy(messageRes = R.string.llm_saved, messageArgs = emptyList(), messageIsError = false) }
            }
        }
    }

    /** 获取模型列表：成功时保存配置并更新模型列表 */
    fun test() {
        val url = validated() ?: return
        val s = _state.value
        _state.update { it.copy(testing = true, messageRes = null) }
        viewModelScope.launch {
            try {
                val config = s.providerId?.let { providers.config(it) }
                val remote = providers.fetchModels(config, url, s.keyInput.ifEmpty { null })
                val action: suspend () -> Unit = {
                    val id = persist(url, s)
                    providers.applyRemoteModels(id, remote)
                    reload(id)
                    _state.update { it.copy(testing = false,
                        messageRes = if (remote.isEmpty()) R.string.connection_fetch_empty else R.string.connection_fetch_ok,
                        messageArgs = listOf(remote.size), messageIsError = false) }
                }
                val future = config?.copy(defaultModel = s.defaultModel, compatibility = s.compatibility)
                val refs = future?.let { providers.remoteConflicts(it, remote) + providers.conflicts(it) }.orEmpty().distinct()
                if (refs.isEmpty()) action() else {
                    pending = action
                    _state.update { it.copy(conflicts = refs, testing = false) }
                }
            } catch (e: LlmException) {
                error(R.string.connection_fetch_failed)
            }
        }
    }

    fun setDefaultModel(modelId: String) {
        val id = _state.value.providerId ?: return
        viewModelScope.launch {
            val config = providers.config(id) ?: return@launch
            review(config.copy(defaultModel = modelId)) {
                providers.setDefaultModel(id, modelId)
                // 只更新这一项：页面上可能还有没保存的名称、地址或 Key
                _state.update { it.copy(defaultModel = modelId) }
            }
        }
    }

    fun clearKey() {
        val id = _state.value.providerId ?: return
        viewModelScope.launch {
            val saved = providers.config(id) ?: return@launch
            providers.save(id, saved.name, saved.baseUrl, "", saved.defaultModel)
            // 只更新这一项：页面上可能还有没保存的名称、地址或新 Key
            _state.update { it.copy(savedKeyMasked = null) }
        }
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

/** 一个服务商：名称、Base URL、API Key、获取模型列表、默认模型、模型列表（点开逐个配置）。 */
@Composable
fun ProviderEditScreen(onBack: () -> Unit, onOpenModel: (Long, String) -> Unit = { _, _ -> }, viewModel: ProviderEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    ImeBackHandler()
    ProviderEditContent(
        state = state,
        models = models,
        onBack = { if (state.dirty) confirmDiscard = true else onBack() },
        onSave = viewModel::save,
        onNameChange = viewModel::setName,
        onKeyChange = viewModel::setKey,
        onClearKey = viewModel::clearKey,
        onBaseUrlChange = viewModel::setBaseUrl,
        onDefaultModel = viewModel::setDefaultModel,
        onModelInput = viewModel::setModelInput,
        onAddModel = viewModel::addModel,
        onTest = viewModel::test,
        onDelete = viewModel::delete,
        onPreset = viewModel::selectPreset,
        onCompatibility = viewModel::setCompatibility,
        onConfigureModel = { onOpenModel(it.providerId, it.modelId) },
    )
    if (state.conflicts.isNotEmpty()) ConflictDialog(state.conflicts, viewModel::dismiss, viewModel::confirm)
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
    onModelInput: (String) -> Unit,
    onAddModel: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onPreset: (ProviderPreset?, String) -> Unit = { _, _ -> },
    onCompatibility: (Compatibility) -> Unit = {},
    onConfigureModel: (ProviderModel) -> Unit = {},
) {
    val editorConfig = ProviderConfig(id = state.providerId ?: 0, kind = ProviderKind.LLM, provider = state.protocol,
        name = state.name, baseUrl = state.baseUrl, defaultModel = state.defaultModel, compatibility = state.compatibility)
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    var showKey by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val nameError = state.messageIsError && state.messageRes == R.string.llm_name_required
    val urlError = state.messageIsError && state.messageRes == R.string.llm_base_url_invalid
    // 预置地址平时只读展示，点“修改”或校验失败后才能编辑；切换预置时恢复只读
    var editUrl by remember(state.presetId) { mutableStateOf(false) }
    LaunchedEffect(urlError) { if (urlError) editUrl = true }
    val urlEditable = editUrl || urlError || state.presetId == null || state.baseUrl.isBlank()
    val formLocation = remember { BringIntoViewRequester() }
    LaunchedEffect(state.validationAttempt) { if (state.validationAttempt > 0) formLocation.bringIntoView() }

    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(
            title = stringResource(if (state.providerId == null) R.string.llm_add_provider else R.string.llm_edit_provider),
            onBack = onBack,
        ) {
            WeSmallButton(stringResource(R.string.action_save), onSave, enabled = state.loaded)
        }
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (state.providerId == null) {
                val choices = ProviderPresets.all.map { stringResource(it.nameRes) to it } + listOf(stringResource(R.string.preset_custom) to null)
                ChoiceRow(stringResource(R.string.provider_preset), stringResource(ProviderPresets.all.firstOrNull { it.id == state.presetId }?.nameRes ?: R.string.preset_custom), options = choices) {
                    onPreset(it, choices.first { pair -> pair.second == it }.first)
                }
            }
            FootnoteText(stringResource(R.string.provider_protocol))
            SectionHeader(stringResource(R.string.llm_provider))
            Column(Modifier.bringIntoViewRequester(formLocation)) {
                FormRow(stringResource(R.string.llm_name), showDivider = true) {
                    TextInput(state.name, onNameChange, stringResource(R.string.llm_name_hint))
                }
                if (nameError) FieldError(stringResource(R.string.llm_name_required))
                FormRow(stringResource(R.string.llm_base_url)) {
                    if (urlEditable) {
                        TextInput(
                            state.baseUrl,
                            onBaseUrlChange,
                            stringResource(R.string.llm_base_url_hint),
                            KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.baseUrl, style = WeTheme.typography.listTitle, color = colors.textTertiary, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                            WeSmallButton(stringResource(R.string.action_modify), { editUrl = true })
                        }
                    }
                }
                if (urlError) FieldError(stringResource(R.string.llm_base_url_invalid))
                if (urlEditable) FootnoteText(stringResource(R.string.llm_base_url_note))
            }

            var advanced by remember { mutableStateOf(false) }
            WeListRow(stringResource(R.string.provider_advanced), onClick = { advanced = !advanced })
            if (advanced) {
                ChoiceRow(stringResource(R.string.provider_compatibility), state.compatibility, options = Compatibility.entries.map { it.name to it }, onPick = onCompatibility)
            }

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
                text = stringResource(if (state.testing) R.string.connection_fetching else R.string.connection_fetch),
                onClick = onTest,
                enabled = !state.testing,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
            )
            state.messageRes?.takeUnless { nameError || urlError }?.let { res ->
                Text(
                    stringResource(res, *state.messageArgs.toTypedArray()),
                    style = WeTheme.typography.small,
                    color = if (state.messageIsError) colors.danger else colors.brand,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(dimens.horizontalPadding),
                )
            }

            SectionHeader(stringResource(R.string.llm_default_model))
            ConfigActionRow(
                title = stringResource(R.string.llm_default_model),
                value = models.firstOrNull { it.modelId == state.defaultModel }?.displayName
                    ?: state.defaultModel.ifEmpty { stringResource(R.string.llm_no_model) },
                onClick = { showModelSheet = true },
            )
            FootnoteText(stringResource(R.string.llm_default_model_hint))

            SectionHeader(stringResource(R.string.llm_models))
            models.forEachIndexed { index, model ->
                ModelRow(
                    model = model,
                    ready = ModelRules.usable(editorConfig, model),
                    showDivider = index != models.lastIndex,
                    onConfigure = { onConfigureModel(model) },
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
            FootnoteText(stringResource(R.string.model_list_note))

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
        val choices = models.filter { m -> ModelRules.usable(editorConfig, m) }
        WeActionSheet(
            title = stringResource(if (choices.isEmpty()) R.string.llm_default_model_empty else R.string.llm_default_model),
            options = listOf(SheetOption(stringResource(R.string.model_unset), selected = state.defaultModel.isBlank()) { onDefaultModel("") }) + choices.map { model ->
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

/** 一个模型：名称和配置状态、模型 ID。点开进入配置，移除也在配置页里。 */
@Composable
private fun ModelRow(model: ProviderModel, ready: Boolean, showDivider: Boolean, onConfigure: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(WeTheme.colors.listBackground)) {
        ConfigActionRow(model.displayName, stringResource(if (ready) R.string.model_ready else R.string.model_pending), onClick = onConfigure)
        FootnoteText(model.modelId)
        if (model.missingFromList) FootnoteText(stringResource(R.string.model_missing))
        if (showDivider) WeDivider(startIndent = WeTheme.dimens.horizontalPadding)
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
internal fun ProviderEditSample(state: ProviderEditState = previewProviderState) = ProviderEditContent(
    state = state, models = previewProviderModels, onBack = {}, onSave = {}, onNameChange = {}, onKeyChange = {},
    onClearKey = {}, onBaseUrlChange = {}, onDefaultModel = {}, onModelInput = {}, onAddModel = {}, onTest = {}, onDelete = {},
)
