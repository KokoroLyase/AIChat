package isomeria.aichat.feature.contactedit

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import isomeria.aichat.R
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.AvatarCropDialog
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/** 联系人编辑：头像、名称、模型、思考强度、温度、系统提示词、上下文条数、分条显示。 */
@Composable
fun ContactEditScreen(
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
    onDeleted: () -> Unit,
    viewModel: ContactEditViewModel = hiltViewModel(),
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val state by viewModel.state.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val form = state.form
    var showAvatarSheet by remember { mutableStateOf(false) }
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
                    color = colors.danger,
                    modifier = Modifier.padding(horizontal = dimens.horizontalPadding, vertical = 6.dp),
                )
            }

            GroupSpacer()
            val modelName = models.firstOrNull { it.modelId == form.model }?.displayName ?: form.model
            WeListRow(
                title = stringResource(R.string.contact_model),
                value = modelName,
                onClick = { showModelSheet = true },
                showDivider = true,
            )
            FormRow(stringResource(R.string.contact_thinking), showDivider = true) {
                Segmented(
                    options = listOf(
                        ThinkingLevel.OFF to stringResource(R.string.thinking_off),
                        ThinkingLevel.HIGH to stringResource(R.string.thinking_high),
                        ThinkingLevel.MAX to stringResource(R.string.thinking_max),
                    ),
                    selected = form.thinking,
                    onSelect = { level -> viewModel.update { it.copy(thinking = level) } },
                )
            }
            TemperatureRow(
                value = form.temperature,
                enabled = form.thinking == ThinkingLevel.OFF,
                onChange = { t -> viewModel.update { it.copy(temperature = t) } },
            )

            GroupSpacer()
            Column(Modifier.fillMaxWidth().background(colors.listBackground).padding(dimens.horizontalPadding)) {
                Text(stringResource(R.string.contact_prompt), style = WeTheme.typography.listTitle, color = colors.textPrimary)
                Spacer(Modifier.height(10.dp))
                BasicTextField(
                    value = form.systemPrompt,
                    onValueChange = { text -> viewModel.update { it.copy(systemPrompt = text) } },
                    textStyle = WeTheme.typography.small.copy(color = colors.textPrimary, lineHeight = WeTheme.typography.thinking.lineHeight),
                    cursorBrush = SolidColor(colors.brand),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp)
                        .clip(RoundedCornerShape(dimens.inputCorner))
                        .background(colors.pageBackground)
                        .padding(10.dp),
                )
            }
            FootnoteText(stringResource(R.string.contact_prompt_hint))

            GroupSpacer()
            ContextCountRow(form.contextCount) { n -> viewModel.update { it.copy(contextCount = n) } }
            WeListRow(
                title = stringResource(R.string.contact_split),
                showArrow = false,
                trailing = {
                    WeSwitch(form.splitReplies, { on -> viewModel.update { it.copy(splitReplies = on) } })
                },
            )
            FootnoteText(stringResource(R.string.contact_split_hint))

            if (!state.isNew) {
                GroupSpacer()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.listBackground)
                        .clickable { showDelete = true }
                        .height(dimens.listRowHeight),
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
    if (showModelSheet) {
        ModelSheet(models, form.model, onPick = { m -> viewModel.update { it.copy(model = m.modelId) } }) {
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

@Composable
private fun ModelSheet(models: List<ProviderModel>, current: String, onPick: (ProviderModel) -> Unit, onDismiss: () -> Unit) {
    val imageLabel = stringResource(R.string.contact_model_image)
    WeActionSheet(
        title = stringResource(R.string.contact_model_pick),
        options = models.map { model ->
            SheetOption(
                label = model.displayName,
                subtitle = if (model.supportsImage) "${model.modelId} · $imageLabel" else model.modelId,
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
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = WeTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.pageBackground)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (active) colors.listBackground else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = WeTheme.typography.small,
                    color = if (active) colors.brand else colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun TemperatureRow(value: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .padding(horizontal = dimens.horizontalPadding, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.contact_temperature),
                style = WeTheme.typography.listTitle,
                color = if (enabled) colors.textPrimary else colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                String.format(Locale.ROOT, "%.1f", value),
                style = WeTheme.typography.listTitle,
                color = if (enabled) colors.textTertiary else colors.textSecondary,
            )
        }
        Slider(
            value = value,
            onValueChange = { onChange((it * 10).roundToInt() / 10f) },
            valueRange = 0f..2f,
            steps = 19,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = colors.brand,
                activeTrackColor = colors.brand,
                inactiveTrackColor = colors.switchTrackOff,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
                disabledThumbColor = colors.disabled,
                disabledActiveTrackColor = colors.disabled,
                disabledInactiveTrackColor = colors.switchTrackOff,
            ),
        )
        Text(
            stringResource(if (enabled) R.string.contact_temperature_hint else R.string.contact_temperature_disabled),
            style = WeTheme.typography.listTime,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun ContextCountRow(value: Int, onChange: (Int) -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .padding(horizontal = dimens.horizontalPadding, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.contact_context_count),
                style = WeTheme.typography.listTitle,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Stepper(value, onChange)
        }
        Text(
            stringResource(R.string.contact_context_count_hint, value),
            style = WeTheme.typography.listTime,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Stepper(value: Int, onChange: (Int) -> Unit) {
    val colors = WeTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(colors.pageBackground),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", enabled = value > MIN_CONTEXT) { onChange((value - stepFor(value - 1)).coerceAtLeast(MIN_CONTEXT)) }
        Text(
            value.toString(),
            style = WeTheme.typography.listTitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(width = 44.dp, height = 32.dp).padding(top = 4.dp),
        )
        StepButton("+", enabled = value < MAX_CONTEXT) { onChange((value + stepFor(value)).coerceAtMost(MAX_CONTEXT)) }
    }
}

/** 20 条以下每次加减 2，以上每次 10 */
private fun stepFor(value: Int) = if (value < 20) 2 else 10

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = WeTheme.colors
    Box(
        Modifier
            .size(36.dp, 32.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = WeTheme.typography.listTitle, color = if (enabled) colors.textPrimary else colors.disabled)
    }
}

private const val MIN_CONTEXT = 2
private const val MAX_CONTEXT = 100
