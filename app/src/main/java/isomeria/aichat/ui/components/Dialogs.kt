package isomeria.aichat.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeMotion
import isomeria.aichat.ui.theme.WeTheme
import isomeria.aichat.ui.theme.rememberEnterProgress
import isomeria.aichat.ui.theme.scaleFadeIn
import kotlinx.coroutines.launch

/**
 * 弹窗是独立窗口，系统会给它重新提供 [LocalDensity]，App 内的“字体大小”不会跟过来。
 * 所有弹窗都走这里，和页面用同一个字号。
 *
 * 0.9.1：[scaleIn] 为 true 时内容淡入加轻微缩放（对话框）；底部菜单自己做滑出，传 false。
 */
@Composable
fun WeDialogWindow(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    scaleIn: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismissRequest, properties = properties) {
        CompositionLocalProvider(LocalDensity provides density) {
            if (scaleIn) {
                val enter = rememberEnterProgress(WeMotion.DIALOG_ENTER)
                Box(Modifier.scaleFadeIn(enter, WeMotion.DIALOG_START_SCALE), propagateMinConstraints = true) { content() }
            } else {
                content()
            }
        }
    }
}

/**
 * 微信式对话框：上面是标题和说明，下面是左右两个按钮，确认按钮为绿色（危险操作为红色）。
 * 内容放不下时只有上半部分滚动，按钮始终留在屏幕内。[content] 接在说明后面，用于名单这类不适合居中的内容。
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
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    WeDialogWindow(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 40.dp, vertical = 24.dp)
                .widthIn(max = dimens.dialogMaxWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.dialogCorner))
                .background(colors.dialogBackground),
        ) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
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
                content?.invoke(this)
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
            .fillMaxHeight()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = WeTheme.typography.listTitle.copy(fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal),
            color = color,
            textAlign = TextAlign.Center,
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
 * 0.9.1：从下往上滑出、背景渐暗；关闭时先滑下去再执行（选项的操作也在滑下去之后）。
 * 背景的暗色由这里自己画（窗口自带的变暗会一下子出现），深浅取窗口原来的变暗程度。
 */
@Composable
fun WeActionSheet(
    options: List<SheetOption>,
    onDismiss: () -> Unit,
    title: String? = null,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val shown = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var leaving by remember { mutableStateOf(false) }
    // 先滑下去，再执行 [then]（选项的操作）并关闭
    val close: (() -> Unit) -> Unit = { then ->
        if (!leaving) {
            leaving = true
            scope.launch {
                shown.animateTo(0f, tween(WeMotion.SHEET_EXIT, easing = FastOutLinearInEasing))
                then()
                onDismiss()
            }
        }
    }
    WeDialogWindow(
        onDismissRequest = { close {} },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        scaleIn = false,
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        val scrim = remember(window) { window?.attributes?.dimAmount?.takeIf { it > 0f } ?: DEFAULT_SCRIM }
        SideEffect { window?.setDimAmount(0f) }
        LaunchedEffect(Unit) { shown.animateTo(1f, tween(WeMotion.SHEET_ENTER, easing = WeMotion.Decelerate)) }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(Color.Black.copy(alpha = scrim * shown.value)) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { close {} },
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // 按屏幕比例限高，选项少的菜单不用滚动就能看全
            val optionsMaxHeight = maxHeight * 0.7f
            Column(
                Modifier
                    .graphicsLayer { translationY = (1f - shown.value) * size.height }
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
                        .heightIn(max = optionsMaxHeight)
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
                                .clickable(role = Role.Button) { close(option.onClick) }
                                .then(if (option.selected) Modifier.semantics { selected = true } else Modifier)
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
                                Icon(WeIcons.Check, null, tint = colors.brand, modifier = Modifier.size(24.dp))
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
                        .clickable(role = Role.Button) { close {} }
                        .heightIn(min = dimens.listRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.action_cancel), style = WeTheme.typography.listTitle, color = colors.textPrimary)
                }
            }
        }
    }
}

/** 窗口没有给出变暗程度时用的背景暗度 */
private const val DEFAULT_SCRIM = 0.6f

/** 加载提示：屏幕中间一块深色圆角方块，转圈加一行字；不能点掉。 */
@Composable
fun WeLoading(text: String) {
    val colors = WeTheme.colors
    WeDialogWindow(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Column(
            Modifier
                .widthIn(min = 136.dp, max = 240.dp)
                .heightIn(min = 136.dp)
                .clip(RoundedCornerShape(WeTheme.dimens.dialogCorner))
                .background(colors.loadingBackground)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(16.dp))
            Text(text, style = WeTheme.typography.small, color = Color.White, textAlign = TextAlign.Center)
        }
    }
}
