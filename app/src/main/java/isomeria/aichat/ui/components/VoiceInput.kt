package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme

/** 按住说话时手指移到了哪里：原地（松开发送）、上滑到左边（取消）、上滑到右边（转文字）。 */
enum class RecordZone { SEND, CANCEL, TO_TEXT }

/** 录音的用途：发语音消息（按住说话），或录音转文字（麦克风按钮）。 */
enum class RecordMode { VOICE_MESSAGE, TO_TEXT }

/** 录音页面状态：[toggled] 是点一下开始、再点结束的录法（读屏、麦克风按钮），这时浮层上的按钮可以点 */
data class RecordingUi(
    val mode: RecordMode,
    val toggled: Boolean,
    val elapsedMillis: Long = 0,
    val level: Float = 0f,
    val zone: RecordZone = RecordZone.SEND,
)

/**
 * 「按住 说话」：按下开始录音，手指上滑到左边松开取消、上滑到右边松开转文字，原地松开发送（0.7.0）。
 * 长按手势读屏用不了：读屏的“点按”（双击）改成点一下开始、再点一下发送，另有“取消”“转文字”两个自定义操作。
 *
 * [onPress] 返回 false 表示这次不能录（没设置识别、没有权限等，调用方已经提示），手指松开前什么也不做。
 */
@Composable
fun HoldToTalkButton(
    recording: RecordingUi?,
    onPress: () -> Boolean,
    onZone: (RecordZone) -> Unit,
    onRelease: (RecordZone) -> Unit,
    onToggle: () -> Unit,
    onCancel: () -> Unit,
    onToText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val density = LocalDensity.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    var left by remember { mutableFloatStateOf(0f) }
    val press by rememberUpdatedState(onPress)
    val zoneChanged by rememberUpdatedState(onZone)
    val release by rememberUpdatedState(onRelease)
    val threshold = with(density) { 72.dp.toPx() }
    val labelIdle = stringResource(R.string.chat_hold_to_talk)
    val labelRecording = stringResource(R.string.voice_release_to_send)
    val labelReleaseCancel = stringResource(R.string.voice_release_to_cancel)
    val labelReleaseText = stringResource(R.string.voice_release_to_text)
    val actionStart = stringResource(R.string.voice_a11y_start)
    val actionSend = stringResource(R.string.voice_a11y_send)
    val actionCancel = stringResource(R.string.voice_cancel)
    val actionToText = stringResource(R.string.voice_to_text)
    val stateRecording = stringResource(R.string.voice_a11y_recording)
    val active = recording != null && recording.mode == RecordMode.VOICE_MESSAGE
    Box(
        modifier
            .heightIn(min = dimens.inputMinHeight)
            .clip(RoundedCornerShape(dimens.inputCorner))
            .background(if (active) colors.listPressed else colors.inputBackground)
            .onGloballyPositioned { left = it.boundsInWindow().left }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    if (!press()) {
                        // 不能录：等手指松开，什么也不做
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                        return@awaitEachGesture
                    }
                    var zone = RecordZone.SEND
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            release(zone)
                            break
                        }
                        val next = when {
                            change.position.y > -threshold -> RecordZone.SEND
                            left + change.position.x < windowWidth / 2f -> RecordZone.CANCEL
                            else -> RecordZone.TO_TEXT
                        }
                        if (next != zone) {
                            zone = next
                            zoneChanged(zone)
                        }
                        change.consume()
                    }
                }
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (active) stateDescription = stateRecording
                onClick(label = if (active) actionSend else actionStart) { onToggle(); true }
                if (active) {
                    customActions = listOf(
                        CustomAccessibilityAction(actionCancel) { onCancel(); true },
                        CustomAccessibilityAction(actionToText) { onToText(); true },
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            when {
                !active -> labelIdle
                recording?.toggled == true -> stateRecording
                recording?.zone == RecordZone.CANCEL -> labelReleaseCancel
                recording?.zone == RecordZone.TO_TEXT -> labelReleaseText
                else -> labelRecording
            },
            style = WeTheme.typography.listTitle,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

/**
 * 录音时盖在聊天上的浮层：中间是随音量跳动的录音气泡和已录秒数（最后 10 秒倒数），下面左边“取消”、右边“转文字”。
 * 按住说话时随手指位置高亮，松开时生效；点一下开始的录法（[RecordingUi.toggled]）可以直接点这些按钮，另有“发送”/“完成”。
 */
@Composable
fun BoxScope.RecordingOverlay(
    state: RecordingUi,
    maxMillis: Long,
    onCancel: () -> Unit,
    onToText: () -> Unit,
    onFinish: () -> Unit,
) {
    val colors = WeTheme.colors
    val cancel = state.zone == RecordZone.CANCEL
    val toText = state.zone == RecordZone.TO_TEXT
    val remaining = ((maxMillis - state.elapsedMillis + 999) / 1000).coerceAtLeast(0)
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.82f)).clickable(enabled = false) {})
    Column(Modifier.matchParentSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .widthIn(min = 160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (cancel) colors.danger else colors.brand)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LevelBars(state.level)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (remaining <= 10) stringResource(R.string.voice_remaining, remaining.toInt()) else stringResource(R.string.voice_seconds, (state.elapsedMillis / 1000).toInt()),
                    style = WeTheme.typography.small,
                    color = Color.White,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            stringResource(
                when {
                    state.toggled && state.mode == RecordMode.TO_TEXT -> R.string.voice_hint_tap_finish_text
                    state.toggled -> R.string.voice_hint_tap_finish
                    cancel -> R.string.voice_hint_release_cancel
                    toText -> R.string.voice_hint_release_text
                    else -> R.string.voice_hint_release_send
                },
            ),
            style = WeTheme.typography.small,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            OverlayButton(stringResource(R.string.voice_cancel), "✕", highlighted = cancel, enabled = state.toggled, onClick = onCancel, modifier = Modifier.weight(1f))
            if (state.toggled) {
                OverlayButton(
                    stringResource(if (state.mode == RecordMode.TO_TEXT) R.string.voice_finish else R.string.chat_send),
                    "✓", highlighted = true, enabled = true, onClick = onFinish, modifier = Modifier.weight(1f),
                )
            }
            if (state.mode == RecordMode.VOICE_MESSAGE) {
                OverlayButton(stringResource(R.string.voice_to_text), stringResource(R.string.voice_to_text_icon), highlighted = toText, enabled = state.toggled, onClick = onToText, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun OverlayButton(label: String, symbol: String, highlighted: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 外框固定 76dp，高亮时圆变大也不会让几个按钮上下错开
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(if (highlighted) 76.dp else 64.dp)
                    .clip(CircleShape)
                    .background(if (highlighted) Color.White else Color.White.copy(alpha = 0.25f))
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                // 白底上的符号固定用深色，深色主题下也看得清
                Text(symbol, style = WeTheme.typography.listTitle, color = if (highlighted) Color(0xFF191919) else Color.White)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = WeTheme.typography.small, color = Color.White, textAlign = TextAlign.Center)
    }
}

/** 音量条：七根，按当前音量起伏 */
@Composable
private fun LevelBars(level: Float) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(28.dp)) {
        val shape = listOf(0.4f, 0.7f, 0.9f, 1f, 0.9f, 0.7f, 0.4f)
        shape.forEach { weight ->
            val height = (6 + 22 * (level.coerceIn(0f, 1f) * weight)).dp
            Box(Modifier.width(4.dp).height(height).clip(RoundedCornerShape(2.dp)).background(Color.White))
        }
    }
}
