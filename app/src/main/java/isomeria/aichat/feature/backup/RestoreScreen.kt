package isomeria.aichat.feature.backup

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.R
import isomeria.aichat.data.backup.BackupManager
import isomeria.aichat.data.backup.BackupPreview
import isomeria.aichat.data.backup.BackupTooNewException
import isomeria.aichat.data.backup.InvalidBackupException
import isomeria.aichat.data.backup.RestoreMode
import isomeria.aichat.data.backup.RestoreSummary
import isomeria.aichat.data.backup.WrongPasswordException
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.di.ApplicationScope
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.PasswordField
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.WeButton
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeLoading
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import javax.inject.Inject

/** 读取或恢复失败的原因 */
sealed interface RestoreError {
    data object Invalid : RestoreError
    data class TooNew(val formatVersion: Int) : RestoreError
    data object ReadFailed : RestoreError
    data object RestoreFailed : RestoreError
}

data class RestoreUiState(
    val reading: Boolean = false,
    val preview: BackupPreview? = null,
    val mode: RestoreMode = RestoreMode.MERGE,
    val password: String = "",
    val wrongPassword: Boolean = false,
    val restoring: Boolean = false,
    val done: RestoreSummary? = null,
    /** 完成时用的恢复方式（提示文字不同） */
    val doneMode: RestoreMode = RestoreMode.MERGE,
    val error: RestoreError? = null,
)

@HiltViewModel
class RestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: BackupManager,
    private val files: FileStorage,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(RestoreUiState())
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    private val cacheFile get() = File(files.restoreCacheDir, "selected.zip")

    /** 先把选中的文件复制到应用缓存目录，再读取校验 */
    fun pick(uri: Uri) {
        _state.update { it.copy(reading = true, error = null, preview = null, password = "", wrongPassword = false) }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
                    input.use { stream -> cacheFile.outputStream().use { stream.copyTo(it) } }
                }
                Result.success(backup.inspect(cacheFile))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _state.update { s ->
                result.fold(
                    onSuccess = { s.copy(reading = false, preview = it, mode = RestoreMode.MERGE) },
                    onFailure = { e ->
                        s.copy(
                            reading = false,
                            error = when (e) {
                                is BackupTooNewException -> RestoreError.TooNew(e.formatVersion)
                                is InvalidBackupException -> RestoreError.Invalid
                                else -> RestoreError.ReadFailed
                            },
                        )
                    },
                )
            }
        }
    }

    fun setMode(mode: RestoreMode) = _state.update { it.copy(mode = mode) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, wrongPassword = false) }
    fun dismissError() = _state.update { it.copy(error = null) }

    /** 恢复放在应用级协程里：离开页面也会做完（或完整回滚），不会停在一半 */
    fun restore() {
        val s = _state.value
        val preview = s.preview ?: return
        if (s.restoring) return
        _state.update { it.copy(restoring = true, wrongPassword = false) }
        appScope.launch {
            val password = s.password.takeIf { preview.needsPassword && it.isNotEmpty() }?.toCharArray()
            try {
                val summary = backup.restore(preview, s.mode, password)
                _state.update { it.copy(restoring = false, done = summary, doneMode = s.mode, preview = null, password = "") }
                withContext(Dispatchers.IO) { cacheFile.delete() }
            } catch (_: WrongPasswordException) {
                _state.update { it.copy(restoring = false, wrongPassword = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(restoring = false, error = RestoreError.RestoreFailed) }
            } finally {
                password?.fill(' ')
            }
        }
    }

    override fun onCleared() {
        if (!_state.value.restoring) cacheFile.delete()
    }
}

