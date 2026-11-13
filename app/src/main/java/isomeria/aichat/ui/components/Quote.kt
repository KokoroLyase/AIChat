package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.core.MessageCopy
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.ui.theme.WeTheme

/** 引用（0.9.0）显示的文字：“名字：内容”；引用的是语音时前面加“[语音]” */
@Composable
fun quoteLabel(name: String, type: MessageType?, text: String): String {
    val body = if (type == MessageType.VOICE) stringResource(R.string.preview_voice) + " " + text else text
    return stringResource(R.string.copy_speaker_line, name, MessageCopy.oneLine(body))
}

/**
 * 气泡下面的引用块（0.9.0）：灰色圆角底，“名字：内容”最多两行。点它跳到原消息（原消息删了时提示），长按和气泡一样弹菜单
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuoteBlock(label: String, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeTheme.colors
    Text(
        label,
        style = WeTheme.typography.small,
        color = colors.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.thinkingBackground)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = stringResource(R.string.quote_jump),
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** 输入栏上面的引用条（0.9.0）：“名字：内容”一行，右边 × 取消引用 */
@Composable
fun QuoteBar(label: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.barBackground)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = WeTheme.typography.small,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.inputBackground)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        Box(
            Modifier
                .size(40.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(WeIcons.Close, stringResource(R.string.quote_cancel), tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}
