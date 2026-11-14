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
import isomeria.aichat.data.backup.BackupModule
import isomeria.aichat.data.backup.ExportSummary
import isomeria.aichat.data.backup.PasswordCipher
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.di.ApplicationScope
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.PasswordField
import isomeria.aichat.ui.components.SectionHeader
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
    /** 要导出的内容（0.7.2），默认全部 */
    val modules: Set<BackupModule> = BackupModule.ALL,
    val password: String = "",
    val confirm: String = "",
    /** 密码校验错误（字符串资源） */
    val errorRes: Int? = null,
    val running: Boolean = false,
    val result: ExportSummary? = null,
    val failed: Boolean = false,
) {
    /** API Key 只跟着模型配置导出 */
    val keyAvailable: Boolean get() = hasApiKey && BackupModule.PROVIDERS in modules
}

/**
 * 勾选或取消一项内容。聊天记录、联系人记忆、用量统计挂在联系人下面：取消联系人时一起取消，选它们时联系人也选上
 */
fun Set<BackupModule>.toggled(module: BackupModule, on: Boolean): Set<BackupModule> = when {
    on && module.requiresContacts -> this + module + BackupModule.CONTACTS
    on -> this + module
    module == BackupModule.CONTACTS -> filterNotTo(LinkedHashSet()) { it == module || it.requiresContacts }
    else -> this - module
}

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
    fun setModule(module: BackupModule, on: Boolean) = _state.update { it.copy(modules = it.modules.toggled(module, on), errorRes = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, errorRes = null) }
    fun setConfirm(value: String) = _state.update { it.copy(confirm = value, errorRes = null) }

    /** 勾选了「包含 API Key」时检查密码；通过时界面接着打开系统的「保存到」 */
    fun validate(): Boolean {
        val s = _state.value
        val error = exportPasswordError(s.includeKey && s.keyAvailable, s.password, s.confirm)
        _state.update { it.copy(errorRes = error) }
        return error == null
    }

    /** 默认文件名，如 AIChat-backup-20261101-2130.zip */
    fun fileName(): String =
        "AIChat-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + if (_state.value.password.isEmpty()) ".zip" else ".aichat"

    /** 写到用户在系统文件选择器里选的位置。放在应用级协程里，离开页面也会写完，不留半个文件 */
    fun export(uri: Uri) {
        val s = _state.value
        if (s.running || s.modules.isEmpty() || !validate()) return
        _state.update { it.copy(running = true, failed = false, result = null) }
        appScope.launch {
            val password = s.password.takeIf { it.isNotEmpty() }?.toCharArray()
            val result = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { backup.export(it, password, s.includeKey && s.keyAvailable, s.modules) }
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
    val state by viewModel.state.collectAsStateWithLifecycle()
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.export(uri)
    }
    ExportContent(state, onBack, viewModel::setIncludeKey, viewModel::setPassword, viewModel::setConfirm,
        { if (viewModel.validate()) createDocument.launch(viewModel.fileName()) }, viewModel::dismissResult, viewModel::setModule)
}

internal fun exportPasswordError(includeKey: Boolean, password: String, confirm: String): Int? = when {
    !includeKey && password.isEmpty() && confirm.isEmpty() -> null
    password.length < PasswordCipher.MIN_PASSWORD_LENGTH -> R.string.export_password_too_short
    password != confirm -> R.string.export_password_mismatch
    else -> null
}

