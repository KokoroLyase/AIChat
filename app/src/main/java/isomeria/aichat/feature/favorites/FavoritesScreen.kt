package isomeria.aichat.feature.favorites

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.CallDurations
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.repo.FavoriteEntry
import isomeria.aichat.data.repo.FavoriteRepository
import isomeria.aichat.data.voice.AndroidVoicePlayer
import isomeria.aichat.data.voice.VoicePlayback
import isomeria.aichat.feature.call.CallReplay
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.MenuItem
import isomeria.aichat.ui.components.TopBarIcon
import isomeria.aichat.ui.components.VoiceBubble
import isomeria.aichat.ui.components.WeContextMenu
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeIcon
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeSearchBar
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** 收藏列表里能直接播放的语音：把语音收藏当成只有一句的“通话”交给 [CallReplay]，id 用收藏的 id */
internal fun voiceTurns(entries: List<FavoriteEntry>): List<CallTurn> = entries
    .filter { it.favorite.type == MessageType.VOICE && it.favorite.audioPath != null }
    .map { entry ->
        val favorite = entry.favorite
        CallTurn(id = favorite.id, messageId = 0, seq = 0, role = favorite.role, text = favorite.content, audioPath = favorite.audioPath, audioMillis = favorite.audioMillis, createdAt = favorite.createdAt)
    }

/** 搜索：文字、语音的转写、通话里的每一句、来源联系人的名字（收藏时的和现在的），不分大小写 */
internal fun matches(entry: FavoriteEntry, query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    val favorite = entry.favorite
    return favorite.content.contains(q, ignoreCase = true) ||
        favorite.contactName.contains(q, ignoreCase = true) ||
        entry.source?.name?.contains(q, ignoreCase = true) == true ||
        entry.turns.any { it.text.contains(q, ignoreCase = true) }
}

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favorites: FavoriteRepository,
    player: AndroidVoicePlayer,
    private val playback: VoicePlayback,
    val timeFormatter: ChatTimeFormatter,
) : ViewModel() {
    val query = MutableStateFlow("")
    val replay = CallReplay(player, viewModelScope)

    /** null 表示还在读 */
    private val all: StateFlow<List<FavoriteEntry>?> = favorites.observeEntries()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val entries: StateFlow<List<FavoriteEntry>?> = combine(all, query) { list, q ->
        list?.also { replay.setTurns(voiceTurns(it)) }?.filter { matches(it, q) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggleVoice(favoriteId: Long) {
        playback.stop()
        replay.toggleTurn(favoriteId)
    }

    fun stop() = replay.stop()

    fun delete(favoriteId: Long) {
        if (replay.state.value?.turnId == favoriteId) replay.stop()
        viewModelScope.launch { favorites.delete(favoriteId) }
    }

    fun copyText(entry: FavoriteEntry): String = favorites.copyText(entry)

    override fun onCleared() = replay.stop()
}

/**
 * 收藏（0.9.0，“我”页进入）：按收藏时间倒序，每条是一张卡片：内容预览（文字、可以直接播放的语音、通话的时长和前几句）、
 * 下面是来源联系人和收藏时间。点开看详情；长按“复制”“删除”。顶栏右边的放大镜搜索文字。
 */
@Composable
fun FavoritesScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, viewModel: FavoritesViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val replay by viewModel.replay.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { viewModel.stop() }
    BackHandler(searching) {
        searching = false
        viewModel.query.value = ""
    }
    FavoritesContent(
        entries = entries,
        query = query,
        searching = searching,
        playingId = replay?.turnId,
        formatter = viewModel.timeFormatter,
        onBack = onBack,
        onStartSearch = { searching = true },
        onQueryChange = { viewModel.query.value = it },
        onCancelSearch = {
            searching = false
            viewModel.query.value = ""
        },
        onOpen = onOpen,
        onToggleVoice = viewModel::toggleVoice,
        onCopy = { entry -> scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("favorite", viewModel.copyText(entry)))) } },
        onDelete = { deleting = it.favorite.id },
    )
    deleting?.let { id ->
        WeDialog(
            message = stringResource(R.string.favorite_delete_confirm),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                viewModel.delete(id)
            },
        )
    }
}

