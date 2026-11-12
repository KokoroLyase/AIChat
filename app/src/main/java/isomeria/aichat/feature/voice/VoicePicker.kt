package isomeria.aichat.feature.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.voice.VoiceCatalog
import isomeria.aichat.data.voice.VoiceCatalogNeedsKeyException
import isomeria.aichat.data.voice.VoiceOption
import isomeria.aichat.data.voice.VoicePreview
import isomeria.aichat.data.voice.VoiceTarget
import isomeria.aichat.feature.chat.failReasonText
import isomeria.aichat.ui.components.ErrorNote
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeSearchBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 选音色页上的一条提示：[res] 是文字，[reason] 是失败原因（拼在 [res] 里），没有原因时为 null */
data class PickerMessage(val res: Int, val reason: Int? = null)

/**
 * 选音色（0.7.2）：搜索、分组列出、试听。
 *
 * - [remoteSearch] 为 true 时（Fish Audio）关键词要点键盘上的搜索才到服务商查；否则在本机按名称、ID、说明过滤
 * - [previewing] 是正在合成试听的音色，[playing] 是正在放的
 */
data class VoicePickerState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val query: String = "",
    val options: List<VoiceOption> = emptyList(),
    val remoteSearch: Boolean = false,
    val current: String = "",
    val error: PickerMessage? = null,
    val previewing: String? = null,
    val playing: String? = null,
    val previewError: PickerMessage? = null,
) {
    val visible: List<VoiceOption>
        get() {
            val keyword = query.trim()
            if (remoteSearch || keyword.isEmpty()) return options
            return options.filter { option ->
                option.name.contains(keyword, ignoreCase = true) || option.id.contains(keyword, ignoreCase = true) ||
                    option.detail.contains(keyword, ignoreCase = true) || option.group.contains(keyword, ignoreCase = true)
            }
        }
}

/**
 * 选音色页的状态和动作，放在服务商编辑页和联系人编辑页的 ViewModel 里用。列表和试听都在 [scope] 里跑，关页时取消
 */
class VoicePickerController(
    private val scope: CoroutineScope,
    private val catalog: VoiceCatalog,
    private val preview: VoicePreview,
    private val previewText: String,
) {
    private val _state = MutableStateFlow(VoicePickerState())
    val state: StateFlow<VoicePickerState> = _state.asStateFlow()
    private var target: VoiceTarget? = null
    private var loadJob: Job? = null
    private var previewJob: Job? = null

    /** 打开。[target] 为 null 表示列表打不开（默认是地址不对），原因是 [missingRes] */
    fun open(target: VoiceTarget?, current: String, missingRes: Int = R.string.voice_picker_bad_url) {
        close()
        this.target = target
        if (target == null) {
            _state.value = VoicePickerState(open = true, current = current, error = PickerMessage(missingRes))
            return
        }
        _state.value = VoicePickerState(open = true, loading = true, current = current, remoteSearch = catalog.searchesRemotely(target))
        load { catalog.list(target) }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value.take(QUERY_MAX_LENGTH)) }

    /** 键盘上的搜索：只对到服务商搜索的来源有用 */
    fun search() {
        val target = target ?: return
        if (!_state.value.remoteSearch) return
        val query = _state.value.query
        _state.update { it.copy(loading = true, error = null) }
        load { catalog.search(target, query) }
    }

    private fun load(block: suspend () -> List<VoiceOption>) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val result = try {
                Result.success(block())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Result.failure(e)
            }
            _state.update { state ->
                result.fold(
                    onSuccess = { state.copy(loading = false, options = it, error = null) },
                    onFailure = { state.copy(loading = false, error = message(it, R.string.voice_picker_failed)) },
                )
            }
        }
    }

    fun preview(option: VoiceOption) {
        val target = target ?: return
        previewJob?.cancel()
        _state.update { it.copy(previewing = option.id, playing = null, previewError = null) }
        previewJob = scope.launch {
            try {
                preview.play(target, option.id, previewText) {
                    _state.update { if (it.playing == option.id) it.copy(playing = null) else it }
                }
                _state.update { if (it.previewing == option.id) it.copy(previewing = null, playing = option.id) else it }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                _state.update { it.copy(previewing = null, previewError = message(e, R.string.voice_picker_preview_failed)) }
            }
        }
    }

    fun close() {
        loadJob?.cancel()
        previewJob?.cancel()
        if (_state.value.open) preview.stop()
        _state.value = VoicePickerState()
    }

    private fun message(e: Throwable, res: Int): PickerMessage = when (e) {
        is VoiceCatalogNeedsKeyException -> PickerMessage(R.string.voice_picker_need_key)
        is LlmException -> PickerMessage(res, failReasonText(FailReason.of(e)))
        else -> PickerMessage(res, failReasonText(FailReason.UNKNOWN))
    }

    private companion object {
        const val QUERY_MAX_LENGTH = 50
    }
}

