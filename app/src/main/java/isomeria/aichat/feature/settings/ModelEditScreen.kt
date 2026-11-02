package isomeria.aichat.feature.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.data.db.*
import isomeria.aichat.data.llm.*
import isomeria.aichat.data.repo.ModelReference
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.ReferencePurpose
import isomeria.aichat.navigation.ModelEditRoute
import isomeria.aichat.ui.components.*
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelEditState(val model: ProviderModel? = null, val provider: ProviderConfig? = null, val saved: Boolean = false,
    val conflicts: List<ModelReference> = emptyList(),
    /** 最后一次读取或保存的内容，用来判断有没有未保存的修改 */
    val original: ProviderModel? = null,
    val removed: Boolean = false,
    /** 移除被拦下时，还在使用这个模型的用途 */
    val inUse: List<ModelReference> = emptyList()) {
    val dirty: Boolean get() = original != null && model != original
}
@HiltViewModel
class ModelEditViewModel @Inject constructor(savedState: SavedStateHandle, private val providers: ProviderRepository) : ViewModel() {
    private val route = savedState.toRoute<ModelEditRoute>()
    private val _state = MutableStateFlow(ModelEditState())
    val state = _state.asStateFlow()
    init { viewModelScope.launch {
        val model = providers.model(route.providerId, route.modelId)
        _state.value = ModelEditState(model, providers.config(route.providerId), original = model)
    } }
    fun edit(model: ProviderModel) { _state.value = _state.value.copy(model = model, saved = false) }
    fun save(confirmed: Boolean = false) { viewModelScope.launch {
        val s = _state.value
        val model = s.model?.copy(legacy = false) ?: return@launch
        val config = s.provider ?: return@launch
        val conflicts = providers.conflicts(config, model)
        if (conflicts.isNotEmpty() && !confirmed) _state.value = s.copy(conflicts = conflicts) else {
            providers.saveModel(model)
            _state.value = s.copy(model = model, original = model, saved = true, conflicts = emptyList())
        }
    } }
    fun dismiss() { _state.value = _state.value.copy(conflicts = emptyList()) }
    fun remove() { viewModelScope.launch {
        val model = _state.value.model ?: return@launch
        if (providers.removeModel(model.providerId, model.modelId)) _state.value = _state.value.copy(removed = true)
        else _state.value = _state.value.copy(inUse = providers.modelReferences(model.providerId, model.modelId))
    } }
    fun dismissInUse() { _state.value = _state.value.copy(inUse = emptyList()) }
}

@Composable
fun ModelEditScreen(onBack: () -> Unit, viewModel: ModelEditViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.removed) { if (s.removed) onBack() }
    ModelEditContent(s, onBack, viewModel::edit, { viewModel.save() }, viewModel::remove)
    if (s.conflicts.isNotEmpty()) ConflictDialog(s.conflicts, viewModel::dismiss) { viewModel.save(true) }
    if (s.inUse.isNotEmpty()) WeDialog(message = stringResource(R.string.model_remove_in_use), dismissText = null,
        onDismiss = viewModel::dismissInUse, onConfirm = viewModel::dismissInUse) { ReferenceList(s.inUse) }
}

