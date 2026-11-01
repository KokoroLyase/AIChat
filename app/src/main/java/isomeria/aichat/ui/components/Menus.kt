package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import isomeria.aichat.ui.theme.WeTheme

data class MenuItem(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/**
 * 聊天消息的长按菜单：深色圆角，横向排列图标和文字，出现在消息上方；上方放不下时出现在下方。
 * 要放在消息气泡所在的布局里调用，菜单以它为锚点。
 */
@Composable
fun WeBubbleMenu(items: List<MenuItem>, onDismiss: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val provider = remember(gap) { AboveAnchorProvider(gap) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(dimens.popupCorner))
                .background(colors.popupBackground)
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            items.forEach { item ->
                Column(
                    Modifier
                        .widthIn(min = 56.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            onDismiss()
                            item.onClick()
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (item.icon != null) {
                        Icon(item.icon, null, tint = colors.popupText, modifier = Modifier.size(20.dp))
                    }
                    Text(item.label, style = WeTheme.typography.listTime, color = colors.popupText, maxLines = 1)
                }
            }
        }
    }
}

/**
 * 在按压位置弹出的竖向菜单（聊天列表长按）。[pressOffset] 是相对锚点左上角的按压位置。
 */
@Composable
fun WeContextMenu(pressOffset: IntOffset, items: List<MenuItem>, onDismiss: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val provider = remember(pressOffset) { AtPointProvider(pressOffset) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .width(IntrinsicSize.Max)
                .widthIn(min = 160.dp)
                .shadow(6.dp, RoundedCornerShape(dimens.popupCorner))
                .clip(RoundedCornerShape(dimens.popupCorner))
                .background(colors.dialogBackground)
                .padding(vertical = 6.dp),
        ) {
            items.forEach { item ->
                Row(
                    Modifier
                        .clickable {
                            onDismiss()
                            item.onClick()
                        }
                        .height(48.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.label, style = WeTheme.typography.listTitle, color = colors.textPrimary, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 顶栏「+」弹出的深色菜单，出现在锚点右下方。
 */
@Composable
fun WeDropdownMenu(items: List<MenuItem>, onDismiss: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val margin = with(LocalDensity.current) { 4.dp.roundToPx() }
    val provider = remember(margin) { BelowEndProvider(margin) }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            Modifier
                .clip(RoundedCornerShape(dimens.popupCorner))
                .background(colors.popupBackground)
                .padding(vertical = 4.dp),
        ) {
            items.forEach { item ->
                Row(
                    Modifier
                        .clickable {
                            onDismiss()
                            item.onClick()
                        }
                        .height(52.dp)
                        .padding(start = 20.dp, end = 28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.icon != null) {
                        Icon(item.icon, null, tint = colors.popupText, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(14.dp))
                    }
                    Text(item.label, style = WeTheme.typography.listTitle, color = colors.popupText, maxLines = 1)
                }
            }
        }
    }
}

private class AboveAnchorProvider(private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.center.x - popupContentSize.width / 2)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val above = anchorBounds.top - popupContentSize.height - gap
        val y = if (above >= windowSize.height / 10) above else anchorBounds.bottom + gap
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

private class AtPointProvider(private val press: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        var x = anchorBounds.left + press.x
        var y = anchorBounds.top + press.y
        if (x + popupContentSize.width > windowSize.width) x -= popupContentSize.width
        if (y + popupContentSize.height > windowSize.height) y -= popupContentSize.height
        return IntOffset(x.coerceAtLeast(0), y.coerceAtLeast(0))
    }
}

private class BelowEndProvider(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceAtLeast(margin)
        return IntOffset(x, anchorBounds.bottom + margin)
    }
}
