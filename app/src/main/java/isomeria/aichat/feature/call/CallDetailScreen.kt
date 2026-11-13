package isomeria.aichat.feature.call

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.SoundWaves
import isomeria.aichat.ui.components.WeIcons
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

/**
 * 顶部播放条和各条的播放状态。[turnId] 是正在放或停在的那一条，[all] 为 true 是完整通话；
 * [paused] 时停着；[position] 和 [total] 是完整通话的进度和总长。
 */
data class CallPlayerUi(
    val turnId: Long? = null,
    val all: Boolean = false,
    val paused: Boolean = false,
    val position: Long = 0,
    val total: Long = 0,
) {
    /** 正在出声 */
    val sounding: Boolean get() = turnId != null && !paused
}

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

    fun togglePlay() {
        if (replay.state.value?.let { it.all && !it.paused } != true) playback.stop()
        replay.toggleAll()
    }

    fun seekTo(position: Long) {
        if (replay.state.value?.let { it.all && !it.paused } == true) playback.stop()
        replay.seekTo(position)
    }

    fun pause() = replay.pause()

    fun stop() = replay.stop()

    override fun onCleared() {
        replay.stop()
    }
}

/**
 * 通话详情（0.8.0）：上面是联系人、什么时候打的、打了多久、为什么结束，和完整通话的播放条（播放、暂停、拖动进度）；
 * 下面像聊天一样列出每一条话，点一条单独放它。放完整通话时，正在放的那一条亮起并滚到看得见的地方。
 * 回到桌面时暂停，离开页面就停。
 */
@Composable
fun CallDetailScreen(onBack: () -> Unit, viewModel: CallDetailViewModel = hiltViewModel()) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val replay by viewModel.replay.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { viewModel.stop() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }
    val current = detail
    if (current == null) {
        Column(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) { WeTopBar(stringResource(R.string.call_detail_title), onBack = onBack) }
        return
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
    CallDetailContent(
        detail = current,
        info = callInfo(current, viewModel.timeFormatter),
        player = CallPlayerUi(
            turnId = state?.turnId,
            all = state?.all == true,
            paused = state?.paused == true,
            position = if (state?.all == true) position else 0L,
            total = viewModel.replay.totalMillis(),
        ),
        playable = viewModel.replay.playable().map { it.id }.toSet(),
        onBack = onBack,
        onToggleTurn = viewModel::toggleTurn,
        onTogglePlay = viewModel::togglePlay,
        onSeek = viewModel::seekTo,
    )
}

/** 详情页顶部的文字：开始时间、时长（还在通话时为 null）、结束原因（正常挂断时为 null） */
data class CallInfo(val started: String, val duration: String?, val reason: String?)

@Composable
private fun callInfo(detail: CallDetail, formatter: ChatTimeFormatter): CallInfo {
    val call = detail.call
    val started = formatter.forChat(call.createdAt, System.currentTimeMillis())
    if (call.callOngoing) return CallInfo(started, null, null)
    val reason = when (CallEndReason.of(call.errorCode)) {
        CallEndReason.AUDIO_LOST -> stringResource(R.string.call_end_audio_lost)
        CallEndReason.FAILURES -> stringResource(R.string.call_end_failures)
        CallEndReason.TIME_LIMIT -> stringResource(R.string.call_end_time_limit)
        CallEndReason.IDLE -> stringResource(R.string.call_end_idle)
        CallEndReason.INTERRUPTED -> stringResource(R.string.call_end_interrupted)
        CallEndReason.MICROPHONE -> stringResource(R.string.call_end_microphone)
        CallEndReason.HANG_UP -> null
    }
    return CallInfo(started, CallDurations.format(call.voiceMillis), reason)
}