/** 设置 → 数据 → 恢复数据 */
@Composable
fun RestoreScreen(onBack: () -> Unit, viewModel: RestoreViewModel = hiltViewModel()) {
    val colors = WeTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmOverwrite by remember { mutableStateOf(false) }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.pick(uri)
    }
    fun pickFile() = openDocument.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))

    BackHandler(enabled = state.restoring) {}
    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(title = stringResource(R.string.restore_title), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FootnoteText(stringResource(R.string.restore_intro), Modifier.padding(top = 8.dp))
            val preview = state.preview
            if (preview == null) {
                Spacer(Modifier.height(40.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    WeButton(stringResource(R.string.restore_pick), ::pickFile, Modifier.width(184.dp))
                }
            } else {
                BackupInfo(preview)
                if (preview.needsPassword) {
                    GroupSpacer()
                    FormRow(stringResource(R.string.restore_password)) {
                        PasswordField(
                            value = state.password,
                            onValueChange = viewModel::setPassword,
                            hint = stringResource(R.string.restore_password_hint),
                        )
                    }
                    if (state.wrongPassword) {
                        Text(
                            stringResource(R.string.restore_wrong_password),
                            style = WeTheme.typography.small,
                            color = colors.danger,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 8.dp),
                        )
                    }
                    FootnoteText(stringResource(R.string.restore_password_note))
                }
                SectionHeader(stringResource(R.string.restore_mode))
                ModeRow(
                    title = stringResource(R.string.restore_mode_merge),
                    description = stringResource(R.string.restore_mode_merge_desc),
                    selected = state.mode == RestoreMode.MERGE,
                    showDivider = true,
                    onClick = { viewModel.setMode(RestoreMode.MERGE) },
                )
                ModeRow(
                    title = stringResource(R.string.restore_mode_overwrite),
                    description = stringResource(R.string.restore_mode_overwrite_desc),
                    selected = state.mode == RestoreMode.OVERWRITE,
                    showDivider = false,
                    onClick = { viewModel.setMode(RestoreMode.OVERWRITE) },
                )
                if (!preview.manifest.includesApiKey) FootnoteText(stringResource(R.string.restore_key_kept_note))
                Spacer(Modifier.height(32.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    WeButton(
                        stringResource(R.string.restore_button),
                        onClick = {
                            if (state.mode == RestoreMode.OVERWRITE) confirmOverwrite = true else viewModel.restore()
                        },
                        modifier = Modifier.width(184.dp),
                        enabled = !state.restoring,
                    )
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .clickable(onClick = ::pickFile)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(stringResource(R.string.restore_pick_again), style = WeTheme.typography.listTitle, color = colors.link)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (state.reading) WeLoading(stringResource(R.string.restore_reading))
    if (state.restoring) WeLoading(stringResource(R.string.restore_running))
    if (confirmOverwrite) {
        WeDialog(
            message = stringResource(R.string.restore_overwrite_confirm),
            confirmText = stringResource(R.string.restore_overwrite_action),
            destructive = true,
            onDismiss = { confirmOverwrite = false },
            onConfirm = {
                confirmOverwrite = false
                viewModel.restore()
            },
        )
    }
    state.done?.let { summary ->
        WeDialog(
            title = stringResource(R.string.restore_done_title),
            message = when {
                state.doneMode == RestoreMode.OVERWRITE && summary.apiKeyRestored ->
                    stringResource(R.string.restore_done_overwrite_key, summary.contactsAdded, summary.messagesAdded)
                state.doneMode == RestoreMode.OVERWRITE ->
                    stringResource(R.string.restore_done_overwrite, summary.contactsAdded, summary.messagesAdded)
                summary.apiKeyRestored ->
                    stringResource(R.string.restore_done_message_key, summary.contactsAdded, summary.messagesAdded)
                else -> stringResource(R.string.restore_done_message, summary.contactsAdded, summary.messagesAdded)
            },
            dismissText = null,
            onDismiss = onBack,
            onConfirm = onBack,
        )
    }
    state.error?.let { error ->
        WeDialog(
            message = when (error) {
                RestoreError.Invalid -> stringResource(R.string.restore_invalid)
                is RestoreError.TooNew -> stringResource(R.string.restore_too_new, error.formatVersion)
                RestoreError.ReadFailed -> stringResource(R.string.restore_read_failed)
                RestoreError.RestoreFailed -> stringResource(R.string.restore_failed)
            },
            dismissText = null,
            onDismiss = viewModel::dismissError,
            onConfirm = viewModel::dismissError,
        )
    }
}

/** 备份的基本信息：导出时间、版本、各类数量、是否包含 API Key */
@Composable
private fun BackupInfo(preview: BackupPreview) {
    val manifest = preview.manifest
    val time = remember(manifest.exportedAt) { Calendar.getInstance().apply { timeInMillis = manifest.exportedAt } }
    Spacer(Modifier.height(8.dp))
    WeListRow(
        title = stringResource(R.string.restore_info_time),
        value = stringResource(
            R.string.time_full,
            time.get(Calendar.YEAR),
            time.get(Calendar.MONTH) + 1,
            time.get(Calendar.DAY_OF_MONTH),
            time.get(Calendar.HOUR_OF_DAY),
            time.get(Calendar.MINUTE),
        ),
        showArrow = false,
        showDivider = true,
    )
    WeListRow(
        title = stringResource(R.string.restore_info_version),
        value = manifest.appVersion,
        showArrow = false,
        showDivider = true,
    )
    WeListRow(
        title = stringResource(R.string.restore_info_contacts),
        value = stringResource(R.string.restore_count_contacts, preview.contactCount),
        showArrow = false,
        showDivider = true,
    )
    WeListRow(
        title = stringResource(R.string.restore_info_messages),
        value = stringResource(R.string.restore_count_messages, preview.messageCount),
        showArrow = false,
        showDivider = true,
    )
    WeListRow(
        title = stringResource(R.string.restore_info_images),
        value = stringResource(R.string.restore_count_images, preview.imageCount),
        showArrow = false,
        showDivider = true,
    )
    WeListRow(
        title = stringResource(R.string.restore_info_key),
        value = stringResource(if (manifest.includesApiKey) R.string.restore_key_included else R.string.restore_key_none),
        showArrow = false,
    )
}

/** 恢复方式的一行：标题和说明，选中时右侧打勾 */
@Composable
private fun ModeRow(title: String, description: String, selected: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .drawBehind {
                if (showDivider) {
                    val y = size.height - dimens.dividerThickness.toPx() / 2
                    drawLine(
                        colors.divider,
                        Offset(dimens.horizontalPadding.toPx(), y),
                        Offset(size.width, y),
                        dimens.dividerThickness.toPx(),
                    )
                }
            }
            .padding(horizontal = dimens.horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = WeTheme.typography.listTitle, color = colors.textPrimary)
            Text(description, style = WeTheme.typography.small, color = colors.textTertiary)
        }
        if (selected) {
            Icon(Icons.Filled.Check, null, tint = colors.brand, modifier = Modifier.padding(start = 12.dp).size(24.dp))
        }
    }
}
