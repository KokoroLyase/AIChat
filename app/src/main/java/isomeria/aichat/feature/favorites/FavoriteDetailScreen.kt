package isomeria.aichat.feature.favorites

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.CallDurations
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.repo.FavoriteEntry
import isomeria.aichat.data.repo.FavoriteRepository
import isomeria.aichat.data.voice.AndroidVoicePlayer
import isomeria.aichat.data.voice.VoicePlayback
import isomeria.aichat.feature.call.CallDetail
import isomeria.aichat.feature.call.CallDetailContent
import isomeria.aichat.feature.call.CallInfo
import isomeria.aichat.feature.call.CallPlayerUi
import isomeria.aichat.feature.call.CallReplay
import isomeria.aichat.navigation.FavoriteDetailRoute
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.TopBarIcon
import isomeria.aichat.ui.components.VoiceBubble
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 收藏的通话在详情页里的样子：复用通话详情（0.8.0）的头部、播放条和逐句列表 */
internal fun callDetailOf(entry: FavoriteEntry): CallDetail {
    val favorite = entry.favorite
    val call = Message(
        id = -favorite.id, contactId = favorite.contactId, role = favorite.role, type = MessageType.CALL,
        status = MessageStatus.SUCCESS, voiceMillis = favorite.audioMillis.coerceAtLeast(1), createdAt = favorite.messageCreatedAt,
    )
    val contact = entry.source ?: Contact(
        id = favorite.contactId, name = favorite.contactName, avatarColor = favorite.contactAvatarColor,
        providerId = 0, model = "", createdAt = favorite.contactCreatedAt, updatedAt = favorite.contactCreatedAt,
    )
    return CallDetail(call, contact, callTurnsOf(entry))
}

/** 收藏的每一句换成通话详情用的样子；语音收藏是只有一句的 */
internal fun callTurnsOf(entry: FavoriteEntry): List<CallTurn> {
    val favorite = entry.favorite
    return if (favorite.type == MessageType.VOICE) {
        listOf(CallTurn(id = favorite.id, messageId = -favorite.id, seq = 0, role = favorite.role, text = favorite.content, audioPath = favorite.audioPath, audioMillis = favorite.audioMillis, createdAt = favorite.messageCreatedAt))
    } else {
        entry.turns.map {
            CallTurn(id = it.id, messageId = -favorite.id, seq = it.seq, role = it.role, text = it.text, audioPath = it.audioPath, audioMillis = it.audioMillis, status = it.status, createdAt = it.createdAt)
        }
    }
}

