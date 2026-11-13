package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import isomeria.aichat.R
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
            .heightIn(min = dimens.groupHeaderHeight)
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
    /** 可展开的行：null 表示不是，展开后箭头朝下 */
    expanded: Boolean? = null,
    /** 标题最多几行。0.9.1 起默认不限（大字体时换行，不截断），需要时调用处再限制 */
    titleMaxLines: Int = Int.MAX_VALUE,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val expandedText = stringResource(if (expanded == true) R.string.state_expanded else R.string.state_collapsed)
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            // 能点的行整行读出来；不能点的行也把标题和值合在一起，别拆成两段
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
            .then(if (expanded != null) Modifier.semantics { stateDescription = expandedText } else Modifier)
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
        val titleText = @Composable {
            Text(
                title,
                style = titleStyle,
                color = if (enabled) titleColor else colors.textSecondary,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (value == null) {
            // 只有右侧控件（如开关）时，标题占满剩下的宽度，太长时换行，不压到控件
            Box(Modifier.weight(1f).padding(vertical = LIST_ROW_TEXT_VERTICAL)) { titleText() }
        } else {
            ListRowTitleValue(
                title = titleText,
                value = {
                    Text(value, style = WeTheme.typography.listTitle, color = colors.textTertiary, textAlign = TextAlign.End)
                },
                modifier = Modifier.weight(1f),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(if (value == null) 16.dp else 8.dp))
            trailing()
        }
        if (showArrow) {
            WeIcon(
                WeIcons.ChevronRight,
                contentDescription = null,
                tint = colors.arrow,
                modifier = Modifier.padding(start = 4.dp).size(18.dp).rotate(if (expanded == true) 90f else 0f),
            )
        }
    }
}

/**
 * 只占一行的短标签（0.9.1）：放不下时缩小字号（最小到 [minScale]），不折成两行。
 * 用在按钮、面板图标下面这类宽度固定的短字上。
 */
@Composable
fun WeFitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
    minScale: Float = FIT_TEXT_MIN_SCALE,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val size = remember(text, style, width) { fitFontSize(measurer, text, style, width, minScale) }
        Text(
            text,
            style = if (size == style.fontSize) style else style.copy(fontSize = size),
            color = color,
            textAlign = textAlign,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

const val FIT_TEXT_MIN_SCALE = 0.6f

/**
 * 一行放下 [text] 用的字号（0.9.1）：放得下就是 [style] 原来的字号（画出来和普通 Text 一样），
 * 放不下按比例缩小，最小到 [minScale] 倍（再放不下由省略号收尾）。
 */
internal fun fitFontSize(measurer: TextMeasurer, text: String, style: TextStyle, maxWidth: Int, minScale: Float): TextUnit {
    fun widthAt(scale: Float) =
        measurer.measure(text, style.copy(fontSize = style.fontSize * scale), maxLines = 1, softWrap = false).size.width
    if (maxWidth == Constraints.Infinity) return style.fontSize
    val natural = widthAt(1f)
    if (natural <= maxWidth) return style.fontSize
    // 宽度大致和字号成正比：先按比例估，再一点点缩到放得下
    var scale = (maxWidth.toFloat() / natural).coerceIn(minScale, 1f)
    while (scale > minScale && widthAt(scale) > maxWidth) scale = (scale - FIT_STEP).coerceAtLeast(minScale)
    return style.fontSize * scale
}

private const val FIT_STEP = 0.02f

/**
 * 可以换行的文字（0.9.1）：中文照常折行；一个英文单词（如名字“Isomeria”）一行放不下时先缩小字号
 * （最小到 [minScale]），不在单词中间断开。
 */
@Composable
fun WeWrapText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minScale: Float = FIT_TEXT_MIN_SCALE,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val size = remember(text, style, width) { fitWordFontSize(measurer, text, style, width, minScale) }
        Text(text, style = if (size == style.fontSize) style else style.copy(fontSize = size), color = color)
    }
}

/** 让 [text] 里最宽的英文单词（含数字、点、下划线等）在 [maxWidth] 里放下的字号；没有英文单词时是原字号 */
internal fun fitWordFontSize(measurer: TextMeasurer, text: String, style: TextStyle, maxWidth: Int, minScale: Float): TextUnit {
    if (maxWidth == Constraints.Infinity) return style.fontSize
    val widest = LATIN_WORD.findAll(text).map { it.value }
        .maxByOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
        ?: return style.fontSize
    return fitFontSize(measurer, widest, style, maxWidth, minScale)
}

