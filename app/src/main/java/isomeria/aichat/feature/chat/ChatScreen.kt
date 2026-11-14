package isomeria.aichat.feature.chat

import android.Manifest
import android.content.ClipData
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import isomeria.aichat.ui.components.WeIcon
import isomeria.aichat.ui.theme.WeMotion
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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
import isomeria.aichat.ui.components.RecordMode
import isomeria.aichat.ui.components.RecordZone
import isomeria.aichat.ui.components.RecordingOverlay
import isomeria.aichat.ui.components.VoiceInputActions
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
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
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeExpand
import isomeria.aichat.ui.components.FadeScaleVisibility
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
    /** 打开语音识别服务商的设置（0.7.0） */
    onOpenVoiceSettings: () -> Unit = {},
    /** 语音通话（0.8.0）；参数是联系人 id */
    onCall: (Long) -> Unit = {},
    /** 看通话详情（0.8.0）；正在进行的通话由 [onCall] 回到通话页 */
    onOpenCall: (isomeria.aichat.data.db.Message) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val colors = WeTheme.colors
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val inCall by viewModel.inCall.collectAsStateWithLifecycle()
    val callStartedAt by viewModel.callStartedAt.collectAsStateWithLifecycle()
    val pageEntry by viewModel.entry.collectAsStateWithLifecycle()
    val pagedRows = viewModel.rows.collectAsLazyPagingItems()
    val newMessageCount by viewModel.newMessageCount.collectAsStateWithLifecycle()
    val background by viewModel.background.collectAsStateWithLifecycle()
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
    // 语音（0.7.0）
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val transcribing by viewModel.transcribing.collectAsStateWithLifecycle()
    val asrAvailable by viewModel.asrAvailable.collectAsStateWithLifecycle()
    val asrOwnSource by viewModel.asrOwnSource.collectAsStateWithLifecycle()
    val playingId by viewModel.playingId.collectAsStateWithLifecycle()
    // 多选（0.9.0）
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val quote by viewModel.quote.collectAsStateWithLifecycle()
    val translating by viewModel.translating.collectAsStateWithLifecycle()
    var deleteSelectedDialog by remember { mutableStateOf<Int?>(null) }
    var showAsrSetup by remember { mutableStateOf(false) }
    var voiceIntroName by remember { mutableStateOf<String?>(null) }
    var showMicDenied by remember { mutableStateOf(false) }

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

    var focusDone by rememberSaveable { mutableStateOf(false) }
    // 进图片查看或联系人页再回来时，这里的状态要保留，否则停在底部也会误显示新消息浮标
    var atBottom by rememberSaveable { mutableStateOf(false) }
    var pendingLatest by remember { mutableStateOf<Long?>(null) }
    var targetMissing by remember { mutableStateOf(false) }
    var highlightId by remember { mutableStateOf<Long?>(null) }
    // 跳到引用的原消息（0.9.0）：列表从它附近重新载入后滚过去
    var pendingJump by remember { mutableStateOf<Long?>(null) }
    val highlight = remember { Animatable(0f) }
    val focusIme = WindowInsets.ime
    val focusDensity = LocalDensity.current

    // 看历史时分页刷新换窗口，位置不能丢；停在底部要跟随新消息，定位和回底时由各自的逻辑负责
    val rowKeys = remember(pagedRows.itemSnapshotList) { pagedRows.itemSnapshotList.items.map { it.key } }
    KeepFirstVisibleItem(listState, rowKeys, enabled = focusDone && !atBottom && pendingLatest == null && pendingJump == null)

    LaunchedEffect(pageEntry, pagedRows.itemSnapshotList, pagedRows.loadState.refresh) {
        val start = pageEntry ?: return@LaunchedEffect
        if (focusDone || pagedRows.loadState.refresh !is LoadState.NotLoading) return@LaunchedEffect
        if (start.targetId == 0L) {
            if (pagedRows.itemCount > 0) listState.scrollToItem(0)
            targetMissing = viewModel.focusMessageId != 0L
            focusDone = true
            atBottom = true
            viewModel.markLatestSeen()
            return@LaunchedEffect
        }
        val index = pagedRows.itemSnapshotList.items.indexOfFirst { it is ChatRow.Item && it.message.id == start.targetId }
        if (index < 0) {
            if (!state.typing && pagedRows.itemCount > 0) {
                // 只有搜索定位才提示；未读定位找不到时留在当前位置就好
                targetMissing = viewModel.focusMessageId != 0L
                focusDone = true
            }
            return@LaunchedEffect
        }
        // 从搜索页带着键盘进来时，键盘收完可视区才变高；先算居中的话目标会被带到屏幕下半部分
        withTimeoutOrNull(FOCUS_IME_WAIT_MILLIS) { snapshotFlow { focusIme.getBottom(focusDensity) }.first { it == 0 } }
        centerOn(listState, index)
        focusDone = true
        atBottom = pagedRows.loadState.prepend.endOfPaginationReached && listState.firstVisibleItemIndex <= 2
        if (atBottom) viewModel.markLatestSeen()
        if (viewModel.focusMessageId != 0L) highlightId = start.targetId
    }
    // Keep the user's intent when a refresh inserts rows before the retained visible key.
    LaunchedEffect(listState, pagedRows) {
        snapshotFlow { Triple(listState.isScrollInProgress, listState.firstVisibleItemIndex, pagedRows.loadState.prepend.endOfPaginationReached) }
            .collect { (scrolling, index, newestLoaded) ->
                if (scrolling) {
                    atBottom = newestLoaded && index <= 2
                    if (atBottom) viewModel.markLatestSeen()
                }
            }
    }
    var previousLatest by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.latestVisibleId, pagedRows.itemSnapshotList) {
        val latest = state.latestVisibleId
        if (latest == 0L) return@LaunchedEffect
        if (focusDone && atBottom && pagedRows.itemSnapshotList.items.firstOrNull().let { it is ChatRow.Item && it.message.id == latest }) {
            if (previousLatest != 0L) listState.animateScrollToItem(0)
            viewModel.markLatestSeen()
        }
        previousLatest = latest
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) viewModel.markRead()
    }
    LaunchedEffect(pendingLatest, pagedRows.itemSnapshotList) {
        val target = pendingLatest ?: return@LaunchedEffect
        if (target != 0L && pagedRows.itemSnapshotList.items.none { it is ChatRow.Item && it.message.id == target }) return@LaunchedEffect
        // 本来就停在底部（比如刚发了一条）：和收到消息一样滑上去；在看历史时直接跳到底部
        if (pagedRows.itemCount > 0) listState.scrollToLatest(animate = focusDone && atBottom)
        atBottom = true
        focusDone = true
        pendingLatest = null
        viewModel.markLatestSeen()
    }
    LaunchedEffect(pendingJump, pagedRows.itemSnapshotList) {
        val target = pendingJump ?: return@LaunchedEffect
        val index = pagedRows.itemSnapshotList.items.indexOfFirst { it is ChatRow.Item && it.message.id == target }
        if (index < 0) return@LaunchedEffect
        pendingJump = null
        centerOn(listState, index)
        focusDone = true
        atBottom = pagedRows.loadState.prepend.endOfPaginationReached && listState.firstVisibleItemIndex <= 2
        highlightId = target
    }
    LaunchedEffect(targetMissing) {
        if (targetMissing) Toast.makeText(context, R.string.chat_target_missing, Toast.LENGTH_SHORT).show()
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
                is ChatEvent.ScrollToLatest -> pendingLatest = event.messageId
                is ChatEvent.ScrollToMessage -> pendingJump = event.messageId
                ChatEvent.QuoteMissing -> Toast.makeText(context, R.string.quote_missing, Toast.LENGTH_SHORT).show()
                ChatEvent.VoiceTooShort -> Toast.makeText(context, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
                ChatEvent.MicUnavailable -> Toast.makeText(context, R.string.voice_mic_unavailable, Toast.LENGTH_SHORT).show()
                ChatEvent.VoiceTextFailed -> Toast.makeText(context, R.string.voice_transcribe_failed, Toast.LENGTH_SHORT).show()
                is ChatEvent.VoiceFileMissing -> Toast.makeText(
                    context,
                    if (event.hasText) R.string.voice_file_missing else R.string.voice_file_missing_no_text,
                    Toast.LENGTH_SHORT,
                ).show()
                is ChatEvent.Favorited -> Toast.makeText(
                    context,
                    if (event.imagesSkipped) R.string.favorite_images_skipped else R.string.favorite_added,
                    Toast.LENGTH_SHORT,
                ).show()
                is ChatEvent.VoiceText -> {
                    // 识别结果放进输入框光标处，用户改完再发
                    val (text, cursor) = TextEditing.insert(input.text, input.selection.start, input.selection.end, event.text)
                    input = TextFieldValue(text, TextRange(cursor))
                    voiceMode = false
                }
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
        viewModel.sendImages(uris)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (ok && uri != null) viewModel.sendImages(listOf(uri.toUri()))
        if (!ok || uri == null) viewModel.finishCamera()
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

    /**
     * 录音前检查（0.7.0）：没设置语音识别时提示去设置；第一次用时说明录音发给谁；没有麦克风权限时申请，
     * 拒绝过（系统不再弹窗）时说明并给设置入口。都通过才返回 true
     */
    fun beforeRecord(): Boolean {
        if (!asrAvailable) {
            showAsrSetup = true
            return false
        }
        if (!viewModel.voiceIntroShown) {
            scope.launch { voiceIntroName = viewModel.asrName() }
            return false
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val activity = context.findActivity()
            if (viewModel.micPermissionAsked && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                showMicDenied = true
            } else {
                viewModel.micPermissionAsked = true
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            return false
        }
        return true
    }

    val voiceActions = VoiceInputActions(
        recording = recording,
        transcribing = transcribing,
        onPress = { beforeSend() && beforeRecord() && viewModel.startRecording(RecordMode.VOICE_MESSAGE, toggled = false) },
        onZone = viewModel::setRecordZone,
        onRelease = viewModel::finishRecording,
        onToggle = {
            if (recording == null) {
                if (beforeSend() && beforeRecord()) viewModel.startRecording(RecordMode.VOICE_MESSAGE, toggled = true)
            } else {
                viewModel.finishRecording(RecordZone.SEND)
            }
        },
        onCancel = { viewModel.finishRecording(RecordZone.CANCEL) },
        onToText = { viewModel.finishRecording(RecordZone.TO_TEXT) },
        onMic = { if (beforeRecord()) viewModel.startRecording(RecordMode.TO_TEXT, toggled = true) },
    )

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
    // 多选（0.9.0）：返回手势先退出多选
    BackHandler(enabled = selection != null) { viewModel.cancelSelection() }

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
        // 0.9.1 审计：进出多选时顶栏也和底栏一样交叉淡变（以前只有底栏有）
        isomeria.aichat.ui.components.SelectionCrossfade(selection) { selected ->
        if (selected != null) {
            // 多选（0.9.0）：顶栏换成“已选 N 条”和“取消”
            WeTopBar(
                title = if (selected.isEmpty()) stringResource(R.string.chat_selected_none) else stringResource(R.string.chat_selected_count, selected.size),
                showLine = true,
            ) {
                isomeria.aichat.ui.components.TopBarTextAction(stringResource(R.string.action_cancel), viewModel::cancelSelection)
            }
        } else {
            WeTopBar(title = title, onBack = onBack, showLine = true) {
                if (contact != null) {
                    TopBarIcon(WeIcons.More, stringResource(R.string.chat_info), { onOpenChatInfo(contact.id) })
                }
            }
        }
        }

        val waiting = state.waitingReply
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
            onVoiceClick = viewModel::toggleVoice,
            onVoicePlay = viewModel::playVoice,
            onVoiceShowText = viewModel::setVoiceShowText,
            onCallClick = { message -> if (opensCallPage(message, inCall)) state.contact?.let { onCall(it.id) } else onOpenCall(message) },
            onStartSelection = { message ->
                closePanels()
                viewModel.startSelection(message)
            },
            onFavorite = viewModel::favorite,
            onQuote = if (inCall) null else { message ->
                viewModel.startQuote(message)
                // 文字模式下弹出键盘接着打字；按住说话模式保持不变
                if (!voiceMode) {
                    panel = InputPanel.NONE
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
            },
            onTranslate = viewModel::translate,
            onHideTranslation = viewModel::hideTranslation,
            onOpenQuote = { message ->
                val target = message.quoteId
                val index = if (target == null) -1 else pagedRows.itemSnapshotList.items.indexOfFirst { it is ChatRow.Item && it.message.id == target }
                when {
                    target == null -> Toast.makeText(context, R.string.quote_missing, Toast.LENGTH_SHORT).show()
                    // 已经在列表里：直接滚过去
                    index >= 0 -> scope.launch {
                        centerOn(listState, index)
                        atBottom = pagedRows.loadState.prepend.endOfPaginationReached && listState.firstVisibleItemIndex <= 2
                        highlightId = target
                    }
                    else -> viewModel.jumpToQuote(target)
                }
            },
            onCopyCall = { message ->
                scope.launch {
                    val text = viewModel.callText(message)
                    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", text)))
                }
            },
        )
        Box(Modifier.weight(1f)) {
        background?.let { ChatBackground(it, Modifier.matchParentSize()) }
        androidx.compose.runtime.CompositionLocalProvider(LocalChatHasBackground provides (background != null)) {
        ChatMessageList(
            rows = emptyList(),
            pagingRows = pagedRows,
            contact = contact,
            myName = state.myName,
            myAvatarPath = state.myAvatarPath,
            waitingReply = waiting,
            actions = actions,
            playingId = playingId,
            listState = listState,
            onBackgroundClick = ::closePanels,
            highlightId = highlightId,
            highlightAlpha = { highlight.value },
            selection = selection,
            onToggleSelection = viewModel::toggleSelection,
            translating = translating,
            modifier = Modifier.fillMaxSize(),
        )
        }
        // 只有真的没内容时才显示：发消息回到底部也会刷新，那时不能闪一下“正在加载”
        if (pagedRows.loadState.refresh is LoadState.Loading && pagedRows.itemCount == 0) {
            Box(Modifier.align(Alignment.TopCenter)) { FootnoteText(stringResource(R.string.local_loading)) }
        }
        if (pagedRows.loadState.refresh is LoadState.Error || pagedRows.loadState.append is LoadState.Error || pagedRows.loadState.prepend is LoadState.Error) {
            WeListRow(
                stringResource(R.string.chat_page_retry),
                Modifier.align(Alignment.TopCenter),
                onClick = pagedRows::retry,
                titleColor = colors.dangerText,
                titleStyle = WeTheme.typography.small,
                showArrow = false,
            )
        }
        // 0.9.1 审计：「N 条新消息」浮标淡入、缩放出现，回到底部时淡出（以前一下子出现、消失）
        FadeScaleVisibility(newMessageCount > 0 && !atBottom && focusDone, Modifier.align(Alignment.BottomEnd)) {
            val lastCount = remember { intArrayOf(1) }
            if (newMessageCount > 0) lastCount[0] = newMessageCount
            NewMessagesButton(lastCount[0], viewModel::goToLatest)
        }
        // 0.9.1 审计：录音浮层淡入淡出（以前一下子盖上、一下子消失）
        val lastRecording = remember { arrayOfNulls<isomeria.aichat.ui.components.RecordingUi>(1) }
        recording?.let { lastRecording[0] = it }
        androidx.compose.animation.AnimatedVisibility(
            visible = recording != null,
            modifier = Modifier.matchParentSize(),
            enter = androidx.compose.animation.fadeIn(tween(WeMotion.OVERLAY_FADE)),
            exit = androidx.compose.animation.fadeOut(tween(WeMotion.OVERLAY_FADE)),
            label = "recordingOverlay",
        ) {
            val rec = recording ?: lastRecording[0]
            if (rec != null) {
                Box(Modifier.fillMaxSize()) {
                    RecordingOverlay(
                        state = rec,
                        maxMillis = MAX_RECORD_MILLIS,
                        onCancel = voiceActions.onCancel,
                        onToText = voiceActions.onToText,
                        onFinish = { viewModel.finishRecording(RecordZone.SEND) },
                    )
                }
            }
        }
        }

        // 0.9.1：进出多选时输入栏和操作栏交叉淡变
        isomeria.aichat.ui.components.SelectionCrossfade(selection) { selectedNow ->
        if (selectedNow != null) {
            // 多选（0.9.0）：输入栏换成复制、收藏、删除（收藏时图片跳过，见 FavoriteRepository.add）
            val any = selectedNow.isNotEmpty()
            isomeria.aichat.ui.components.SelectionBar(
                listOf(
                    isomeria.aichat.ui.components.SelectionAction(stringResource(R.string.action_copy), WeIcons.Copy, any) {
                        scope.launch {
                            val text = viewModel.takeSelectedText() ?: return@launch
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("messages", text)))
                        }
                    },
                    isomeria.aichat.ui.components.SelectionAction(stringResource(R.string.chat_menu_favorite), WeIcons.Star, any) {
                        viewModel.favoriteSelected()
                    },
                    isomeria.aichat.ui.components.SelectionAction(stringResource(R.string.action_delete), WeIcons.Delete, any, danger = true) {
                        deleteSelectedDialog = selectedNow.size
                    },
                ),
            )
        } else if (contact != null && inCall) {
            // 0.8.0：正在和这个联系人通话，不从这里发消息
            InCallBar(callStartedAt) { onCall(contact.id) }
        } else if (contact != null) {
            // 0.9.1 审计：引用条展开、收起，输入栏和消息列表跟着平滑移动（以前一下子跳）
            WeExpand(quote) { quoted ->
                val name = if (quoted.role == isomeria.aichat.data.db.Role.USER) state.myName.ifBlank { stringResource(R.string.default_nickname) } else contact.name
                isomeria.aichat.ui.components.QuoteBar(
                    label = isomeria.aichat.ui.components.quoteLabel(name, quoted.type, quoted.content),
                    onClose = viewModel::cancelQuote,
                )
            }
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
                focusRequester = focusRequester,
                voice = voiceActions,
            )
            ChatBottomArea(
                open = panel != InputPanel.NONE || awaitingKeyboard,
                height = panelHeight,
                panel = when (panel) {
                    InputPanel.EMOJI -> { { EmojiPanel(
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
                    ) } }
                    InputPanel.MORE -> { { MorePanel(
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
                        onCall = { state.contact?.let { onCall(it.id) } },
                    ) } }
                    InputPanel.NONE -> null
                },
            )
        } else if (state.loaded) {
            Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chat_contact_missing), color = colors.textTertiary)
            }
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
        val needsSettings = reason == FailReason.AUTH || reason == FailReason.NO_API_KEY || reason == FailReason.CONFIGURATION || reason == FailReason.ASR_UNAVAILABLE
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
                else if (reason == FailReason.ASR_UNAVAILABLE) onOpenVoiceSettings()
                else if (needsSettings) state.contact?.let { onOpenProvider(it.providerId) } else viewModel.retry()
            },
        )
    }

    if (showAsrSetup) {
        // 0.7.2：联系人单独选的识别来源用不了时不换成全局的，提示去联系人资料改，或把那个来源设置好
        val own = asrOwnSource && state.contact != null
        WeDialog(
            title = stringResource(if (own) R.string.voice_asr_own_unavailable_title else R.string.voice_asr_setup_title),
            message = if (own) stringResource(R.string.voice_asr_own_unavailable_message, state.contact?.name.orEmpty())
            else stringResource(R.string.voice_asr_setup_message),
            confirmText = stringResource(if (own) R.string.voice_asr_own_unavailable_action else R.string.action_go_settings),
            onDismiss = { showAsrSetup = false },
            onConfirm = {
                showAsrSetup = false
                if (own) state.contact?.let { onEditContact(it.id) } else onOpenVoiceSettings()
            },
        )
    }
    voiceIntroName?.let { name ->
        WeDialog(
            title = stringResource(R.string.voice_intro_title),
            // 离线识别（0.7.1）时录音不离开手机
            message = if (name.isEmpty()) stringResource(R.string.voice_intro_message_local) else stringResource(R.string.voice_intro_message, name),
            confirmText = stringResource(R.string.voice_intro_confirm),
            onDismiss = { voiceIntroName = null },
            onConfirm = {
                voiceIntroName = null
                viewModel.voiceIntroShown = true
            },
        )
    }
    if (showMicDenied) {
        WeDialog(
            title = stringResource(R.string.voice_mic_denied_title),
            message = stringResource(R.string.voice_mic_denied_message),
            confirmText = stringResource(R.string.action_go_settings),
            onDismiss = { showMicDenied = false },
            onConfirm = {
                showMicDenied = false
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
    }

    deleteSelectedDialog?.let { count ->
        WeDialog(
            message = stringResource(R.string.chat_delete_selected_confirm, count),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleteSelectedDialog = null },
            onConfirm = {
                deleteSelectedDialog = null
                viewModel.deleteSelected()
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
    pagingRows: LazyPagingItems<ChatRow>? = null,
    playingId: Long? = null,
    /** 截图用：打开这条消息的长按菜单 */
    menuOpenId: Long? = null,
    /** 多选（0.9.0）时选中的消息 id；不在多选时为 null */
    selection: Set<Long>? = null,
    onToggleSelection: (Message) -> Unit = {},
    /** 正在翻译、翻译失败的消息（0.9.0） */
    translating: Map<Long, TranslationUi> = emptyMap(),
    /** 打开聊天的时间（0.9.1）：之后新到的、自己刚发的消息有出现动画，分页加载的历史没有 */
    openedAt: Long = remember { System.currentTimeMillis() },
) {
    val defaultName = stringResource(R.string.default_nickname)
    val highlightColor = WeTheme.colors.messageHighlight
    val appear = remember(openedAt) { BubbleAppearTracker(openedAt) }
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
        items(count = pagingRows?.itemCount ?: rows.size, key = pagingRows?.itemKey { it.key } ?: { index -> rows[rows.lastIndex - index].key }) { index ->
            val row = if (pagingRows != null) pagingRows[index] else rows[rows.lastIndex - index]
            if (row == null) return@items
            val highlighted = row is ChatRow.Item && row.message.id == highlightId
            // 记在这一项里：重组时不会再判断一次；滚出屏幕再回来时 tracker 已经见过它，不重播
            val appearProgress = remember(row.key) {
                val fresh = row is ChatRow.Item && appear.claim(row.message.id, row.message.createdAt)
                Animatable(if (fresh) 0f else 1f)
            }
            LaunchedEffect(appearProgress) {
                if (appearProgress.value < 1f) appearProgress.animateTo(1f, tween(WeMotion.BUBBLE_APPEAR, easing = WeMotion.Decelerate))
            }
            Box(
                Modifier
                    // 不要默认的淡入：往上翻加载历史时整页消息不该一条条淡入（0.9.1）；位置变化和删除的淡出保留
                    .animateItem(fadeInSpec = null)
                    .graphicsLayer {
                        // 气泡出现：淡入加轻微上移，约 180ms；系统移除动画时 Compose 直接跳到终点
                        val p = appearProgress.value
                        alpha = p
                        translationY = (1f - p) * WeMotion.BUBBLE_RISE.dp.toPx()
                    }
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
                        playing = row.message.id == playingId,
                        menuInitiallyOpen = row.message.id == menuOpenId,
                        translating = translating[row.message.id],
                        selection = selection?.let { ids ->
                            RowSelection(
                                selected = row.message.id in ids,
                                selectable = isomeria.aichat.data.repo.isSelectable(row.message),
                                onToggle = { onToggleSelection(row.message) },
                            )
                        },
                    )
                }
            }
        }
    }
}

