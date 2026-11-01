package isomeria.aichat.feature.chat

import android.Manifest
import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.LifecycleEventObserver
import isomeria.aichat.R
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.ui.components.ChatInputBar
import isomeria.aichat.ui.components.EmojiPanel
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.InputPanel
import isomeria.aichat.ui.components.MorePanel
import isomeria.aichat.ui.components.TopBarIcon
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.SelfAvatarColor
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.launch

/** 聊天页 */
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenContact: (Long) -> Unit,
    onOpenImage: (String) -> Unit,
    onOpenLlmSettings: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val colors = WeTheme.colors
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf(TextFieldValue("")) }
    var draftLoaded by remember { mutableStateOf(false) }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var panel by rememberSaveable { mutableStateOf(InputPanel.NONE) }
    var showNoKey by remember { mutableStateOf(false) }
    var failedDialog by remember { mutableStateOf<Message?>(null) }
    var deleteDialog by remember { mutableStateOf<Message?>(null) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    // 草稿：进入时载入，离开时保存
    LaunchedEffect(Unit) {
        val draft = viewModel.loadDraft()
        if (draft.isNotEmpty()) input = TextFieldValue(draft, TextRange(draft.length))
        draftLoaded = true
    }
    val latestInput by androidx.compose.runtime.rememberUpdatedState(input.text)
    DisposableEffect(Unit) {
        onDispose { if (draftLoaded) viewModel.saveDraft(latestInput) }
    }

    // 聊天页可见且 App 在前台时：不发通知、不加未读
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onVisible()
                Lifecycle.Event.ON_PAUSE -> viewModel.onHidden()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    // 新消息到达时滚到底部
    val lastKey = state.rows.lastOrNull()?.key
    LaunchedEffect(lastKey) {
        if (lastKey == null) return@LaunchedEffect
        if (state.lastMessageMine || listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
        // 只有真正在看（页面处于前台）时才清未读；下拉通知栏、分屏时不清，免得刚发出的通知被取消
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.markRead()
    }

    LaunchedEffect(Unit) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                ChatEvent.ImageImportFailed ->
                    Toast.makeText(context, R.string.chat_image_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        viewModel.sendImages(uris)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (ok && uri != null) viewModel.sendImages(listOf(uri.toUri()))
    }

    /** 发送前检查：没配置 Key 时提示去设置；第一次发送时申请通知权限 */
    fun beforeSend(): Boolean {
        if (!state.hasApiKey) {
            showNoKey = true
            return false
        }
        if (viewModel.shouldAskNotificationPermission()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        return true
    }

    fun closePanels() {
        if (panel != InputPanel.NONE) panel = InputPanel.NONE
        keyboard?.hide()
        focusManager.clearFocus()
    }

    BackHandler(enabled = panel != InputPanel.NONE) { panel = InputPanel.NONE }
    ImeBackHandler()

    val contact = state.contact
    val title = when {
        state.typing -> stringResource(R.string.chat_typing)
        contact != null -> contact.name
        state.loaded -> stringResource(R.string.chat_contact_missing)
        else -> ""
    }

    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(title = title, onBack = onBack, showLine = true) {
            if (contact != null) {
                TopBarIcon(WeIcons.More, stringResource(R.string.chat_info), { onOpenContact(contact.id) })
            }
        }

        val waiting = state.rows.any {
            it is ChatRow.Item && it.message.status == MessageStatus.SENDING
        }
        val actions = MessageActions(
            onCopy = { text ->
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text))) }
            },
            onDelete = { deleteDialog = it },
            onRegenerate = {
                if (state.hasApiKey) viewModel.regenerate() else showNoKey = true
            },
            onCancelReply = viewModel::cancelReply,
            onFailedClick = { failedDialog = it },
            onImageClick = onOpenImage,
            onAvatarClick = { contact?.let { onOpenContact(it.id) } },
        )
        ChatMessageList(
            rows = state.rows,
            contact = contact,
            myName = state.myName,
            myAvatarPath = state.myAvatarPath,
            waitingReply = waiting,
            actions = actions,
            listState = listState,
            onBackgroundClick = ::closePanels,
            modifier = Modifier.weight(1f),
        )

        if (contact != null) {
            ChatInputBar(
                value = input,
                onValueChange = { input = it },
                voiceMode = voiceMode,
                panel = panel,
                onToggleVoice = {
                    voiceMode = !voiceMode
                    panel = InputPanel.NONE
                    if (voiceMode) closePanels()
                },
                onTogglePanel = { target ->
                    if (target == InputPanel.NONE) {
                        panel = InputPanel.NONE
                        focusRequester.requestFocus()
                        keyboard?.show()
                    } else {
                        voiceMode = false
                        keyboard?.hide()
                        focusManager.clearFocus()
                        panel = target
                    }
                },
                onSend = {
                    if (beforeSend()) {
                        viewModel.sendText(input.text)
                        input = TextFieldValue("")
                    }
                },
                onInputFocused = { panel = InputPanel.NONE },
                onVoiceComingSoon = {
                    Toast.makeText(context, R.string.chat_voice_coming, Toast.LENGTH_SHORT).show()
                },
                focusRequester = focusRequester,
                modifier = if (panel == InputPanel.NONE) Modifier.navigationBarsPadding() else Modifier,
            )
            when (panel) {
                InputPanel.EMOJI -> EmojiPanel(
                    onPick = { emoji ->
                        val text = input.text
                        val start = input.selection.min.coerceIn(0, text.length)
                        val end = input.selection.max.coerceIn(0, text.length)
                        val newText = text.substring(0, start) + emoji + text.substring(end)
                        input = TextFieldValue(newText, TextRange(start + emoji.length))
                    },
                    modifier = Modifier.navigationBarsPadding(),
                )
                InputPanel.MORE -> MorePanel(
                    imageEnabled = state.supportsImage,
                    onAlbum = {
                        if (!state.supportsImage) {
                            Toast.makeText(context, R.string.chat_image_unsupported, Toast.LENGTH_LONG).show()
                        } else if (beforeSend()) {
                            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    },
                    onCamera = {
                        if (!state.supportsImage) {
                            Toast.makeText(context, R.string.chat_image_unsupported, Toast.LENGTH_LONG).show()
                        } else if (beforeSend()) {
                            val uri = viewModel.newCameraUri()
                            pendingCameraUri = uri.toString()
                            takePicture.launch(uri)
                        }
                    },
                    modifier = Modifier.navigationBarsPadding(),
                )
                InputPanel.NONE -> Unit
            }
        } else if (state.loaded) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chat_contact_missing), color = colors.textTertiary)
            }
        }
    }

    if (showNoKey) {
        WeDialog(
            title = stringResource(R.string.chat_no_key_title),
            message = stringResource(R.string.chat_no_key_message),
            confirmText = stringResource(R.string.action_go_settings),
            onDismiss = { showNoKey = false },
            onConfirm = {
                showNoKey = false
                onOpenLlmSettings()
            },
        )
    }

    failedDialog?.let { message ->
        val reason = FailReason.of(message.errorCode)
        val needsSettings = reason == FailReason.AUTH || reason == FailReason.NO_API_KEY
        WeDialog(
            title = stringResource(R.string.chat_resend_title),
            message = stringResource(failReasonText(reason)),
            confirmText = stringResource(if (needsSettings) R.string.action_go_settings else R.string.action_resend),
            onDismiss = { failedDialog = null },
            onConfirm = {
                failedDialog = null
                if (needsSettings) onOpenLlmSettings() else viewModel.retry()
            },
        )
    }

    deleteDialog?.let { message ->
        WeDialog(
            message = stringResource(R.string.chat_delete_message_confirm),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleteDialog = null },
            onConfirm = {
                deleteDialog = null
                viewModel.delete(message.id)
            },
        )
    }
}

