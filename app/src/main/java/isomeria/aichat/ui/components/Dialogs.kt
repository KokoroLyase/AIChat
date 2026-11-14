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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
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
 *
 * 0.9.1 审计后补上关闭动画：以前只有出现动画，点按钮、返回、点外面时对话框和背后的变暗一下子消失。
 * 现在关闭时先淡出、缩小，背后的变暗也跟着渐变，再执行按钮的操作。弹窗里的按钮用 [LocalDialogClose] 关闭。
 * 由调用方决定什么时候消失的（加载提示）传 [visible]：变成 false 时先淡出再移除。
 */
@Composable
fun WeDialogWindow(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    scaleIn: Boolean = true,
    visible: Boolean = true,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    if (!scaleIn) {
        if (!visible) return
        Dialog(onDismissRequest = onDismissRequest, properties = properties) {
            CompositionLocalProvider(LocalDensity provides density, content = content)
        }
        return
    }
    val shown = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val leaving = remember { mutableStateOf(false) }
    val close: (() -> Unit) -> Unit = remember {
        { then ->
            if (!leaving.value) {
                leaving.value = true
                scope.launch {
                    shown.animateTo(0f, tween(WeMotion.DIALOG_EXIT, easing = FastOutLinearInEasing))
                    then()
                    // 调用方没把对话框关掉（比如输入为空不能确定）：过两帧还在就重新显示
                    repeat(2) { withFrameNanos { } }
                    leaving.value = false
                    shown.animateTo(1f, tween(WeMotion.DIALOG_ENTER, easing = WeMotion.Decelerate))
                }
            }
        }
    }
    // [visible] 变成 false 后接着显示到淡出结束
    val everShown = remember { booleanArrayOf(false) }
    var exited by remember { mutableStateOf(false) }
    if (visible) everShown[0] = true
    LaunchedEffect(visible) {
        if (visible) {
            exited = false
            shown.animateTo(1f, tween(WeMotion.DIALOG_ENTER, easing = WeMotion.Decelerate))
        } else if (everShown[0]) {
            shown.animateTo(0f, tween(WeMotion.DIALOG_EXIT, easing = FastOutLinearInEasing))
            exited = true
        }
    }
    if (!visible && (!everShown[0] || exited)) return
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    Dialog(onDismissRequest = { close { latestDismiss() } }, properties = properties) {
        CompositionLocalProvider(LocalDensity provides density, LocalDialogClose provides close) {
            // 背后的变暗跟着淡入淡出：窗口自带的变暗一下子出现、消失，改成按进度设置深浅（第一帧之前就设好）
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            val dim = remember(window) { window?.attributes?.dimAmount?.takeIf { it > 0f } ?: DEFAULT_SCRIM }
            SideEffect { window?.setDimAmount(dim * shown.value) }
            LaunchedEffect(window) {
                if (window == null) return@LaunchedEffect
                snapshotFlow { shown.value }.collect { window.setDimAmount(dim * it) }
            }
            Box(Modifier.scaleFadeIn(shown.asState(), WeMotion.DIALOG_START_SCALE), propagateMinConstraints = true) { content() }
        }
    }
}

/**
 * 弹窗里的按钮用它关闭：先播放关闭动画，再执行传进来的操作（0.9.1 审计）。不在 [WeDialogWindow] 里时直接执行。
 */
val LocalDialogClose = staticCompositionLocalOf<(() -> Unit) -> Unit> { { it() } }

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
    /** 为 false 时点确认按钮不关闭、不执行（比如名称还是空的） */
    canConfirm: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    WeDialogWindow(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val close = LocalDialogClose.current
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
                    DialogMessage(message, if (title == null) colors.textPrimary else colors.textTertiary)
                }
                content?.invoke(this)
            }
            WeDivider(Modifier.background(colors.dialogBackground))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                if (dismissText != null) {
                    DialogButton(dismissText, colors.textPrimary, { close(onDismiss) }, Modifier.weight(1f))
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
                    { if (canConfirm) close(onConfirm) },
                    Modifier.weight(1f),
                    bold = true,
                )
            }
        }
    }
}

/** 对话框正文（0.9.1）：一行放得下时居中，两行以上左对齐 */
@Composable
private fun DialogMessage(text: String, color: Color) {
    val style = WeTheme.typography.dialogMessage
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val lines = remember(text, style, width) { measurer.measure(text, style, constraints = Constraints(maxWidth = width)).lineCount }
        Text(
            text,
            style = style,
            color = color,
            textAlign = dialogMessageAlign(lines),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal fun dialogMessageAlign(lineCount: Int): TextAlign = if (lineCount > 1) TextAlign.Start else TextAlign.Center

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
                                WeIcon(WeIcons.Check, null, tint = colors.brand, modifier = Modifier.size(24.dp))
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

/**
 * 加载提示：屏幕中间一块深色圆角方块，转圈加一行字；不能点掉。
 * 0.9.1 审计：用 [visible] 控制显示，做完时淡出（以前由调用方直接移除，一下子消失）。
 */
@Composable
fun WeLoading(text: String, visible: Boolean = true) {
    val colors = WeTheme.colors
    WeDialogWindow(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        visible = visible,
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