private const val FOCUS_IME_WAIT_MILLIS = 800L

/**
 * 哪些消息播出现动画（0.9.1）：打开聊天以后才有的消息（新到的回复、自己刚发的、到点送达的），每条只播一次；
 * 打开时已有的和往上翻加载的历史都不播。滚出屏幕再滚回来不重播。
 */
internal class BubbleAppearTracker(private val openedAt: Long) {
    private val seen = HashSet<Long>()

    /** 第一次见到这条消息、而且是打开聊天以后才有的，返回 true */
    fun claim(id: Long, createdAt: Long): Boolean = seen.add(id) && createdAt >= openedAt
}

@Composable
fun NewMessagesButton(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .padding(end = WeTheme.dimens.chatAvatarEdge, bottom = WeTheme.dimens.messageSpacing)
            .shadow(2.dp, shape)
            .clip(shape)
            .background(WeTheme.colors.listBackground)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = WeTheme.dimens.buttonHeight)
            .padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.chat_new_messages, count), color = WeTheme.colors.brandText, style = WeTheme.typography.small)
    }
}

/**
 * 输入栏下面的区域：键盘、表情或更多面板、导航栏，取最高的那个。
 *
 * 面板（0.9.1 真机反馈后改）：打开时像键盘一样从下往上升起，关上（返回手势、点聊天区域、切到语音）时落下，
 * 输入栏和消息列表跟着走；落下的过程中仍画着刚才的面板。和键盘互相切换时从键盘当前的高度接着走，输入栏不跳。
 * [open] 时留出 [height]（不含导航栏）；[panel] 是面板内容，null 表示只留位置（从面板切回键盘、等键盘升起时）。
 * 高度在布局阶段按窗口边衬计算，键盘动画时不会让整页重组。[ime] 是键盘的位置，测试里可以指定。
 */
