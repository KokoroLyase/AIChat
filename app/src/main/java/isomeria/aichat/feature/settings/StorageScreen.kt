package isomeria.aichat.feature.settings

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.data.repo.*
import isomeria.aichat.ui.components.*
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class StorageError { BUSY, FAILED }
data class StorageState(
    val loading: Boolean = true, val working: Boolean = false, val usage: StorageUsage? = null,
    val error: StorageError? = null, val orphans: OrphanPreview? = null, val result: CleanupResult? = null,
)

@HiltViewModel
class StorageViewModel @Inject constructor(private val storage: StorageRepository) : ViewModel() {
    val state = MutableStateFlow(StorageState())
    init { refresh() }
    fun refresh() = run { state.value = state.value.copy(usage = storage.usage(), loading = false) }
    fun previewOrphans() = run { state.value = state.value.copy(orphans = storage.previewOrphans()) }
    fun dismissPreview() { state.value = state.value.copy(orphans = null) }
    fun cleanOrphans() {
        val preview = state.value.orphans ?: return
        state.value = state.value.copy(orphans = null)
        run { state.value = state.value.copy(result = storage.cleanOrphans(preview), usage = storage.usage()) }
    }
    fun clearCache() = run { state.value = state.value.copy(result = storage.cleanCache(), usage = storage.usage()) }
    private fun run(action: suspend () -> Unit) {
        if (state.value.working) return
        state.value = state.value.copy(working = true, error = null, result = null)
        viewModelScope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: StorageBusyException) { state.value = state.value.copy(error = StorageError.BUSY) }
            catch (_: Exception) { state.value = state.value.copy(error = StorageError.FAILED) }
            finally { state.value = state.value.copy(loading = false, working = false) }
        }
    }
}

@Composable
fun StorageScreen(onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageContent(state, onBack, viewModel::refresh, viewModel::previewOrphans, viewModel::dismissPreview,
        viewModel::cleanOrphans, viewModel::clearCache)
}

@Composable
fun StorageContent(state: StorageState, onBack: () -> Unit, onRefresh: () -> Unit, onPreviewOrphans: () -> Unit,
    onDismissPreview: () -> Unit, onCleanOrphans: () -> Unit, onClearCache: () -> Unit,
) {
    val context = LocalContext.current
    val colors = WeTheme.colors
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    var confirmCache by remember { mutableStateOf(false) }
    BackHandler(state.working) { }
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(stringResource(R.string.storage_title), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            val calculating = stringResource(R.string.storage_calculating)
            val usage = state.usage
            if (usage != null || state.loading) {
                val rows = listOf(
                    R.string.storage_images to usage?.images, R.string.storage_avatars to usage?.avatars,
                    R.string.storage_backgrounds to usage?.backgrounds,
                    R.string.crash_title to usage?.logs, R.string.storage_cache to usage?.cache,
                    R.string.storage_database to usage?.database,
                )
                rows.forEachIndexed { index, (label, bytes) ->
                    WeListRow(
                        stringResource(label),
                        value = bytes?.let(::size) ?: calculating,
                        showArrow = false,
                        showDivider = index < rows.lastIndex,
                    )
                }
                GroupSpacer()
            }
            WeListRow(stringResource(R.string.storage_clear_cache), onClick = { confirmCache = true }, enabled = !state.working, showDivider = true, showArrow = false)
            WeListRow(stringResource(R.string.storage_orphans), onClick = onPreviewOrphans, enabled = !state.working, showArrow = false)
            state.error?.let { ErrorNote(stringResource(if (it == StorageError.BUSY) R.string.storage_busy else R.string.local_failed)) }
            if (state.error == StorageError.FAILED) {
                WeListRow(stringResource(R.string.local_refresh), onClick = onRefresh, enabled = !state.working, showArrow = false)
            }
            state.result?.let {
                Text(
                    stringResource(R.string.storage_result, it.count, size(it.bytes)),
                    Modifier.fillMaxWidth().padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 6.dp),
                    style = WeTheme.typography.small,
                    color = colors.brandText,
                )
            }
            if (state.orphans?.count == 0) FootnoteText(stringResource(R.string.storage_no_orphans))
            FootnoteText(stringResource(R.string.storage_notice))
        }
    }
    state.orphans?.takeIf { it.count > 0 }?.let {
        WeDialog(
            stringResource(R.string.storage_confirm_orphans, it.count, size(it.bytes)),
            onDismissPreview,
            onCleanOrphans,
            confirmText = stringResource(R.string.action_clean),
            destructive = true,
        )
    }
    if (confirmCache) {
        WeDialog(
            stringResource(R.string.storage_confirm_cache),
            { confirmCache = false },
            { confirmCache = false; onClearCache() },
            confirmText = stringResource(R.string.action_clean),
        )
    }
    if (state.working && !state.loading) WeLoading(stringResource(R.string.local_working))
}
