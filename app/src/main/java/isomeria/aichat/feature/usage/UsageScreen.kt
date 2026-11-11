package isomeria.aichat.feature.usage

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.core.UsageRange
import isomeria.aichat.core.UsageStats
import isomeria.aichat.core.UsageTotal
import isomeria.aichat.data.db.UsageDaily
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.UsageRepository
import isomeria.aichat.navigation.NO_USAGE_FILTER
import isomeria.aichat.navigation.UsageDetailRoute
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeSegmented
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import javax.inject.Inject

enum class UsageGrouping { BY_DAY, BY_CONTACT }

/** 用量列表的一行。[title] 为 null 时是已删除的联系人。 */
data class UsageRowItem(val key: Long, val title: String?, val total: UsageTotal)

data class UsageState(
    val loaded: Boolean = false,
    val range: UsageRange = UsageRange.WEEK,
    val grouping: UsageGrouping = UsageGrouping.BY_DAY,
    val total: UsageTotal = UsageTotal(0),
    val rows: List<UsageRowItem> = emptyList(),
)

/** 日期的显示文字；用量表里存的是 epochDay。 */
private fun ChatTimeFormatter.dayLabel(day: Long): String {
    val zone = ZoneId.systemDefault()
    val time = java.time.LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
    return forDate(time, Instant.now().toEpochMilli())
}

@HiltViewModel
class UsageViewModel @Inject constructor(
    usage: UsageRepository,
    contacts: ContactRepository,
    private val formatter: ChatTimeFormatter,
) : ViewModel() {
    private val range = MutableStateFlow(UsageRange.WEEK)
    private val grouping = MutableStateFlow(UsageGrouping.BY_DAY)

    val state: StateFlow<UsageState> = combine(
        usage.observeAll(),
        contacts.observeContacts(),
        range,
        grouping,
    ) { rows, contactList, currentRange, currentGrouping ->
        val names = contactList.associate { it.id to it.name }
        val today = UsageStats.epochDay(System.currentTimeMillis())
        val visible = UsageStats.inRange(rows, currentRange, today)
        UsageState(
            loaded = true,
            range = currentRange,
            grouping = currentGrouping,
            total = UsageStats.total(visible),
            rows = when (currentGrouping) {
                UsageGrouping.BY_DAY -> UsageStats.byDay(visible).map { UsageRowItem(it.key, formatter.dayLabel(it.key), it) }
                UsageGrouping.BY_CONTACT -> UsageStats.byContact(visible).map { UsageRowItem(it.key, names[it.key], it) }
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageState())

    fun setRange(value: UsageRange) {
        range.value = value
    }

    fun setGrouping(value: UsageGrouping) {
        grouping.value = value
    }
}

/** 设置 → 用量统计：近 7 天 / 近 30 天 / 全部，按天或按联系人合计，点进去看明细。 */
@Composable
fun UsageScreen(
    onBack: () -> Unit,
    onOpenDay: (Long) -> Unit,
    onOpenContact: (Long) -> Unit,
    viewModel: UsageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    UsageContent(state, onBack, viewModel::setRange, viewModel::setGrouping) { row ->
        if (state.grouping == UsageGrouping.BY_DAY) onOpenDay(row.key) else onOpenContact(row.key)
    }
}

@Composable
fun UsageContent(
    state: UsageState,
    onBack: () -> Unit,
    onRange: (UsageRange) -> Unit,
    onGrouping: (UsageGrouping) -> Unit,
    onOpen: (UsageRowItem) -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.usage_title), onBack = onBack)
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            Column(
                Modifier.fillMaxWidth().background(colors.listBackground).padding(dimens.horizontalPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WeSegmented(
                    options = listOf(
                        UsageRange.WEEK to stringResource(R.string.usage_range_week),
                        UsageRange.MONTH to stringResource(R.string.usage_range_month),
                        UsageRange.ALL to stringResource(R.string.usage_range_all),
                    ),
                    selected = state.range,
                    onSelect = onRange,
                )
                Text(
                    stringResource(R.string.usage_total_tokens, number(state.total.totalTokens)),
                    style = WeTheme.typography.contactName,
                    color = colors.textPrimary,
                )
                Text(
                    usageDetail(state.total),
                    style = WeTheme.typography.small,
                    color = colors.textTertiary,
                )
                if (state.total.hasVoice) {
                    Text(voiceDetail(state.total), style = WeTheme.typography.small, color = colors.textTertiary)
                }
                WeSegmented(
                    options = listOf(
                        UsageGrouping.BY_DAY to stringResource(R.string.usage_by_day),
                        UsageGrouping.BY_CONTACT to stringResource(R.string.usage_by_contact),
                    ),
                    selected = state.grouping,
                    onSelect = onGrouping,
                )
            }
            GroupSpacer()
            UsageRows(state.rows, onOpen)
            FootnoteText(stringResource(R.string.usage_hint))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun UsageRows(rows: List<UsageRowItem>, onOpen: ((UsageRowItem) -> Unit)?) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    if (rows.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().background(colors.listBackground).padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.usage_empty), style = WeTheme.typography.small, color = colors.textTertiary)
        }
        return
    }
    rows.forEachIndexed { index, row ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.listBackground)
                .then(if (onOpen != null) Modifier.clickable { onOpen(row) } else Modifier)
                .padding(horizontal = dimens.horizontalPadding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        row.title ?: stringResource(R.string.usage_deleted_contact),
                        style = WeTheme.typography.listTitle,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(number(row.total.totalTokens), style = WeTheme.typography.listTitle, color = colors.textPrimary)
                }
                Text(usageDetail(row.total), style = WeTheme.typography.listTime, color = colors.textTertiary)
                if (row.total.hasVoice) Text(voiceDetail(row.total), style = WeTheme.typography.listTime, color = colors.textTertiary)
            }
            if (onOpen != null) {
                Icon(
                    WeIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.arrow,
                    modifier = Modifier.padding(start = 4.dp).size(18.dp),
                )
            }
        }
        if (index != rows.lastIndex) WeDivider(startIndent = dimens.horizontalPadding)
    }
}

