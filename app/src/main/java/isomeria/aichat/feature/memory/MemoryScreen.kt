package isomeria.aichat.feature.memory

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
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
import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.data.db.*
import isomeria.aichat.data.llm.*
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.memory.MemoryTaskState
import isomeria.aichat.ui.components.ThinkingSelector
import isomeria.aichat.feature.settings.ChoiceRow
import isomeria.aichat.feature.settings.ConfigActionRow
import isomeria.aichat.data.memory.BacklogResult
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.MemoryRepository
import isomeria.aichat.feature.chat.failReasonText
import isomeria.aichat.navigation.MemoryEditRoute
import isomeria.aichat.navigation.MemoryRoute
import isomeria.aichat.ui.components.ErrorNote
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.configurationIssueText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeLoading
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 一条记忆在列表里的样子。 */
data class MemoryRow(val id: Long, val range: String, val content: String, val edited: Boolean)

data class MemoryListState(
    val loaded: Boolean = false,
    val autoMemory: Boolean = true,
    val rows: List<MemoryRow> = emptyList(),
    /** 还没总结的更早消息有多少条 */
    val backlog: Int = 0,
    val contact: Contact? = null,
    val provider: ProviderConfig? = null,
    val models: List<ProviderModel> = emptyList(),
    val task: MemoryTaskState = MemoryTaskState(),

)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MemoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val memories: MemoryRepository,
    private val contacts: ContactRepository,
    private val providers: ProviderRepository,
    private val formatter: ChatTimeFormatter,
) : ViewModel() {
    private val contactId = savedState.toRoute<MemoryRoute>().contactId

    private val messages = Channel<Int>(Channel.BUFFERED)
    /** 要提示的文字（字符串资源） */
    val messageFlow: Flow<Int> = messages.receiveAsFlow()

    private val selection = contacts.observeContact(contactId).flatMapLatest { contact ->
        if (contact == null) flowOf(Triple<Contact?, ProviderConfig?, List<ProviderModel>>(null, null, emptyList()))
        else combine(providers.observeConfig(contact.providerId), providers.observeModels(contact.providerId)) { config, models -> Triple(contact, config, models) }
    }
    val state: StateFlow<MemoryListState> = combine(
        memories.observe(contactId), memories.observeBacklog(contactId), selection, memories.taskStates,
    ) { list, backlog, settings, tasks ->
        val now = System.currentTimeMillis()
        MemoryListState(loaded = true, autoMemory = settings.first?.autoMemory ?: true,
            rows = list.asReversed().map { it.toRow(now) }, backlog = backlog, contact = settings.first,
            provider = settings.second, models = settings.third, task = tasks[contactId] ?: MemoryTaskState())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryListState())

    fun setModel(value: String?) { viewModelScope.launch {
        val contact = contacts.get(contactId) ?: return@launch
        if (value != null && providers.model(contact.providerId, value)?.let { m -> providers.config(contact.providerId)?.let { ModelRules.usable(it, m) } } != true) return@launch
        contacts.update(contact.copy(memoryModel = value))
    } }
    fun setThinking(value: String) { viewModelScope.launch {
        val contact = contacts.get(contactId) ?: return@launch
        contacts.update(contact.copy(memoryThinkingOption = value))
    } }

    private fun Memory.toRow(now: Long): MemoryRow {
        val from = formatter.forDate(fromTime, now)
        val to = formatter.forDate(toTime, now)
        return MemoryRow(id, if (from == to) from else "$from – $to", content, edited)
    }

    /** 离开页面后不再弹提示；回到页面时，结果显示在任务状态里 */
    fun summarizeBacklog() = memories.startBacklogSummary(
        contactId,
        onResult = { result ->
            messages.trySend(when (result) {
                BacklogResult.DONE -> R.string.memory_backlog_done
                BacklogResult.NOTHING -> R.string.memory_backlog_nothing
                BacklogResult.NO_API_KEY -> R.string.error_no_key
            })
        },
        onFailure = { e -> messages.trySend(failReasonText(FailReason.of(e))) },
    )

    fun clearAll() {
        viewModelScope.launch { memories.clear(contactId) }
    }
}

