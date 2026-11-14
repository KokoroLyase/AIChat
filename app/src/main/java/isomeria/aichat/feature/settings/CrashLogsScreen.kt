package isomeria.aichat.feature.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.R
import isomeria.aichat.data.logs.CrashLogs
import isomeria.aichat.ui.components.*
import isomeria.aichat.ui.components.WeActionRow
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import javax.inject.Inject

data class CrashLogsState(
    val loading: Boolean = true,
    val names: List<String> = emptyList(),
    val selected: String? = null,
    val detail: String? = null,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val completed: Boolean = false,
    /** 读列表和详情很快，不弹加载框，否则每次点开都会闪一下 */
    val quiet: Boolean = false,
)

@HiltViewModel
class CrashLogsViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val store = CrashLogs.store(context)
    val state = MutableStateFlow(CrashLogsState())
    init { refresh() }
    fun refresh() = work(quiet = true) { state.value = CrashLogsState(loading = false, names = store.list().map { it.name }) }
    fun select(name: String?) = work(quiet = true) {
        state.value = state.value.copy(selected = name, detail = name?.let(store::read), failed = false)
    }
    fun clear() = work {
        store.clear()
        state.value = CrashLogsState(loading = false, completed = true)
    }
    fun export(share: (File) -> Unit) = work {
        val name = state.value.selected ?: return@work
        val directory = File(context.cacheDir, CrashLogs.EXPORT_DIRECTORY).apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        val target = File(directory, "crash.txt").apply { writeText(store.read(name)) }
        withContext(Dispatchers.Main) { share(target) }
    }
    private fun work(quiet: Boolean = false, action: suspend () -> Unit) {
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, quiet = quiet, failed = false, completed = false)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (_: Exception) { state.value = state.value.copy(loading = false, failed = true) }
            finally { state.value = state.value.copy(busy = false) }
        }
    }
}

@Composable
fun CrashLogsScreen(onBack: () -> Unit, viewModel: CrashLogsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.crash_export)
    CrashLogsContent(state, onBack, viewModel::refresh, viewModel::select, viewModel::clear) {
        viewModel.export { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.crashfileprovider", file)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, shareTitle))
        }
    }
}

/** 文件名以毫秒时间开头（`时间-标识.txt`），列表显示成用户看得懂的时间；解析不了时原样显示。 */
@Composable
private fun crashLogTitle(name: String): String {
    val millis = name.substringBefore('-').toLongOrNull() ?: return name
    return crashLogTime(millis)
}

@Composable
private fun crashLogTime(millis: Long): String {
    val time = remember(millis) { Calendar.getInstance().apply { timeInMillis = millis } }
    return stringResource(
        R.string.time_full,
        time.get(Calendar.YEAR),
        time.get(Calendar.MONTH) + 1,
        time.get(Calendar.DAY_OF_MONTH),
        time.get(Calendar.HOUR_OF_DAY),
        time.get(Calendar.MINUTE),
    )
}

/** Presentation only: the stored/exported text remains byte-for-byte unchanged. */
@Composable
internal fun CrashLogDetail(raw: String) {
    val millis = remember(raw) { raw.lineSequence().firstOrNull()?.removePrefix("time=")?.toLongOrNull() }
    if (millis != null) {
        Text(
            stringResource(R.string.crash_detail_time, crashLogTime(millis)),
            Modifier.fillMaxWidth().background(WeTheme.colors.listBackground).padding(WeTheme.dimens.horizontalPadding),
            color = WeTheme.colors.textPrimary,
            style = WeTheme.typography.small,
        )
    }
    FootnoteText(stringResource(R.string.crash_detail_scroll))
    val display = if (millis != null) raw.substringAfter('\n', "") else raw
    SelectionContainer {
        Text(
            display,
            Modifier.fillMaxWidth().background(WeTheme.colors.listBackground)
                .horizontalScroll(rememberScrollState()).padding(WeTheme.dimens.horizontalPadding),
            color = WeTheme.colors.textPrimary,
            style = WeTheme.typography.small.copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
        )
    }
}

@Composable
fun CrashLogsContent(
    state: CrashLogsState, onBack: () -> Unit, onRefresh: () -> Unit,
    onSelect: (String?) -> Unit, onClear: () -> Unit, onExport: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val colors = WeTheme.colors
    // 系统返回和顶栏返回一致：详情先回到列表
    BackHandler(enabled = state.selected != null) { onSelect(null) }
    // 列表和下面的说明；详情页也用（说明、读取失败）
    @Composable
    fun page(title: Int, back: () -> Unit, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
            WeTopBar(stringResource(title), onBack = back)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                GroupSpacer()
                content()
                if (state.failed) {
                    ErrorNote(stringResource(R.string.local_failed))
                    WeListRow(stringResource(R.string.local_refresh), onClick = onRefresh, enabled = !state.busy, showArrow = false)
                }
                if (state.completed) FootnoteText(stringResource(R.string.crash_cleared))
                FootnoteText(stringResource(R.string.crash_notice))
            }
        }
    }
    // 0.9.1 审计：详情像二级页面一样推入、滑出（以前是原地换内容）；回到列表时还停在原来的位置
    isomeria.aichat.navigation.SubPageHost(
        state.detail.takeIf { !state.loading },
        subPage = { detail ->
            page(R.string.crash_detail_title, back = { onSelect(null) }) {
                CrashLogDetail(detail)
                GroupSpacer()
                WeListRow(stringResource(R.string.crash_export), enabled = !state.busy, onClick = onExport, showArrow = false)
            }
        },
    ) {
        page(R.string.crash_title, back = onBack) {
            when {
                state.loading -> FootnoteText(stringResource(R.string.local_loading))
                else -> {
                    if (state.names.isEmpty() && !state.failed && !state.completed) FootnoteText(stringResource(R.string.crash_empty))
                    state.names.forEachIndexed { index, name ->
                        WeListRow(crashLogTitle(name), onClick = { onSelect(name) }, enabled = !state.busy, showDivider = index < state.names.lastIndex, titleMaxLines = 3)
                    }
                    if (state.names.isNotEmpty()) {
                        GroupSpacer()
                        WeActionRow(stringResource(R.string.crash_clear), onClick = { confirm = true }, danger = true, enabled = !state.busy)
                    }
                }
            }
        }
    }
    if (confirm) {
        WeDialog(
            stringResource(R.string.crash_clear_confirm),
            onDismiss = { confirm = false },
            onConfirm = { confirm = false; onClear() },
            confirmText = stringResource(R.string.action_clear),
            destructive = true,
        )
    }
    WeLoading(stringResource(R.string.local_working), visible = state.busy && !state.loading && !state.quiet)
}