/** 消息列表：最新的在底部。点空白处收起键盘和面板。 */
@Composable
fun ChatMessageList(
    rows: List<ChatRow>,
    contact: Contact?,
    myName: String,
    myAvatarPath: String?,
    waitingReply: Boolean,
    actions: MessageActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onBackgroundClick: () -> Unit = {},
) {
    val defaultName = stringResource(R.string.default_nickname)
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onBackgroundClick,
            ),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(rows.asReversed(), key = { it.key }) { row ->
            Box(Modifier.animateItem()) {
                when (row) {
                    is ChatRow.TimeBar -> TimeBarRow(row.text)
                    is ChatRow.Item -> MessageRow(
                        message = row.message,
                        contactName = contact?.name.orEmpty(),
                        contactAvatarPath = contact?.avatarPath,
                        contactAvatarColor = contact?.avatarColor ?: SelfAvatarColor,
                        myName = myName.ifBlank { defaultName },
                        myAvatarPath = myAvatarPath,
                        myAvatarColor = SelfAvatarColor,
                        canRegenerate = row.canRegenerate,
                        waitingReply = waitingReply,
                        maxBubbleWidth = 1000.dp,
                        actions = actions,
                    )
                }
            }
        }
    }
}

fun failReasonText(reason: FailReason): Int = when (reason) {
    FailReason.AUTH -> R.string.error_auth
    FailReason.BALANCE -> R.string.error_balance
    FailReason.RATE_LIMIT -> R.string.error_rate_limit
    FailReason.BAD_REQUEST -> R.string.error_bad_request
    FailReason.SERVER -> R.string.error_server
    FailReason.NETWORK -> R.string.error_network
    FailReason.CANCELLED -> R.string.error_cancelled
    FailReason.INTERRUPTED -> R.string.error_interrupted
    FailReason.NO_API_KEY -> R.string.error_no_key
    FailReason.EMPTY_REPLY -> R.string.error_empty_reply
    FailReason.UNKNOWN -> R.string.error_unknown
}
