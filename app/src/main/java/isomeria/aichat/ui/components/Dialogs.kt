package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme

/**
 * 微信式对话框：上面是标题和说明，下面是左右两个按钮，确认按钮为绿色（危险操作为红色）。
 */
@Composable
fun WeDialog(
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String? = null,
    confirmText: String = stringResource(R.string.action_ok),
    dismissText: String? = stringResource(R.string.action_cancel),
    destructive: Boolean = false,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 40.dp)
                .widthIn(max = dimens.dialogMaxWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.dialogCorner))
                .background(colors.dialogBackground),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (title != null) {
                    Text(
                        title,
                        style = WeTheme.typography.dialogTitle,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                    )
                }
                if (message.isNotEmpty()) {
                    Text(
                        message,
                        style = WeTheme.typography.dialogMessage,
                        color = if (title == null) colors.textPrimary else colors.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            WeDivider(Modifier.background(colors.dialogBackground))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                if (dismissText != null) {
                    DialogButton(dismissText, colors.textPrimary, onDismiss, Modifier.weight(1f))
                    Box(
                        Modifier
                            .width(dimens.dividerThickness)
                            .fillMaxHeight()
                            .background(colors.divider),
                    )
                }
                DialogButton(
                    confirmText,
                    if (destructive) colors.danger else colors.brand,
                    onConfirm,
                    Modifier.weight(1f),
                    bold = true,
                )
            }
        }
    }
}

@Composable
private fun DialogButton(text: String, color: Color, onClick: () -> Unit, modifier: Modifier, bold: Boolean = false) {
    Box(
        modifier
            .height(56.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = WeTheme.typography.listTitle.copy(fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal),
            color = color,
        )
    }
}

data class SheetOption(
    val label: String,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val subtitle: String? = null,
    val onClick: () -> Unit,
)

/**
 * 底部操作菜单：选项在上，间隔后是「取消」。选中项右侧打勾。
 */
@Composable
fun WeActionSheet(
    options: List<SheetOption>,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = dimens.dialogCorner, topEnd = dimens.dialogCorner))
                    .background(colors.pageBackground)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .navigationBarsPadding(),
            ) {
                Column(
                    Modifier
                        .heightIn(max = 480.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (title != null) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .background(colors.listBackground)
                                .padding(vertical = 16.dp, horizontal = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                title,
                                style = WeTheme.typography.small,
                                color = colors.textTertiary,
                                textAlign = TextAlign.Center,
                            )
                        }
                        WeDivider()
                    }
                    options.forEachIndexed { index, option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(colors.listBackground)
                                .clickable {
                                    option.onClick()
                                    onDismiss()
                                }
                                .heightIn(min = dimens.listRowHeight)
                                .padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.size(24.dp))
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    option.label,
                                    style = WeTheme.typography.listTitle,
                                    color = if (option.destructive) colors.danger else colors.textPrimary,
                                    textAlign = TextAlign.Center,
                                )
                                if (option.subtitle != null) {
                                    Text(
                                        option.subtitle,
                                        style = WeTheme.typography.listTime,
                                        color = colors.textTertiary,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            }
                            if (option.selected) {
                                Icon(Icons.Filled.Check, null, tint = colors.brand, modifier = Modifier.size(24.dp))
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        }
                        if (index != options.lastIndex) WeDivider()
                    }
                }
                Spacer(Modifier.height(dimens.groupSpacing))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.listBackground)
                        .clickable(onClick = onDismiss)
                        .height(dimens.listRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.action_cancel), style = WeTheme.typography.listTitle, color = colors.textPrimary)
                }
            }
        }
    }
}
