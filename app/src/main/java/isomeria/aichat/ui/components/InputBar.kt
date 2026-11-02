package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme

enum class InputPanel { NONE, EMOJI, MORE }

/**
 * 聊天输入栏：语音切换、输入框（自动增高，最多 5 行）、表情、「+」；
 * 输入文字后「+」变成绿色「发送」按钮。语音只有外观；表情面板见 EmojiPanel.kt。
 */
@Composable
fun ChatInputBar(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    voiceMode: Boolean,
    panel: InputPanel,
    onToggleVoice: () -> Unit,
    onTogglePanel: (InputPanel) -> Unit,
    onSend: () -> Unit,
    onInputFocused: () -> Unit,
    onVoiceComingSoon: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val canSend = value.text.isNotBlank() && !voiceMode
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.barBackground)
            .padding(horizontal = 8.dp, vertical = dimens.inputBarVerticalPadding),
        verticalAlignment = Alignment.Bottom,
    ) {
        BarIcon(
            icon = if (voiceMode) Icons.Outlined.Keyboard else WeIcons.Voice,
            description = stringResource(if (voiceMode) R.string.chat_keyboard else R.string.chat_voice),
            onClick = onToggleVoice,
        )
        Spacer(Modifier.width(8.dp))
        if (voiceMode) {
            HoldToTalkButton(onVoiceComingSoon, Modifier.weight(1f))
        } else {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = dimens.inputMinHeight)
                    .clip(RoundedCornerShape(dimens.inputCorner))
                    .background(colors.inputBackground)
                    .padding(start = 10.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = WeTheme.typography.message.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.brand),
                    maxLines = dimens.inputMaxLines,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { if (it.isFocused) onInputFocused() },
                )
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = stringResource(R.string.chat_voice),
                    tint = colors.inputIcon,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(22.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onVoiceComingSoon,
                        ),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        BarIcon(
            icon = if (panel == InputPanel.EMOJI) Icons.Outlined.Keyboard else WeIcons.Emoji,
            description = stringResource(R.string.chat_emoji),
            onClick = { onTogglePanel(if (panel == InputPanel.EMOJI) InputPanel.NONE else InputPanel.EMOJI) },
        )
        Spacer(Modifier.width(8.dp))
        if (canSend) {
            SendButton(onSend)
        } else {
            BarIcon(
                icon = WeIcons.PlusCircle,
                description = stringResource(R.string.chat_more),
                onClick = { onTogglePanel(if (panel == InputPanel.MORE) InputPanel.NONE else InputPanel.MORE) },
            )
        }
    }
}

@Composable
private fun BarIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    val dimens = WeTheme.dimens
    Box(
        Modifier
            .height(dimens.inputMinHeight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = WeTheme.colors.textPrimary, modifier = Modifier.size(dimens.inputBarIcon))
    }
}

@Composable
private fun SendButton(onClick: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .height(dimens.inputMinHeight)
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) colors.brandPressed else colors.brand)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.chat_send), style = WeTheme.typography.small, color = Color.White)
    }
}

@Composable
private fun HoldToTalkButton(onPress: () -> Unit, modifier: Modifier) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Box(
        modifier
            .height(dimens.inputMinHeight)
            .clip(RoundedCornerShape(dimens.inputCorner))
            .background(colors.inputBackground)
            .clickable(onClick = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.chat_hold_to_talk), style = WeTheme.typography.listTitle, color = colors.textPrimary)
    }
}

/** 「+」面板：相册、拍照。当前模型不支持图片时置灰。 */
@Composable
fun MorePanel(
    imageEnabled: Boolean,
    onAlbum: () -> Unit,
    onCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeTheme.colors
    // 高度由调用方给（和键盘一样高）
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.barBackground)
            .padding(horizontal = 24.dp, vertical = 20.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            PanelItem(Icons.Outlined.Image, stringResource(R.string.chat_album), imageEnabled, onAlbum)
            PanelItem(Icons.Outlined.PhotoCamera, stringResource(R.string.chat_camera), imageEnabled, onCamera)
        }
    }
}

@Composable
private fun PanelItem(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = WeTheme.colors
    Column(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.inputBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = if (enabled) colors.textPrimary else colors.disabled,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(label, style = WeTheme.typography.listTime, color = if (enabled) colors.textTertiary else colors.disabled)
    }
}