@HiltViewModel
class FavoriteDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val favorites: FavoriteRepository,
    player: AndroidVoicePlayer,
    private val playback: VoicePlayback,
    val timeFormatter: ChatTimeFormatter,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<FavoriteDetailRoute>().favoriteId
    val replay = CallReplay(player, viewModelScope)

    /** 外层 null 是还在读；里面 null 是这条收藏已经不在了 */
    val entry: StateFlow<Result<FavoriteEntry?>?> = favorites.observeEntries()
        .map { list -> Result.success(list.firstOrNull { it.favorite.id == id }) }
        .onEach { result -> result.getOrNull()?.let { replay.setTurns(callTurnsOf(it)) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleTurn(turnId: Long) {
        playback.stop()
        replay.toggleTurn(turnId)
    }

    fun togglePlay() {
        if (replay.state.value?.let { it.all && !it.paused } != true) playback.stop()
        replay.toggleAll()
    }

    fun seekTo(position: Long) = replay.seekTo(position)

    fun pause() = replay.pause()

    fun stop() = replay.stop()

    fun copyText(entry: FavoriteEntry): String = favorites.copyText(entry)

    fun delete() {
        replay.stop()
        viewModelScope.launch { favorites.delete(id) }
    }

    override fun onCleared() = replay.stop()
}

/**
 * 收藏详情（0.9.0）：文字可以选择复制；语音可以播放、下面是转写；通话复用通话详情的播放条和逐句列表。
 * 右上角“···”：复制、删除。0.9.0 不做“跳到原消息”。
 */
@Composable
fun FavoriteDetailScreen(onBack: () -> Unit, viewModel: FavoriteDetailViewModel = hiltViewModel()) {
    val result by viewModel.entry.collectAsStateWithLifecycle()
    val replay by viewModel.replay.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }
    val entry = result?.getOrNull()
    // 在别处删掉了（例如恢复了备份）：回到列表
    LaunchedEffect(result) { if (result != null && entry == null) onBack() }
    if (entry == null) {
        Column(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) { WeTopBar(stringResource(R.string.favorite_detail_title), onBack = onBack) }
        return
    }
    val more: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        TopBarIcon(WeIcons.More, stringResource(R.string.chat_menu_more), { sheet = true })
    }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(replay) {
        position = viewModel.replay.position()
        while (replay?.paused == false) {
            delay(100)
            position = viewModel.replay.position()
        }
    }
    val state = replay
    val player = CallPlayerUi(
        turnId = state?.turnId,
        all = state?.all == true,
        paused = state?.paused == true,
        position = if (state?.all == true) position else 0L,
        total = viewModel.replay.totalMillis(),
    )
    val favorite = entry.favorite
    if (favorite.type == MessageType.CALL) {
        CallDetailContent(
            detail = callDetailOf(entry),
            info = CallInfo(viewModel.timeFormatter.forChat(favorite.messageCreatedAt, System.currentTimeMillis()), CallDurations.format(favorite.audioMillis), null),
            player = player,
            playable = viewModel.replay.playable().map { it.id }.toSet(),
            onBack = onBack,
            onToggleTurn = viewModel::toggleTurn,
            onTogglePlay = viewModel::togglePlay,
            onSeek = viewModel::seekTo,
            title = stringResource(R.string.favorite_detail_title),
            topBarActions = more,
        )
    } else {
        FavoriteMessageDetail(
            entry = entry,
            time = viewModel.timeFormatter.forChat(favorite.messageCreatedAt, System.currentTimeMillis()),
            savedAt = viewModel.timeFormatter.forChat(favorite.createdAt, System.currentTimeMillis()),
            playing = player.sounding && player.turnId == favorite.id,
            onBack = onBack,
            onToggleVoice = { viewModel.toggleTurn(favorite.id) },
            topBarActions = more,
        )
    }
    if (sheet) {
        WeActionSheet(
            options = listOf(
                SheetOption(stringResource(R.string.action_copy)) {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("favorite", viewModel.copyText(entry)))) }
                },
                SheetOption(stringResource(R.string.action_delete), destructive = true) { confirmDelete = true },
            ),
            onDismiss = { sheet = false },
        )
    }
    if (confirmDelete) {
        WeDialog(
            message = stringResource(R.string.favorite_delete_confirm),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                viewModel.delete()
                onBack()
            },
        )
    }
}

/** 文字和语音收藏的详情：上面是来源联系人和原消息时间，下面是内容（文字可以选择复制），最后一行是收藏时间 */
@Composable
fun FavoriteMessageDetail(
    entry: FavoriteEntry,
    time: String,
    savedAt: String,
    playing: Boolean,
    onBack: () -> Unit,
    onToggleVoice: () -> Unit,
    topBarActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val favorite = entry.favorite
    val name = entry.source?.name ?: favorite.contactName
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(stringResource(R.string.favorite_detail_title), onBack = onBack, showLine = true, actions = topBarActions)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .background(colors.listBackground)
                .padding(horizontal = dimens.horizontalPadding, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(entry.source?.avatarPath, name, entry.source?.avatarColor ?: favorite.contactAvatarColor, 40.dp)
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(if (favorite.role == Role.USER) stringResource(R.string.favorite_from_me, name) else name, style = WeTheme.typography.listTitle, color = colors.textPrimary)
                    Text(time, style = WeTheme.typography.small, color = colors.textTertiary)
                }
            }
            if (favorite.type == MessageType.VOICE) {
                if (favorite.audioPath != null) {
                    VoiceBubble(
                        millis = favorite.audioMillis,
                        sent = favorite.role == Role.USER,
                        playing = playing,
                        unplayed = false,
                        onClick = onToggleVoice,
                        onLongClick = {},
                    )
                } else {
                    Text(stringResource(R.string.favorite_voice_missing), style = WeTheme.typography.small, color = colors.textTertiary)
                }
            }
            if (favorite.content.isNotBlank()) {
                SelectionContainer { Text(favorite.content, style = WeTheme.typography.message, color = colors.textPrimary) }
            }
            Text(stringResource(R.string.favorite_saved_at, savedAt), style = WeTheme.typography.small, color = colors.textTertiary)
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}
