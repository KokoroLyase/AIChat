package isomeria.aichat.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
    val conflicts: List<ModelReference> = emptyList())
@HiltViewModel
class ModelEditViewModel @Inject constructor(savedState: SavedStateHandle, private val providers: ProviderRepository) : ViewModel() {
    private val route = savedState.toRoute<ModelEditRoute>()
    private val _state = MutableStateFlow(ModelEditState())
    val state = _state.asStateFlow()
    init { viewModelScope.launch { _state.value = ModelEditState(providers.model(route.providerId, route.modelId), providers.config(route.providerId)) } }
    fun edit(model: ProviderModel) { _state.value = _state.value.copy(model = model, saved = false) }
    fun save(confirmed: Boolean = false) { viewModelScope.launch {
        val s = _state.value
        val model = s.model?.copy(legacy = false) ?: return@launch
        val config = s.provider ?: return@launch
        val conflicts = providers.conflicts(config, model)
        if (conflicts.isNotEmpty() && !confirmed) _state.value = s.copy(conflicts = conflicts) else {
            providers.saveModel(model)
            _state.value = s.copy(model = model, saved = true, conflicts = emptyList())
        }
    } }
    fun dismiss() { _state.value = _state.value.copy(conflicts = emptyList()) }
}

@Composable
fun ModelEditScreen(onBack: () -> Unit, viewModel: ModelEditViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    ModelEditContent(s, onBack, viewModel::edit, { viewModel.save() })
    if (s.conflicts.isNotEmpty()) ConflictDialog(s.conflicts, viewModel::dismiss) { viewModel.save(true) }
}

