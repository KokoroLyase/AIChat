package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme

/**
 * 顶栏：最小高 50dp，标题居中 17sp 中粗。沉浸式：背景延伸到状态栏下方。
 * 字体调大时顶栏跟着长高；0.9.1 起标题在居中放不下时改用两侧按钮之间的全部空间（尽量靠中间），
 * 还放不下就缩小字号（最小到原来的 [TOP_BAR_MIN_TITLE_SCALE]），最后才显示省略号，不会压到两侧的返回键和按钮。
 */
@Composable
fun WeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    showLine: Boolean = false,
    background: Color = WeTheme.colors.pageBackground,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val titleStyle = WeTheme.typography.topBarTitle
    val measurer = rememberTextMeasurer()
    val backLabel = stringResource(R.string.action_back)
    // 先量两侧按钮，算出标题能占的宽度和字号，再组合标题（放得下时和以前一样是原字号的普通 Text）
    SubcomposeLayout(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind {
                if (showLine) {
                    val y = size.height - dimens.dividerThickness.toPx() / 2
                    drawLine(colors.topBarLine, Offset(0f, y), Offset(size.width, y), dimens.dividerThickness.toPx())
                }
            }
            .statusBarsPadding()
            .heightIn(min = dimens.topBarHeight),
    ) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = subcompose(TOP_BAR_START) {
            Box(Modifier.padding(start = 4.dp)) {
                if (onBack != null) TopBarIcon(icon = WeIcons.Back, contentDescription = backLabel, onClick = onBack)
            }
        }.first().measure(loose)
        val end = subcompose(TOP_BAR_END) {
            Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }.first().measure(loose)
        val natural = measurer.measure(title, titleStyle, maxLines = 1, softWrap = false, density = this).size.width
        val slot = topBarTitleSlot(
            barWidth = constraints.maxWidth,
            startWidth = start.width,
            endWidth = end.width,
            naturalTitleWidth = natural,
            minSide = TOP_BAR_MIN_SIDE.roundToPx(),
            edge = TOP_BAR_EDGE.roundToPx(),
        )
        val slotWidth = slot.to - slot.from
        val fontSize = fitFontSize(measurer, title, titleStyle, slotWidth, TOP_BAR_MIN_TITLE_SCALE)
        val title = subcompose(TOP_BAR_TITLE) {
            Text(
                text = title,
                style = if (fontSize == titleStyle.fontSize) titleStyle else titleStyle.copy(fontSize = fontSize),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }.first().measure(loose.copy(maxWidth = slotWidth))
        val height = maxOf(constraints.minHeight, start.height, end.height, title.height)
        layout(constraints.maxWidth, height) {
            // 和 Alignment.Center 一样四舍五入，标题的位置不会差 1 像素
            fun centered(space: Int) = (space + 1) / 2
            start.placeRelative(0, centered(height - start.height))
            // 尽量在整条顶栏居中；居中会压到按钮时贴着可用空间的一边
            val x = centered(constraints.maxWidth - title.width).coerceIn(slot.from, (slot.to - title.width).coerceAtLeast(slot.from))
            title.placeRelative(x, centered(height - title.height))
            end.placeRelative(constraints.maxWidth - end.width, centered(height - end.height))
        }
    }
}

private const val TOP_BAR_START = "start"
private const val TOP_BAR_TITLE = "title"
private const val TOP_BAR_END = "end"
private val TOP_BAR_MIN_SIDE = 96.dp
/** 标题和两侧按钮（或屏幕边缘）之间至少留的空 */
private val TOP_BAR_EDGE = 12.dp
/** 标题放不下时最多缩到原字号的多少 */
internal const val TOP_BAR_MIN_TITLE_SCALE = 0.6f

/**
 * 顶栏标题可以占的横向范围（像素，[TitleSlot.from] 到 [TitleSlot.to]）：居中放得下时两边按较宽的一侧留白（至少 [minSide]），和以前一样；
 * 放不下时用返回键和右侧按钮之间的全部空间，两边各空出 [edge]。
 */
internal fun topBarTitleSlot(barWidth: Int, startWidth: Int, endWidth: Int, naturalTitleWidth: Int, minSide: Int, edge: Int): TitleSlot {
    val side = maxOf(startWidth, endWidth, minSide)
    if (naturalTitleWidth <= barWidth - side * 2) return TitleSlot(side, barWidth - side)
    val from = startWidth + edge
    val to = (barWidth - endWidth - edge).coerceAtLeast(from)
    return TitleSlot(from, to)
}

internal data class TitleSlot(val from: Int, val to: Int)

@Composable
fun TopBarIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = WeTheme.colors.textPrimary,
) {
    val dimens = WeTheme.dimens
    Box(
        modifier
            .size(dimens.topBarIconTouch)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        WeIcon(icon, contentDescription, tint = tint, modifier = Modifier.size(dimens.topBarIcon))
    }
}

/** 顶栏里的文字按钮（0.9.0 多选的“取消”）：普通字色，点击区至少 48dp */
@Composable
fun TopBarTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = WeTheme.dimens.topBarIconTouch)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(text, style = WeTheme.typography.listTitle, color = WeTheme.colors.textPrimary)
    }
}

data class TabItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val badgeCount: Int = 0,
)

/**
 * 底部 Tab 栏：最小高 56dp，字体调大时跟着长高。选中态为实心绿色图标 + 绿字；点击无涟漪。
 */
@Composable
fun WeBottomBar(
    items: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.barBackground)
            .drawBehind {
                drawLine(colors.divider, Offset.Zero, Offset(size.width, 0f), dimens.dividerThickness.toPx())
            }
            .navigationBarsPadding()
            .heightIn(min = dimens.bottomBarHeight),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val tint = if (selected) colors.brand else colors.textPrimary
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = dimens.bottomBarHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                    ) { onSelect(index) }
                    .semantics { this.selected = selected },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    WeIcon(
                        if (selected) item.selectedIcon else item.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(26.dp),
                    )
                    if (item.badgeCount > 0) {
                        UnreadBadge(
                            count = item.badgeCount,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-4).dp),
                        )
                    }
                }
                Text(
                    item.label,
                    style = WeTheme.typography.tab,
                    color = tint,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 2.dp).wrapContentSize(),
                )
            }
        }
    }
}
