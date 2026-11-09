package isomeria.aichat.feature.contactedit

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import isomeria.aichat.R
import isomeria.aichat.core.ContextBudgetSteps
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.feature.settings.ConfigActionRow
import isomeria.aichat.ui.components.ModelParameterControls
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.AvatarCropDialog
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.ErrorNote
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSegmented
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.launch

/** 联系人编辑：头像、名称、服务商、模型、思考强度、温度、系统提示词、上下文预算、全局记忆、回复偏好、分条显示、自动记忆。 */
@Composable
fun ContactEditScreen(
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
    onDeleted: () -> Unit,
    onOpenProvider: (Long) -> Unit = {},
    onOpenModel: (Long, String) -> Unit = { _, _ -> },
    onOpenPrompt: () -> Unit = {},
    viewModel: ContactEditViewModel = hiltViewModel(),
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val state by viewModel.state.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val providerList by viewModel.providerList.collectAsStateWithLifecycle()
    val promptUsage by viewModel.promptUsage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val form = state.form
    var showAvatarSheet by remember { mutableStateOf(false) }
    var showProviderSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickAvatar(uri)
    }

    fun tryBack() {
        if (state.dirty) showDiscard = true else onBack()
    }
    BackHandler(enabled = state.dirty) { showDiscard = true }
    ImeBackHandler()

    fun save() {
        scope.launch {
            val id = viewModel.save() ?: return@launch
            if (state.isNew) onCreated(id) else onBack()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(
            title = stringResource(if (state.isNew) R.string.contact_edit_title_new else R.string.contact_edit_title),
            onBack = ::tryBack,
        ) {
            WeSmallButton(stringResource(R.string.action_save), ::save, enabled = state.loaded)
        }
        if (!state.loaded) return@Column

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.contact_avatar),
                onClick = { showAvatarSheet = true },
                showDivider = true,
                trailing = {
                    Avatar(
                        form.avatarPath,
                        form.name.ifBlank { "?" },
                        form.avatarColor,
                        dimens.listRowAvatar,
                    )
                },
            )
            FormRow(stringResource(R.string.contact_name)) {
                InlineField(
                    value = form.name,
                    onValueChange = viewModel::setName,
                    hint = stringResource(R.string.contact_name_hint),
                )
            }
            if (state.showNameError) {
                Text(
                    stringResource(R.string.contact_name_required),
                    style = WeTheme.typography.small,
                    color = colors.dangerText,
                    modifier = Modifier.padding(horizontal = dimens.horizontalPadding, vertical = 6.dp),
                )
            }

            GroupSpacer()
            val provider = providerList.firstOrNull { it.id == form.providerId }
            ConfigActionRow(
                title = stringResource(R.string.contact_provider),
                value = provider?.name.orEmpty(),
                showDivider = true,
                onClick = { showProviderSheet = true },
            )
            val currentModel = models.firstOrNull { it.modelId == form.model }
            val modelPending = currentModel != null && !ModelRules.complete(currentModel)
            ConfigActionRow(
                title = stringResource(R.string.contact_model),
                value = currentModel?.displayName ?: form.model.ifEmpty { stringResource(R.string.llm_no_model) },
                showDivider = (currentModel != null && !modelPending) || provider?.compatibility == "LEGACY",
                onClick = { showModelSheet = true },
            )
            if (state.showModelError) {
                ErrorNote(stringResource(if (form.model.isBlank()) R.string.contact_model_required else R.string.contact_model_pending_required))
            }
            // 确实没有模型时不展示虚构的强度和温度；旧服务商（LEGACY）没有模型也沿用原有选项
            if (currentModel == null && provider?.compatibility != "LEGACY") {
                FootnoteText(stringResource(if (form.model.isBlank()) R.string.contact_model_pick_first else R.string.contact_model_unavailable))
            } else if (currentModel != null && modelPending) {
                // 模型没配置好时档位和温度都无从选择，直接带用户去配置
                if (!state.showModelError) ErrorNote(stringResource(R.string.contact_model_pending))
                WeListRow(stringResource(R.string.action_configure_model), onClick = { onOpenModel(form.providerId, currentModel.modelId) })
            } else {
                ModelParameterControls(provider, currentModel, form.thinkingOption ?: ModelRules.legacyOption(form.thinking), form.temperature,
                    onOption = { value -> viewModel.update { it.copy(thinkingOption = value) } },
                    onTemperature = { value -> viewModel.update { it.copy(temperature = value) } })
            }

            GroupSpacer()
            PromptRow(form.systemPrompt, promptUsage, onOpenPrompt)

            GroupSpacer()
            ContextBudgetRow(form.contextTokens, viewModel::setContextValue, state.contextInput,
                viewModel::setContextInput, viewModel::finishContextInput, state.contextFocusRequest)
            GlobalSwitchRows(form, viewModel::update)
            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.contact_auto_memory),
                showArrow = false,
                trailing = {
                    WeSwitch(form.autoMemory, { on -> viewModel.update { it.copy(autoMemory = on) } }, label = stringResource(R.string.contact_auto_memory))
                },
            )
            FootnoteText(stringResource(R.string.contact_auto_memory_hint))

            if (!state.isNew) {
                GroupSpacer()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.listBackground)
                        .clickable { showDelete = true }
                        .heightIn(min = dimens.listRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.contact_delete), style = WeTheme.typography.listTitle, color = colors.danger)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showAvatarSheet) {
        WeActionSheet(
            options = buildList {
                add(
                    SheetOption(stringResource(R.string.contact_avatar_pick)) {
                        pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                if (form.avatarPath != null) {
                    add(SheetOption(stringResource(R.string.contact_avatar_reset)) { viewModel.resetAvatar() })
                }
            },
            onDismiss = { showAvatarSheet = false },
        )
    }
    if (showProviderSheet) {
        ProviderSheet(providerList, form.providerId, onPick = viewModel::selectProvider) {
            showProviderSheet = false
        }
    }
    if (showModelSheet) {
        ModelSheet(models.filter { m -> providerList.firstOrNull { it.id == form.providerId }?.let { ModelRules.usable(it, m) } == true }, form.model, onPick = viewModel::selectModel, onConfigure = { onOpenProvider(form.providerId) }) {
            showModelSheet = false
        }
    }
    state.cropSource?.let { bitmap ->
        AvatarCropDialog(bitmap, onDismiss = viewModel::cancelCrop, onCropped = viewModel::applyCrop)
    }
    if (showDiscard) {
        WeDialog(
            message = stringResource(R.string.contact_discard_title),
            confirmText = stringResource(R.string.contact_discard),
            destructive = true,
            onDismiss = { showDiscard = false },
            onConfirm = {
                showDiscard = false
                onBack()
            },
        )
    }
    if (showDelete) {
        WeDialog(
            message = stringResource(R.string.contact_delete_confirm, form.name),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { showDelete = false },
            onConfirm = {
                showDelete = false
                viewModel.delete(onDeleted)
            },
        )
    }
}

/**
 * 0.5.0 的两个开关和分条显示：「使用全局记忆」「使用回复偏好」默认开启；
 * 开启回复偏好时由它拆条，分条显示的说明随之改变。
 */
@Composable
internal fun GlobalSwitchRows(form: ContactForm, update: ((ContactForm) -> ContactForm) -> Unit) {
    GroupSpacer()
    WeListRow(
        title = stringResource(R.string.contact_use_global_memory),
        showArrow = false,
        trailing = {
            WeSwitch(form.useGlobalMemory, { on -> update { it.copy(useGlobalMemory = on) } }, label = stringResource(R.string.contact_use_global_memory))
        },
    )
    FootnoteText(stringResource(R.string.contact_use_global_memory_hint))
    GroupSpacer()
    WeListRow(
        title = stringResource(R.string.contact_use_reply_preferences),
        showArrow = false,
        showDivider = true,
        trailing = {
            WeSwitch(form.useReplyPreferences, { on -> update { it.copy(useReplyPreferences = on) } }, label = stringResource(R.string.contact_use_reply_preferences))
        },
    )
    WeListRow(
        title = stringResource(R.string.contact_split),
        showArrow = false,
        trailing = {
            WeSwitch(form.splitReplies, { on -> update { it.copy(splitReplies = on) } }, label = stringResource(R.string.contact_split))
        },
    )
    FootnoteText(stringResource(R.string.contact_use_reply_preferences_hint) + stringResource(if (form.useReplyPreferences) R.string.contact_split_hint_overridden else R.string.contact_split_hint))
}

/**
 * 「系统提示词」：提示词可能很长，单独一页编辑；这里只显示开头两行，没写时显示「未填写」。
 * 下面一行是系统提示词合计占用（含全局记忆、记忆、回复偏好要求），超过上下文上限一半时改成提醒。
 */
@Composable
internal fun PromptRow(prompt: String, usage: PromptUsage? = null, onClick: () -> Unit) {
    val preview = promptPreview(prompt)
    DetailEntryRow(
        title = stringResource(R.string.contact_prompt),
        detail = preview ?: stringResource(R.string.contact_prompt_empty),
        detailMaxLines = 2,
        onClick = onClick,
    )
    PromptUsageNote(usage)
}

@Composable
internal fun PromptUsageNote(usage: PromptUsage?) {
    if (usage == null || usage.tokens == 0) return
    if (usage.overHalf) ErrorNote(stringResource(R.string.contact_prompt_usage_warning, usage.tokens))
    else FootnoteText(stringResource(R.string.contact_prompt_usage, usage.tokens, usage.percent))
}

@Composable
private fun ProviderSheet(
    providers: List<ProviderConfig>,
    current: Long,
    onPick: (ProviderConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val noKey = stringResource(R.string.llm_api_key_missing)
    WeActionSheet(
        title = stringResource(R.string.contact_provider_pick),
        options = providers.map { provider ->
            SheetOption(
                label = provider.name,
                subtitle = if (provider.apiKeyEncrypted.isNullOrEmpty()) noKey else null,
                selected = provider.id == current,
            ) { onPick(provider) }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun ModelSheet(models: List<ProviderModel>, current: String, onPick: (ProviderModel) -> Unit, onConfigure: () -> Unit, onDismiss: () -> Unit) {
    val choices = models.filter(ModelRules::complete)
    if (choices.isEmpty()) {
        WeActionSheet(
            title = stringResource(R.string.contact_model_empty),
            options = listOf(SheetOption(stringResource(R.string.action_configure_model), onClick = onConfigure)),
            onDismiss = onDismiss,
        )
        return
    }
    val imageLabel = stringResource(R.string.contact_model_image)
    val thinkingLabel = stringResource(R.string.contact_model_thinking)
    WeActionSheet(
        title = stringResource(R.string.contact_model_pick),
        options = choices.map { model ->
            SheetOption(
                label = model.displayName,
                subtitle = buildList {
                    add(model.modelId)
                    if (model.supportsImage) add(imageLabel)
                    if (model.supportsThinking) add(thinkingLabel)
                }.joinToString(" · "),
                selected = model.modelId == current,
            ) { onPick(model) }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun InlineField(value: String, onValueChange: (String) -> Unit, hint: String) {
    val colors = WeTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, style = WeTheme.typography.listTitle, color = colors.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.brand),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ContextBudgetRow(value: Int, onChange: (Int) -> Unit,
    input: String = value.toString(), onInput: (String) -> Unit = { parseContextInput(it)?.let(onChange) },
    onFinish: () -> Unit = {}, focusRequest: Int = 0,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .padding(horizontal = dimens.horizontalPadding, vertical = 12.dp),
    ) {
        Layout(content = {
            Text(
                stringResource(R.string.contact_context_tokens),
                style = WeTheme.typography.listTitle,
                color = colors.textPrimary,
            )
            Stepper(input, onInput, onFinish, focusRequest, onChange)
        }) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val title = measurables[0].measure(loose)
            val control = measurables[1].measure(loose)
            val gap = 8.dp.roundToPx()
            val stacked = title.width + control.width + gap > constraints.maxWidth
            val height = if (stacked) title.height + gap + control.height else maxOf(title.height, control.height)
            layout(constraints.maxWidth, height) {
                title.placeRelative(0, if (stacked) 0 else (height - title.height) / 2)
                control.placeRelative(constraints.maxWidth - control.width, if (stacked) title.height + gap else (height - control.height) / 2)
            }
        }
        if (parseContextInput(input) == null) ErrorNote(stringResource(R.string.contact_context_invalid))
        Text(
            stringResource(R.string.contact_context_tokens_hint),
            style = WeTheme.typography.listTime,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Stepper(input: String, onInput: (String) -> Unit, onFinish: () -> Unit, focusRequest: Int, onChange: (Int) -> Unit) {
    val colors = WeTheme.colors
    val value = parseContextInput(input)
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    val manager = LocalFocusManager.current
    var field by remember { mutableStateOf(TextFieldValue(input)) }
    var focused by remember { mutableStateOf(false) }
    // 首次触摸的光标定位会晚于焦点回调；等该帧结束再全选。
    LaunchedEffect(focused) {
        if (focused) {
            androidx.compose.runtime.withFrameNanos { }
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }
    val label = stringResource(R.string.contact_context_tokens)
    val style = WeTheme.typography.listTitle.copy(color = colors.textPrimary, textAlign = TextAlign.Center)
    val measured = rememberTextMeasurer().measure("200000", style).size.width
    val fieldWidth = with(LocalDensity.current) { measured.toDp() } + 16.dp
    LaunchedEffect(input) { if (field.text != input) field = TextFieldValue(input, TextRange(input.length)) }
    LaunchedEffect(focusRequest) { if (focusRequest > 0) { bring.bringIntoView(); focus.requestFocus() } }
    Row(
        // 按钮的点击区是 48dp，看到的灰底仍是原来的高度；字放大后灰底跟着变高
        Modifier.drawBehind {
            val minimum = 48.dp.toPx()
            val height = if (size.height > minimum) size.height else 32.dp.toPx()
            drawRoundRect(colors.pageBackground, Offset(0f, (size.height - height) / 2), Size(size.width, height), CornerRadius(6.dp.toPx()))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", stringResource(R.string.contact_context_decrease), enabled = value != null && value > Contact.MIN_CONTEXT_TOKENS) { value?.let { onChange(ContextBudgetSteps.decrease(it)) } }
        BasicTextField(
            value = field,
            onValueChange = { next -> if (next.text.all { it in '0'..'9' }) { field = next; onInput(next.text) } },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(colors.brand),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onFinish(); if (value != null) manager.clearFocus() }),
            modifier = Modifier.widthIn(min = 48.dp, max = fieldWidth).heightIn(min = 48.dp)
                .focusRequester(focus).bringIntoViewRequester(bring)
                .onFocusChanged { focused = it.isFocused; if (!it.isFocused) onFinish() }
                .semantics { contentDescription = label },
            decorationBox = { inner -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) { inner() } },
        )
        StepButton("+", stringResource(R.string.contact_context_increase), enabled = value != null && value < Contact.MAX_CONTEXT_TOKENS) { value?.let { onChange(ContextBudgetSteps.increase(it)) } }
    }
}

@Composable
private fun StepButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = WeTheme.colors
    Box(
        Modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = WeTheme.typography.listTitle, color = if (enabled) colors.textPrimary else colors.disabled)
    }
}
