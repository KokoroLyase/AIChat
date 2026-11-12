package isomeria.aichat.feature.replyprefs

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.GlobalMemoryRepository
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReplyPreferencesState(
    val loaded: Boolean = false,
    val preferences: ReplyPreferences = ReplyPreferences(),
    val contactsUsing: Int = 0,
    val contactsTotal: Int = 0,
    /** 其中单独设置了回复偏好的（0.7.2） */
    val contactsCustom: Int = 0,
)

@HiltViewModel
class ReplyPreferencesViewModel @Inject constructor(
    private val settings: SettingsStore,
    contacts: ContactRepository,
    private val globals: GlobalMemoryRepository,
) : ViewModel() {
    val state: StateFlow<ReplyPreferencesState> = combine(settings.replyPreferences, contacts.observeContacts()) { prefs, all ->
        ReplyPreferencesState(true, prefs, all.count { it.useReplyPreferences }, all.size, all.count { it.useReplyPreferences && it.replyPreferences != null })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReplyPreferencesState())

    fun update(transform: (ReplyPreferences) -> ReplyPreferences) = settings.setReplyPreferences(transform(settings.replyPreferences.value))

    fun setAllContacts(on: Boolean) {
        viewModelScope.launch { globals.setUseReplyPreferencesForAll(on) }
    }
}

@Composable
fun ReplyPreferencesScreen(onBack: () -> Unit, viewModel: ReplyPreferencesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReplyPreferencesContent(state, onBack, viewModel::update, viewModel::setAllContacts)
}

/**
 * 回复偏好：全局一份；联系人可以跟随它、单独设置一套（0.7.2）或不使用。改动即时保存，下一次回复起生效。
 * 最少条数 / 最短间隔不会超过最多条数 / 最长间隔：调大最少时最多跟着变大，调小最多时最少跟着变小。
 */
@Composable
fun ReplyPreferencesContent(
    state: ReplyPreferencesState,
    onBack: () -> Unit,
    onChange: ((ReplyPreferences) -> ReplyPreferences) -> Unit,
    onSetAllContacts: (Boolean) -> Unit,
) {
    val colors = WeTheme.colors
    val p = state.preferences
    var contactsSheet by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.reply_pref_title), onBack = onBack)
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FootnoteText(stringResource(R.string.reply_pref_notice))
            GroupSpacer()
            isomeria.aichat.feature.globalmemory.ValueRow(
                title = stringResource(R.string.reply_pref_contacts),
                value = stringResource(R.string.global_memory_contacts_value, state.contactsUsing, state.contactsTotal),
                onClick = { contactsSheet = true },
            )
            // 单独设置了的联系人不受这里影响（0.7.2）
            if (state.contactsCustom > 0) FootnoteText(stringResource(R.string.reply_pref_contacts_custom, state.contactsCustom))

            ReplyPreferencesFields(p, onChange)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (contactsSheet) {
        WeActionSheet(
            title = stringResource(R.string.reply_pref_contacts),
            options = listOf(
                SheetOption(stringResource(R.string.global_memory_all_on)) { onSetAllContacts(true) },
                SheetOption(stringResource(R.string.global_memory_all_off), destructive = true) { onSetAllContacts(false) },
            ),
            onDismiss = { contactsSheet = false },
        )
    }
}

/**
 * 标题在左，「− 值 +」在右；字放大后一行放不下时，控件换到标题下面一行，不裁字。
 */
