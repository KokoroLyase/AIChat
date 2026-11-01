package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import isomeria.aichat.ui.theme.AvatarPalette
import isomeria.aichat.ui.theme.WeTheme
import java.io.File

/**
 * 圆角方形头像。没有图片时显示「名称首字 + 色块」。
 */
@Composable
fun Avatar(
    path: String?,
    name: String,
    color: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = WeTheme.dimens.avatarCorner,
) {
    val shape = RoundedCornerShape(corner)
    if (path != null) {
        AsyncImage(
            model = File(path),
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).background(Color(color)),
        )
    } else {
        val letter = remember(name) { avatarLetter(name) }
        val fontSize = with(LocalDensity.current) { (size * 0.42f).toSp() }
        Box(
            modifier.size(size).clip(shape).background(Color(color)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter,
                color = Color.White,
                style = WeTheme.typography.avatarLetter.copy(fontSize = fontSize),
                maxLines = 1,
            )
        }
    }
}

/** 名称首字（按字符而不是按 UTF-16，emoji 也能正确取出） */
fun avatarLetter(name: String): String {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "?"
    val end = trimmed.offsetByCodePoints(0, 1)
    return trimmed.substring(0, end).uppercase()
}

fun randomAvatarColor(): Int = AvatarPalette.random()

/** 未读红点：有数字时显示数字，否则为小圆点。 */
@Composable
fun UnreadBadge(count: Int, modifier: Modifier = Modifier, dotOnly: Boolean = false) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    if (dotOnly || count <= 0) {
        Box(modifier.size(dimens.badgeDot).clip(CircleShape).background(colors.badge))
        return
    }
    val text = if (count > 99) "99+" else count.toString()
    Box(
        modifier
            .defaultMinSize(minWidth = dimens.badgeSize, minHeight = dimens.badgeSize)
            .clip(RoundedCornerShape(50))
            .background(colors.badge)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, style = WeTheme.typography.badge, maxLines = 1)
    }
}

/** 列表分割线：从文字左侧开始。 */
@Composable
fun WeDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    val colors = WeTheme.colors
    val thickness = WeTheme.dimens.dividerThickness
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .background(colors.listBackground)
            .drawBehind {
                drawLine(
                    colors.divider,
                    Offset(startIndent.toPx(), size.height / 2),
                    Offset(size.width, size.height / 2),
                    thickness.toPx(),
                )
            },
    )
}

/** 分组之间的间距 */
@Composable
fun GroupSpacer(modifier: Modifier = Modifier) {
    Spacer(modifier.height(WeTheme.dimens.groupSpacing))
}

/** 分组标题行（通讯录字母、设置分组名） */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    val dimens = WeTheme.dimens
    Box(
        modifier
            .fillMaxWidth()
            .height(dimens.groupHeaderHeight)
            .background(WeTheme.colors.pageBackground)
            .padding(horizontal = dimens.horizontalPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = WeTheme.typography.small, color = WeTheme.colors.textTertiary)
    }
}

/**
 * 通讯录与设置的列表行：高 56dp，左侧图标或头像占 40dp，文字从 72dp 开始，右侧可有值和箭头。
 */
@Composable
fun WeListRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    value: String? = null,
    showArrow: Boolean = onClick != null,
    titleColor: Color = WeTheme.colors.textPrimary,
    titleStyle: TextStyle = WeTheme.typography.listTitle,
    showDivider: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .heightIn(min = dimens.listRowHeight)
            .drawBehind {
                if (showDivider) {
                    val start = if (leading != null) dimens.listRowTextStart else dimens.horizontalPadding
                    val y = size.height - dimens.dividerThickness.toPx() / 2
                    drawLine(colors.divider, Offset(start.toPx(), y), Offset(size.width, y), dimens.dividerThickness.toPx())
                }
            }
            .padding(start = dimens.horizontalPadding, end = dimens.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(dimens.listRowAvatar), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(dimens.listRowTextStart - dimens.horizontalPadding - dimens.listRowAvatar))
        }
        // 有值或右侧控件时，标题按自身宽度显示，剩下的宽度全给右侧，让值和控件靠右对齐
        val hasEnd = value != null || trailing != null
        Text(
            title,
            style = titleStyle,
            color = if (enabled) titleColor else colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (hasEnd) Modifier else Modifier.weight(1f),
        )
        if (value != null) {
            Text(
                value,
                style = WeTheme.typography.listTitle,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 16.dp),
                textAlign = TextAlign.End,
            )
        }
        if (trailing != null) {
            if (value == null) Spacer(Modifier.weight(1f).widthIn(min = 16.dp)) else Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (showArrow) {
            Icon(
                WeIcons.ChevronRight,
                contentDescription = null,
                tint = colors.arrow,
                modifier = Modifier.padding(start = 4.dp).size(18.dp),
            )
        }
    }
}

/** 主按钮：绿色底白字，按下变深。 */
@Composable
fun WeButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = WeTheme.colors.brand,
    contentColor: Color = Color.White,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background = when {
        !enabled -> colors.disabled
        pressed && color == colors.brand -> colors.brandPressed
        pressed -> color.copy(alpha = 0.85f)
        else -> color
    }
    Box(
        modifier
            .height(dimens.buttonHeight)
            .clip(RoundedCornerShape(dimens.buttonCorner))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = WeTheme.typography.button,
            color = if (enabled) contentColor else colors.textSecondary,
        )
    }
}

/** 顶栏右侧的小号绿色按钮（如「保存」「完成」） */
@Composable
fun WeSmallButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = WeTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .padding(end = 8.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    !enabled -> colors.disabled
                    pressed -> colors.brandPressed
                    else -> colors.brand
                },
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = WeTheme.typography.small,
            color = if (enabled) Color.White else colors.textSecondary,
        )
    }
}

@Composable
fun WeSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = WeTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.brand,
            checkedBorderColor = colors.brand,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = colors.switchTrackOff,
            uncheckedBorderColor = colors.switchTrackOff,
            disabledCheckedTrackColor = colors.brand.copy(alpha = 0.4f),
            disabledUncheckedTrackColor = colors.switchTrackOff,
        ),
        thumbContent = null,
    )
}

/** 页面底部的灰色说明文字 */
@Composable
fun FootnoteText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = WeTheme.typography.small,
        color = WeTheme.colors.textTertiary,
        modifier = modifier.fillMaxWidth().padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 8.dp),
    )
}

/** 带左侧标题的白色卡片行，内容自定义（用于表单） */
@Composable
fun FormRow(
    label: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .heightIn(min = dimens.listRowHeight)
            .drawBehind {
                if (showDivider) {
                    val y = size.height - dimens.dividerThickness.toPx() / 2
                    drawLine(
                        colors.divider,
                        Offset(dimens.horizontalPadding.toPx(), y),
                        Offset(size.width, y),
                        dimens.dividerThickness.toPx(),
                    )
                }
            }
            .padding(horizontal = dimens.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            label,
            style = WeTheme.typography.listTitle,
            color = colors.textPrimary,
            modifier = Modifier.width(96.dp),
        )
        Box(Modifier.weight(1f)) { content() }
    }
}
