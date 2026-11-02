package isomeria.aichat.feature.chat

import android.Manifest
import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.LifecycleEventObserver
import isomeria.aichat.R
import isomeria.aichat.core.TextEditing
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.ui.components.ChatInputBar
import isomeria.aichat.ui.components.EmojiCatalog
import isomeria.aichat.ui.components.EmojiGroup
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 聊天页 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenContact: (Long) -> Unit,
    onOpenChatInfo: (Long) -> Unit,
    onOpenImage: (String) -> Unit,
    /** 打开服务商设置；参数是服务商 id */
    onOpenProvider: (Long) -> Unit,
    onEditContact: (Long) -> Unit = {},
    /** 打开模型配置；参数是服务商 id 和模型 id */
    onOpenModel: (Long, String) -> Unit = { _, _ -> },
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
    val latestInput by rememberUpdatedState(input.text)
    val latestDraftLoaded by rememberUpdatedState(draftLoaded)
    DisposableEffect(Unit) {
        onDispose { if (latestDraftLoaded) viewModel.saveDraft(latestInput) }
    }

    // 聊天页可见且 App 在前台时：不发通知、不加未读。切到后台时也存一次草稿，免得 App 被系统清掉后丢失
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onVisible()
                Lifecycle.Event.ON_PAUSE -> viewModel.onHidden()
                Lifecycle.Event.ON_STOP -> if (latestDraftLoaded) viewModel.saveDraft(latestInput)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    // 新消息到达时滚到底部（刚打开时列表本来就在底部，不用滚）
    val lastKey = state.rows.lastOrNull()?.key
    var previousLastKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastKey) {
        if (lastKey == null) return@LaunchedEffect
        val firstLoad = previousLastKey == null
        previousLastKey = lastKey
        if (!firstLoad && (state.lastMessageMine || listState.firstVisibleItemIndex <= 2)) listState.animateScrollToItem(0)
        // 只有真正在看（页面处于前台）时才清未读；下拉通知栏、分屏时不清，免得刚发出的通知被取消
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.markRead()
    }

    // 从搜索结果进来：定位到那条消息，放在屏幕中间，整行高亮一下再淡出
    var focusDone by rememberSaveable { mutableStateOf(viewModel.focusMessageId == 0L) }
    var highlightId by remember { mutableStateOf<Long?>(null) }
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(state.rows.isNotEmpty()) {
        if (focusDone || state.rows.isEmpty()) return@LaunchedEffect
        focusDone = true
        val index = state.rows.indexOfFirst { it is ChatRow.Item && it.message.id == viewModel.focusMessageId }
        if (index < 0) return@LaunchedEffect
        val position = state.rows.lastIndex - index
        listState.scrollToItem(position)
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == position }
        if (item != null) {
            val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
            val centered = (viewport - item.size) / 2
            listState.scrollBy((item.offset - centered).toFloat())
        }
        highlightId = viewModel.focusMessageId
    }
    LaunchedEffect(highlightId) {
        if (highlightId == null) return@LaunchedEffect
        highlight.snapTo(1f)
        delay(1_200)
        highlight.animateTo(0f, tween(800))
        highlightId = null
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

    // 面板和键盘一样高：键盘弹出过一次后记下高度，面板和键盘互相切换时输入栏不上下跳
    val density = LocalDensity.current
    val dimens = WeTheme.dimens
    val ime = WindowInsets.ime
    val navigationBars = WindowInsets.navigationBars
    var keyboardHeightPx by remember { mutableIntStateOf(viewModel.keyboardHeightPx) }
    val panelHeight = with(density) {
        if (keyboardHeightPx > 0) keyboardHeightPx.toDp() else dimens.panelHeight
    }.coerceIn(dimens.panelMinHeight, dimens.panelMaxHeight)
    LaunchedEffect(Unit) {
        val minHeight = with(density) { dimens.panelMinHeight.roundToPx() }
        snapshotFlow { ime.getBottom(density) - navigationBars.getBottom(density) }.collectLatest { height ->
            delay(300)
            if (height >= minHeight && height != keyboardHeightPx) {
                keyboardHeightPx = height
                viewModel.keyboardHeightPx = height
            }
        }
    }
    // 从面板切回键盘：键盘升起前先把位置留着，等键盘到位再收起
    var awaitingKeyboard by remember { mutableStateOf(false) }
    LaunchedEffect(awaitingKeyboard) {
        if (!awaitingKeyboard) return@LaunchedEffect
        val target = with(density) { panelHeight.roundToPx() } + navigationBars.getBottom(density)
        withTimeoutOrNull(600) { snapshotFlow { ime.getBottom(density) }.first { it >= target - 4 } }
        awaitingKeyboard = false
    }
    // 表情面板打开时输入框保留光标；再点输入框弹出键盘，面板随之收起
    val imeVisible = WindowInsets.isImeVisible
    var wasImeVisible by remember { mutableStateOf(imeVisible) }
    LaunchedEffect(imeVisible) {
        if (imeVisible && !wasImeVisible && panel != InputPanel.NONE) {
            awaitingKeyboard = true
            panel = InputPanel.NONE
        }
        wasImeVisible = imeVisible
    }
    val emojiGroups by produceState<List<EmojiGroup>?>(null) { value = EmojiCatalog.load(context) }
    val recentEmoji = remember(panel == InputPanel.EMOJI) { viewModel.recentEmoji() }

    BackHandler(enabled = panel != InputPanel.NONE) { panel = InputPanel.NONE }
    ImeBackHandler()

    val contact = state.contact
    val title = when {
        state.typing -> stringResource(R.string.chat_typing)
        contact != null -> contact.name
        state.loaded -> stringResource(R.string.chat_contact_missing)
        else -> ""
    }

    fun send() {
        if (beforeSend()) {
            viewModel.sendText(input.text)
            input = TextFieldValue("")
        }
    }

    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = title, onBack = onBack, showLine = true) {
            if (contact != null) {
                TopBarIcon(WeIcons.More, stringResource(R.string.chat_info), { onOpenChatInfo(contact.id) })
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
            highlightId = highlightId,
            highlightAlpha = { highlight.value },
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
                        awaitingKeyboard = true
                        panel = InputPanel.NONE
                        focusRequester.requestFocus()
                        keyboard?.show()
                    } else {
                        voiceMode = false
                        keyboard?.hide()
                        // 表情面板保留输入框的光标，能看到表情插在哪里
                        if (target != InputPanel.EMOJI) focusManager.clearFocus()
                        panel = target
                    }
                },
                onSend = ::send,
                onInputFocused = {
                    if (panel != InputPanel.NONE) {
                        awaitingKeyboard = true
                        panel = InputPanel.NONE
                    }
                },
                onVoiceComingSoon = {
                    Toast.makeText(context, R.string.chat_voice_coming, Toast.LENGTH_SHORT).show()
                },
                focusRequester = focusRequester,
            )
            ChatBottomArea(reserve = if (panel != InputPanel.NONE || awaitingKeyboard) panelHeight else null) {
                when (panel) {
                    InputPanel.EMOJI -> EmojiPanel(
                        groups = emojiGroups,
                        recent = recentEmoji,
                        canSend = input.text.isNotBlank(),
                        canDelete = input.text.isNotEmpty(),
                        onPick = { emoji ->
                            val (text, cursor) = TextEditing.insert(input.text, input.selection.start, input.selection.end, emoji)
                            input = TextFieldValue(text, TextRange(cursor))
                            viewModel.addRecentEmoji(emoji)
                        },
                        onDelete = {
                            val (text, cursor) = TextEditing.deleteBackward(input.text, input.selection.start, input.selection.end)
                            input = TextFieldValue(text, TextRange(cursor))
                        },
                        onSend = ::send,
                        modifier = Modifier.height(panelHeight),
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
                        modifier = Modifier.height(panelHeight),
                    )
                    InputPanel.NONE -> Unit
                }
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
                state.contact?.let { onOpenProvider(it.providerId) }
            },
        )
    }

    failedDialog?.let { message ->
        val reason = FailReason.of(message.errorCode)
        val needsSettings = reason == FailReason.AUTH || reason == FailReason.NO_API_KEY || reason == FailReason.CONFIGURATION
        // 问题在模型规则时，修正入口是模型配置页，联系人页改不了
        val modelToConfigure by produceState<String?>(null, message.id) {
            value = if (reason == FailReason.CONFIGURATION) viewModel.modelToConfigure() else null
        }
        WeDialog(
            title = stringResource(when (reason) {
                FailReason.CONFIGURATION -> R.string.chat_config_title
                FailReason.NO_API_KEY -> R.string.chat_no_key_title
                FailReason.AUTH -> R.string.chat_send_failed_title
                else -> R.string.chat_resend_title
            }),
            message = stringResource(failReasonText(reason)),
            confirmText = stringResource(when {
                modelToConfigure != null -> R.string.action_configure_model
                reason == FailReason.CONFIGURATION -> R.string.action_fix_contact
                needsSettings -> R.string.action_go_settings
                else -> R.string.action_resend
            }),
            onDismiss = { failedDialog = null },
            onConfirm = {
                failedDialog = null
                val model = modelToConfigure
                if (model != null) state.contact?.let { onOpenModel(it.providerId, model) }
                else if (reason == FailReason.CONFIGURATION) state.contact?.let { onEditContact(it.id) }
                else if (needsSettings) state.contact?.let { onOpenProvider(it.providerId) } else viewModel.retry()
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
    highlightId: Long? = null,
    highlightAlpha: () -> Float = { 0f },
) {
    val defaultName = stringResource(R.string.default_nickname)
    val highlightColor = WeTheme.colors.messageHighlight
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
            val highlighted = row is ChatRow.Item && row.message.id == highlightId
            Box(
                Modifier
                    .animateItem()
                    .then(
                        if (highlighted) {
                            Modifier.drawBehind { drawRect(highlightColor.copy(alpha = highlightColor.alpha * highlightAlpha())) }
                        } else {
                            Modifier
                        },
                    ),
            ) {
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

/**
 * 输入栏下面的区域：高度取「键盘」「导航栏」「面板（[reserve]）+ 导航栏」中最高的。
 * 面板和键盘一样高时，两者切换的过程中这块区域高度不变，输入栏就不会跳动。
 * 高度在布局阶段按窗口边衬计算，键盘动画时不会让整页重组。
 */
@Composable
private fun ChatBottomArea(reserve: Dp?, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val navigationBars = WindowInsets.navigationBars
    val reserveInsets = if (reserve != null) {
        WindowInsets(bottom = reserve + with(density) { navigationBars.getBottom(density).toDp() })
    } else {
        WindowInsets(0, 0, 0, 0)
    }
    Box(Modifier.fillMaxWidth().background(WeTheme.colors.barBackground)) {
        Spacer(
            Modifier
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.ime.union(navigationBars).union(reserveInsets)),
        )
        content()
    }
}

fun failReasonText(reason: FailReason): Int = when (reason) {
    FailReason.CONFIGURATION -> R.string.error_configuration
    FailReason.AUTH -> R.string.error_auth
    FailReason.BALANCE -> R.string.error_balance
    FailReason.RATE_LIMIT -> R.string.error_rate_limit
    FailReason.BAD_REQUEST -> R.string.error_bad_request
    FailReason.NOT_FOUND -> R.string.error_not_found
    FailReason.SERVER -> R.string.error_server
    FailReason.NETWORK -> R.string.error_network
    FailReason.CANCELLED -> R.string.error_cancelled
    FailReason.INTERRUPTED -> R.string.error_interrupted
    FailReason.NO_API_KEY -> R.string.error_no_key
    FailReason.EMPTY_REPLY -> R.string.error_empty_reply
    FailReason.UNKNOWN -> R.string.error_unknown
}
