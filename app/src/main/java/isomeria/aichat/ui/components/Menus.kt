package isomeria.aichat.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import isomeria.aichat.ui.theme.WeTheme

data class MenuItem(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/** 菜单也是独立窗口，要和 [WeDialogWindow] 一样带上 App 内的字体大小。 */
@Composable
private fun WePopup(provider: PopupPositionProvider, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        CompositionLocalProvider(LocalDensity provides density, content = content)
    }
}

/**
 * 聊天消息的长按菜单：深色圆角，图标和文字排成等宽的格子，出现在消息上方；上方放不下时出现在下方。
 * 要放在消息气泡所在的布局里调用，菜单以它为锚点。
 *
 * 0.9.0 修正：
 * - 菜单窗口不抢焦点，键盘开着时长按消息，键盘和输入框保持原样；
 * - 菜单下面铺一层透明的全屏层，点菜单外面只关菜单，不会点到后面的消息或空白处；返回键也是先关菜单；
 * - 左右离屏幕边缘至少 [BUBBLE_MENU_SIDE_MARGIN]，上面不压住标题栏，下面不压住键盘；
 * - 每格一样宽（以最长的一项为准），每行最多 5 项，各行数量尽量均匀（11 项排成 4 + 4 + 3），见 [bubbleMenuRows]。
 */
@Composable
fun WeBubbleMenu(items: List<MenuItem>, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    val view = LocalView.current
    val dimens = WeTheme.dimens
    // 锚点（消息气泡所在的布局）在屏幕上的位置；菜单窗口自己的位置在摆放时再取，两者都用屏幕坐标，不受状态栏、键盘影响
    var anchor by remember { mutableStateOf<IntRect?>(null) }
    Spacer(
        Modifier.onGloballyPositioned { coordinates ->
            val parent = coordinates.parentLayoutCoordinates ?: return@onGloballyPositioned
            val topLeft = parent.positionOnScreen().round()
            anchor = IntRect(topLeft, parent.size)
        },
    )
    BackHandler(onBack = onDismiss)
    Popup(
        popupPositionProvider = WindowOriginProvider,
        onDismissRequest = onDismiss,
        // 不抢焦点：键盘留着。也不让系统按窗口可见区域裁剪，透明层才能盖满整个窗口
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false, clippingEnabled = false),
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            val margins = with(density) {
                BubbleMenuMargins(
                    side = BUBBLE_MENU_SIDE_MARGIN.roundToPx(),
                    vertical = BUBBLE_MENU_VERTICAL_MARGIN.roundToPx(),
                    topBar = dimens.topBarHeight.roundToPx(),
                    gap = BUBBLE_MENU_GAP.roundToPx(),
                )
            }
            // 菜单本体在透明层里的位置，用来分辨按下是在菜单上还是菜单外面
            val cardBounds = remember { arrayOfNulls<IntRect>(1) }
            Layout(
                content = { BubbleMenuGrid(items, onDismiss) },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(onDismiss) {
                        // 菜单外面的任何按下都只用来关菜单，不传给后面的页面
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (cardBounds[0]?.contains(down.position.round()) != true) {
                                down.consume()
                                onDismiss()
                            }
                        }
                    },
            ) { measurables, constraints ->
                val visible = android.graphics.Rect().also { view.getWindowVisibleDisplayFrame(it) }
                if (visible.isEmpty) {
                    // 取不到可见区域时（极少见）退回整个应用窗口
                    val root = view.rootView
                    val at = IntArray(2).also { root.getLocationOnScreen(it) }
                    visible.set(at[0], at[1], at[0] + root.width, at[1] + root.height)
                }
                val maxCardWidth = (visible.width() - 2 * margins.side).coerceAtLeast(0)
                val card = measurables.first().measure(Constraints(maxWidth = maxCardWidth.coerceAtMost(constraints.maxWidth)))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val target = anchor ?: return@layout
                    val origin = coordinates?.positionOnScreen()?.round() ?: return@layout
                    val position = bubbleMenuPosition(
                        anchor = target,
                        menu = IntSize(card.width, card.height),
                        visible = IntRect(visible.left, visible.top, visible.right, visible.bottom),
                        margins = margins,
                    )
                    val local = position - origin
                    cardBounds[0] = IntRect(local, IntSize(card.width, card.height))
                    card.place(local)
                }
            }
        }
    }
}

