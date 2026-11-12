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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
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

data class ModelEditState(val model: ProviderModel? = null, val provider: ProviderConfig? = null,
    /** 保存成功后要提示的文字；有值时页面返回 */
    val savedMessage: Int? = null,
    /** 这次保存才产生的冲突，要确认 */
    val conflicts: List<ModelReference> = emptyList(),
    /** 原本就有的冲突，只作提示 */
    val existingConflicts: List<ModelReference> = emptyList(),
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
    fun edit(model: ProviderModel) { _state.value = _state.value.copy(model = model) }
    fun save(confirmed: Boolean = false) { viewModelScope.launch {
        val s = _state.value
        val model = s.model?.copy(legacy = false) ?: return@launch
        val config = s.provider ?: return@launch
        val change = providers.conflictChange(config, providers.conflicts(config, model))
        if (change.added.isNotEmpty() && !confirmed) _state.value = s.copy(conflicts = change.added, existingConflicts = change.existing) else {
            providers.saveModel(model)
            val message = when {
                change.added.isNotEmpty() -> R.string.model_saved_conflict
                !ModelRules.usable(config, model) -> R.string.model_saved_pending
                change.existing.isNotEmpty() -> R.string.model_saved_conflict
                else -> R.string.llm_saved
            }
            _state.value = s.copy(model = model, original = model, savedMessage = message, conflicts = emptyList(), existingConflicts = emptyList())
        }
    } }
    fun dismiss() { _state.value = _state.value.copy(conflicts = emptyList(), existingConflicts = emptyList()) }
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
    val context = LocalContext.current
    LaunchedEffect(s.removed) { if (s.removed) onBack() }
    LaunchedEffect(s.savedMessage) {
        s.savedMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onBack()
        }
    }
    ModelEditContent(s, onBack, viewModel::edit, { viewModel.save() }, viewModel::remove)
    if (s.conflicts.isNotEmpty()) ConflictDialog(s.conflicts, viewModel::dismiss, s.existingConflicts) { viewModel.save(true) }
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
                when {
                    invalidInputs.isNotEmpty() -> Toast.makeText(context, R.string.model_input_invalid, Toast.LENGTH_SHORT).show()
                    // 保存后会返回，没添加的档位不能悄悄丢掉
                    effortValue.isNotEmpty() || effortLabel.isNotEmpty() -> Toast.makeText(context, R.string.model_effort_draft, Toast.LENGTH_SHORT).show()
                    else -> onSave()
                }
            }, enabled = model != null)
        }
        if (model == null) return@Column
        val reported = ModelRules.decode(model.reportedConfig)
        val user = ModelRules.decode(model.userConfig)
        val effective = user.over(reported)
        fun change(value: ModelConfig) = onChange(model.copy(userConfig = ModelRules.encode(value)))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            val gaps = state.provider?.let { ModelRules.gaps(it, model) } ?: listOf(ModelGap.ENCODING)
            ModelSummary(model, if (model.legacy) stringResource(R.string.model_legacy) else modelStatusText(gaps))
            if (gaps.isNotEmpty() && !model.legacy) FootnoteText(stringResource(R.string.model_incomplete_save))
            val uri = LocalUriHandler.current
            val docs = docsPreset(state.provider?.presetId, state.provider?.compatibility)
            if (docs != null) {
                GroupSpacer()
                WeListRow(stringResource(R.string.provider_docs), onClick = { uri.openUri(docs.docs) })
            }
            SectionHeader(stringResource(R.string.model_capabilities))
            ChoiceRow(stringResource(R.string.model_chat), stringResource(when (effective.chat) { true -> R.string.model_chat_yes; false -> R.string.model_chat_no; null -> R.string.rule_unknown }), sourceLabel(user.chat != null, reported.chat != null),
                listOf(stringResource(R.string.model_chat_yes) to true, stringResource(R.string.model_chat_no) to false), showDivider = true) { change(user.copy(chat = it)) }
            val image = model.imageInputUserMark ?: when (model.imageInput) { Capability.YES -> true; Capability.NO -> false; Capability.UNKNOWN -> null }
            ChoiceRow(stringResource(R.string.llm_mark_image), booleanLabel(image), sourceLabel(model.imageInputUserMark != null, model.imageInput != Capability.UNKNOWN),
                listOf(stringResource(R.string.rule_yes) to true, stringResource(R.string.rule_no) to false), showDivider = true) { onChange(model.copy(imageInputUserMark = it)) }
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
                }, enabled = issue == null, showArrow = false)
            }
            SectionHeader(stringResource(R.string.contact_temperature))
            TemperatureForm(stringResource(R.string.temperature_normal), effective.temperature?.normal, onInvalid, note = sourceLabel(user.temperature != null, reported.temperature != null)) {
                change(user.copy(temperature = TemperatureRule(it, effective.temperature?.thinking)))
            }
            if ((thinking?.mode == ThinkingMode.SWITCH || thinking?.mode == ThinkingMode.ALWAYS) && effective.temperature != null) {
                val separate = effective.temperature.thinking != null
                ChoiceRow(stringResource(R.string.temperature_thinking_override),
                    stringResource(if (separate) R.string.temperature_use_separate else R.string.temperature_use_default),
                    options = listOf(stringResource(R.string.temperature_use_separate) to true, stringResource(R.string.temperature_use_default) to false), showDivider = separate) { value ->
                    change(user.copy(temperature = effective.temperature.copy(thinking = if (value) effective.temperature.thinking ?: effective.temperature.normal else null)))
                }
                if (separate) TemperatureForm(stringResource(R.string.temperature_thinking), effective.temperature.thinking, onInvalid) {
                    change(user.copy(temperature = effective.temperature.copy(thinking = it)))
                }
            }
            FootnoteText(stringResource(R.string.model_config_source_note))
            var advanced by rememberSaveable { mutableStateOf(false) }
            WeListRow(stringResource(R.string.model_advanced), onClick = { advanced = !advanced }, expanded = advanced, showDivider = advanced)
            if (advanced) {
                val override = ModelRules.override(model.compatibilityOverride)
                val inherited = state.provider?.let { ModelRules.encoding(it, model.copy(compatibilityOverride = null)) } ?: Compatibility.GENERIC.encoding
                fun set(value: CompatibilityOverride) = onChange(model.copy(compatibilityOverride = ModelRules.encode(value)))
                ChoiceRow(stringResource(R.string.thinking_field), thinkingFieldLabel(override.thinkingField ?: inherited.thinkingField), stringResource(if (override.thinkingField == null) R.string.rule_inherited else R.string.rule_user),
                    ThinkingField.entries.map { thinkingFieldLabel(it) to it }, showDivider = true) { set(override.copy(thinkingField = it)) }
                ChoiceRow(stringResource(R.string.effort_field), effortFieldLabel(override.effortField ?: inherited.effortField), stringResource(if (override.effortField == null) R.string.rule_inherited else R.string.rule_user),
                    EffortField.entries.map { effortFieldLabel(it) to it }, showDivider = true) { set(override.copy(effortField = it)) }
                ChoiceRow(stringResource(R.string.stream_options), stringResource(if (override.streamOptions ?: inherited.streamOptions) R.string.rule_send else R.string.rule_no_send), stringResource(if (override.streamOptions == null) R.string.rule_inherited else R.string.rule_user),
                    listOf(stringResource(R.string.rule_send) to true, stringResource(R.string.rule_no_send) to false), showDivider = true) { set(override.copy(streamOptions = it)) }
                // 这是一个动作，不进入下一页；没有覆盖时没什么可恢复的
                WeListRow(stringResource(R.string.rule_restore_inheritance), onClick = { onChange(model.copy(compatibilityOverride = null)) }, showArrow = false, enabled = model.compatibilityOverride != null)
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
/** 字段的来源；来源未知时不显示，否则会和值一起变成“待确认 · 待确认”。 */
@Composable internal fun sourceLabel(user: Boolean, reported: Boolean): String? = when { user -> stringResource(R.string.rule_user); reported -> stringResource(R.string.rule_reported); else -> null }
/** 实际发送的字段名是协议的一部分，不翻译。 */
@Composable internal fun thinkingFieldLabel(field: ThinkingField): String = when (field) {
    ThinkingField.NONE -> stringResource(R.string.rule_no_send)
    ThinkingField.THINKING_TYPE -> "thinking.type"
    ThinkingField.THINKING_TYPE_ADAPTIVE -> "thinking.type (adaptive / disabled)"
    ThinkingField.ENABLE_THINKING -> "enable_thinking"
    ThinkingField.REASONING_ENABLED -> "reasoning.enabled"
    ThinkingField.REASONING_EFFORT -> "reasoning_effort"
}
@Composable internal fun effortFieldLabel(field: EffortField): String = when (field) {
    EffortField.NONE -> stringResource(R.string.rule_no_send)
    EffortField.REASONING_EFFORT -> "reasoning_effort"
    EffortField.REASONING_OBJECT -> "reasoning.effort"
}
/** 模型页顶部的摘要：名称、ID（和名称相同时不重复）、状态。 */
@Composable private fun ModelSummary(model: ProviderModel, status: String) {
    val d = WeTheme.dimens
    Column(Modifier.fillMaxWidth().background(WeTheme.colors.listBackground).padding(horizontal = d.horizontalPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(model.displayName, style = WeTheme.typography.listTitle, color = WeTheme.colors.textPrimary)
        if (model.modelId != model.displayName) Text(model.modelId, style = WeTheme.typography.small, color = WeTheme.colors.textTertiary)
        Text(status, style = WeTheme.typography.small, color = WeTheme.colors.textTertiary)
    }
}
@Composable internal fun thinkingModeLabel(mode: ThinkingMode?): String = stringResource(when (mode) {
    ThinkingMode.NONE -> R.string.thinking_none; ThinkingMode.SWITCH -> R.string.thinking_switch; ThinkingMode.ALWAYS -> R.string.thinking_always; else -> R.string.rule_unknown
})
/** 模型状态：已配置，或具体缺什么。 */
@Composable internal fun modelStatusText(gaps: List<ModelGap>): String {
    val separator = stringResource(R.string.reference_separator)
    return when {
        gaps.isEmpty() -> stringResource(R.string.model_ready)
        ModelGap.NOT_CHAT in gaps -> stringResource(R.string.model_chat_no)
        gaps == listOf(ModelGap.ENCODING) -> stringResource(R.string.model_pending_encoding)
        else -> stringResource(R.string.model_pending_gaps, gaps.map { stringResource(when (it) {
            ModelGap.IMAGE -> R.string.model_gap_image
            ModelGap.THINKING -> R.string.model_gap_thinking
            ModelGap.TEMPERATURE -> R.string.model_gap_temperature
            ModelGap.ENCODING -> R.string.model_gap_encoding
            ModelGap.CHAT, ModelGap.NOT_CHAT -> R.string.model_gap_chat
        }) }.joinToString(separator))
    }
}
/**
 * 点开选择的行。菜单里默认按文字标出当前项；行上显示的文字和选项不同时，用 [isSelected] 说明。
 * [subtitle] 是选项的小字，比如同名模型的 ID。
 */
@Composable internal fun <T> ChoiceRow(title: String, value: String, note: String? = null, options: List<Pair<String, T>>, valueColor: Color = WeTheme.colors.textTertiary, showDivider: Boolean = false,
    isSelected: (String, T) -> Boolean = { label, _ -> label == value }, subtitle: (T) -> String? = { null }, onPick: (T) -> Unit) {
    var show by remember { mutableStateOf(false) }
    // 没有可选项时点开只会是一个空菜单
    ConfigActionRow(title, value, note, valueColor = valueColor, enabled = options.isNotEmpty(), showDivider = showDivider) { show = true }
    if (show) WeActionSheet(title = title, options = options.map { (label, option) -> SheetOption(label, selected = isSelected(label, option), subtitle = subtitle(option)) { show = false; onPick(option) } }, onDismiss = { show = false })
}
/**
 * 配置行：放得下时和普通列表行一样“左标题、右值、箭头”，放不下时值换到标题下方。
 * 高度随内容变化，不靠缩小文字或隐藏状态解决大字体。[note] 是值后面的来源小字，[detail] 是整行下方的小字。
 */
@Composable internal fun ConfigActionRow(title: String, value: String, note: String? = null, titleColor: Color = WeTheme.colors.textPrimary, valueColor: Color = WeTheme.colors.textTertiary, enabled: Boolean = true,
    showDivider: Boolean = false, valueStyle: TextStyle = WeTheme.typography.listTitle, detail: String? = null, onClick: () -> Unit) {
    val colors = WeTheme.colors
    val d = WeTheme.dimens
    val noteSize = WeTheme.typography.listTime.fontSize
    Row(
        Modifier.fillMaxWidth().background(colors.listBackground).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = d.listRowHeight)
            .drawBehind {
                if (showDivider) {
                    val y = size.height - d.dividerThickness.toPx() / 2
                    drawLine(colors.divider, Offset(d.horizontalPadding.toPx(), y), Offset(size.width, y), d.dividerThickness.toPx())
                }
            }
            .padding(horizontal = d.horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TitleAndValue(
            modifier = Modifier.weight(1f),
            title = { Text(title, style = WeTheme.typography.listTitle, color = if (enabled) titleColor else colors.textSecondary) },
            value = {
                Text(
                    buildAnnotatedString {
                        append(value)
                        if (note != null) withStyle(SpanStyle(fontSize = noteSize, color = colors.textTertiary)) { append(" · $note") }
                    },
                    style = valueStyle,
                    color = valueColor,
                )
            },
            detail = detail?.let { text -> { Text(text, style = WeTheme.typography.small, color = colors.textTertiary) } },
        )
        if (enabled) Icon(WeIcons.ChevronRight, null, tint = colors.arrow, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}
/** 一行放得下就左右排，值靠右，并按整行垂直居中（和箭头对齐）；放不下就上下排，值靠左。[detail] 总在标题一侧的最下面。 */
@Composable private fun TitleAndValue(title: @Composable () -> Unit, value: @Composable () -> Unit, detail: (@Composable () -> Unit)?, modifier: Modifier) {
    Layout(content = { title(); value(); detail?.invoke() }, modifier = modifier) { measurables, constraints ->
        val titleMeasurable = measurables[0]
        val valueMeasurable = measurables[1]
        val detailMeasurable = measurables.getOrNull(2)
        val gap = 16.dp.roundToPx()
        val width = constraints.maxWidth
        val inline = titleMeasurable.maxIntrinsicWidth(Constraints.Infinity) + gap + valueMeasurable.maxIntrinsicWidth(Constraints.Infinity) <= width
        val free = Constraints(maxWidth = width, maxHeight = constraints.maxHeight)
        val titlePlaceable = titleMeasurable.measure(free)
        val valuePlaceable = valueMeasurable.measure(free)
        if (inline) {
            // 小字不能压到右边的值
            val detailPlaceable = detailMeasurable?.measure(free.copy(maxWidth = width - valuePlaceable.width - gap))
            val leftHeight = titlePlaceable.height + (detailPlaceable?.height ?: 0)
            val height = maxOf(leftHeight, valuePlaceable.height)
            layout(width, height) {
                val top = (height - leftHeight) / 2
                titlePlaceable.placeRelative(0, top)
                detailPlaceable?.placeRelative(0, top + titlePlaceable.height)
                valuePlaceable.placeRelative(width - valuePlaceable.width, (height - valuePlaceable.height) / 2)
            }
        } else {
            val detailPlaceable = detailMeasurable?.measure(free)
            layout(width, titlePlaceable.height + valuePlaceable.height + (detailPlaceable?.height ?: 0)) {
                titlePlaceable.placeRelative(0, 0)
                valuePlaceable.placeRelative(0, titlePlaceable.height)
                detailPlaceable?.placeRelative(0, titlePlaceable.height + valuePlaceable.height)
            }
        }
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
    WeDivider(startIndent = d.horizontalPadding)
}
/** 字段下方的红字提示。 */
@Composable internal fun FieldError(text: String) = ErrorNote(text)
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
    FormRow(title, showDivider = true) {
        Box(Modifier.fillMaxWidth().padding(vertical = WeTheme.dimens.inputBarVerticalPadding)) {
            if (value.isEmpty() && hint != null) Text(hint, style = WeTheme.typography.listTitle, color = c.textSecondary, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(value, { onChange(it.take(80)) }, singleLine = true, textStyle = WeTheme.typography.listTitle.copy(color = c.textPrimary), cursorBrush = SolidColor(c.brand),
                keyboardOptions = KeyboardOptions(keyboardType = when { number -> KeyboardType.Decimal; ascii -> KeyboardType.Ascii; else -> KeyboardType.Text }, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth())
        }
    }
}
@Composable internal fun TemperatureForm(title: String, spec: TemperatureSpec?, onInvalid: (String, Boolean) -> Unit, note: String? = null, onChange: (TemperatureSpec) -> Unit) {
    fun label(mode: TemperatureMode?): Int = when(mode) { TemperatureMode.ADJUSTABLE -> R.string.temperature_adjustable; TemperatureMode.FIXED -> R.string.temperature_fixed; TemperatureMode.UNSUPPORTED -> R.string.temperature_unsupported; else -> R.string.rule_unknown }
    val hasInputs = spec?.mode == TemperatureMode.ADJUSTABLE || spec?.mode == TemperatureMode.FIXED
    ChoiceRow(title, stringResource(label(spec?.mode)), note, options = TemperatureMode.entries.map { stringResource(label(it)) to it }, showDivider = hasInputs) {
        onChange((spec ?: TemperatureSpec(it)).copy(mode = it))
    }
    if (spec != null) {
        val form = title
        @Composable fun number(title: String, value: Float, set: (Float) -> Unit) {
            var input by remember(spec.mode) { mutableStateOf(value.toString()) }
            val invalid = parseTemperatureInput(input) == null
            val key = "$form/$title"
            DisposableEffect(key, invalid) { onInvalid(key, invalid); onDispose { onInvalid(key, false) } }
            RuleInput(title, input, true, hint = stringResource(R.string.temperature_input_hint)) { input = it; parseTemperatureInput(it)?.let(set) }
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
@Composable internal fun ConflictDialog(refs: List<ModelReference>, onDismiss: () -> Unit, existing: List<ModelReference> = emptyList(), onConfirm: () -> Unit) {
    WeDialog(title = stringResource(R.string.conflict_title), message = stringResource(R.string.conflict_note),
        confirmText = stringResource(R.string.conflict_save), onDismiss = onDismiss, onConfirm = onConfirm) {
        Column(Modifier.fillMaxWidth()) {
            ReferenceList(refs)
            if (existing.isNotEmpty()) {
                Text(stringResource(R.string.conflict_existing), style = WeTheme.typography.small, color = WeTheme.colors.textTertiary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                ReferenceList(existing)
            }
        }
    }
}
/** 同一联系人的多个用途合成一行；服务商默认模型没有联系人，单独一行。 */
internal fun groupReferences(refs: List<ModelReference>): List<Pair<String?, List<ReferencePurpose>>> =
    refs.groupBy { it.contactId to it.contactName }.map { (key, items) -> key.second to items.map { it.purpose }.distinct() }
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