/** 所有修改都是受约束的字段；原生强度值与展示名称分别保存。 */
@Composable
fun ModelEditContent(state: ModelEditState, onBack: () -> Unit, onChange: (ProviderModel) -> Unit, onSave: () -> Unit) {
    val model = state.model
    val colors = WeTheme.colors
    val d = WeTheme.dimens
    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(stringResource(R.string.model_configure), onBack = onBack) { WeSmallButton(stringResource(R.string.action_save), onSave, enabled = model != null) }
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
            ChoiceRow(stringResource(R.string.model_chat), booleanLabel(effective.chat), sourceLabel(user.chat != null, reported.chat != null),
                listOf(stringResource(R.string.model_chat_yes) to true, stringResource(R.string.model_chat_no) to false)) { change(user.copy(chat = it)) }
            val image = model.imageInputUserMark ?: when (model.imageInput) { Capability.YES -> true; Capability.NO -> false; Capability.UNKNOWN -> null }
            ChoiceRow(stringResource(R.string.llm_mark_image), booleanLabel(image), sourceLabel(model.imageInputUserMark != null, model.imageInput != Capability.UNKNOWN),
                listOf(stringResource(R.string.rule_yes) to true, stringResource(R.string.rule_no) to false)) { onChange(model.copy(imageInputUserMark = it)) }
            val thinking = effective.thinking
            ChoiceRow(stringResource(R.string.contact_thinking), thinkingModeLabel(thinking?.mode), sourceLabel(user.thinking != null, reported.thinking != null),
                ThinkingMode.entries.filter { it != ThinkingMode.UNKNOWN }.map { thinkingModeLabel(it) to it }) {
                change(user.copy(thinking = ThinkingRule(it, if (it == ThinkingMode.NONE) emptyList() else thinking?.efforts.orEmpty())))
            }
            if (thinking != null && thinking.mode != ThinkingMode.NONE) {
                FootnoteText(stringResource(R.string.effort_note))
                thinking.efforts.forEach { effort ->
                    ConfigActionRow(effort.label, effort.value, onClick = { change(user.copy(thinking = thinking.copy(efforts = thinking.efforts - effort))) })
                }
                var value by remember(model.modelId) { mutableStateOf("") }
                var label by remember(model.modelId) { mutableStateOf("") }
                RuleInput(stringResource(R.string.effort_value), value) { value = it }
                RuleInput(stringResource(R.string.effort_label), label) { label = it }
                val newEffort = NativeEffort(value.trim(), label.trim())
                val newRule = thinking.copy(efforts = thinking.efforts + newEffort)
                WeListRow(stringResource(R.string.effort_add), onClick = {
                    if (ModelRules.validThinking(newRule)) { change(user.copy(thinking = newRule)); value = ""; label = "" }
                }, enabled = ModelRules.validThinking(newRule))
            }
            SectionHeader(stringResource(R.string.contact_temperature))
            FootnoteText(sourceLabel(user.temperature != null, reported.temperature != null))
            TemperatureForm(stringResource(R.string.temperature_normal), effective.temperature?.normal) {
                change(user.copy(temperature = TemperatureRule(it, effective.temperature?.thinking)))
            }
            if (thinking?.mode != ThinkingMode.NONE && effective.temperature != null) {
                val separate = effective.temperature.thinking != null
                ChoiceRow(stringResource(R.string.temperature_thinking_override),
                    stringResource(if (separate) R.string.temperature_use_separate else R.string.temperature_use_default),
                    options = listOf(stringResource(R.string.temperature_use_separate) to true, stringResource(R.string.temperature_use_default) to false)) { value ->
                    change(user.copy(temperature = effective.temperature.copy(thinking = if (value) effective.temperature.thinking ?: effective.temperature.normal else null)))
                }
                if (separate) TemperatureForm(stringResource(R.string.temperature_thinking), effective.temperature.thinking) {
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
                ChoiceRow(stringResource(R.string.stream_options), booleanLabel(override.streamOptions ?: inherited.streamOptions), stringResource(if (override.streamOptions == null) R.string.rule_inherited else R.string.rule_user),
                    listOf(stringResource(R.string.rule_yes) to true, stringResource(R.string.rule_no) to false)) { set(override.copy(streamOptions = it)) }
                WeListRow(stringResource(R.string.rule_restore_inheritance), onClick = { onChange(model.copy(compatibilityOverride = null)) })
                FootnoteText(stringResource(R.string.model_advanced_note))
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
@Composable internal fun RuleInput(title: String, value: String, number: Boolean = false, onChange: (String) -> Unit) {
    val c = WeTheme.colors
    FormRow(title) {
        BasicTextField(value, { onChange(it.take(80)) }, singleLine = true, textStyle = WeTheme.typography.listTitle.copy(color = c.textPrimary), cursorBrush = SolidColor(c.brand),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Decimal else KeyboardType.Ascii, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().padding(vertical = WeTheme.dimens.inputBarVerticalPadding))
    }
}
@Composable internal fun TemperatureForm(title: String, spec: TemperatureSpec?, onChange: (TemperatureSpec) -> Unit) {
    fun label(mode: TemperatureMode?): Int = when(mode) { TemperatureMode.ADJUSTABLE -> R.string.temperature_adjustable; TemperatureMode.FIXED -> R.string.temperature_fixed; TemperatureMode.UNSUPPORTED -> R.string.temperature_unsupported; else -> R.string.rule_unknown }
    ChoiceRow(title, stringResource(label(spec?.mode)), options = TemperatureMode.entries.map { stringResource(label(it)) to it }) {
        onChange((spec ?: TemperatureSpec(it)).copy(mode = it))
    }
    if (spec != null) {
        @Composable fun number(title: String, value: Float, set: (Float) -> Unit) {
            var input by remember(spec.mode) { mutableStateOf(value.toString()) }
            RuleInput(title, input, true) { input = it; it.toFloatOrNull()?.takeIf { it.isFinite() }?.let(set) }
        }
        if (spec.mode == TemperatureMode.ADJUSTABLE) {
            number(stringResource(R.string.temperature_min), spec.min) { onChange(spec.copy(min = it)) }
            number(stringResource(R.string.temperature_max), spec.max) { onChange(spec.copy(max = it)) }
        }
        if (spec.mode == TemperatureMode.FIXED) number(stringResource(R.string.temperature_value), spec.fixed) { onChange(spec.copy(fixed = it)) }
        if (!spec.valid()) FootnoteText(stringResource(R.string.temperature_invalid))
    }
}
@Composable internal fun ConflictDialog(refs: List<ModelReference>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val lines = refs.map { ref -> stringResource(when (ref.purpose) { ReferencePurpose.DEFAULT -> R.string.reference_default; ReferencePurpose.CHAT -> R.string.reference_chat; ReferencePurpose.MEMORY -> R.string.reference_memory }, ref.contactName.orEmpty()) }
    WeDialog(title = stringResource(R.string.conflict_title), message = stringResource(R.string.conflict_note, lines.joinToString("\n")),
        confirmText = stringResource(R.string.conflict_save), onDismiss = onDismiss, onConfirm = onConfirm)
}