private val LATIN_WORD = Regex("""[\p{IsLatin}\p{Nd}_.'@-]+""")

/**
 * 列表里的动作行（0.9.1 统一）：点了直接做事、不进下一页，没有箭头。
 * 普通动作（恢复默认、添加、下载）链接色、左对齐；危险动作（删除、清空）红色、居中。文字长时换行。
 */
@Composable
fun WeActionRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
    showDivider: Boolean = false,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = dimens.listRowHeight)
            .drawBehind {
                if (showDivider) {
                    val y = size.height - dimens.dividerThickness.toPx() / 2
                    drawLine(colors.divider, Offset(dimens.horizontalPadding.toPx(), y), Offset(size.width, y), dimens.dividerThickness.toPx())
                }
            }
            .padding(horizontal = dimens.horizontalPadding, vertical = LIST_ROW_TEXT_VERTICAL),
        contentAlignment = if (danger) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(
            text,
            style = WeTheme.typography.listTitle,
            color = when {
                !enabled -> colors.textSecondary
                danger -> colors.danger
                else -> colors.link
            },
            textAlign = if (danger) TextAlign.Center else TextAlign.Start,
        )
    }
}

/**
 * 列表行的标题和右侧的值（0.9.1）：放得下时同一行，标题按自身宽度、值靠右；
 * 放不下时（窄屏加大字体）值换到标题下面、靠右，两者都可以换行，不再截成“跟随系…”。
 */
@Composable
private fun ListRowTitleValue(title: @Composable () -> Unit, value: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Layout(
        contents = listOf(title, value),
        modifier = modifier.padding(vertical = LIST_ROW_TEXT_VERTICAL),
    ) { (titleM, valueM), constraints ->
        val gap = LIST_ROW_VALUE_GAP.roundToPx()
        val width = constraints.maxWidth
        val titleNatural = titleM.first().maxIntrinsicWidth(Constraints.Infinity)
        val valueNatural = valueM.first().maxIntrinsicWidth(Constraints.Infinity)
        if (listRowValueFitsBeside(titleNatural, valueNatural, width, gap)) {
            val t = titleM.first().measure(Constraints(maxWidth = titleNatural))
            val v = valueM.first().measure(Constraints(maxWidth = (width - t.width - gap).coerceAtLeast(0)))
            val height = maxOf(t.height, v.height)
            layout(width, height) {
                t.placeRelative(0, (height - t.height) / 2)
                v.placeRelative(width - v.width, (height - v.height) / 2)
            }
        } else {
            val t = titleM.first().measure(Constraints(maxWidth = width))
            val v = valueM.first().measure(Constraints(maxWidth = width))
            layout(width, t.height + v.height) {
                t.placeRelative(0, 0)
                v.placeRelative(width - v.width, t.height)
            }
        }
    }
}

/** 列表行的值能不能和标题放在同一行（都按不换行的宽度算） */
internal fun listRowValueFitsBeside(titleWidth: Int, valueWidth: Int, rowWidth: Int, gap: Int): Boolean =
    titleWidth + gap + valueWidth <= rowWidth

private val LIST_ROW_VALUE_GAP = 16.dp
/** 文字换成多行时上下留的空（一行时行高 56dp 已经够） */
private val LIST_ROW_TEXT_VERTICAL = 6.dp

/**
 * 分段选择（思考强度、用量的时间范围）。
 * 一行放得下时各项按自身宽度排成一行；0.9.1 起放不下时（窄屏加大字体）分成几行、每格等宽，
 * 各行项数尽量平均，不再让最后一项被挤出去。
 */
@Composable
fun <T> WeSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = WeTheme.colors
    Layout(
        content = {
            options.forEach { (value, label) ->
                val active = value == selected
                Box(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (active) colors.listBackground else Color.Transparent)
                        .selectable(selected = active, enabled = enabled, role = Role.Tab) { onSelect(value) }
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = WeTheme.typography.small,
                        color = when {
                            !enabled -> colors.textSecondary
                            active -> colors.brandText
                            else -> colors.textTertiary
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.pageBackground)
            .padding(2.dp),
    ) { measurables, constraints ->
        val gap = 2.dp.roundToPx()
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val perRow = segmentedPerRow(natural, maxWidth, gap)
        val placeables = if (perRow >= measurables.size) {
            measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(natural[i].coerceAtMost(maxWidth))) }
        } else {
            val cell = ((maxWidth - gap * (perRow - 1)) / perRow).coerceAtLeast(0)
            measurables.map { it.measure(Constraints.fixedWidth(cell)) }
        }
        val rows = placeables.chunked(perRow)
        // 同一行的格子一样高
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        val width = rows.maxOf { row -> row.sumOf { it.width } + gap * (row.size - 1) }
        val height = rowHeights.sum() + gap * (rows.size - 1)
        layout(width.coerceIn(constraints.minWidth, maxWidth), height.coerceAtLeast(constraints.minHeight)) {
            var y = 0
            rows.forEachIndexed { r, row ->
                var x = 0
                row.forEach { p ->
                    p.placeRelative(x, y + (rowHeights[r] - p.height) / 2)
                    x += p.width + gap
                }
                y += rowHeights[r] + gap
            }
        }
    }
}