/** 所有修改都是受约束的字段；原生强度值与展示名称分别保存。 */
@Composable
fun ModelEditContent(state: ModelEditState, onBack: () -> Unit, onChange: (ProviderModel) -> Unit, onSave: () -> Unit, onRemove: () -> Unit = {}) {
    val model = state.model
    val colors = WeTheme.colors
    val d = WeTheme.dimens
    val context = LocalContext.current
    // 输入框里内容不合法的字段；规则只保存完整合法的值，所以这些内容要单独记下来
    val invalidInputs = remember { mutableStateMapOf<String, Unit>() }
    val onInvalid = { key: String, invalid: Boolean -> if (invalid) invalidInputs[key] = Unit else invalidInputs.remove(key); Unit }
    var effortValue by remember(model?.modelId) { mutableStateOf("") }
    var effortLabel by remember(model?.modelId) { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val unsaved = state.dirty || invalidInputs.isNotEmpty() || effortValue.isNotEmpty() || effortLabel.isNotEmpty()
    BackHandler(enabled = unsaved) { confirmDiscard = true }
    ImeBackHandler()
    if (confirmDiscard) WeDialog(message = stringResource(R.string.contact_discard_title), confirmText = stringResource(R.string.contact_discard), destructive = true,
        onDismiss = { confirmDiscard = false }, onConfirm = { confirmDiscard = false; onBack() })
    if (confirmRemove && model != null) WeDialog(message = stringResource(R.string.model_remove_confirm, model.displayName), confirmText = stringResource(R.string.llm_remove_model), destructive = true,
        onDismiss = { confirmRemove = false }, onConfirm = { confirmRemove = false; onRemove() })
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(stringResource(R.string.model_configure), onBack = { if (unsaved) confirmDiscard = true else onBack() }) {
            WeSmallButton(stringResource(R.string.action_save), {
                if (invalidInputs.isEmpty()) onSave() else Toast.makeText(context, R.string.model_input_invalid, Toast.LENGTH_SHORT).show()
            }, enabled = model != null)
        }
        if (model == null) return@Column
        val reported = ModelRules.decode(model.reportedConfig)
        val user = ModelRules.decode(model.userConfig)
        val effective = user.over(reported)
        fun change(value: ModelConfig) = onChange(model.copy(userConfig = ModelRules.encode(value)))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FootnoteText(model.modelId)
            FootnoteText(stringResource(if (model.legacy) R.string.model_legacy else if (state.provider?.let { ModelRules.usable(it, model) } == true) R.string.model_ready else R.string.model_pending))
            if (state.saved) FootnoteText(stringResource(R.string.llm_saved))
            FootnoteText(stringResource(R.string.model_incomplete_save))
            val uri = LocalUriHandler.current
            val docs = ProviderPresets.all.firstOrNull { it.id == state.provider?.presetId }
                ?: ProviderPresets.all.firstOrNull { it.compatibility.name == state.provider?.compatibility }
            if (docs != null) WeListRow(stringResource(R.string.provider_docs), onClick = { uri.openUri(docs.docs) })
            FootnoteText(stringResource(R.string.model_config_source_note))
            SectionHeader(stringResource(R.string.model_capabilities))
            ChoiceRow(stringResource(R.string.model_chat), stringResource(when (effective.chat) { true -> R.string.model_chat_yes; false -> R.string.model_chat_no; null -> R.string.rule_unknown }), sourceLabel(user.chat != null, reported.chat != null),
                listOf(stringResource(R.string.model_chat_yes) to true, stringResource(R.string.model_chat_no) to false)) { change(user.copy(chat = it)) }
            val image = model.imageInputUserMark ?: when (model.imageInput) { Capability.YES -> true; Capability.NO -> false; Capability.UNKNOWN -> null }
            ChoiceRow(stringResource(R.string.llm_mark_image), booleanLabel(image), sourceLabel(model.imageInputUserMark != null, model.imageInput != Capability.UNKNOWN),
                listOf(stringResource(R.string.rule_yes) to true, stringResource(R.string.rule_no) to false)) { onChange(model.copy(imageInputUserMark = it)) }
            val thinking = effective.thinking
            ChoiceRow(stringResource(R.string.model_thinking_mode), thinkingModeLabel(thinking?.mode), sourceLabel(user.thinking != null, reported.thinking != null),
                ThinkingMode.entries.filter { it != ThinkingMode.UNKNOWN }.map { thinkingModeLabel(it) to it }) {
                change(user.copy(thinking = ThinkingRule(it, if (it == ThinkingMode.NONE) emptyList() else thinking?.efforts.orEmpty())))
            }
            if (thinking != null && thinking.mode != ThinkingMode.NONE) {
                FootnoteText(stringResource(R.string.effort_note))
                thinking.efforts.forEach { effort ->
                    EffortRow(effort) { change(user.copy(thinking = thinking.copy(efforts = thinking.efforts - effort))) }
                }
                RuleInput(stringResource(R.string.effort_value), effortValue, hint = stringResource(R.string.effort_value_hint)) { effortValue = it }
                // 显示名称可以是中文，不能强制英文键盘
                RuleInput(stringResource(R.string.effort_label), effortLabel, ascii = false, hint = stringResource(R.string.effort_label_hint)) { effortLabel = it }
                val issue = effortIssue(thinking, effortValue, effortLabel)
                if (issue != null && (effortValue.isNotEmpty() || effortLabel.isNotEmpty())) FieldError(stringResource(issue.message))
                WeListRow(stringResource(R.string.effort_add), onClick = {
                    if (issue == null) {
                        change(user.copy(thinking = thinking.copy(efforts = thinking.efforts + NativeEffort(effortValue.trim(), effortLabel.trim()))))
                        effortValue = ""; effortLabel = ""
                    }
                }, enabled = issue == null)
            }
            SectionHeader(stringResource(R.string.contact_temperature))
            FootnoteText(sourceLabel(user.temperature != null, reported.temperature != null))
            TemperatureForm(stringResource(R.string.temperature_normal), effective.temperature?.normal, onInvalid) {
                change(user.copy(temperature = TemperatureRule(it, effective.temperature?.thinking)))
            }
            if (thinking?.mode != ThinkingMode.NONE && effective.temperature != null) {
                val separate = effective.temperature.thinking != null
                ChoiceRow(stringResource(R.string.temperature_thinking_override),
                    stringResource(if (separate) R.string.temperature_use_separate else R.string.temperature_use_default),
                    options = listOf(stringResource(R.string.temperature_use_separate) to true, stringResource(R.string.temperature_use_default) to false)) { value ->
                    change(user.copy(temperature = effective.temperature.copy(thinking = if (value) effective.temperature.thinking ?: effective.temperature.normal else null)))
                }
                if (separate) TemperatureForm(stringResource(R.string.temperature_thinking), effective.temperature.thinking, onInvalid) {
                    change(user.copy(temperature = effective.temperature.copy(thinking = it)))
                }
            }
            var advanced by remember { mutableStateOf(false) }
            WeListRow(stringResource(R.string.model_advanced), onClick = { advanced = !advanced })
            if (advanced) {
                val override = ModelRules.override(model.compatibilityOverride)
                val inherited = state.provider?.let { ModelRules.encoding(it, model.copy(compatibilityOverride = null)) } ?: Compatibility.GENERIC.encoding
                fun set(value: CompatibilityOverride) = onChange(model.copy(compatibilityOverride = ModelRules.encode(value)))
                ChoiceRow(stringResource(R.string.thinking_field), (override.thinkingField ?: inherited.thinkingField).name, stringResource(if (override.thinkingField == null) R.string.rule_inherited else R.string.rule_user),
                    ThinkingField.entries.map { it.name to it }) { set(override.copy(thinkingField = it)) }
                ChoiceRow(stringResource(R.string.effort_field), (override.effortField ?: inherited.effortField).name, stringResource(if (override.effortField == null) R.string.rule_inherited else R.string.rule_user),
                    EffortField.entries.map { it.name to it }) { set(override.copy(effortField = it)) }
                ChoiceRow(stringResource(R.string.stream_options), stringResource(if (override.streamOptions ?: inherited.streamOptions) R.string.rule_send else R.string.rule_no_send), stringResource(if (override.streamOptions == null) R.string.rule_inherited else R.string.rule_user),
                    listOf(stringResource(R.string.rule_send) to true, stringResource(R.string.rule_no_send) to false)) { set(override.copy(streamOptions = it)) }
                WeListRow(stringResource(R.string.rule_restore_inheritance), onClick = { onChange(model.copy(compatibilityOverride = null)) })
                FootnoteText(stringResource(R.string.model_advanced_note))
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().background(colors.listBackground).clickable { confirmRemove = true }.heightIn(min = d.listRowHeight), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.model_remove), style = WeTheme.typography.listTitle, color = colors.danger)
            }
            Spacer(Modifier.height(d.horizontalPadding))
        }
    }
}