@Composable
internal fun ChatBottomArea(
    open: Boolean,
    height: Dp,
    panel: (@Composable () -> Unit)?,
    ime: WindowInsets = WindowInsets.ime,
) {
    val density = LocalDensity.current
    val navigationBars = WindowInsets.navigationBars
    val target = with(density) { height.roundToPx() }.toFloat()
    val reserve = remember { Animatable(if (open) target else 0f) }
    LaunchedEffect(open, target) {
        val keyboard = (ime.getBottom(density) - navigationBars.getBottom(density)).coerceAtLeast(0).toFloat()
        if (open) {
            // 键盘正开着（从键盘切到面板）：从键盘的高度接着走，看起来不动
            if (keyboard > reserve.value) reserve.snapTo(keyboard.coerceAtMost(target))
            reserve.animateTo(target, tween(WeMotion.PANEL_OPEN, easing = WeMotion.Panel))
        } else if (keyboard >= reserve.value - 4f) {
            // 已经换成键盘、键盘到位：被键盘盖着，直接收起
            reserve.snapTo(0f)
        } else {
            reserve.animateTo(0f, tween(WeMotion.PANEL_CLOSE, easing = WeMotion.Panel))
        }
    }
    // 落下的过程中 panel 已经是 null，接着画刚才的面板
    val lastPanel = remember { mutableStateOf(panel) }
    SideEffect { if (panel != null) lastPanel.value = panel }
    val drawn = panel ?: lastPanel.value
    val showing by remember { derivedStateOf { reserve.value >= 1f } }
    Box(
        Modifier
            .fillMaxWidth()
            .background(WeTheme.colors.barBackground)
            .layout { measurable, constraints ->
                val nav = navigationBars.getBottom(this)
                val reserved = reserve.value.roundToInt()
                val h = maxOf(ime.getBottom(this), nav, if (reserved > 0) reserved + nav else 0)
                val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                layout(placeable.width, h) { placeable.place(0, 0) }
            },
    ) {
        if (showing && drawn != null) {
            // 面板按完整高度排版，只露出已经升起的部分（顶部先出来，和键盘一样）
            Box(
                Modifier
                    .fillMaxWidth()
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                        layout(placeable.width, minOf(reserve.value.roundToInt(), placeable.height)) { placeable.place(0, 0) }
                    },
            ) { drawn() }
        }
    }
}