/** 分组标题：从服务商取的几组换成页面文字，内置列表的分组本身就是文字 */
@Composable
private fun groupLabel(group: String): String = when (group) {
    VoiceCatalog.GROUP_MINE -> stringResource(R.string.voice_group_mine)
    VoiceCatalog.GROUP_SYSTEM -> stringResource(R.string.voice_group_system)
    VoiceCatalog.GROUP_PUBLIC -> stringResource(R.string.voice_group_public)
    else -> group
}

@Composable
private fun pickerText(message: PickerMessage): String =
    message.reason?.let { stringResource(message.res, stringResource(it)) } ?: stringResource(message.res)

/** 选音色页：顶部搜索框，下面按分组列出音色，每一行可以试听；点一行就选它 */
@Composable
fun VoicePickerContent(
    state: VoicePickerState,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (String) -> Unit,
    onPreview: (VoiceOption) -> Unit,
    onClose: () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeSearchBar(
            query = state.query,
            onQueryChange = onQuery,
            onCancel = onClose,
            focusRequester = focus,
            hint = stringResource(if (state.remoteSearch) R.string.voice_picker_search_remote_hint else R.string.voice_picker_search_hint),
            onSearch = onSearch,
        )
        val visible = state.visible
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            item {
                FootnoteText(stringResource(R.string.voice_picker_note))
                state.error?.let { ErrorNote(pickerText(it)) }
                state.previewError?.let { ErrorNote(pickerText(it)) }
                if (state.loading) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = dimens.horizontalPadding, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = colors.textTertiary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.voice_picker_loading), style = WeTheme.typography.small, color = colors.textTertiary)
                    }
                } else if (visible.isEmpty() && state.error == null) {
                    // 一个都没有（OpenRouter 上没有内置列表的模型）时说明要手填，不说换关键词
                    FootnoteText(stringResource(if (state.options.isEmpty() && !state.remoteSearch) R.string.voice_picker_no_list else R.string.voice_picker_empty))
                }
            }
            val groups = visible.groupBy { it.group }
            groups.forEach { (group, options) ->
                if (group.isNotEmpty()) item(key = "group:$group") { SectionHeader(groupLabel(group)) }
                items(options, key = { "voice:" + group + ":" + it.id }) { option ->
                    VoiceOptionRow(
                        option = option,
                        selected = option.id == state.current,
                        previewing = state.previewing == option.id,
                        playing = state.playing == option.id,
                        showDivider = option != options.last(),
                        onPick = { onPick(option.id) },
                        onPreview = { onPreview(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceOptionRow(
    option: VoiceOption,
    selected: Boolean,
    previewing: Boolean,
    playing: Boolean,
    showDivider: Boolean,
    onPick: () -> Unit,
    onPreview: () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .clickable(role = Role.Button, onClick = onPick)
            .heightIn(min = dimens.listRowHeight)
            .padding(start = dimens.horizontalPadding, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option.name, style = WeTheme.typography.listTitle, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val secondary = listOf(option.detail, option.id.takeIf { it != option.name }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
            if (secondary.isNotEmpty()) {
                Text(secondary, style = WeTheme.typography.listTime, color = colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) Icon(Icons.Filled.Check, stringResource(R.string.voice_picker_current), tint = colors.brand, modifier = Modifier.padding(start = 8.dp).size(20.dp))
        Box(
            Modifier
                .clickable(role = Role.Button, enabled = !previewing, onClick = onPreview)
                .heightIn(min = dimens.listRowHeight)
                .padding(horizontal = dimens.horizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(
                    when {
                        previewing -> R.string.voice_picker_previewing
                        playing -> R.string.voice_picker_playing
                        else -> R.string.voice_picker_preview
                    },
                ),
                style = WeTheme.typography.small,
                color = if (previewing) colors.textTertiary else colors.link,
            )
        }
    }
    if (showDivider) WeDivider(startIndent = dimens.horizontalPadding)
}