@Composable internal fun booleanLabel(value: Boolean?): String = stringResource(when (value) { true -> R.string.rule_yes; false -> R.string.rule_no; null -> R.string.rule_unknown })
@Composable internal fun sourceLabel(user: Boolean, reported: Boolean): String = stringResource(when { user -> R.string.rule_user; reported -> R.string.rule_reported; else -> R.string.rule_unknown })
@Composable internal fun thinkingModeLabel(mode: ThinkingMode?): String = stringResource(when (mode) {
    ThinkingMode.NONE -> R.string.thinking_none; ThinkingMode.SWITCH -> R.string.thinking_switch; ThinkingMode.ALWAYS -> R.string.thinking_always; else -> R.string.rule_unknown
})
@Composable internal fun <T> ChoiceRow(title: String, value: String, note: String? = null, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var show by remember { mutableStateOf(false) }
    ConfigActionRow(title, value, note) { show = true }
    if (show) WeActionSheet(title = title, options = options.map { (label, value) -> SheetOption(label) { show = false; onPick(value) } }, onDismiss = { show = false })
}
@Composable internal fun ConfigActionRow(title: String, value: String, note: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(WeTheme.colors.listBackground).clickable(onClick = onClick)
        .heightIn(min = WeTheme.dimens.listRowHeight).padding(WeTheme.dimens.horizontalPadding)) {
        Text(title, style = WeTheme.typography.listTitle, color = WeTheme.colors.textPrimary)
        Text(value, style = WeTheme.typography.small, color = WeTheme.colors.textTertiary)
        if (note != null) Text(note, style = WeTheme.typography.listTime, color = WeTheme.colors.textTertiary)
    }
}
/** 档位行：只有右边的“移除”能点，整行不再是点了就删。 */
@Composable private fun EffortRow(effort: NativeEffort, onRemove: () -> Unit) {
    val d = WeTheme.dimens
    Row(Modifier.fillMaxWidth().background(WeTheme.colors.listBackground).heightIn(min = d.listRowHeight).padding(start = d.horizontalPadding), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(effort.label, style = WeTheme.typography.listTitle, color = WeTheme.colors.textPrimary)
            Text(effort.value, style = WeTheme.typography.small, color = WeTheme.colors.textTertiary)
        }
        Box(Modifier.clickable(onClick = onRemove).heightIn(min = 48.dp).padding(horizontal = d.horizontalPadding), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.llm_remove_model), style = WeTheme.typography.small, color = WeTheme.colors.danger)
        }
    }
}
/** 字段下方的红字提示。 */
@Composable internal fun FieldError(text: String) {
    Text(text, style = WeTheme.typography.small, color = WeTheme.colors.danger,
        modifier = Modifier.fillMaxWidth().padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 6.dp))
}
/** 只接受完整合法的非负数。"0." 这类输入中途的内容不算，否则删字时会把中间值存进规则。 */
internal fun parseTemperatureInput(input: String): Float? =
    input.trim().takeIf { it.matches(Regex("""\d*\.?\d+([eE][+-]?\d+)?""")) }?.toFloatOrNull()?.takeIf { it.isFinite() }
