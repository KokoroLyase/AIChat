package isomeria.aichat.feature.proactive

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.core.Clock
import isomeria.aichat.core.ProactiveBlock
import isomeria.aichat.core.ProactivePolicy
import isomeria.aichat.core.ProactiveQuota
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.UsageStats
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.reply.ProactiveEngine
import isomeria.aichat.data.reply.ProactiveScheduler
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.feature.contactedit.DetailEntryRow
import isomeria.aichat.feature.replyprefs.StackingRow
import isomeria.aichat.feature.replyprefs.StepperRow
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 一个开启了主动聊天的联系人现在的状态。 */
data class ProactiveContactStatus(
    val id: Long,
    val name: String,
    val frequency: ProactiveFrequency,
    /** 现在会被什么挡住；null 表示正常 */
    val block: ProactiveBlock? = null,
    /** 最近一次为它发起主动请求的时间，已排好版；没有时为 null */
    val lastAttempt: String? = null,
    /** 最近的一个约定提醒，已排好版 */
    val nextReminder: String? = null,
)

data class ProactiveScreenState(
    val loaded: Boolean = false,
    val settings: ProactiveSettings = ProactiveSettings(),
    val usedToday: Int = 0,
    /** 最近一次检查的时间，已排好版；还没运行过时为 null */
    val lastRun: String? = null,
    /** 周期检查是否已经注册 */
    val scheduled: Boolean = false,
    /** 新消息通知开关和系统权限都开着 */
    val canNotify: Boolean = true,
    val contacts: List<ProactiveContactStatus> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProactiveViewModel @Inject constructor(
    private val settings: SettingsStore,
    private val db: AppDatabase,
    private val engine: ProactiveEngine,
    scheduler: ProactiveScheduler,
    private val notifier: ReplyNotifier,
    private val formatter: ChatTimeFormatter,
    private val clock: Clock,
) : ViewModel() {
    /** 暂停、正在等回复这些状态不在被观察的表里：页面打开时隔一会儿重算一次，回到页面时立刻重算 */
    private val refresh = MutableStateFlow(0)
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(REFRESH_MILLIS)
        }
    }

    val state: StateFlow<ProactiveScreenState> = combine(
        settings.proactiveSettings,
        db.contactDao().observeProactiveContacts(),
        db.proactiveDao().observeState(),
        db.proactiveDao().observeReminders(),
        combine(scheduler.observeCheckScheduled(), settings.notifyEnabled, refresh, ticker) { scheduled, notify, _, _ -> scheduled to notify },
    ) { limits, contacts, state, reminders, (scheduled, notify) ->
        val now = clock.now()
        ProactiveScreenState(
            loaded = true,
            settings = limits,
            usedToday = ProactiveQuota(state?.day ?: 0, state?.used ?: 0).usedOn(UsageStats.epochDay(now)),
            lastRun = state?.lastRunAt?.takeIf { it > 0 }?.let { formatter.forChat(it, now) },
            scheduled = scheduled,
            canNotify = notify && notifier.canNotify(),
            contacts = contacts.map { contact ->
                ProactiveContactStatus(
                    id = contact.id,
                    name = contact.name,
                    frequency = contact.proactiveFrequency,
                    block = engine.blockNow(contact),
                    lastAttempt = db.conversationDao().get(contact.id)?.lastProactiveAt?.takeIf { it > 0 }?.let { formatter.forChat(it, now) },
                    nextReminder = reminders.firstOrNull { it.contactId == contact.id }?.let { formatter.forChat(it.dueAt, now) },
                )
            },
        )
    }.mapLatest { it }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProactiveScreenState())

    fun update(transform: (ProactiveSettings) -> ProactiveSettings) = settings.setProactiveSettings(transform(settings.proactiveSettings.value))

    fun refresh() {
        refresh.value++
    }

    private companion object {
        const val REFRESH_MILLIS = 15_000L
    }
}

@Composable
fun ProactiveScreen(onBack: () -> Unit, onOpenContact: (Long) -> Unit, viewModel: ProactiveViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    ProactiveContent(
        state = state,
        onBack = onBack,
        onChange = viewModel::update,
        onOpenContact = onOpenContact,
        onOpenNotificationSettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        },
    )
}

/**
 * 我 → 主动聊天（0.6.0）：全局的免打扰时段和每日调用上限，以及运行状态——今日已用、最近检查时间、
 * 后台任务是否注册、每个开启的联系人是否暂停及原因。没有这些，用户分不清「没触发」和「被系统限制了」。
 * 开关和频率在各联系人的「朋友资料」里，默认都是关闭。改动即时保存。
 */