@Composable
fun ExportContent(state: ExportUiState, onBack: () -> Unit, onIncludeKey: (Boolean) -> Unit,
    onPassword: (String) -> Unit, onConfirm: (String) -> Unit, onExport: () -> Unit, onDismissResult: () -> Unit,
    onModule: (BackupModule, Boolean) -> Unit = { _, _ -> },
) {
    val colors = WeTheme.colors
    // 导出过程中不能返回
    BackHandler(enabled = state.running) {}
    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(title = stringResource(R.string.export_title), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FootnoteText(stringResource(R.string.export_intro), Modifier.padding(top = 8.dp))
            ModuleSwitches(
                header = stringResource(R.string.export_modules),
                available = BackupModule.ALL,
                selected = state.modules,
                onModule = onModule,
            ) { module ->
                // API Key 紧跟在模型配置下面：只跟着模型配置导出
                if (module == BackupModule.PROVIDERS) {
                    WeListRow(
                        title = stringResource(R.string.export_include_key),
                        value = if (state.hasApiKey) null else stringResource(R.string.export_no_key),
                        showArrow = false,
                        showDivider = true,
                        enabled = state.keyAvailable,
                        trailing = if (state.hasApiKey) {
                            { WeSwitch(state.includeKey && state.keyAvailable, onIncludeKey, enabled = state.keyAvailable, label = stringResource(R.string.export_include_key)) }
                        } else {
                            null
                        },
                    )
                }
            }
            FootnoteText(stringResource(R.string.export_modules_note))
            run {
                GroupSpacer()
                FormRow(stringResource(R.string.export_password), showDivider = true) {
                    PasswordField(
                        value = state.password,
                        onValueChange = onPassword,
                        hint = stringResource(
                            if (state.includeKey && state.keyAvailable) R.string.export_password_hint else R.string.export_password_optional_hint,
                            PasswordCipher.MIN_PASSWORD_LENGTH,
                        ),
                        imeAction = ImeAction.Next,
                    )
                }
                FormRow(stringResource(R.string.export_password_confirm)) {
                    PasswordField(
                        value = state.confirm,
                        onValueChange = onConfirm,
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
                        color = colors.dangerText,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 8.dp),
                    )
                }
                FootnoteText(stringResource(if (state.includeKey && state.keyAvailable) R.string.export_key_hint else R.string.export_key_off_hint))
            }
            Spacer(Modifier.height(40.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                WeButton(
                    stringResource(R.string.export_button),
                    onClick = onExport,
                    modifier = Modifier.width(184.dp),
                    enabled = !state.running && state.modules.isNotEmpty(),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    WeLoading(stringResource(R.string.export_running), visible = state.running)
    state.result?.let { result ->
        WeDialog(
            title = stringResource(R.string.export_done_title),
            message = (if (result.includesApiKey) {
                stringResource(R.string.export_done_message_key, result.contacts, result.messages)
            } else {
                stringResource(R.string.export_done_message, result.contacts, result.messages)
            }) + "\n\n" + stringResource(if (result.encrypted) R.string.export_encrypted_done else R.string.export_plain_done),
            dismissText = null,
            onDismiss = onDismissResult,
            onConfirm = onDismissResult,
        )
    }
    if (state.failed) {
        WeDialog(
            message = stringResource(R.string.export_failed),
            dismissText = null,
            onDismiss = onDismissResult,
            onConfirm = onDismissResult,
        )
    }
}

/**
 * 选择导出或恢复哪些内容的开关列表（0.7.2）：分「联系人和聊天」「设置和记忆」两组，只列 [available] 里的。
 * 挂在联系人下面的几项在联系人没选时不能打开。[after] 可以在某一项后面加一行（导出页的 API Key）
 */
@Composable
internal fun ModuleSwitches(
    header: String,
    available: Set<BackupModule>,
    selected: Set<BackupModule>,
    onModule: (BackupModule, Boolean) -> Unit,
    after: @Composable (BackupModule) -> Unit = {},
) {
    val groups = listOf(
        listOf(BackupModule.CONTACTS, BackupModule.CHATS, BackupModule.MEMORIES, BackupModule.USAGE, BackupModule.FAVORITES),
        listOf(
            BackupModule.GLOBAL_MEMORY, BackupModule.PROVIDERS, BackupModule.REPLY_PREFERENCES,
            BackupModule.PROACTIVE, BackupModule.VOICE_SETTINGS, BackupModule.PROFILE,
        ),
    ).map { group -> group.filter { it in available } }.filter { it.isNotEmpty() }
    groups.forEachIndexed { groupIndex, group ->
        if (groupIndex == 0) SectionHeader(header) else GroupSpacer()
        group.forEachIndexed { index, module ->
            val title = stringResource(moduleTitle(module))
            val enabled = !module.requiresContacts || BackupModule.CONTACTS in selected
            WeListRow(
                title = title,
                showArrow = false,
                enabled = enabled,
                showDivider = index != group.lastIndex,
                trailing = { WeSwitch(module in selected, { onModule(module, it) }, enabled = enabled, label = title) },
            )
            after(module)
        }
    }
}

internal fun moduleTitle(module: BackupModule): Int = when (module) {
    BackupModule.CONTACTS -> R.string.backup_module_contacts
    BackupModule.CHATS -> R.string.backup_module_chats
    BackupModule.MEMORIES -> R.string.backup_module_memories
    BackupModule.USAGE -> R.string.backup_module_usage
    BackupModule.GLOBAL_MEMORY -> R.string.backup_module_global_memory
    BackupModule.PROVIDERS -> R.string.backup_module_providers
    BackupModule.REPLY_PREFERENCES -> R.string.backup_module_reply_preferences
    BackupModule.PROACTIVE -> R.string.backup_module_proactive
    BackupModule.VOICE_SETTINGS -> R.string.backup_module_voice
    BackupModule.PROFILE -> R.string.backup_module_profile
    BackupModule.FAVORITES -> R.string.backup_module_favorites
}
