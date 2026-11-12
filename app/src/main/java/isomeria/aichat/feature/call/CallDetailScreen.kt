package isomeria.aichat.feature.call

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.CallDurations
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallEndReason
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.Role as SpeakerRole
import isomeria.aichat.data.voice.AndroidVoicePlayer
import isomeria.aichat.data.voice.VoicePlayback
import isomeria.aichat.navigation.CallDetailRoute
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 通话详情页要显示的东西 */
data class CallDetail(val call: Message, val contact: Contact?, val turns: List<CallTurn>)

@HiltViewModel
class CallDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    db: AppDatabase,
    player: AndroidVoicePlayer,
    private val playback: VoicePlayback,
    val timeFormatter: ChatTimeFormatter,
) : ViewModel() {
    private val messageId = savedStateHandle.toRoute<CallDetailRoute>().messageId

    val replay = CallReplay(player, viewModelScope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val detail: StateFlow<CallDetail?> = db.messageDao().observe(messageId).flatMapLatest { call ->
        if (call == null) flowOf(null) else combine(db.contactDao().observe(call.contactId), db.callDao().observeTurns(messageId)) { contact, turns ->
            replay.setTurns(turns)
            CallDetail(call, contact, turns)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 开始放之前停掉聊天页正在放的语音消息 */
    fun toggleTurn(turnId: Long) {
        playback.stop()
        replay.toggleTurn(turnId)
    }

    fun playAll(fromTurnId: Long? = null) {
        playback.stop()
        replay.playAll(fromTurnId)
    }

    fun stop() = replay.stop()

    override fun onCleared() {
        replay.stop()
    }
}

/**
 * 通话详情（0.8.0）：这次通话什么时候、打了多久、为什么结束；「播放完整通话」（按顺序接着放各条，进度条可以拖到某一条），
 * 下面像聊天一样列出每一条话，点一条放它的音频。离开页面就停。
 */
@Composable
fun CallDetailScreen(onBack: () -> Unit, viewModel: CallDetailViewModel = hiltViewModel()) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val playing by viewModel.replay.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    val current = detail
    if (current == null) {
        Column(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) { WeTopBar(stringResource(R.string.call_detail_title), onBack = onBack) }
        return
    }
    var position by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playing) {
        while (playing != null) {
            position = viewModel.replay.position()
            delay(200)
        }
    }
    CallDetailContent(
        detail = current,
        header = callHeader(current, viewModel.timeFormatter),
        playingTurnId = playing?.turnId,
        playingAll = playing?.all == true,
        position = if (playing?.all == true) position else 0L,
        total = viewModel.replay.totalMillis(),
        playable = viewModel.replay.playable().map { it.id }.toSet(),
        onBack = onBack,
        onToggleTurn = viewModel::toggleTurn,
        onPlayAll = { viewModel.playAll() },
        onStop = viewModel::stop,
        onSeek = { to -> viewModel.replay.turnAt(to)?.let { viewModel.playAll(it.id) } },
    )
}

/** “11月1日 20:00 开始，通话时长 03:21”，结束原因另起一行 */
@Composable
private fun callHeader(detail: CallDetail, formatter: ChatTimeFormatter): String {
    val call = detail.call
    val started = formatter.forChat(call.createdAt, System.currentTimeMillis())
    val line = if (call.callOngoing) {
        stringResource(R.string.call_detail_ongoing, started)
    } else {
        stringResource(R.string.call_detail_summary, started, CallDurations.format(call.voiceMillis))
    }
    val reason = when (CallEndReason.of(call.errorCode)) {
        CallEndReason.AUDIO_LOST -> stringResource(R.string.call_end_audio_lost)
        CallEndReason.FAILURES -> stringResource(R.string.call_end_failures)
        CallEndReason.TIME_LIMIT -> stringResource(R.string.call_end_time_limit)
        CallEndReason.IDLE -> stringResource(R.string.call_end_idle)
        CallEndReason.INTERRUPTED -> stringResource(R.string.call_end_interrupted)
        CallEndReason.HANG_UP -> null
    }
    return if (reason == null || call.callOngoing) line else "$line\n$reason"
}

@Composable
fun CallDetailContent(
    detail: CallDetail,
    header: String,
    playingTurnId: Long?,
    playingAll: Boolean,
    position: Long,
    total: Long,
    playable: Set<Long>,
    onBack: () -> Unit,
    onToggleTurn: (Long) -> Unit,
    onPlayAll: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val name = detail.contact?.name.orEmpty()
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(stringResource(R.string.call_detail_title), onBack = onBack, showLine = true)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.fillMaxWidth().background(colors.listBackground).padding(horizontal = dimens.horizontalPadding, vertical = 16.dp)) {
                    Text(header, style = WeTheme.typography.small, color = colors.textTertiary)
                    if (playable.isNotEmpty()) {
                        Spacer(Modifier.size(12.dp))
                        PlayAllRow(playingAll, position, total, onPlayAll, onStop, onSeek)
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
            if (detail.turns.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.call_detail_empty),
                        style = WeTheme.typography.small,
                        color = colors.textTertiary,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            items(detail.turns, key = { it.id }) { turn ->
                TurnRow(
                    turn = turn,
                    name = if (turn.role == SpeakerRole.USER) stringResource(R.string.call_subtitle_me) else name,
                    playing = playingTurnId == turn.id,
                    playable = turn.id in playable,
                    onClick = { onToggleTurn(turn.id) },
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** 「播放完整通话」/「停止」和进度条（可以拖到某一条） */
@Composable
private fun PlayAllRow(playing: Boolean, position: Long, total: Long, onPlay: () -> Unit, onStop: () -> Unit, onSeek: (Long) -> Unit) {
    val colors = WeTheme.colors
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    Text(
        stringResource(if (playing) R.string.call_detail_stop else R.string.call_detail_play_all),
        style = WeTheme.typography.listTitle,
        color = colors.link,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Button) { if (playing) onStop() else onPlay() }
            .padding(vertical = 8.dp),
    )
    // 时间单独一行，字放大、屏幕窄时也不和按钮挤在一起
    val shown = if (dragging) (dragValue * total).toLong() else position
    Text(
        "${CallDurations.format(shown)} / ${CallDurations.format(total)}",
        style = WeTheme.typography.small,
        color = colors.textTertiary,
    )
    val progressLabel = stringResource(R.string.call_detail_progress)
    Slider(
        value = if (dragging) dragValue else if (total > 0) (position.toFloat() / total).coerceIn(0f, 1f) else 0f,
        onValueChange = {
            dragging = true
            dragValue = it
        },
        onValueChangeFinished = {
            dragging = false
            onSeek((dragValue * total).toLong())
        },
        colors = SliderDefaults.colors(thumbColor = colors.brand, activeTrackColor = colors.brand, inactiveTrackColor = colors.sliderTrackOff),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = progressLabel },
    )
}

/** 一条话：像聊天一样，自己的在右、绿色；联系人的在左；上面一行小字写是谁说的。点一下放它的音频 */
@Composable
private fun TurnRow(turn: CallTurn, name: String, playing: Boolean, playable: Boolean, onClick: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val sent = turn.role == SpeakerRole.USER
    val text = when {
        turn.status == CallTurnStatus.ASR_FAILED || turn.text.isBlank() -> stringResource(R.string.call_turn_asr_failed)
        else -> turn.text
    }
    val note = when (turn.status) {
        CallTurnStatus.TTS_FAILED -> stringResource(R.string.call_turn_tts_failed)
        CallTurnStatus.INTERRUPTED -> stringResource(R.string.call_turn_interrupted)
        else -> null
    }
    val seconds = stringResource(R.string.voice_seconds, ((turn.audioMillis + 500) / 1000).toInt().coerceAtLeast(1))
    val state = stringResource(if (playing) R.string.voice_state_playing else R.string.call_turn_tap_to_play)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = dimens.chatAvatarEdge, vertical = dimens.messageSpacing / 2),
        horizontalArrangement = if (sent) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (sent) Spacer(Modifier.size(dimens.bubbleOppositeMinGap))
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (sent) Alignment.End else Alignment.Start) {
            Text(name, style = WeTheme.typography.listTime, color = colors.textTertiary)
            Spacer(Modifier.size(2.dp))
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(dimens.bubbleCorner))
                    .background(if (sent) colors.bubbleSent else colors.bubbleReceived)
                    .then(if (playable) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                    .semantics { if (playable) stateDescription = state }
                    .padding(horizontal = dimens.bubblePaddingHorizontal, vertical = dimens.bubblePaddingVertical),
            ) {
                Text(text, style = WeTheme.typography.message, color = if (sent) colors.bubbleSentText else colors.bubbleReceivedText)
            }
            val meta = listOfNotNull(if (playable) seconds else null, if (playing) stringResource(R.string.voice_state_playing) else null, note).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.size(2.dp))
                Text(meta, style = WeTheme.typography.listTime, color = if (playing) colors.brandText else colors.textTertiary)
            }
        }
        if (!sent) Spacer(Modifier.size(dimens.bubbleOppositeMinGap))
    }
}
