package isomeria.aichat.feature.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.R
import isomeria.aichat.data.backup.BackupManager
import isomeria.aichat.data.backup.ExportSummary
import isomeria.aichat.data.backup.PasswordCipher
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.di.ApplicationScope
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.PasswordField
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.WeButton
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeLoading
import isomeria.aichat.ui.components.WeSwitch
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ExportUiState(
    val hasApiKey: Boolean = false,
    val includeKey: Boolean = false,
    val password: String = "",
    val confirm: String = "",
    /** 密码校验错误（字符串资源） */
    val errorRes: Int? = null,
    val running: Boolean = false,
    val result: ExportSummary? = null,
    val failed: Boolean = false,
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: BackupManager,
    providers: ProviderRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            providers.hasApiKey.collect { has -> _state.update { it.copy(hasApiKey = has, includeKey = it.includeKey && has) } }
        }
    }

    fun setIncludeKey(include: Boolean) = _state.update { it.copy(includeKey = include, errorRes = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, errorRes = null) }
    fun setConfirm(value: String) = _state.update { it.copy(confirm = value, errorRes = null) }

    /** 勾选了「包含 API Key」时检查密码；通过时界面接着打开系统的「保存到」 */
    fun validate(): Boolean {
        val s = _state.value
        if (!s.includeKey) return true
        val error = when {
            s.password.length < PasswordCipher.MIN_PASSWORD_LENGTH -> R.string.export_password_too_short
            s.password != s.confirm -> R.string.export_password_mismatch
            else -> null
        }
        _state.update { it.copy(errorRes = error) }
        return error == null
    }

    /** 默认文件名，如 AIChat-backup-20261101-2130.zip */
    fun fileName(): String =
        "AIChat-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".zip"

    /** 写到用户在系统文件选择器里选的位置。放在应用级协程里，离开页面也会写完，不留半个文件 */
    fun export(uri: Uri) {
        val s = _state.value
        if (s.running) return
        _state.update { it.copy(running = true, failed = false, result = null) }
        appScope.launch {
            val password = if (s.includeKey && s.hasApiKey) s.password.toCharArray() else null
            val result = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { backup.export(it, password) }
                } ?: error("无法写入所选位置")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 写了一半的文件删掉
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) } }
                null
            } finally {
                password?.fill(' ')
            }
            _state.update {
                if (result != null) {
                    it.copy(running = false, result = result, password = "", confirm = "")
                } else {
                    it.copy(running = false, failed = true)
                }
            }
        }
    }

    fun dismissResult() = _state.update { it.copy(result = null, failed = false) }
}

/** 设置 → 数据 → 导出数据 */
@Composable
fun ExportScreen(onBack: () -> Unit, viewModel: ExportViewModel = hiltViewModel()) {
    val colors = WeTheme.colors
    val state by viewModel.state.collectAsStateWithLifecycle()
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(uri)
    }

    // 导出过程中不能返回
    BackHandler(enabled = state.running) {}
    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(title = stringResource(R.string.export_title), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FootnoteText(stringResource(R.string.export_intro), Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            WeListRow(
                title = stringResource(R.string.export_include_key),
                value = if (state.hasApiKey) null else stringResource(R.string.export_no_key),
                showArrow = false,
                enabled = state.hasApiKey,
                trailing = if (state.hasApiKey) {
                    { WeSwitch(state.includeKey, viewModel::setIncludeKey) }
                } else {
                    null
                },
            )
            if (state.includeKey) {
                GroupSpacer()
                FormRow(stringResource(R.string.export_password), showDivider = true) {
                    PasswordField(
                        value = state.password,
                        onValueChange = viewModel::setPassword,
                        hint = stringResource(R.string.export_password_hint, PasswordCipher.MIN_PASSWORD_LENGTH),
                        imeAction = ImeAction.Next,
                    )
                }
                FormRow(stringResource(R.string.export_password_confirm)) {
                    PasswordField(
                        value = state.confirm,
                        onValueChange = viewModel::setConfirm,
                        hint = stringResource(R.string.export_password_confirm_hint),
                    )
                }
                state.errorRes?.let { error ->
                    Text(
                        if (error == R.string.export_password_too_short) {
                            stringResource(error, PasswordCipher.MIN_PASSWORD_LENGTH)
                        } else {
                            stringResource(error)
                        },
                        style = WeTheme.typography.small,
                        color = colors.danger,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 8.dp),
                    )
                }
                FootnoteText(stringResource(R.string.export_key_hint))
            } else {
                FootnoteText(stringResource(R.string.export_key_off_hint))
            }
            Spacer(Modifier.height(40.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                WeButton(
                    stringResource(R.string.export_button),
                    onClick = { if (viewModel.validate()) createDocument.launch(viewModel.fileName()) },
                    modifier = Modifier.width(184.dp),
                    enabled = !state.running,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.running) WeLoading(stringResource(R.string.export_running))
    state.result?.let { result ->
        WeDialog(
            title = stringResource(R.string.export_done_title),
            message = if (result.includesApiKey) {
                stringResource(R.string.export_done_message_key, result.contacts, result.messages)
            } else {
                stringResource(R.string.export_done_message, result.contacts, result.messages)
            },
            dismissText = null,
            onDismiss = viewModel::dismissResult,
            onConfirm = viewModel::dismissResult,
        )
    }
    if (state.failed) {
        WeDialog(
            message = stringResource(R.string.export_failed),
            dismissText = null,
            onDismiss = viewModel::dismissResult,
            onConfirm = viewModel::dismissResult,
        )
    }
}