@Composable
private fun usageDetail(total: UsageTotal): String {
    val input = number(total.promptTokens)
    val output = number(total.completionTokens)
    return when {
        total.memoryTokens > 0 && total.proactiveTokens > 0 ->
            stringResource(R.string.usage_detail_memory_proactive, input, output, number(total.memoryTokens), number(total.proactiveTokens))
        total.memoryTokens > 0 -> stringResource(R.string.usage_detail_memory, input, output, number(total.memoryTokens))
        total.proactiveTokens > 0 -> stringResource(R.string.usage_detail_proactive, input, output, number(total.proactiveTokens))
        else -> stringResource(R.string.usage_detail, input, output)
    }
}

/** 语音合成和识别（0.7.0）：服务商按字数或时长计费，单独一行，不算进 token */
@Composable
private fun voiceDetail(total: UsageTotal): String {
    val parts = buildList {
        if (total.ttsChars > 0 || total.ttsSeconds > 0) add(stringResource(R.string.usage_detail_tts, number(total.ttsChars), number(total.ttsSeconds)))
        if (total.asrSeconds > 0) add(stringResource(R.string.usage_detail_asr, number(total.asrSeconds)))
    }
    return parts.joinToString(stringResource(R.string.usage_detail_separator))
}

private fun number(value: Long): String = String.format(Locale.ROOT, "%,d", value)

data class UsageDetailState(val loaded: Boolean = false, val title: String? = null, val rows: List<UsageRowItem> = emptyList())

@HiltViewModel
class UsageDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    usage: UsageRepository,
    contacts: ContactRepository,
    private val formatter: ChatTimeFormatter,
) : ViewModel() {
    private val route = savedState.toRoute<UsageDetailRoute>()

    val state: StateFlow<UsageDetailState> = combine(usage.observeAll(), contacts.observeContacts()) { rows, contactList ->
        val names = contactList.associate { it.id to it.name }
        if (route.day != NO_USAGE_FILTER) {
            detailOfDay(rows.filter { it.day == route.day }, names)
        } else {
            detailOfContact(rows.filter { it.contactId == route.contactId }, names)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageDetailState())

    private fun detailOfDay(rows: List<UsageDaily>, names: Map<Long, String>) = UsageDetailState(
        loaded = true,
        title = formatter.dayLabel(route.day),
        rows = UsageStats.byContact(rows).map { UsageRowItem(it.key, names[it.key], it) },
    )

    private fun detailOfContact(rows: List<UsageDaily>, names: Map<Long, String>) = UsageDetailState(
        loaded = true,
        title = names[route.contactId],
        rows = UsageStats.byDay(rows).map { UsageRowItem(it.key, formatter.dayLabel(it.key), it) },
    )
}

/** 某一天各联系人的用量，或某个联系人每天的用量。 */
@Composable
fun UsageDetailScreen(onBack: () -> Unit, viewModel: UsageDetailViewModel = hiltViewModel()) {
    val colors = WeTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = state.title ?: stringResource(R.string.usage_deleted_contact), onBack = onBack)
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            UsageRows(state.rows, null)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 截图用的示例数据：用了语音（0.7.0） */
internal val previewVoiceUsageState = UsageState(
    loaded = true,
    total = UsageTotal(0, promptTokens = 58_320, completionTokens = 9_140, memoryTokens = 1_850, requests = 86, ttsChars = 1_284, ttsSeconds = 315, asrSeconds = 142),
    rows = listOf(
        UsageRowItem(3, "今天", UsageTotal(3, promptTokens = 12_400, completionTokens = 2_100, requests = 18, ttsChars = 1_284, ttsSeconds = 315, asrSeconds = 142)),
        UsageRowItem(2, "昨天", UsageTotal(2, promptTokens = 21_500, completionTokens = 3_300, memoryTokens = 1_850, requests = 31)),
        UsageRowItem(1, "10月30日", UsageTotal(1, asrSeconds = 12)),
    ),
)

/** 截图用的示例数据 */
internal val previewUsageState = UsageState(
    loaded = true,
    total = UsageTotal(0, promptTokens = 58_320, completionTokens = 9_140, memoryTokens = 1_850, requests = 86),
    rows = listOf(
        UsageRowItem(3, "今天", UsageTotal(3, promptTokens = 12_400, completionTokens = 2_100, requests = 18)),
        UsageRowItem(2, "昨天", UsageTotal(2, promptTokens = 21_500, completionTokens = 3_300, memoryTokens = 1_850, requests = 31)),
        UsageRowItem(1, "10月30日", UsageTotal(1, promptTokens = 24_420, completionTokens = 3_740, requests = 37)),
    ),
)
