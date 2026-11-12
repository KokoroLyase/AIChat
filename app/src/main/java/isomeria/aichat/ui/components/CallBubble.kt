package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.core.CallDurations
import isomeria.aichat.data.db.Message
import isomeria.aichat.ui.theme.WeTheme

/**
 * 通话消息的气泡（0.8.0）：和文字气泡同样的形状颜色，里面是「通话时长 03:21」和电话图标；还在通话时写「通话中」。
 * 点击进入这次通话的详情，长按出菜单。
 */
@Composable
fun CallBubble(message: Message, sent: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val background = if (sent) colors.bubbleSent else colors.bubbleReceived
    val content = if (sent) colors.bubbleSentText else colors.bubbleReceivedText
    val shape = remember(sent, dimens) {
        BubbleShape(sent, dimens.bubbleCorner, dimens.bubbleTailWidth, dimens.bubbleTailHeight, dimens.bubbleTailCenterY)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedOverlay = if (colors.isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.08f)
    val label = if (message.callOngoing) {
        stringResource(R.string.call_message_ongoing)
    } else {
        stringResource(R.string.call_message_duration, CallDurations.format(message.voiceMillis))
    }
    val clickLabel = stringResource(R.string.call_message_open)
    val menuLabel = stringResource(R.string.chat_menu_more)
    Box(
        modifier
            .clip(shape)
            .background(if (pressed) pressedOverlay.compositeOver(background) else background)
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                onClick(label = clickLabel) { onClick(); true }
                onLongClick(label = menuLabel) { onLongClick(); true }
            }
            .defaultMinSize(minHeight = dimens.bubbleMinHeight)
            .padding(
                start = dimens.bubblePaddingHorizontal + if (sent) 0.dp else dimens.bubbleTailWidth,
                end = dimens.bubblePaddingHorizontal + if (sent) dimens.bubbleTailWidth else 0.dp,
                top = dimens.bubblePaddingVertical,
                bottom = dimens.bubblePaddingVertical,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val icon = @Composable { Icon(WeIcons.Phone, contentDescription = null, tint = content, modifier = Modifier.size(20.dp)) }
            val text = @Composable { Text(label, style = WeTheme.typography.message, color = content) }
            if (sent) {
                text()
                icon()
            } else {
                icon()
                text()
            }
        }
    }
}