/** 回到最新一条：[animate] 时滑过去（和收到新消息一样），否则直接跳到底部 */
internal suspend fun LazyListState.scrollToLatest(animate: Boolean) {
    if (animate) animateScrollToItem(0) else scrollToItem(0)
}

/**
 * 点聊天里的通话消息（0.8.0）：是正在进行的这通电话就回到通话页，否则看详情。
 * 还标着“通话中”、却不是正在进行的（App 在通话中被关掉、下次启动还没补完）也看详情，不会因此又打一通
 */
internal fun opensCallPage(message: isomeria.aichat.data.db.Message, inCall: Boolean): Boolean = message.callOngoing && inCall

/**
 * 正在和这个联系人通话时代替输入栏（0.8.0）：浅绿色底，左边一个呼吸的绿点，写着通话已经打了多久；点一下回到通话页。
 * [startedAt] 是通话开始的时间（截图测试里传 [now] 固定时长）。
 */
@Composable
internal fun InCallBar(startedAt: Long?, now: (() -> Long)? = null, onClick: () -> Unit) {
    val colors = WeTheme.colors
    var current by remember { mutableLongStateOf(now?.invoke() ?: System.currentTimeMillis()) }
    if (now == null) {
        LaunchedEffect(Unit) {
            while (true) {
                current = System.currentTimeMillis()
                delay(1_000 - current % 1_000)
            }
        }
    }
    val pulse by isomeria.aichat.ui.theme.loopingFloat(
        0.35f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        still = 1f,
        label = "inCallPulse",
    )
    val tint = colors.brand.copy(alpha = if (colors.isDark) 0.16f else 0.10f).compositeOver(colors.barBackground)
    val text = startedAt?.let { stringResource(R.string.chat_in_call_duration, isomeria.aichat.core.CallDurations.format((current - it).coerceAtLeast(0))) }
        ?: stringResource(R.string.chat_in_call)
    Box(
        Modifier
            .fillMaxWidth()
            .background(tint)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .navigationBarsPadding()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = pulse }
                    .background(colors.brand, androidx.compose.foundation.shape.CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            WeIcon(isomeria.aichat.ui.components.WeIcons.Phone, null, tint = colors.brandText, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = WeTheme.typography.listTitle, color = colors.brandText, modifier = Modifier.weight(1f, fill = false))
        }
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
    FailReason.ASR_FAILED -> R.string.error_asr_failed
    FailReason.ASR_UNAVAILABLE -> R.string.error_asr_unavailable
    FailReason.UNKNOWN -> R.string.error_unknown
}

/** Compose 里拿到的 Context 可能包了几层，找到 Activity 才能判断“不再询问” */
internal fun android.content.Context.findActivity(): android.app.Activity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return null
}

/** 滚到第 [index] 行并放在可视区中间（搜索定位、跳到引用的原消息） */
private suspend fun centerOn(listState: LazyListState, index: Int) {
    listState.scrollToItem(index)
    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val viewport = listState.layoutInfo.viewportEndOffset - listState.layoutInfo.viewportStartOffset
    listState.scrollBy((item.offset - (viewport - item.size) / 2).toFloat())
}