/** 联系人详情 → 记忆：查看、编辑、删除；手动总结更早的聊天。 */
@Composable
fun MemoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onOpenProvider: (Long) -> Unit = {}, onOpenModel: (Long, String) -> Unit = { _, _ -> }, viewModel: MemoryViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmBacklog by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { res -> Toast.makeText(context, res, Toast.LENGTH_SHORT).show() }
    }

    MemoryContent(
        state = state,
        onBack = onBack,
        onOpen = onOpen,
        onSummarizeBacklog = { confirmBacklog = true },
        onClearAll = { confirmClear = true },
        onModel = viewModel::setModel,
        onThinking = viewModel::setThinking,
        onOpenProvider = onOpenProvider,
        onOpenModel = onOpenModel,
    )


    if (confirmBacklog) {
        WeDialog(
            title = stringResource(R.string.memory_backlog),
            message = stringResource(R.string.memory_backlog_confirm, MemoryPolicy.BATCH_MAX_TOKENS),
            confirmText = stringResource(R.string.memory_backlog_action),
            onDismiss = { confirmBacklog = false },
            onConfirm = {
                confirmBacklog = false
                viewModel.summarizeBacklog()
            },
        )
    }
    if (confirmClear) {
        WeDialog(
            message = stringResource(R.string.memory_clear_confirm),
            confirmText = stringResource(R.string.action_clear),
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                viewModel.clearAll()
            },
        )
    }
}

@Composable
fun MemoryContent(
    state: MemoryListState,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onSummarizeBacklog: () -> Unit,
    onClearAll: () -> Unit,
    onModel: (String?) -> Unit = {},
    onThinking: (String) -> Unit = {},
    onOpenProvider: (Long) -> Unit = {},
    onOpenModel: (Long, String) -> Unit = { _, _ -> },
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.memory_title), onBack = onBack)
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            state.contact?.let { contact ->
                val config = state.provider
                val actual = contact.memoryModel ?: config?.defaultModel?.ifBlank { contact.model } ?: contact.model
                val model = state.models.firstOrNull { it.modelId == actual }
                val follow = stringResource(R.string.memory_follow)
                val issue = config?.let { ModelRules.validate(it, model, contact.memoryThinkingOption, 0.3f) }
                ChoiceRow(stringResource(R.string.memory_model), if (contact.memoryModel == null) follow else stringResource(R.string.memory_independent),
                    options = listOf(follow to null) + state.models.filter { config != null && ModelRules.usable(config, it) }.map { it.displayName to it.modelId },
                    valueColor = if (issue == ConfigurationIssue.MODEL) colors.danger else colors.textTertiary, onPick = onModel)
                FootnoteText(stringResource(R.string.memory_actual, config?.name.orEmpty(), model?.displayName ?: actual))
                if (contact.memoryModel == null) FootnoteText(stringResource(R.string.memory_follow_fallback))
                // 没有模型时不展示虚构的档位；旧服务商（LEGACY）没有模型也沿用原有选项
                if (model != null || config?.compatibility == "LEGACY") {
                    ThinkingSelector(model, contact.memoryThinkingOption, stringResource(R.string.memory_thinking), onThinking)
                }
                // 只说实际的原因；档位的问题已经在思考那一行下面说明了
                if (issue != null && issue != ConfigurationIssue.THINKING) ErrorNote(stringResource(configurationIssueText(issue, memory = true)))
                if (model != null) WeListRow(stringResource(R.string.model_configure), onClick = { onOpenModel(contact.providerId, model.modelId) })
                else WeListRow(stringResource(R.string.memory_open_provider), onClick = { onOpenProvider(contact.providerId) })
                FootnoteText(stringResource(R.string.memory_model_note))
            }
            MemoryTaskFeedback(state.task)

            if (state.rows.isEmpty()) {
                Text(
                    stringResource(R.string.memory_empty),
                    style = WeTheme.typography.small,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.horizontalPadding, vertical = 48.dp),
                )
            } else {
                GroupSpacer()
                state.rows.forEachIndexed { index, row ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.listBackground)
                            .clickable { onOpen(row.id) }
                            .padding(horizontal = dimens.horizontalPadding, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.range,
                                style = WeTheme.typography.listTime,
                                color = colors.textTertiary,
                                modifier = Modifier.weight(1f),
                            )
                            if (row.edited) {
                                Text(
                                    stringResource(R.string.memory_edited),
                                    style = WeTheme.typography.listTime,
                                    color = colors.textTertiary,
                                )
                            }
                        }
                        Text(row.content, style = WeTheme.typography.small, color = colors.textPrimary)
                    }
                    if (index != state.rows.lastIndex) WeDivider(startIndent = dimens.horizontalPadding)
                }
            }

            FootnoteText(
                stringResource(if (state.autoMemory) R.string.memory_hint else R.string.memory_hint_off),
            )

            if (state.backlog > 0) {
                GroupSpacer()
                ConfigActionRow(
                    title = stringResource(R.string.memory_backlog),
                    value = if (state.task.manual) stringResource(R.string.memory_backlog_running) else stringResource(R.string.memory_backlog_count, state.backlog),
                    enabled = !state.task.manual,
                    onClick = onSummarizeBacklog,
                )
            }
            if (state.rows.isNotEmpty()) {
                GroupSpacer()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.listBackground)
                        .clickable(onClick = onClearAll)
                        .heightIn(min = dimens.listRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.memory_clear), style = WeTheme.typography.listTitle, color = colors.danger)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun MemoryTaskFeedback(task: MemoryTaskState) {
    if (!task.generating && task.queued == 0 && task.lastSuccess == null && task.lastFailure == null) FootnoteText(stringResource(R.string.memory_idle))
    if (task.generating) TaskProgress(stringResource(R.string.memory_generating))
    if (task.queued > 0) TaskProgress(stringResource(R.string.memory_queue, task.queued))
    val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
    task.lastSuccess?.let { FootnoteText(stringResource(R.string.memory_last_success, date.format(java.util.Date(it)))) }
    task.failure?.let { reason -> task.lastFailure?.let {
        ErrorNote(stringResource(R.string.memory_last_failure, date.format(java.util.Date(it)), stringResource(failReasonText(reason))))
    } }
}

