package isomeria.aichat.feature.contactedit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import isomeria.aichat.ui.components.WeIcon
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.theme.WeTheme

/**
 * 标题下面带小字说明、点击进入下一页的行（联系人详情的「朋友资料」、编辑联系人的「系统提示词」）。
 * 比普通行上下多留一点，标题和小字之间空开，小字行距放宽。
 * [shown] 是画出来的小字（可以带断行控制符）；读屏读 [detail]。
 */
@Composable
internal fun DetailEntryRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    shown: String = detail,
    detailMaxLines: Int = Int.MAX_VALUE,
    showDivider: Boolean = false,
) {
    val colors = WeTheme.colors
    val d = WeTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = d.listRowHeight)
            .drawBehind {
                if (showDivider) {
                    val y = size.height - d.dividerThickness.toPx() / 2
                    drawLine(colors.divider, Offset(d.horizontalPadding.toPx(), y), Offset(size.width, y), d.dividerThickness.toPx())
                }
            }
            .padding(start = d.horizontalPadding, end = d.horizontalPadding, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = WeTheme.typography.listTitle, color = colors.textPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                shown,
                style = WeTheme.typography.thinking,
                color = colors.textTertiary,
                maxLines = detailMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { text = AnnotatedString(detail) },
            )
        }
        WeIcon(WeIcons.ChevronRight, null, tint = colors.arrow, modifier = Modifier.padding(start = 16.dp).size(18.dp))
    }
}
