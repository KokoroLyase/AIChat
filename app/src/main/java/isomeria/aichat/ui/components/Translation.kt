package isomeria.aichat.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme

/**
 * 气泡下面的译文（0.9.0），和“转文字”一样的位置：译文 + “翻译”标识；长按弹出“复制”“隐藏翻译”
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranslationBlock(text: String, sent: Boolean, onCopy: () -> Unit, onHide: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    var menuOpen by remember { mutableStateOf(false) }
    val description = stringResource(R.string.translation_desc, text)
    Box(modifier) {
        Column(
            Modifier
                .clip(RoundedCornerShape(dimens.bubbleCorner))
                .background(if (sent) colors.bubbleSent else colors.bubbleReceived)
                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                .clearAndSetSemantics { contentDescription = description }
                .padding(horizontal = dimens.bubblePaddingHorizontal, vertical = dimens.bubblePaddingVertical),
        ) {
            Text(text, style = WeTheme.typography.message, color = if (sent) colors.bubbleSentText else colors.bubbleReceivedText)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(WeIcons.Translate, null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
                Box(Modifier.width(4.dp))
                Text(stringResource(R.string.translation_label), style = WeTheme.typography.tab, color = colors.textTertiary)
            }
        }
        if (menuOpen) {
            WeBubbleMenu(
                listOf(
                    MenuItem(stringResource(R.string.action_copy), Icons.Outlined.ContentCopy, onCopy),
                    MenuItem(stringResource(R.string.chat_menu_hide_translation), WeIcons.Translate, onHide),
                ),
            ) { menuOpen = false }
        }
    }
}

/** 正在翻译（0.9.0） */
@Composable
fun TranslationLoading(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.translation_loading), style = WeTheme.typography.small, color = WeTheme.colors.textSecondary, modifier = modifier)
}

/** 翻译失败（0.9.0）：显示原因，点一下重试 */
@Composable
fun TranslationFailed(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.translation_failed, reason),
        style = WeTheme.typography.small,
        color = WeTheme.colors.textSecondary,
        modifier = modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.translation_retry), onClick = onRetry),
    )
}
