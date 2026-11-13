package isomeria.aichat.feature.chatinfo

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.di.ApplicationScope
import isomeria.aichat.navigation.ChatInfoRoute
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionRow
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.AvatarPalette
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatInfoState(
    val loaded: Boolean = false,
    val contact: Contact? = null,
    val pinned: Boolean = false,
    val muted: Boolean = false,
    /** 已设置聊天背景（不管文件还在不在：在就能更换，丢了也能恢复默认） */
    val hasBackground: Boolean = false,
)

@HiltViewModel
class ChatInfoViewModel @Inject constructor(
    savedState: SavedStateHandle,
    contacts: ContactRepository,
    private val chats: ChatRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    val contactId: Long = savedState.toRoute<ChatInfoRoute>().contactId

    val state: StateFlow<ChatInfoState> = combine(
        contacts.observeContact(contactId),
        chats.observeConversation(contactId),
    ) { contact, conversation ->
        ChatInfoState(
            loaded = true,
            contact = contact,
            pinned = conversation?.pinned == true,
            muted = conversation?.muted == true,
            hasBackground = conversation?.backgroundPath != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatInfoState())

    private val failures = Channel<Unit>(Channel.CONFLATED)

    /** 设置背景失败（图片读不出来等），页面弹提示 */
    val backgroundFailed = failures.receiveAsFlow()

    fun setMuted(muted: Boolean) {
        viewModelScope.launch { chats.setMuted(contactId, muted) }
    }

    /** 离开页面也要把选好的图存完，放在应用级作用域 */
    fun setBackground(uri: Uri) {
        appScope.launch {
            try {
                chats.setBackground(contactId, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failures.trySend(Unit)
            }
        }
    }

    fun clearBackground() {
        appScope.launch { chats.clearBackground(contactId) }
    }

    fun setPinned(pinned: Boolean) {
        viewModelScope.launch { chats.setPinned(contactId, pinned) }
    }

    fun clearHistory() {
        appScope.launch { chats.clearHistory(contactId) }
    }
}

/** 聊天信息（聊天页右上角「···」进入） */
@Composable
fun ChatInfoScreen(
    onBack: () -> Unit,
    onOpenContact: (Long) -> Unit,
    onSearchHistory: (Long) -> Unit,
    viewModel: ChatInfoViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var backgroundSheet by remember { mutableStateOf(false) }
    val pickBackground = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.setBackground(uri)
    }
    fun launchPicker() = pickBackground.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.backgroundFailed.collect {
            Toast.makeText(context, R.string.chat_image_import_failed, Toast.LENGTH_SHORT).show()
        }
    }
    ChatInfoContent(
        state = state,
        onBack = onBack,
        onOpenContact = { onOpenContact(viewModel.contactId) },
        onSearchHistory = { onSearchHistory(viewModel.contactId) },
        onMutedChange = viewModel::setMuted,
        onPinnedChange = viewModel::setPinned,
        // 还没有背景时直接打开相册；已有背景时可以更换或恢复默认
        onBackground = { if (state.hasBackground) backgroundSheet = true else launchPicker() },
        onClear = { confirmClear = true },
    )
    if (backgroundSheet) {
        WeActionSheet(
            options = listOf(
                SheetOption(stringResource(R.string.contact_avatar_pick)) { launchPicker() },
                SheetOption(stringResource(R.string.chat_info_background_reset)) { viewModel.clearBackground() },
            ),
            onDismiss = { backgroundSheet = false },
        )
    }
    if (confirmClear) {
        WeDialog(
            message = stringResource(R.string.chat_info_clear_confirm, state.contact?.name.orEmpty()),
            confirmText = stringResource(R.string.action_clear),
            destructive = true,
            onDismiss = { confirmClear = false },
            onConfirm = {
                confirmClear = false
                viewModel.clearHistory()
                Toast.makeText(context, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
fun ChatInfoContent(
    state: ChatInfoState,
    onBack: () -> Unit,
    onOpenContact: () -> Unit,
    onSearchHistory: () -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onPinnedChange: (Boolean) -> Unit,
    onBackground: () -> Unit,
    onClear: () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.chat_info), onBack = onBack)
        val contact = state.contact ?: return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            // 联系人头像和名称，点击进入联系人详情
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.listBackground)
                    .padding(horizontal = dimens.horizontalPadding, vertical = 16.dp),
            ) {
                // 0.9.1：名字放不下时这一列变宽（最多头像的 3 倍），再放不下就换行，不截断
                Column(
                    Modifier
                        .widthIn(min = dimens.chatInfoAvatar + 8.dp, max = dimens.chatInfoAvatar * 3)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onOpenContact,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Avatar(contact.avatarPath, contact.name, contact.avatarColor, dimens.chatInfoAvatar)
                    Text(
                        contact.name,
                        style = WeTheme.typography.listTime,
                        color = colors.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            GroupSpacer()
            WeListRow(title = stringResource(R.string.chat_info_search), onClick = onSearchHistory)
            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.chat_info_mute),
                showArrow = false,
                showDivider = true,
                trailing = { WeSwitch(state.muted, onMutedChange) },
            )
            WeListRow(
                title = stringResource(R.string.chat_info_pin),
                showArrow = false,
                trailing = { WeSwitch(state.pinned, onPinnedChange) },
            )
            GroupSpacer()
            WeListRow(title = stringResource(R.string.chat_info_background), onClick = onBackground, titleMaxLines = 2)
            GroupSpacer()
            WeActionRow(stringResource(R.string.chat_info_clear), onClick = onClear, danger = true)
            Spacer(Modifier.height(24.dp))
        }
    }
}

internal val previewChatInfo = ChatInfoState(
    loaded = true,
    contact = Contact(
        id = 1,
        name = "小雨",
        avatarColor = AvatarPalette[0],
        providerId = 1,
        model = "deepseek-flash",
        createdAt = 0,
        updatedAt = 0,
    ),
    pinned = true,
)

@Preview(name = "聊天信息 浅色", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun ChatInfoLightPreview() {
    AIChatTheme(darkTheme = false) { ChatInfoContent(previewChatInfo, {}, {}, {}, {}, {}, {}, {}) }
}

@Preview(name = "聊天信息 深色", showBackground = true, widthDp = 400, heightDp = 860)
@Composable
private fun ChatInfoDarkPreview() {
    AIChatTheme(darkTheme = true) { ChatInfoContent(previewChatInfo, {}, {}, {}, {}, {}, {}, {}) }
}