@Composable
fun ProactiveContent(
    state: ProactiveScreenState,
    onBack: () -> Unit,
    onChange: ((ProactiveSettings) -> ProactiveSettings) -> Unit,
    onOpenContact: (Long) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val colors = WeTheme.colors
    val limits = state.settings
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.proactive_title), onBack = onBack)
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FootnoteText(stringResource(R.string.proactive_notice))

            SectionHeader(stringResource(R.string.proactive_status))
            StatusRow(stringResource(R.string.proactive_used_today), stringResource(R.string.proactive_used_value, state.usedToday, limits.dailyLimit), showDivider = true)
            StatusRow(stringResource(R.string.proactive_last_run), state.lastRun ?: stringResource(R.string.proactive_never_run), showDivider = true)
            StatusRow(
                stringResource(R.string.proactive_task),
                stringResource(if (state.scheduled) R.string.proactive_task_on else R.string.proactive_task_off),
                showDivider = !state.canNotify,
            )
            if (!state.canNotify) {
                WeListRow(
                    title = stringResource(R.string.proactive_notify_off),
                    titleMaxLines = 3,
                    titleColor = colors.danger,
                    onClick = onOpenNotificationSettings,
                )
            }
            FootnoteText(stringResource(R.string.proactive_status_hint))

            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.proactive_quiet),
                showArrow = false,
                showDivider = limits.quietEnabled,
                trailing = {
                    WeSwitch(limits.quietEnabled, { on -> onChange { it.copy(quietEnabled = on) } }, label = stringResource(R.string.proactive_quiet))
                },
            )
            if (limits.quietEnabled) {
                TimeStepper(stringResource(R.string.proactive_quiet_start), limits.quietStartMinutes, showDivider = true) { v -> onChange { it.copy(quietStartMinutes = v) } }
                TimeStepper(stringResource(R.string.proactive_quiet_end), limits.quietEndMinutes) { v -> onChange { it.copy(quietEndMinutes = v) } }
            }
            FootnoteText(stringResource(R.string.proactive_quiet_hint))

            GroupSpacer()
            StepperRow(
                stringResource(R.string.proactive_limit),
                stringResource(R.string.proactive_limit_value, limits.dailyLimit),
                ProactiveSettings.LIMIT_RANGE,
                limits.dailyLimit,
            ) { v -> onChange { it.copy(dailyLimit = v) } }
            FootnoteText(stringResource(R.string.proactive_limit_hint))

            SectionHeader(stringResource(R.string.proactive_contacts))
            if (state.contacts.isEmpty()) {
                WeListRow(title = stringResource(R.string.proactive_contacts_empty), titleMaxLines = 3, showArrow = false, titleColor = colors.textTertiary)
            }
            state.contacts.forEachIndexed { index, contact ->
                DetailEntryRow(
                    title = contact.name,
                    detail = contactDetail(contact),
                    onClick = { onOpenContact(contact.id) },
                    showDivider = index < state.contacts.lastIndex,
                )
            }
            FootnoteText(stringResource(R.string.proactive_contacts_hint, ProactivePolicy.MAX_UNANSWERED))

            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.reply_pref_reset),
                titleColor = colors.link,
                showArrow = false,
                enabled = limits != ProactiveSettings(),
                onClick = { onChange { ProactiveSettings() } },
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 状态行：值是这一页最要紧的信息，字放大后放不下就换到下一行，不截断 */
@Composable
private fun StatusRow(title: String, value: String, showDivider: Boolean = false) {
    val description = "$title $value"
    Box(Modifier.clearAndSetSemantics { contentDescription = description }) {
        StackingRow(title, showDivider) {
            Text(value, style = WeTheme.typography.listTitle, color = WeTheme.colors.textTertiary)
        }
    }
}

/** 时间用「− 23:00 +」调整，每次 30 分钟，过了午夜接着转 */
@Composable
private fun TimeStepper(title: String, minutes: Int, showDivider: Boolean = false, onValue: (Int) -> Unit) {
    val step = ProactiveSettings.QUIET_STEP_MINUTES
    val slots = ProactiveSettings.MINUTES_PER_DAY / step
    StepperRow(
        title = title,
        valueText = stringResource(R.string.proactive_time_value, minutes / 60, minutes % 60),
        // 两头都能继续按：到头后绕回去
        range = -1..slots,
        value = minutes / step,
        showDivider = showDivider,
    ) { slot -> onValue(((slot % slots) + slots) % slots * step) }
}

@Composable
private fun frequencyLabel(frequency: ProactiveFrequency): String = stringResource(
    when (frequency) {
        ProactiveFrequency.LOW -> R.string.proactive_frequency_low
        ProactiveFrequency.MEDIUM -> R.string.proactive_frequency_medium
        ProactiveFrequency.HIGH -> R.string.proactive_frequency_high
    },
)

@Composable
private fun blockLabel(block: ProactiveBlock?): String = when (block) {
    null -> stringResource(R.string.proactive_state_ok)
    ProactiveBlock.QUIET_HOURS -> stringResource(R.string.proactive_state_quiet)
    ProactiveBlock.QUOTA_EXHAUSTED -> stringResource(R.string.proactive_state_quota)
    ProactiveBlock.PAUSED -> stringResource(R.string.proactive_state_paused, ProactivePolicy.MAX_UNANSWERED)
    ProactiveBlock.BUSY -> stringResource(R.string.proactive_state_busy)
    ProactiveBlock.VIEWING -> stringResource(R.string.proactive_state_viewing)
    ProactiveBlock.NO_KEY -> stringResource(R.string.proactive_state_no_key)
    ProactiveBlock.INVALID_CONFIG -> stringResource(R.string.proactive_state_invalid)
}

@Composable
private fun contactDetail(contact: ProactiveContactStatus): String {
    val lines = mutableListOf(stringResource(R.string.proactive_contact_line, frequencyLabel(contact.frequency), blockLabel(contact.block)))
    contact.lastAttempt?.let { lines += stringResource(R.string.proactive_contact_last, it) }
    contact.nextReminder?.let { lines += stringResource(R.string.proactive_contact_reminder, it) }
    return lines.joinToString("\n")
}