/** 菜单本体：等宽格子，各行项数见 [bubbleMenuRows]，左对齐 */
@Composable
private fun BubbleMenuGrid(items: List<MenuItem>, onDismiss: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val minCell = with(LocalDensity.current) { BUBBLE_MENU_MIN_CELL.roundToPx() }
    Layout(
        content = {
            items.forEach { item ->
                Column(
                    Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(role = Role.Button) {
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
                    Text(
                        item.label,
                        style = WeTheme.typography.listTime,
                        color = colors.popupText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        modifier = Modifier
            .clip(RoundedCornerShape(dimens.popupCorner))
            .background(colors.popupBackground)
            .padding(4.dp),
    ) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}
        val widest = measurables.maxOf { it.maxIntrinsicWidth(Constraints.Infinity) }
        val cell = widest.coerceAtLeast(minCell).coerceAtMost(constraints.maxWidth)
        val rows = bubbleMenuRows(measurables.size, cell, constraints.maxWidth)
        val placeables = measurables.map { it.measure(Constraints.fixedWidth(cell)) }
        val cellHeight = placeables.maxOf { it.height }
        layout(rows.max() * cell, rows.size * cellHeight) {
            var index = 0
            rows.forEachIndexed { row, size ->
                repeat(size) { column ->
                    val placeable = placeables[index++]
                    placeable.place(column * cell, row * cellHeight + (cellHeight - placeable.height) / 2)
                }
            }
        }
    }
}

/**
 * 每行放几项：先看一行放得下几格（最多 [BUBBLE_MENU_PER_ROW]），算出要几行，再把项数均匀分到这几行里，
 * 前面的行多一项，免得最后一行只剩一个（11 项 → 4 + 4 + 3，8 项 → 4 + 4，6 项 → 3 + 3，5 项以内一行；
 * 字号大、一行只放得下 3 格时 10 项 → 3 + 3 + 2 + 2）。
 */
internal fun bubbleMenuRows(count: Int, cellWidth: Int, maxWidth: Int, maxPerRow: Int = BUBBLE_MENU_PER_ROW): List<Int> {
    if (count <= 0) return emptyList()
    val fit = (if (cellWidth > 0) maxWidth / cellWidth else maxPerRow).coerceIn(1, maxPerRow)
    val rows = (count + fit - 1) / fit
    return List(rows) { row -> count / rows + if (row < count % rows) 1 else 0 }
}

internal data class BubbleMenuMargins(val side: Int, val vertical: Int, val topBar: Int, val gap: Int)

/**
 * 菜单左上角在屏幕上的位置。[visible] 是窗口可见区域（去掉状态栏、导航栏和键盘），都是屏幕坐标。
 * 水平：对准消息居中，左右离边缘至少 side。垂直：先放消息上方（不压住标题栏），放不下放下方，
 * 两边都放不下（很长的消息）就贴着标题栏下面放，不超出可见区域。
 */
internal fun bubbleMenuPosition(anchor: IntRect, menu: IntSize, visible: IntRect, margins: BubbleMenuMargins): IntOffset {
    val left = visible.left + margins.side
    val right = (visible.right - margins.side - menu.width).coerceAtLeast(left)
    val x = (anchor.center.x - menu.width / 2).coerceIn(left, right)
    val top = visible.top + margins.topBar + margins.vertical
    val bottom = (visible.bottom - margins.vertical - menu.height).coerceAtLeast(top)
    val above = anchor.top - margins.gap - menu.height
    val below = anchor.bottom + margins.gap
    val y = when {
        above >= top -> above
        below <= bottom -> below
        else -> top
    }
    return IntOffset(x, y.coerceIn(top, bottom))
}

/** 菜单窗口放在应用窗口左上角，铺满整个窗口；菜单本体在里面按屏幕坐标摆放 */
private object WindowOriginProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

private const val BUBBLE_MENU_PER_ROW = 5
private val BUBBLE_MENU_SIDE_MARGIN = 12.dp
private val BUBBLE_MENU_VERTICAL_MARGIN = 8.dp
private val BUBBLE_MENU_GAP = 6.dp
private val BUBBLE_MENU_MIN_CELL = 56.dp

/**
 * 在按压位置弹出的竖向菜单（聊天列表长按）。[pressOffset] 是相对锚点左上角的按压位置。
 */
@Composable
fun WeContextMenu(pressOffset: IntOffset, items: List<MenuItem>, onDismiss: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val provider = remember(pressOffset) { AtPointProvider(pressOffset) }
    WePopup(provider, onDismiss) {
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
                        .clickable(role = Role.Button) {
                            onDismiss()
                            item.onClick()
                        }
                        .heightIn(min = 48.dp)
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
    WePopup(provider, onDismiss) {
        Column(
            Modifier
                .clip(RoundedCornerShape(dimens.popupCorner))
                .background(colors.popupBackground)
                .padding(vertical = 4.dp),
        ) {
            items.forEach { item ->
                Row(
                    Modifier
                        .clickable(role = Role.Button) {
                            onDismiss()
                            item.onClick()
                        }
                        .heightIn(min = 52.dp)
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