@Composable
fun CallDetailContent(
    detail: CallDetail,
    info: CallInfo,
    player: CallPlayerUi,
    playable: Set<Long>,
    onBack: () -> Unit,
    onToggleTurn: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    /** 收藏的通话（0.9.0）也用这一页：标题和右上角换掉 */
    title: String = stringResource(R.string.call_detail_title),
    topBarActions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val colors = WeTheme.colors
    val name = detail.contact?.name.orEmpty()
    val listState = rememberLazyListState()
    // 放完整通话时跟着正在放的那一条：不在屏幕上就滚过去（列表前面有两项：头部和间隔）
    LaunchedEffect(player.turnId, player.all, player.paused) {
        if (!player.all || player.paused) return@LaunchedEffect
        val index = detail.turns.indexOfFirst { it.id == player.turnId }.takeIf { it >= 0 } ?: return@LaunchedEffect
        val target = index + HEADER_ITEMS
        val visible = listState.layoutInfo.visibleItemsInfo
        val shown = visible.firstOrNull { it.index == target }
        val viewport = listState.layoutInfo.viewportEndOffset
        if (shown == null || shown.offset < 0 || shown.offset + shown.size > viewport) listState.animateScrollToItem(target, -viewport / 4)
    }
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title, onBack = onBack, showLine = true, actions = topBarActions)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "header") { CallDetailHeader(detail, info, name, player, playable.isNotEmpty(), onTogglePlay, onSeek) }
            item(key = "gap") { Spacer(Modifier.size(8.dp)) }
            if (detail.turns.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.call_detail_empty),
                        style = WeTheme.typography.small,
                        color = colors.textTertiary,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
            itemsIndexed(detail.turns, key = { _, turn -> turn.id }) { _, turn ->
                TurnRow(
                    turn = turn,
                    name = if (turn.role == SpeakerRole.USER) stringResource(R.string.call_subtitle_me) else name,
                    playing = player.sounding && player.turnId == turn.id,
                    current = player.all && player.turnId == turn.id,
                    playable = turn.id in playable,
                    onClick = { onToggleTurn(turn.id) },
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

private const val HEADER_ITEMS = 2

/** 头部：头像、名字、开始时间和时长、结束原因；有能放的音频时下面是播放条 */
@Composable
private fun CallDetailHeader(
    detail: CallDetail,
    info: CallInfo,
    name: String,
    player: CallPlayerUi,
    canPlay: Boolean,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val contact = detail.contact
    Column(Modifier.fillMaxWidth().background(colors.listBackground).padding(horizontal = dimens.horizontalPadding, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(contact?.avatarPath, name, contact?.avatarColor ?: 0, 52.dp)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = WeTheme.typography.listTitle, color = colors.textPrimary)
                Spacer(Modifier.size(4.dp))
                val line = if (info.duration == null) {
                    stringResource(R.string.call_detail_ongoing, info.started)
                } else {
                    stringResource(R.string.call_detail_summary, info.started, info.duration)
                }
                Text(line, style = WeTheme.typography.small, color = colors.textTertiary)
            }
        }
        info.reason?.let { reason ->
            Spacer(Modifier.size(12.dp))
            Text(
                reason,
                style = WeTheme.typography.listTime,
                color = colors.textTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.pageBackground)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (canPlay) {
            Spacer(Modifier.size(16.dp))
            PlayerBar(player, onTogglePlay, onSeek)
        }
    }
}

/** 播放条：左边圆形的播放/暂停，右边进度条（可以拖），下面是现在的位置和总长 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // 自定义滑块和轨道的 Slider 重载仍是实验性 API
@Composable
private fun PlayerBar(player: CallPlayerUi, onTogglePlay: () -> Unit, onSeek: (Long) -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val playing = player.all && player.sounding
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val buttonColor by animateColorAsState(if (pressed) colors.brandPressed else colors.brand, label = "playButton")
    val buttonLabel = stringResource(if (playing) R.string.call_detail_pause else R.string.call_detail_play_all)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(interactionSource = interaction, indication = null, onClick = onTogglePlay)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = buttonLabel
                    onClick { onTogglePlay(); true }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) WeIcons.Pause else WeIcons.Play,
                contentDescription = null,
                tint = Color.White,
                // 三角形看起来偏左，往右挪一点才居中
                modifier = Modifier.size(24.dp).then(if (playing) Modifier else Modifier.padding(start = 2.dp)),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val total = player.total
            val shown = if (dragging) (dragValue * total).toLong() else player.position
            val sliderColors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = colors.brand,
                inactiveTrackColor = colors.sliderTrackOff,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            )
            val progressLabel = stringResource(R.string.call_detail_progress)
            val progressState = stringResource(R.string.call_detail_progress_state, CallDurations.format(shown), CallDurations.format(total))
            Slider(
                value = if (dragging) dragValue else if (total > 0) (player.position.toFloat() / total).coerceIn(0f, 1f) else 0f,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek((dragValue * total).toLong())
                },
                colors = sliderColors,
                thumb = {
                    Box(Modifier.size(18.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
                },
                track = { sliderState ->
                    SliderDefaults.Track(
                        sliderState = sliderState,
                        modifier = Modifier.height(dimens.sliderTrack),
                        colors = sliderColors,
                        drawStopIndicator = null,
                        drawTick = { _, _ -> },
                        thumbTrackGapSize = 0.dp,
                        trackInsideCornerSize = dimens.sliderTrack / 2,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(28.dp).semantics {
                    contentDescription = progressLabel
                    stateDescription = progressState
                },
            )
            // 和进度条的两端对齐（轨道两头各留了半个滑块）
            Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp)) {
                Text(CallDurations.format(shown), style = WeTheme.typography.listTime, color = if (player.all) colors.brandText else colors.textTertiary)
                Spacer(Modifier.weight(1f))
                Text(CallDurations.format(total), style = WeTheme.typography.listTime, color = colors.textTertiary)
            }
        }
    }
}

/**
 * 一条话：像聊天一样，自己的在右、绿色；联系人的在左；上面一行小字写是谁说的，下面是时长和状态。点一下单独放它。
 * [playing] 时下面的声波动起来；[current] 是完整通话停在或正在放的那一条，气泡描一圈绿边。
 */
@Composable
private fun TurnRow(turn: CallTurn, name: String, playing: Boolean, current: Boolean, playable: Boolean, onClick: () -> Unit) {
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
    val background = if (sent) colors.bubbleSent else colors.bubbleReceived
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedOverlay = if (colors.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f)
    val outline by animateColorAsState(if (current || playing) colors.brand else Color.Transparent, label = "turnOutline")
    val shape = RoundedCornerShape(dimens.bubbleCorner)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = dimens.chatAvatarEdge, vertical = dimens.messageSpacing / 2),
        horizontalArrangement = if (sent) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (sent) Spacer(Modifier.size(dimens.bubbleOppositeMinGap))
        Column(Modifier.weight(1f, fill = false), horizontalAlignment = if (sent) Alignment.End else Alignment.Start) {
            Text(name, style = WeTheme.typography.listTime, color = colors.textTertiary)
            Spacer(Modifier.size(4.dp))
            Box(
                Modifier
                    .widthIn(max = 280.dp)
                    .clip(shape)
                    .background(if (pressed && playable) pressedOverlay.compositeOver(background) else background)
                    .border(2.dp, outline, shape)
                    .then(if (playable) Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick) else Modifier)
                    .semantics { if (playable) stateDescription = state }
                    .padding(horizontal = dimens.bubblePaddingHorizontal, vertical = dimens.bubblePaddingVertical),
            ) {
                Text(text, style = WeTheme.typography.message, color = if (sent) colors.bubbleSentText else colors.bubbleReceivedText)
            }
            val meta = listOfNotNull(if (playable) seconds else null, if (playing) stringResource(R.string.voice_state_playing) else null, note).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val tint = if (playing) colors.brandText else colors.textTertiary
                    if (playable) {
                        if (playing) {
                            val step by isomeria.aichat.ui.theme.loopingFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), still = 2.5f, label = "turnWaveStep")
                            SoundWaves(tint, step.toInt().coerceIn(0, 2) + 1, pointingRight = true, size = 14.dp)
                        } else {
                            Icon(WeIcons.Play, contentDescription = null, tint = tint, modifier = Modifier.size(12.dp))
                        }
                    }
                    Text(meta, style = WeTheme.typography.listTime, color = tint)
                }
            }
        }
        if (!sent) Spacer(Modifier.size(dimens.bubbleOppositeMinGap))
    }
}
