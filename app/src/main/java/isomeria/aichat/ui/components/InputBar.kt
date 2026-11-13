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

/** 输入栏里和语音有关的操作（0.7.0），见 [HoldToTalkButton] 和录音转文字的麦克风按钮。 */
data class VoiceInputActions(
    val recording: RecordingUi? = null,
    val transcribing: Boolean = false,
    val onPress: () -> Boolean = { false },
    val onZone: (RecordZone) -> Unit = {},
    val onRelease: (RecordZone) -> Unit = {},
    val onToggle: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onToText: () -> Unit = {},
    /** 文字框右侧的麦克风：录音转文字 */
    val onMic: () -> Unit = {},
)

/**
 * 聊天输入栏：语音切换、输入框（自动增高，最多 5 行）、表情、「+」；
 * 输入文字后「+」变成绿色「发送」按钮。语音模式下是「按住 说话」（0.7.0）；文字框右侧的麦克风是录音转文字。
 * 表情面板见 EmojiPanel.kt。
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
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    voice: VoiceInputActions = VoiceInputActions(),
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
            icon = if (voiceMode) WeIcons.Keyboard else WeIcons.Voice,
            description = stringResource(if (voiceMode) R.string.chat_keyboard else R.string.chat_voice),
            onClick = onToggleVoice,
        )
        Spacer(Modifier.width(8.dp))
        if (voiceMode) {
            HoldToTalkButton(
                recording = voice.recording,
                onPress = voice.onPress,
                onZone = voice.onZone,
                onRelease = voice.onRelease,
                onToggle = voice.onToggle,
                onCancel = voice.onCancel,
                onToText = voice.onToText,
                modifier = Modifier.weight(1f),
            )
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
                Box(Modifier.weight(1f).padding(vertical = 8.dp), contentAlignment = Alignment.CenterStart) {
                    if (voice.transcribing && value.text.isEmpty()) {
                        // 录音转文字正在识别：在空输入框里提示
                        Text(stringResource(R.string.voice_transcribing), style = WeTheme.typography.message, color = colors.textSecondary)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = WeTheme.typography.message.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.brand),
                        maxLines = dimens.inputMaxLines,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { if (it.isFocused) onInputFocused() },
                    )
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = !voice.transcribing,
                            role = Role.Button,
                            onClick = voice.onMic,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        WeIcons.Mic,
                        contentDescription = stringResource(R.string.voice_mic_desc),
                        tint = if (voice.transcribing) colors.disabled else colors.inputIcon,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        BarIcon(
            icon = if (panel == InputPanel.EMOJI) WeIcons.Keyboard else WeIcons.Emoji,
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

/** 「+」面板：相册、拍照（当前模型不支持图片时置灰），语音通话（0.8.0）。 */
@Composable
fun MorePanel(
    imageEnabled: Boolean,
    onAlbum: () -> Unit,
    onCamera: () -> Unit,
    modifier: Modifier = Modifier,
    onCall: () -> Unit = {},
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
            PanelItem(WeIcons.Image, stringResource(R.string.chat_album), imageEnabled, onAlbum)
            PanelItem(WeIcons.Camera, stringResource(R.string.chat_camera), imageEnabled, onCamera)
            PanelItem(WeIcons.Phone, stringResource(R.string.chat_voice_call), true, onCall)
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
