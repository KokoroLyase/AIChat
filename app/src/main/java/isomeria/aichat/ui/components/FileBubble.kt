package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme

/** 文件图标的颜色和角标（0.9.2）：按扩展名分 PDF、Word、Excel、PPT、图片，别的是灰色 */
internal enum class FileKind(val tag: String, val color: Color) {
    PDF("PDF", Color(0xFFE5483F)),
    WORD("W", Color(0xFF3A7BEA)),
    EXCEL("X", Color(0xFF23A55A)),
    PPT("P", Color(0xFFF07B2D)),
    IMAGE("IMG", Color(0xFF8E6BE0)),
    OTHER("FILE", Color(0xFF8A8F99)),
    ;

    companion object {
        fun of(name: String): FileKind = when (name.substringAfterLast('.', "").lowercase()) {
            "pdf" -> PDF
            "doc", "docx" -> WORD
            "xls", "xlsx" -> EXCEL
            "ppt", "pptx" -> PPT
            "png", "jpg", "jpeg", "jp2", "webp", "gif", "bmp" -> IMAGE
            else -> OTHER
        }
    }
}

/**
 * 文件消息的气泡（0.9.2）：和微信的文件卡片一样，自己发的、联系人发的都是白底（深色模式深灰）带尖角的卡片；
 * 左边文件名（最多两行）和大小，右边按类型画的图标。[status] 是“解析中…”这类说明，有时在大小下面展开出来，
 * 和语音转文字、译文一样有展开收起的动画。
 *
 * 点一下打开文件，长按出菜单；[held] 为 true 时菜单开着，保持按下的颜色（和别的气泡一样）
 */
@Composable
fun FileBubble(
    name: String,
    size: Long,
    status: String?,
    sent: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    held: Boolean = false,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val context = LocalContext.current
    val shape = remember(sent, dimens) {
        BubbleShape(sent, dimens.bubbleCorner, dimens.bubbleTailWidth, dimens.bubbleTailHeight, dimens.bubbleTailCenterY)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedOverlay = if (colors.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f)
    val background = colors.bubbleReceived
    val sizeText = remember(size) { android.text.format.Formatter.formatShortFileSize(context, size.coerceAtLeast(0)) }
    val label = stringResource(R.string.file_message_desc, name, sizeText) + (status?.let { "，$it" }.orEmpty())
    val clickLabel = stringResource(R.string.file_message_open)
    val menuLabel = stringResource(R.string.chat_menu_more)
    val kind = remember(name) { FileKind.of(name) }
    Box(
        modifier
            .width(FILE_BUBBLE_WIDTH)
            .clip(shape)
            .background(if (pressed || held) pressedOverlay.compositeOver(background) else background)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick(label = clickLabel) { onClick(); true }
                onLongClick(label = menuLabel) { onLongClick(); true }
            }
            .padding(
                start = 12.dp + if (sent) 0.dp else dimens.bubbleTailWidth,
                end = 12.dp + if (sent) dimens.bubbleTailWidth else 0.dp,
                top = 12.dp,
                bottom = 12.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    style = WeTheme.typography.message,
                    color = colors.bubbleReceivedText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(4.dp))
                Text(sizeText, style = WeTheme.typography.listTime, color = colors.textTertiary)
                // 和语音转文字、译文一样的展开收起
                WeExpand(status) { shown ->
                    Text(
                        shown.toString(),
                        style = WeTheme.typography.listTime,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            FileIcon(kind)
        }
    }
}

/** 文件类型图标：圆角色块里写类型的简称 */
@Composable
internal fun FileIcon(kind: FileKind, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(kind.color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            kind.tag,
            color = Color.White,
            fontSize = if (kind.tag.length > 2) 11.sp else 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private val FILE_BUBBLE_WIDTH = 232.dp