/** 进行中的任务：转圈加文字。 */
@Composable
private fun TaskProgress(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(color = WeTheme.colors.textTertiary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = WeTheme.typography.small, color = WeTheme.colors.textTertiary)
    }
}

data class MemoryEditState(val loaded: Boolean = false, val exists: Boolean = false, val content: String = "", val original: String = "") {
    val dirty: Boolean get() = loaded && content.trim() != original
}

@HiltViewModel
class MemoryEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val memories: MemoryRepository,
) : ViewModel() {
    private val memoryId = savedState.toRoute<MemoryEditRoute>().memoryId

    private val _state = MutableStateFlow(MemoryEditState())
    val state: StateFlow<MemoryEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val memory = memories.get(memoryId)
            _state.value = MemoryEditState(
                loaded = true,
                exists = memory != null,
                content = memory?.content.orEmpty(),
                original = memory?.content.orEmpty(),
            )
        }
    }

    fun setContent(value: String) = _state.update { it.copy(content = value.take(MemoryPolicy.CONTENT_MAX_LENGTH)) }

    suspend fun save() = memories.updateContent(memoryId, _state.value.content)

    suspend fun delete() = memories.delete(memoryId)
}

/** 编辑一条记忆。清空后不能保存，要删除请点下面的「删除这条记忆」。 */
@Composable
fun MemoryEditScreen(onBack: () -> Unit, viewModel: MemoryEditViewModel = hiltViewModel()) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    LaunchedEffect(saving) {
        if (saving) {
            viewModel.save()
            onBack()
        }
    }
    LaunchedEffect(deleting) {
        if (deleting) {
            viewModel.delete()
            onBack()
        }
    }

    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(title = stringResource(R.string.memory_edit_title), onBack = onBack) {
            WeSmallButton(
                stringResource(R.string.action_save),
                { saving = true },
                enabled = state.exists && state.content.isNotBlank() && !saving,
            )
        }
        if (!state.exists) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            Column(Modifier.fillMaxWidth().background(colors.listBackground).padding(dimens.horizontalPadding)) {
                BasicTextField(
                    value = state.content,
                    onValueChange = viewModel::setContent,
                    textStyle = WeTheme.typography.small.copy(color = colors.textPrimary, lineHeight = WeTheme.typography.thinking.lineHeight),
                    cursorBrush = SolidColor(colors.brand),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .clip(RoundedCornerShape(dimens.inputCorner))
                        .background(colors.pageBackground)
                        .padding(10.dp),
                )
            }
            FootnoteText(stringResource(R.string.memory_edit_hint))
            GroupSpacer()
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(colors.listBackground)
                    .clickable { confirmDelete = true }
                    .heightIn(min = dimens.listRowHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.memory_delete), style = WeTheme.typography.listTitle, color = colors.danger)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        WeDialog(
            message = stringResource(R.string.memory_delete_confirm),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                deleting = true
            },
        )
    }
}

/** 截图用的示例数据 */
internal val previewMemoryState = MemoryListState(
    loaded = true,
    rows = listOf(
        MemoryRow(2, "10月28日 – 11月1日", "对方最近在准备考试，压力有点大，约好考完一起去看海。喜欢喝不加糖的拿铁。", edited = true),
        MemoryRow(1, "10月20日 – 10月27日", "对方养了一只叫团子的橘猫，常在晚上聊天。提到过想学吉他。", edited = false),
    ),
    backlog = 128,
)