/**
 * 分段选择每行放几项：按各项自身宽度一行放得下就全放一行；否则按最宽的一项算等宽格，
 * 能放几格放几格，再把行数摊平（5 项每行最多 3 格时是 3 + 2，不是 3 + 2 以外的不均匀分法）。
 */
internal fun segmentedPerRow(natural: List<Int>, maxWidth: Int, gap: Int): Int {
    val n = natural.size
    if (n == 0) return 1
    if (natural.sum() + gap * (n - 1) <= maxWidth) return n
    val widest = natural.max()
    val fit = ((maxWidth + gap) / (widest + gap)).coerceIn(1, n)
    val rows = (n + fit - 1) / fit
    return (n + rows - 1) / rows
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
            .heightIn(min = dimens.buttonHeight)
            .clip(RoundedCornerShape(dimens.buttonCorner))
            .background(background)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
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
    // 点击区至少 48dp，看到的按钮仍是 32dp 高
    Box(
        modifier
            .padding(end = 8.dp)
            .heightIn(min = 48.dp)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 32.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    when {
                        !enabled -> colors.disabled
                        pressed -> colors.brandPressed
                        else -> colors.brand
                    },
                )
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = WeTheme.typography.small,
                color = if (enabled) Color.White else colors.textSecondary,
                softWrap = false,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun WeSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: String? = null) {
    val colors = WeTheme.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        // 开关是单独的读屏焦点，要自己说明开关的是什么
        modifier = if (label != null) modifier.semantics { contentDescription = label } else modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = colors.brand,
            checkedBorderColor = colors.brand,
            uncheckedThumbColor = colors.inputIcon,
            uncheckedTrackColor = colors.switchTrackOff,
            uncheckedBorderColor = colors.inputIcon,
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

/** 需要用户处理的状态：红字，紧跟在出问题的那一行下面。普通说明用 [FootnoteText]。 */
@Composable
fun ErrorNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = WeTheme.typography.small,
        color = WeTheme.colors.dangerText,
        modifier = modifier.fillMaxWidth().padding(horizontal = WeTheme.dimens.horizontalPadding, vertical = 6.dp),
    )
}

/** 带左侧标题的白色卡片行，内容自定义（用于表单）。标题放不下时换到内容上方，不把字挤成两行。 */
@Composable
fun FormRow(
    label: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Layout(
        content = {
            Text(label, style = WeTheme.typography.listTitle, color = colors.textPrimary)
            Box { content() }
        },
        modifier = modifier
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
    ) { measurables, constraints ->
        val (labelMeasurable, contentMeasurable) = measurables
        val contentStart = 96.dp.roundToPx()
        // 右边留 8dp，标题和内容不贴在一起
        val labelWidth = contentStart - 8.dp.roundToPx()
        val width = constraints.maxWidth
        fun contentConstraints(w: Int) = Constraints(minWidth = w, maxWidth = w, maxHeight = constraints.maxHeight)
        if (labelMeasurable.maxIntrinsicWidth(Constraints.Infinity) <= labelWidth) {
            val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = labelWidth, maxHeight = constraints.maxHeight))
            val contentPlaceable = contentMeasurable.measure(contentConstraints(width - contentStart))
            val height = maxOf(labelPlaceable.height, contentPlaceable.height, constraints.minHeight)
            layout(width, height) {
                labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
                contentPlaceable.placeRelative(contentStart, (height - contentPlaceable.height) / 2)
            }
        } else {
            val top = 10.dp.roundToPx()
            val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = width, maxHeight = constraints.maxHeight))
            val contentPlaceable = contentMeasurable.measure(contentConstraints(width))
            val height = maxOf(top + labelPlaceable.height + contentPlaceable.height, constraints.minHeight)
            layout(width, height) {
                labelPlaceable.placeRelative(0, top)
                contentPlaceable.placeRelative(0, top + labelPlaceable.height)
            }
        }
    }
}