@Composable
fun FavoritesContent(
    entries: List<FavoriteEntry>?,
    query: String,
    searching: Boolean,
    playingId: Long?,
    formatter: ChatTimeFormatter,
    onBack: () -> Unit,
    onStartSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onCancelSearch: () -> Unit,
    onOpen: (Long) -> Unit,
    onToggleVoice: (Long) -> Unit,
    onCopy: (FavoriteEntry) -> Unit,
    onDelete: (FavoriteEntry) -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val colors = WeTheme.colors
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        if (searching) {
            val focus = remember { FocusRequester() }
            WeSearchBar(query, onQueryChange, onCancelSearch, focus, hint = stringResource(R.string.favorites_search_hint))
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        } else {
            WeTopBar(stringResource(R.string.favorites_title), onBack = onBack, showLine = true) {
                TopBarIcon(WeIcons.Search, stringResource(R.string.favorites_search_hint), onStartSearch)
            }
        }
        when {
            entries == null -> Unit
            entries.isEmpty() -> Text(
                stringResource(if (query.isBlank()) R.string.favorites_empty else R.string.favorites_no_result),
                style = WeTheme.typography.small,
                color = colors.textTertiary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            else -> LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.favorite.id }) { entry ->
                    FavoriteCard(
                        entry = entry,
                        time = formatter.forList(entry.favorite.createdAt, now),
                        playing = playingId == entry.favorite.id,
                        onClick = { onOpen(entry.favorite.id) },
                        onToggleVoice = { onToggleVoice(entry.favorite.id) },
                        onCopy = { onCopy(entry) },
                        onDelete = { onDelete(entry) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** 收藏列表的一张卡片 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoriteCard(
    entry: FavoriteEntry,
    time: String,
    playing: Boolean,
    onClick: () -> Unit,
    onToggleVoice: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val favorite = entry.favorite
    var pressAt by remember { mutableStateOf(Offset.Zero) }
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.listBackground)
                .pointerInput(Unit) {
                    // 只记录按下位置，用来在手指处弹出菜单，不消费事件
                    awaitEachGesture { pressAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position }
                }
                .combinedClickable(onClick = onClick, onLongClick = { menuAt = IntOffset(pressAt.x.roundToInt(), pressAt.y.roundToInt()) })
                .padding(horizontal = dimens.horizontalPadding, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FavoritePreview(entry, playing, onToggleVoice)
            SourceLine(entry, time)
        }
        menuAt?.let { at ->
            WeContextMenu(
                pressOffset = at,
                items = listOf(
                    MenuItem(stringResource(R.string.action_copy), onClick = onCopy),
                    MenuItem(stringResource(R.string.action_delete), onClick = onDelete),
                ),
                onDismiss = { menuAt = null },
            )
        }
    }
}

/** 卡片上面的内容：文字最多三行；语音是能直接点着放的语音条和转写；通话是时长和前两句 */
@Composable
private fun FavoritePreview(entry: FavoriteEntry, playing: Boolean, onToggleVoice: () -> Unit) {
    val colors = WeTheme.colors
    val favorite = entry.favorite
    when (favorite.type) {
        MessageType.VOICE -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                Text(stringResource(R.string.copy_voice), style = WeTheme.typography.message, color = colors.textSecondary)
            }
            if (favorite.content.isNotBlank()) {
                Text(favorite.content, style = WeTheme.typography.message, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        MessageType.CALL -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeIcon(WeIcons.Phone, null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.favorite_call_summary, CallDurations.format(favorite.audioMillis)), style = WeTheme.typography.message, color = colors.textPrimary)
            }
            val me = stringResource(R.string.copy_me)
            val failed = stringResource(R.string.call_turn_asr_failed)
            // 来源联系人还在时用现在的名字，和下面的来源一行一致
            val contactName = entry.source?.name ?: favorite.contactName
            for (turn in entry.turns.take(PREVIEW_TURNS)) {
                val speaker = if (turn.role == Role.USER) me else contactName
                val text = if (turn.status == CallTurnStatus.ASR_FAILED || turn.text.isBlank()) failed else turn.text
                Text(
                    stringResource(R.string.copy_speaker_line, speaker, text),
                    style = WeTheme.typography.small,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        else -> Text(favorite.content, style = WeTheme.typography.message, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** 卡片下面一行：来源联系人的小头像和名字（已经不在的用存下的名字和首字头像）、收藏时间 */
@Composable
internal fun SourceLine(entry: FavoriteEntry, time: String) {
    val colors = WeTheme.colors
    val favorite = entry.favorite
    val name = entry.source?.name ?: favorite.contactName
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(entry.source?.avatarPath, name, entry.source?.avatarColor ?: favorite.contactAvatarColor, 18.dp, corner = 3.dp)
        Spacer(Modifier.width(6.dp))
        Text(name, style = WeTheme.typography.listTime, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(time, style = WeTheme.typography.listTime, color = colors.textTertiary)
    }
}

private const val PREVIEW_TURNS = 2
