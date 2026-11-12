package isomeria.aichat.feature.settings

import android.widget.Toast
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
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
import isomeria.aichat.data.repo.ConflictChange
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
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeButton
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeIcons
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

/** 已保存的 Key 会不会被发给另一个地址：地址变了，又没有填新 Key。 */
internal fun ProviderConfig.keyMovesTo(url: String, keyInput: String): Boolean =
    !apiKeyEncrypted.isNullOrEmpty() && keyInput.isEmpty() && !BaseUrlRules.isSameUrl(baseUrl, url)

/** 已保存的连接信息，用来判断有没有未保存的修改 */
data class ProviderSavedForm(val name: String = "", val baseUrl: String = "", val compatibility: String = "GENERIC", val presetId: String? = null)

data class ProviderEditState(
    val loaded: Boolean = false,
    /** null 表示还没保存的新服务商 */
    val providerId: Long? = null,
    val protocol: String = ProviderProtocol.OPENAI,
    val presetId: String? = null,
    val compatibility: String = "GENERIC",
    /** 这次保存才产生的冲突，要确认 */
    val conflicts: List<ModelReference> = emptyList(),
    /** 原本就有的冲突，只作提示 */
    val existingConflicts: List<ModelReference> = emptyList(),
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
    /** 保存成功的次数；每次提示一下，页面不返回，接着可以获取模型列表 */
    val savedCount: Int = 0,
    /** 刚才保存后还有联系人待修正 */
    val savedWithConflicts: Boolean = false,
    /** 不能删除的原因；操作在页面底部，提示要弹出来才看得到 */
    val deleteBlockedRes: Int? = null,
    val deleteBlockedArgs: List<Any> = emptyList(),
    /** 不为空时要确认：已保存的 Key 会发给这个新地址 */
    val keyMoveUrl: String? = null,
) {
    val dirty: Boolean get() = loaded && (ProviderSavedForm(name, baseUrl, compatibility, presetId) != savedForm || keyInput.isNotEmpty() || modelInput.isNotEmpty())
}

