package isomeria.aichat.feature.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class CrashLogsState(
    val loading: Boolean = true,
    val names: List<String> = emptyList(),
    val selected: String? = null,
    val detail: String? = null,
    val failed: Boolean = false,
    val busy: Boolean = false,
    val completed: Boolean = false,
)

@HiltViewModel
class CrashLogsViewModel @Inject constructor(@ApplicationContext private val context: Context) : ViewModel() {
    private val store = CrashLogs.store(context)
    val state = MutableStateFlow(CrashLogsState())
    init { refresh() }
    fun refresh() = work { state.value = CrashLogsState(loading = false, names = store.list().map { it.name }) }
    fun select(name: String?) = work {
        state.value = state.value.copy(selected = name, detail = name?.let(store::read), failed = false)
    }
    fun clear() = work {
        store.clear()
        state.value = CrashLogsState(loading = false, completed = true)
    }
    fun export(share: (File) -> Unit) = work {
        val name = state.value.selected ?: return@work
        val directory = File(context.cacheDir, "crash-exports").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        val target = File(directory, "crash.txt").apply { writeText(store.read(name)) }
        withContext(Dispatchers.Main) { share(target) }
    }
    private fun work(action: suspend () -> Unit) {
        if (state.value.busy) return
        state.value = state.value.copy(busy = true, failed = false, completed = false)
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
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, shareTitle))
        }
    }
}

@Composable
fun CrashLogsContent(
    state: CrashLogsState, onBack: () -> Unit, onRefresh: () -> Unit,
    onSelect: (String?) -> Unit, onClear: () -> Unit, onExport: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) {
        WeTopBar(stringResource(R.string.crash_title), onBack = { if (state.selected != null) onSelect(null) else onBack() })
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FootnoteText(stringResource(R.string.crash_notice))
            when {
                state.loading -> FootnoteText(stringResource(R.string.local_loading))
                state.detail != null -> {
                    SelectionContainer { Text(state.detail, Modifier.padding(WeTheme.dimens.horizontalPadding), color = WeTheme.colors.textPrimary, style = WeTheme.typography.small) }
                    WeListRow(stringResource(R.string.crash_export), enabled = !state.busy, onClick = onExport)
                }
                else -> {
                    if (state.names.isEmpty()) FootnoteText(stringResource(R.string.crash_empty))
                    state.names.forEach { name -> WeListRow(name, onClick = { onSelect(name) }, enabled = !state.busy) }
                    WeListRow(stringResource(R.string.action_clear), onClick = { confirm = true }, enabled = !state.busy && state.names.isNotEmpty())
                }
            }
            if (state.failed) FootnoteText(stringResource(R.string.local_failed))
            if (state.completed) FootnoteText(stringResource(R.string.local_completed))
            WeListRow(stringResource(R.string.local_refresh), onClick = onRefresh, enabled = !state.busy)
        }
    }
    if (confirm) WeDialog(stringResource(R.string.crash_clear_confirm), onDismiss = { confirm = false }, onConfirm = { confirm = false; onClear() }, destructive = true)
    if (state.busy && !state.loading) WeLoading(stringResource(R.string.local_working))
}