internal enum class EffortIssue(val message: Int) {
    VALUE_EMPTY(R.string.effort_issue_value_empty), VALUE_RESERVED(R.string.effort_issue_value_reserved), VALUE_FORMAT(R.string.effort_issue_value_format),
    VALUE_DUPLICATE(R.string.effort_issue_value_duplicate), LABEL_EMPTY(R.string.effort_issue_label_empty), EXISTING(R.string.effort_issue_existing),
}
/** 说明新档位为什么不能添加；可以添加时返回 null。判断标准与 [ModelRules.validThinking] 一致。 */
internal fun effortIssue(rule: ThinkingRule, value: String, label: String): EffortIssue? {
    val v = value.trim()
    return when {
        v.isEmpty() -> EffortIssue.VALUE_EMPTY
        v in listOf("off", "on", "none") -> EffortIssue.VALUE_RESERVED
        !v.matches(Regex("[A-Za-z0-9_-]{1,40}")) -> EffortIssue.VALUE_FORMAT
        rule.efforts.any { it.value == v } -> EffortIssue.VALUE_DUPLICATE
        label.isBlank() -> EffortIssue.LABEL_EMPTY
        !ModelRules.validThinking(rule.copy(efforts = rule.efforts + NativeEffort(v, label.trim()))) -> EffortIssue.EXISTING
        else -> null
    }
}
@Composable internal fun RuleInput(title: String, value: String, number: Boolean = false, ascii: Boolean = true, hint: String? = null, onChange: (String) -> Unit) {
    val c = WeTheme.colors
    FormRow(title) {
        Box(Modifier.fillMaxWidth().padding(vertical = WeTheme.dimens.inputBarVerticalPadding)) {
            if (value.isEmpty() && hint != null) Text(hint, style = WeTheme.typography.listTitle, color = c.textSecondary, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, { onChange(it.take(80)) }, singleLine = true, textStyle = WeTheme.typography.listTitle.copy(color = c.textPrimary), cursorBrush = SolidColor(c.brand),
                keyboardOptions = KeyboardOptions(keyboardType = when { number -> KeyboardType.Decimal; ascii -> KeyboardType.Ascii; else -> KeyboardType.Text }, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
        }
    }
}
@Composable internal fun TemperatureForm(title: String, spec: TemperatureSpec?, onInvalid: (String, Boolean) -> Unit, onChange: (TemperatureSpec) -> Unit) {
    fun label(mode: TemperatureMode?): Int = when(mode) { TemperatureMode.ADJUSTABLE -> R.string.temperature_adjustable; TemperatureMode.FIXED -> R.string.temperature_fixed; TemperatureMode.UNSUPPORTED -> R.string.temperature_unsupported; else -> R.string.rule_unknown }
    ChoiceRow(title, stringResource(label(spec?.mode)), options = TemperatureMode.entries.map { stringResource(label(it)) to it }) {
        onChange((spec ?: TemperatureSpec(it)).copy(mode = it))
    }
    if (spec != null) {
        val form = title
        @Composable fun number(title: String, value: Float, set: (Float) -> Unit) {
            var input by remember(spec.mode) { mutableStateOf(value.toString()) }
            val invalid = parseTemperatureInput(input) == null
            val key = "$form/$title"
            DisposableEffect(key, invalid) { onInvalid(key, invalid); onDispose { onInvalid(key, false) } }
            RuleInput(title, input, true) { input = it; parseTemperatureInput(it)?.let(set) }
            if (invalid) FieldError(stringResource(R.string.temperature_input_invalid))
        }
        if (spec.mode == TemperatureMode.ADJUSTABLE) {
            number(stringResource(R.string.temperature_min), spec.min) { onChange(spec.copy(min = it)) }
            number(stringResource(R.string.temperature_max), spec.max) { onChange(spec.copy(max = it)) }
        }
        if (spec.mode == TemperatureMode.FIXED) number(stringResource(R.string.temperature_value), spec.fixed) { onChange(spec.copy(fixed = it)) }
        if (!spec.valid()) FieldError(stringResource(R.string.temperature_invalid))
    }
}
@Composable internal fun ConflictDialog(refs: List<ModelReference>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    WeDialog(title = stringResource(R.string.conflict_title), message = stringResource(R.string.conflict_note),
        confirmText = stringResource(R.string.conflict_save), onDismiss = onDismiss, onConfirm = onConfirm) { ReferenceList(refs) }
}
/** 同一联系人的多个用途合成一行；服务商默认模型没有联系人，单独一行。 */
internal fun groupReferences(refs: List<ModelReference>): List<Pair<String?, List<ReferencePurpose>>> =
    refs.groupBy { it.contactName }.map { (name, items) -> name to items.map { it.purpose }.distinct() }
@Composable internal fun ReferenceList(refs: List<ModelReference>) {
    val separator = stringResource(R.string.reference_separator)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groupReferences(refs).forEach { (name, purposes) ->
            val text = if (name == null) stringResource(R.string.reference_default) else stringResource(R.string.reference_contact, name,
                purposes.map { stringResource(when (it) { ReferencePurpose.DEFAULT -> R.string.reference_default; ReferencePurpose.CHAT -> R.string.reference_chat; ReferencePurpose.MEMORY -> R.string.reference_memory }) }.joinToString(separator))
            Text(text, style = WeTheme.typography.dialogMessage, color = WeTheme.colors.textPrimary)
        }
    }
}