@Composable
internal fun KeyMoveDialog(url: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    WeDialog(
        title = stringResource(R.string.provider_key_move_title),
        message = stringResource(R.string.provider_key_move_message, url),
        confirmText = stringResource(R.string.provider_key_move_confirm),
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
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
        // 百炼的预置地址要先换成自己的业务空间地址（0.7.2）
        if (BaseUrlRules.hasPlaceholder(s.baseUrl)) {
            error(R.string.base_url_placeholder_required)
            _state.update { it.copy(validationAttempt = it.validationAttempt + 1) }
            return null
        }
        val url = BaseUrlRules.normalize(s.baseUrl, allowLocalHttp = BuildConfig.DEBUG)
        if (url == null) {
            error(R.string.llm_base_url_invalid)
            _state.update { it.copy(validationAttempt = it.validationAttempt + 1) }
            return null
        }
        // Key 里混进中文、全角符号或空格时发不出去：不保存，提示检查
        if (s.keyInput.isNotEmpty() && !isomeria.aichat.data.llm.LlmEndpoint.isUsableKey(s.keyInput)) {
            error(R.string.llm_api_key_invalid_chars)
            _state.update { it.copy(validationAttempt = it.validationAttempt + 1) }
            return null
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
    /** 只有这次新增的冲突才要确认；[action] 的参数表示保存后是否还有联系人待修正。 */
    private suspend fun review(saved: isomeria.aichat.data.db.ProviderConfig?, after: List<ModelReference>, action: suspend (Boolean) -> Unit) {
        val change = saved?.let { providers.conflictChange(it, after) } ?: ConflictChange()
        if (change.added.isEmpty()) action(change.existing.isNotEmpty()) else {
            pending = { action(true) }
            _state.update { it.copy(conflicts = change.added, existingConflicts = change.existing, testing = false) }
        }
    }
    fun confirm() { val action = pending ?: return; pending = null; _state.update { it.copy(conflicts = emptyList(), existingConflicts = emptyList()) }; viewModelScope.launch { action() } }
    fun dismiss() { pending = null; _state.update { it.copy(conflicts = emptyList(), existingConflicts = emptyList()) } }
    fun selectPreset(preset: ProviderPreset?, name: String) = _state.update {
        if (it.providerId != null) it else it.copy(presetId = preset?.id, name = name, baseUrl = preset?.baseUrl.orEmpty(), compatibility = (preset?.compatibility ?: Compatibility.GENERIC).name, messageRes = null)
    }
    fun setCompatibility(value: Compatibility) = _state.update { it.copy(compatibility = value.name) }
    private var pendingKeyMove: (() -> Unit)? = null

    /** 先确认再继续：保存、获取列表、手动添加模型都会让已保存的 Key 跟着新地址走。 */
    private fun afterKeyMoveCheck(url: String, action: () -> Unit) {
        val s = _state.value
        viewModelScope.launch {
            val saved = s.providerId?.let { providers.config(it) }
            if (saved?.keyMovesTo(url, s.keyInput) == true) {
                pendingKeyMove = action
                _state.update { it.copy(keyMoveUrl = url) }
            } else {
                action()
            }
        }
    }

    fun confirmKeyMove() {
        val action = pendingKeyMove ?: return
        pendingKeyMove = null
        _state.update { it.copy(keyMoveUrl = null) }
        action()
    }

    fun dismissKeyMove() {
        pendingKeyMove = null
        _state.update { it.copy(keyMoveUrl = null) }
    }

    fun save() {
        val url = validated() ?: return
        afterKeyMoveCheck(url) { saveTo(url) }
    }

    private fun saveTo(url: String) {
        val s = _state.value
        viewModelScope.launch {
            val saved = s.providerId?.let { providers.config(it) }
            val after = saved?.let { providers.conflicts(it.copy(defaultModel = s.defaultModel, compatibility = s.compatibility)) }.orEmpty()
            review(saved, after) { remaining ->
                val id = persist(url, s)
                reload(id)
                _state.update { it.copy(messageRes = null, savedWithConflicts = remaining, savedCount = it.savedCount + 1) }
            }
        }
    }

    /** 获取模型列表：成功时保存配置并更新模型列表 */
    fun test() {
        val url = validated() ?: return
        afterKeyMoveCheck(url) { fetchFrom(url) }
    }

    private fun fetchFrom(url: String) {
        val s = _state.value
        _state.update { it.copy(testing = true, messageRes = null) }
        viewModelScope.launch {
            try {
                val config = s.providerId?.let { providers.config(it) }
                val remote = providers.fetchModels(config, url, s.keyInput.ifEmpty { null })
                val future = config?.copy(defaultModel = s.defaultModel, compatibility = s.compatibility)
                review(config, future?.let { providers.remoteConflicts(it, remote) }.orEmpty()) { remaining ->
                    val id = persist(url, s)
                    providers.applyRemoteModels(id, remote)
                    reload(id)
                    _state.update { it.copy(testing = false,
                        messageRes = when {
                            remote.isEmpty() -> R.string.connection_fetch_empty
                            remaining -> R.string.connection_fetch_ok_conflict
                            else -> R.string.connection_fetch_ok
                        },
                        messageArgs = listOf(remote.size), messageIsError = false) }
                }
            } catch (e: LlmException) {
                error(fetchFailureText(e))
            }
        }
    }

    fun setDefaultModel(modelId: String) {
        val id = _state.value.providerId ?: return
        viewModelScope.launch {
            val config = providers.config(id) ?: return@launch
            review(config, providers.conflicts(config.copy(defaultModel = modelId))) {
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
        afterKeyMoveCheck(url) {
            viewModelScope.launch {
                val id = persist(url)
                providers.addManualModel(id, modelId)
                reload(id)
                _state.update { it.copy(modelInput = "") }
            }
        }
    }

    fun delete() {
        val id = _state.value.providerId ?: return
        viewModelScope.launch {
            when (val result = providers.delete(id)) {
                DeleteProviderResult.Deleted -> _state.update { it.copy(deleted = true) }
                is DeleteProviderResult.InUse -> _state.update { it.copy(deleteBlockedRes = R.string.llm_delete_in_use, deleteBlockedArgs = listOf(result.contacts)) }
                DeleteProviderResult.LastOne -> _state.update { it.copy(deleteBlockedRes = R.string.llm_delete_last, deleteBlockedArgs = emptyList()) }
            }
        }
    }

    fun dismissDeleteBlocked() = _state.update { it.copy(deleteBlockedRes = null) }

    private companion object {
        const val NAME_MAX_LENGTH = 30
    }
}

/** 获取列表失败的原因。只按分类提示，不显示服务端返回的内容。 */
internal fun fetchFailureText(e: LlmException): Int = when {
    e.isMissingKey -> R.string.fetch_failed_no_key
    else -> when (e.error) {
        LlmError.CONFIGURATION -> R.string.llm_base_url_invalid
        LlmError.AUTH -> R.string.fetch_failed_auth
        LlmError.BALANCE -> R.string.fetch_failed_balance
        LlmError.RATE_LIMIT -> R.string.fetch_failed_rate_limit
        LlmError.BAD_REQUEST -> R.string.fetch_failed_bad_request
        LlmError.NOT_FOUND -> R.string.fetch_failed_not_found
        LlmError.SERVER -> R.string.fetch_failed_server
        LlmError.NETWORK -> R.string.fetch_failed_network
        LlmError.UNKNOWN -> R.string.fetch_failed_unreadable
    }
}

/** 模型超过这个数量才显示搜索框；待配置的也默认折起来。 */
internal const val MODEL_LIST_LONG = 20

/** 模型列表里的一行和它缺的配置。 */
internal data class ModelListRow(val model: ProviderModel, val gaps: List<ModelGap>)

internal fun modelListRows(config: ProviderConfig, models: List<ProviderModel>): List<ModelListRow> = models.map { ModelListRow(it, ModelRules.gaps(config, it)) }

/** 按名称或 ID 在本机过滤，不区分大小写。 */
internal fun List<ModelListRow>.matching(query: String): List<ModelListRow> {
    val text = query.trim()
    return if (text.isEmpty()) this else filter { it.model.displayName.contains(text, ignoreCase = true) || it.model.modelId.contains(text, ignoreCase = true) }
}

/**
 * 可以选的兼容方案。LEGACY 只为 0.3.0 升级的数据保留：已保存的就是它才显示，其他服务商不能切过去。
 */
internal fun compatibilityChoices(saved: String): List<Compatibility> = Compatibility.entries.filter { it != Compatibility.LEGACY || saved == Compatibility.LEGACY.name }

internal fun compatibilityLabel(value: Compatibility): Int = when (value) {
    Compatibility.LEGACY -> R.string.compat_legacy
    Compatibility.GENERIC -> R.string.compat_generic
    Compatibility.DEEPSEEK -> R.string.compat_deepseek
    Compatibility.VOLCENGINE -> R.string.compat_volcengine
    Compatibility.QWEN -> R.string.compat_qwen
    Compatibility.ZHIPU -> R.string.compat_zhipu
    Compatibility.KIMI -> R.string.compat_kimi
    Compatibility.MIMO -> R.string.compat_mimo
    Compatibility.MINIMAX -> R.string.compat_minimax
    Compatibility.SILICONFLOW -> R.string.compat_siliconflow
    Compatibility.OPENROUTER -> R.string.compat_openrouter
}

/** 官方文档：优先看预置，其次看兼容方案对应的服务商。 */
internal fun docsPreset(presetId: String?, compatibility: String?): ProviderPreset? =
    ProviderPresets.all.firstOrNull { it.id == presetId } ?: ProviderPresets.all.firstOrNull { it.compatibility.name == compatibility }

/** 一个服务商：名称、Base URL、API Key、获取模型列表、默认模型、模型列表（点开逐个配置）。 */
@Composable
fun ProviderEditScreen(onBack: () -> Unit, onOpenModel: (Long, String) -> Unit = { _, _ -> }, viewModel: ProviderEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    var confirmDiscard by remember { mutableStateOf(false) }
    val context = LocalContext.current
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.savedCount) { if (state.savedCount > 0) Toast.makeText(context, if (state.savedWithConflicts) R.string.model_saved_conflict else R.string.llm_saved, Toast.LENGTH_SHORT).show() }
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
    state.keyMoveUrl?.let { KeyMoveDialog(it, viewModel::dismissKeyMove, viewModel::confirmKeyMove) }
    if (state.conflicts.isNotEmpty()) ConflictDialog(state.conflicts, viewModel::dismiss, state.existingConflicts, viewModel::confirm)
    state.deleteBlockedRes?.let { res ->
        WeDialog(
            message = stringResource(res, *state.deleteBlockedArgs.toTypedArray()),
            dismissText = null,
            onDismiss = viewModel::dismissDeleteBlocked,
            onConfirm = viewModel::dismissDeleteBlocked,
        )
    }
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
    val urlError = state.messageIsError && (state.messageRes == R.string.llm_base_url_invalid || state.messageRes == R.string.base_url_placeholder_required)
    // 预置地址平时只读展示，点“修改”或校验失败后才能编辑；切换预置时恢复只读
    var editUrl by remember(state.presetId) { mutableStateOf(false) }
    LaunchedEffect(urlError) { if (urlError) editUrl = true }
    val urlEditable = editUrl || urlError || state.presetId == null || state.baseUrl.isBlank() || BaseUrlRules.hasPlaceholder(state.baseUrl)
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
                if (urlError) FieldError(stringResource(state.messageRes ?: R.string.llm_base_url_invalid))
                if (urlEditable) FootnoteText(stringResource(R.string.llm_base_url_note))
            }

            val uri = LocalUriHandler.current
            docsPreset(state.presetId, state.compatibility)?.let { docs ->
                WeListRow(stringResource(R.string.provider_docs), onClick = { uri.openUri(docs.docs) }, showDivider = true)
            }
            var advanced by rememberSaveable { mutableStateOf(false) }
            WeListRow(stringResource(R.string.provider_advanced), onClick = { advanced = !advanced }, expanded = advanced)
            if (advanced) {
                WeDivider(startIndent = dimens.horizontalPadding)
                ChoiceRow(
                    stringResource(R.string.provider_compatibility),
                    Compatibility.entries.firstOrNull { it.name == state.compatibility }?.let { stringResource(compatibilityLabel(it)) } ?: state.compatibility,
                    options = compatibilityChoices(state.savedForm.compatibility).map { stringResource(compatibilityLabel(it)) to it },
                    onPick = onCompatibility,
                )
                if (state.savedForm.compatibility == Compatibility.LEGACY.name) FootnoteText(stringResource(R.string.compat_legacy_note))
                FootnoteText(stringResource(R.string.provider_protocol))
            }

            GroupSpacer()
            val keyLabel = stringResource(R.string.llm_api_key)
            // 输入框也说明已保存的摘要，替换 Key 时可直接知道原有状态。
            val keyDescription = state.savedKeyMasked?.takeIf { state.keyInput.isEmpty() }
                ?.let { keyLabel + stringResource(R.string.reference_separator) + stringResource(R.string.llm_api_key_saved, it) } ?: keyLabel
            FormRow(keyLabel, showDivider = state.savedKeyMasked != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        if (state.keyInput.isEmpty()) {
                            Text(
                                stringResource(R.string.llm_api_key_hint),
                                style = WeTheme.typography.listTitle,
                                color = colors.textSecondary,
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
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = keyDescription },
                        )
                    }
                    Box(
                        // 点击区 48dp；往右挪一点，让图标仍和其他行的右边对齐
                        Modifier.offset(x = 13.dp).size(48.dp).clickable(role = Role.Button) { showKey = !showKey },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            stringResource(if (showKey) R.string.key_hide else R.string.key_show),
                            tint = colors.inputIcon,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            if (state.savedKeyMasked != null) {
                if (state.keyInput.isEmpty()) FootnoteText(stringResource(R.string.llm_api_key_saved, state.savedKeyMasked))
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
                    color = if (state.messageIsError) colors.dangerText else colors.brandText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(dimens.horizontalPadding),
                )
            }

            Spacer(Modifier.height(16.dp))
            ConfigActionRow(
                title = stringResource(R.string.llm_default_model),
                value = models.firstOrNull { it.modelId == state.defaultModel }?.displayName
                    ?: state.defaultModel.ifEmpty { stringResource(R.string.llm_no_model) },
                onClick = { showModelSheet = true },
            )
            FootnoteText(stringResource(R.string.llm_default_model_hint))

            SectionHeader(stringResource(R.string.llm_models))
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
            ModelList(editorConfig, models, onConfigureModel)

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

/**
 * 模型列表：已配置的在上，待配置的可以折叠。候选很多时待配置默认折起来，并提供搜索。
 * 搜索和折叠只影响显示，不算对服务商的修改。
 */
@Composable
private fun ModelList(config: ProviderConfig, models: List<ProviderModel>, onConfigure: (ProviderModel) -> Unit) {
    val rows = remember(models, config.compatibility, config.provider) { modelListRows(config, models) }
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = models.size > MODEL_LIST_LONG
    val active = if (searchable) query.trim() else ""
    val (configured, pending) = remember(rows, active) { rows.matching(active).partition { it.gaps.isEmpty() } }
    // 用户没动过时按数量决定；动过就记住，从模型页回来还是原样
    var pendingChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }

    if (searchable) ModelSearchField(query) { query = it }
    if (active.isNotEmpty() && configured.isEmpty() && pending.isEmpty()) FootnoteText(stringResource(R.string.model_search_empty))
    if (configured.isNotEmpty()) {
        SectionHeader(stringResource(R.string.model_group_ready, configured.size))
        ModelRows(configured, onConfigure)
    }
    if (pending.isNotEmpty()) {
        val title = stringResource(R.string.model_group_pending, pending.size)
        val expanded = active.isNotEmpty() || (pendingChoice ?: (pending.size <= MODEL_LIST_LONG))
        if (active.isNotEmpty()) {
            SectionHeader(title)
        } else {
            GroupSpacer()
            WeListRow(title, onClick = { pendingChoice = !expanded }, expanded = expanded, showDivider = expanded)
        }
        if (expanded) ModelRows(pending, onConfigure)
    }
}

@Composable
private fun ModelRows(rows: List<ModelListRow>, onConfigure: (ProviderModel) -> Unit) {
    rows.forEachIndexed { index, row ->
        key(row.model.modelId) {
            ModelRow(row.model, row.gaps, showDivider = index != rows.lastIndex) { onConfigure(row.model) }
        }
    }
}

/** 一个模型：名称、配置状态和箭头，下面一行小字是 ID（和名称相同时不重复）。点开进入配置，移除也在配置页里。 */
@Composable
private fun ModelRow(model: ProviderModel, gaps: List<ModelGap>, showDivider: Boolean, onConfigure: () -> Unit) {
    val missing = if (model.missingFromList) stringResource(R.string.model_missing) else null
    val detail = listOfNotNull(model.modelId.takeIf { it != model.displayName }, missing).joinToString(stringResource(R.string.model_detail_separator))
    ConfigActionRow(
        title = model.displayName,
        value = modelStatusText(gaps),
        valueStyle = WeTheme.typography.small,
        detail = detail.ifEmpty { null },
        showDivider = showDivider,
        onClick = onConfigure,
    )
}

/** 列表上方的搜索框。 */
@Composable
private fun ModelSearchField(query: String, onChange: (String) -> Unit) {
    val colors = WeTheme.colors
    val hint = stringResource(R.string.model_search_hint)
    Box(Modifier.fillMaxWidth().background(colors.listBackground).padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.pageBackground)
                .padding(start = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(WeIcons.Search, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Box(Modifier.weight(1f).padding(vertical = 6.dp)) {
                if (query.isEmpty()) {
                    Text(hint, style = WeTheme.typography.small, color = colors.textSecondary, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = WeTheme.typography.small.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                    // 占位文字读屏读不到，这里单独说明这个输入框是做什么的
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
                )
            }
            if (query.isNotEmpty()) {
                // 高度不超过搜索框，输入后这一行不跳动；点击区由 Compose 按最小触控尺寸扩到 48dp
                Box(Modifier.size(48.dp, 36.dp).clickable(role = Role.Button) { onChange("") }, contentAlignment = Alignment.Center) {
                    Icon(WeIcons.Close, stringResource(R.string.action_clear), tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                }
            } else {
                Spacer(Modifier.size(10.dp))
            }
        }
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
internal fun ProviderEditSample(state: ProviderEditState = previewProviderState, models: List<ProviderModel> = previewProviderModels) = ProviderEditContent(
    state = state, models = models, onBack = {}, onSave = {}, onNameChange = {}, onKeyChange = {},
    onClearKey = {}, onBaseUrlChange = {}, onDefaultModel = {}, onModelInput = {}, onAddModel = {}, onTest = {}, onDelete = {},
)
