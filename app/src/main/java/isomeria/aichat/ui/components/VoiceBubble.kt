package isomeria.aichat.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme
import kotlin.math.sqrt

/** 语音条显示的秒数：不足 1 秒按 1 秒 */
fun voiceSeconds(millis: Long): Int = ((millis + 500) / 1000).toInt().coerceIn(1, 60)

/** 语音条宽度：1 秒最短，越长越宽，60 秒到 [max]；增长先快后慢 */
fun voiceBubbleWidth(seconds: Int, min: Dp = 72.dp, max: Dp = 200.dp): Dp {
    val fraction = sqrt((seconds.coerceIn(1, 60) - 1) / 59f)
    return min + (max - min) * fraction
}

/** 声波图标的大小（随字体放大前）和它与秒数的间距 */
private val VOICE_WAVE_SIZE = 20.dp
private val VOICE_ICON_GAP = 8.dp

/**
 * 语音条最窄能到多宽（0.9.1）：两边留白、尾巴、声波和秒数刚好放得下一行。
 * 聊天里旁边还有「转文字」时，语音条先缩短这一截以外的长度（见 [voiceRowBubbleWidth]）。
 */
@Composable
internal fun voiceBubbleMinWidth(seconds: Int): Dp {
    val dimens = WeTheme.dimens
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val text = stringResource(R.string.voice_seconds, seconds)
    val style = WeTheme.typography.message
    val textWidth = remember(text, style, density, measurer) { measurer.measure(text, style).size.width }
    val iconScale = LocalIconScale.current
    return dimens.bubblePaddingHorizontal * 2 + dimens.bubbleTailWidth + VOICE_WAVE_SIZE * iconScale + VOICE_ICON_GAP +
        with(density) { (textWidth + 1).toDp() }
}

/**
 * 聊天里语音条的宽度（像素，0.9.1 真机验证时改）：按秒数的宽度 [desired]，旁边的红点和「转文字」（[besides]）放不下时缩短，
 * 最短到 [minimum]（声波和秒数放得下一行）；秒数比按秒数的宽度还宽（特大字体的短语音）时放宽到 [minimum]；不超过 [available]。
 * 以前语音条按秒数定宽、先量，大字体或窄屏时「转文字」被挤成一字一行，窄屏时整行比屏幕宽、居中后语音条的尾巴和「转文字」都看不见。
 */
internal fun voiceRowBubbleWidth(available: Int, desired: Int, minimum: Int, besides: Int): Int =
    (available - besides).coerceIn(minimum, maxOf(desired, minimum)).coerceAtMost(available)

/**
 * 语音消息的气泡（0.7.0）：和文字气泡同样的形状和颜色，宽度随时长变化，里面是声波图标和秒数。
 * 对方的图标在左、朝右；自己的在右、朝左。正在播放时声波一格一格亮起。
 */
@Composable
fun VoiceBubble(
    millis: Long,
    sent: Boolean,
    playing: Boolean,
    unplayed: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 0.9.1：长按菜单开着时保持按下的颜色 */
    held: Boolean = false,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val background = if (sent) colors.bubbleSent else colors.bubbleReceived
    val content = if (sent) colors.bubbleSentText else colors.bubbleReceivedText
    val shape = remember(sent, dimens) {
        BubbleShape(sent, dimens.bubbleCorner, dimens.bubbleTailWidth, dimens.bubbleTailHeight, dimens.bubbleTailCenterY)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedOverlay = if (colors.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f)
    val seconds = voiceSeconds(millis)
    val description = stringResource(R.string.voice_bubble_desc, seconds)
    val state = when {
        playing -> stringResource(R.string.voice_state_playing)
        unplayed -> stringResource(R.string.voice_state_unplayed)
        else -> ""
    }
    val clickLabel = stringResource(if (playing) R.string.voice_stop else R.string.voice_action_play)
    val menuLabel = stringResource(R.string.chat_menu_more)
    val step = if (playing) {
        // 系统移除动画时停在三道声波都亮（0.9.1）
        val value by isomeria.aichat.ui.theme.loopingFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), still = 2.5f, label = "voiceStep")
        value.toInt().coerceIn(0, 2) + 1
    } else {
        3
    }
    Box(
        modifier
            .width(voiceBubbleWidth(seconds) + dimens.bubbleTailWidth)
            .clip(shape)
            .background(if (pressed || held) pressedOverlay.compositeOver(background) else background)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                if (state.isNotEmpty()) stateDescription = state
                onClick(label = clickLabel) { onClick(); true }
                onLongClick(label = menuLabel) { onLongClick(); true }
            }
            .defaultMinSize(minHeight = dimens.bubbleMinHeight)
            .padding(
                start = dimens.bubblePaddingHorizontal + if (sent) 0.dp else dimens.bubbleTailWidth,
                end = dimens.bubblePaddingHorizontal + if (sent) dimens.bubbleTailWidth else 0.dp,
                top = dimens.bubblePaddingVertical,
                bottom = dimens.bubblePaddingVertical,
            ),
        contentAlignment = if (sent) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(VOICE_ICON_GAP)) {
            val text = @Composable { Text(stringResource(R.string.voice_seconds, seconds), style = WeTheme.typography.message, color = content) }
            if (sent) {
                text()
                SoundWaves(content, step, pointingRight = false)
            } else {
                SoundWaves(content, step, pointingRight = true)
                text()
            }
        }
    }
}

/** 声波图标：一个点和两道弧，[visible] 是亮着几格（1～3）；0.8.0 起通话详情页也用 */
@Composable
internal fun SoundWaves(color: Color, visible: Int, pointingRight: Boolean, size: Dp = VOICE_WAVE_SIZE) {
    // 和其他图标一样随 App 字体大小放大（0.9.1）
    Canvas(Modifier.size(size * LocalIconScale.current)) {
        scale(scaleX = if (pointingRight) 1f else -1f, scaleY = 1f) {
            val stroke = 2.dp.toPx()
            val center = Offset(this.size.width * 0.2f, this.size.height / 2)
            drawCircle(color, radius = 2.2.dp.toPx(), center = center)
            listOf(0.38f, 0.7f).forEachIndexed { index, r ->
                if (index + 2 <= visible) {
                    val radius = this.size.width * r
                    drawArc(
                        color = color,
                        startAngle = -45f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/** 对方没听过的语音：气泡旁的小红点 */
@Composable
fun UnplayedDot(modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(WeTheme.colors.badge))
}
