package isomeria.aichat.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import isomeria.aichat.core.LinkDetector
import isomeria.aichat.ui.theme.WeTheme

/**
 * 气泡形状：圆角 4dp 的矩形，加一条宽 5dp、高 9dp 的小尾巴，尾巴中心在气泡顶部下方 20dp。
 * 发出的气泡尾巴在右侧，收到的在左侧。形状的宽度包含尾巴。
 */
class BubbleShape(
    private val tailOnRight: Boolean,
    private val corner: Dp,
    private val tailWidth: Dp,
    private val tailHeight: Dp,
    private val tailCenterY: Dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { corner.toPx() }
        val tw = with(density) { tailWidth.toPx() }
        val th = with(density) { tailHeight.toPx() }
        val cy = with(density) { tailCenterY.toPx() }.coerceAtMost(size.height / 2)
        val bodyLeft = if (tailOnRight) 0f else tw
        val bodyRight = if (tailOnRight) size.width - tw else size.width
        val body = Path().apply {
            addRoundRect(RoundRect(bodyLeft, 0f, bodyRight, size.height, CornerRadius(r, r)))
        }
        val overlap = 1f
        val tail = Path().apply {
            if (tailOnRight) {
                moveTo(bodyRight - overlap, cy - th / 2)
                lineTo(size.width, cy)
                lineTo(bodyRight - overlap, cy + th / 2)
            } else {
                moveTo(bodyLeft + overlap, cy - th / 2)
                lineTo(0f, cy)
                lineTo(bodyLeft + overlap, cy + th / 2)
            }
            close()
        }
        // 必须求并集：直接把尾巴画进同一个 Path 时，右侧尾巴的绕向和圆角矩形相反，
        // 重叠的 1px 按非零规则会被抵消成空洞，发出的气泡和尾巴之间出现一条浅色细缝
        return Outline.Generic(Path.combine(PathOperation.Union, body, tail))
    }
}

/**
 * 文字气泡。链接自动识别并可点击；长按回调用于弹出菜单，按下时气泡略微变暗。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TextBubble(
    text: String,
    sent: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit = {},
    onClick: () -> Unit = {},
    /** 0.9.1：长按菜单开着时保持按下的颜色 */
    held: Boolean = false,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val background = if (sent) colors.bubbleSent else colors.bubbleReceived
    val textColor = if (sent) colors.bubbleSentText else colors.bubbleReceivedText
    val linkColor = if (sent) colors.linkInSent else colors.link
    val shape = remember(sent, dimens) {
        BubbleShape(sent, dimens.bubbleCorner, dimens.bubbleTailWidth, dimens.bubbleTailHeight, dimens.bubbleTailCenterY)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedOverlay = if (colors.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f)
    val annotated = remember(text, linkColor) {
        buildAnnotatedString {
            append(text)
            LinkDetector.find(text).forEach { link ->
                addLink(
                    LinkAnnotation.Url(
                        link.url,
                        TextLinkStyles(style = SpanStyle(color = linkColor)),
                    ),
                    link.start,
                    link.end,
                )
            }
        }
    }
    Box(
        modifier
            .clip(shape)
            .background(if (pressed || held) pressedOverlay.compositeOver(background) else background)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .defaultMinSize(minHeight = dimens.bubbleMinHeight)
            .padding(
                start = dimens.bubblePaddingHorizontal + if (sent) 0.dp else dimens.bubbleTailWidth,
                end = dimens.bubblePaddingHorizontal + if (sent) dimens.bubbleTailWidth else 0.dp,
                top = dimens.bubblePaddingVertical,
                bottom = dimens.bubblePaddingVertical,
            ),
    ) {
        Text(annotated, style = WeTheme.typography.message, color = textColor)
    }
}
