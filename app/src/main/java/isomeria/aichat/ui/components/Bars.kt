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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme

/**
 * 顶栏：最小高 50dp，标题居中 17sp 中粗。沉浸式：背景延伸到状态栏下方。
 * 字体调大时顶栏跟着长高，标题放不下时显示省略号，不会压到两侧的返回键和按钮。
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
    Layout(
        content = {
            Box(Modifier.layoutId(TOP_BAR_START).padding(start = 4.dp)) {
                if (onBack != null) {
                    TopBarIcon(
                        icon = WeIcons.Back,
                        contentDescription = stringResource(R.string.action_back),
                        onClick = onBack,
                    )
                }
            }
            Text(
                text = title,
                style = WeTheme.typography.topBarTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.layoutId(TOP_BAR_TITLE),
            )
            Row(
                modifier = Modifier.layoutId(TOP_BAR_END).padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        },
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
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        fun find(id: String) = measurables.first { it.layoutId == id }
        val start = find(TOP_BAR_START).measure(loose)
        val end = find(TOP_BAR_END).measure(loose)
        // 标题居中：两边按较宽的一侧留白，至少 96dp
        val side = maxOf(start.width, end.width, TOP_BAR_MIN_SIDE.roundToPx())
        val titleWidth = (constraints.maxWidth - side * 2).coerceAtLeast(0)
        val title = find(TOP_BAR_TITLE).measure(loose.copy(maxWidth = titleWidth))
        val height = maxOf(constraints.minHeight, start.height, end.height, title.height)
        layout(constraints.maxWidth, height) {
            // 和 Alignment.Center 一样四舍五入，标题的位置不会差 1 像素
            fun centered(space: Int) = (space + 1) / 2
            start.placeRelative(0, centered(height - start.height))
            title.placeRelative(centered(constraints.maxWidth - title.width), centered(height - title.height))
            end.placeRelative(constraints.maxWidth - end.width, centered(height - end.height))
        }
    }
}

private const val TOP_BAR_START = "start"
private const val TOP_BAR_TITLE = "title"
private const val TOP_BAR_END = "end"
private val TOP_BAR_MIN_SIDE = 96.dp

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
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(dimens.topBarIcon))
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
                    Icon(
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