@Composable
internal fun StepperRow(
    title: String,
    valueText: String,
    range: IntRange,
    value: Int,
    showDivider: Boolean = false,
    onValue: (Int) -> Unit,
) {
    val colors = WeTheme.colors
    StackingRow(title, showDivider) {
            Row(
                Modifier.drawBehind {
                    val minimum = 48.dp.toPx()
                    val height = if (size.height > minimum) size.height else 32.dp.toPx()
                    drawRoundRect(colors.pageBackground, Offset(0f, (size.height - height) / 2), Size(size.width, height), CornerRadius(6.dp.toPx()))
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton("−", stringResource(R.string.reply_pref_decrease, title), value > range.first) { onValue(value - 1) }
                Text(
                    valueText,
                    style = WeTheme.typography.listTitle,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(min = 64.dp).semantics { contentDescription = "$title $valueText" },
                )
                StepButton("+", stringResource(R.string.reply_pref_increase, title), value < range.last) { onValue(value + 1) }
            }
    }
}

/**
 * 标题在左、控件在右的一行；字放大后一行放不下时，控件换到标题下面一行（靠右），不裁字、不挤掉标题。
 * 回复偏好的加减行、主动聊天的状态行和频率行共用。
 */
@Composable
internal fun StackingRow(title: String, showDivider: Boolean = false, control: @Composable () -> Unit) {
    val colors = WeTheme.colors
    val d = WeTheme.dimens
    Layout(
        content = {
            Text(title, style = WeTheme.typography.listTitle, color = colors.textPrimary)
            Box { control() }
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .heightIn(min = d.listRowHeight)
            .drawBehind {
                if (showDivider) {
                    val y = size.height - d.dividerThickness.toPx() / 2
                    drawLine(colors.divider, Offset(d.horizontalPadding.toPx(), y), Offset(size.width, y), d.dividerThickness.toPx())
                }
            }
            .padding(horizontal = d.horizontalPadding, vertical = 4.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val control = measurables[1].measure(loose)
        val titlePlaceable = measurables[0].measure(loose)
        val gap = 8.dp.roundToPx()
        val stacked = titlePlaceable.width + control.width + gap > constraints.maxWidth
        val height = maxOf(
            constraints.minHeight,
            if (stacked) titlePlaceable.height + gap + control.height else maxOf(titlePlaceable.height, control.height),
        )
        layout(constraints.maxWidth, height) {
            if (stacked) {
                titlePlaceable.placeRelative(0, (height - titlePlaceable.height - gap - control.height) / 2)
                control.placeRelative(constraints.maxWidth - control.width, (height + titlePlaceable.height + gap - control.height) / 2)
            } else {
                titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
                control.placeRelative(constraints.maxWidth - control.width, (height - control.height) / 2)
            }
        }
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

/**
 * 回复偏好的各项（等待时间、字数、条数、去标点、间隔、恢复默认），「我 → 回复偏好」和联系人单独设置（0.7.2）共用。
 * 最少条数 / 最短间隔不会超过最多条数 / 最长间隔：调大最少时最多跟着变大，调小最多时最少跟着变小。
 */
@Composable
internal fun ReplyPreferencesFields(p: ReplyPreferences, onChange: ((ReplyPreferences) -> ReplyPreferences) -> Unit) {
    val colors = WeTheme.colors
    GroupSpacer()
    StepperRow(
        stringResource(R.string.reply_pref_wait), stringResource(R.string.reply_pref_seconds, p.waitSeconds), ReplyPreferences.WAIT_RANGE, p.waitSeconds,
    ) { v -> onChange { it.copy(waitSeconds = v) } }
    FootnoteText(stringResource(R.string.reply_pref_wait_hint))

    GroupSpacer()
    StepperRow(
        stringResource(R.string.reply_pref_length), stringResource(R.string.reply_pref_length_value, p.targetLength),
        ReplyPreferences.LENGTH_RANGE, p.targetLength, showDivider = true,
    ) { v -> onChange { it.copy(targetLength = v) } }
    StepperRow(
        stringResource(R.string.reply_pref_min_count), stringResource(R.string.reply_pref_count_value, p.minCount),
        ReplyPreferences.COUNT_RANGE, p.minCount, showDivider = true,
    ) { v -> onChange { it.copy(minCount = v, maxCount = maxOf(it.maxCount, v)) } }
    StepperRow(
        stringResource(R.string.reply_pref_max_count), stringResource(R.string.reply_pref_count_value, p.maxCount),
        ReplyPreferences.COUNT_RANGE, p.maxCount,
    ) { v -> onChange { it.copy(maxCount = v, minCount = minOf(it.minCount, v)) } }
    FootnoteText(stringResource(R.string.reply_pref_count_hint))

    GroupSpacer()
    WeListRow(
        title = stringResource(R.string.reply_pref_strip),
        showArrow = false,
        trailing = {
            WeSwitch(p.stripPunctuation, { on -> onChange { it.copy(stripPunctuation = on) } }, label = stringResource(R.string.reply_pref_strip))
        },
    )
    FootnoteText(stringResource(R.string.reply_pref_strip_hint))

    GroupSpacer()
    StepperRow(
        stringResource(R.string.reply_pref_min_interval), stringResource(R.string.reply_pref_seconds, p.minIntervalSeconds), ReplyPreferences.INTERVAL_RANGE,
        p.minIntervalSeconds, showDivider = true,
    ) { v -> onChange { it.copy(minIntervalSeconds = v, maxIntervalSeconds = maxOf(it.maxIntervalSeconds, v)) } }
    StepperRow(
        stringResource(R.string.reply_pref_max_interval), stringResource(R.string.reply_pref_seconds, p.maxIntervalSeconds), ReplyPreferences.INTERVAL_RANGE,
        p.maxIntervalSeconds,
    ) { v -> onChange { it.copy(maxIntervalSeconds = v, minIntervalSeconds = minOf(it.minIntervalSeconds, v)) } }
    FootnoteText(stringResource(R.string.reply_pref_interval_hint))

    GroupSpacer()
    WeListRow(
        title = stringResource(R.string.reply_pref_reset),
        titleColor = colors.link,
        showArrow = false,
        enabled = p != ReplyPreferences(),
        onClick = { onChange { ReplyPreferences() } },
    )
}
