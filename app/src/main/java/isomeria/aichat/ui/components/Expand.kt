package isomeria.aichat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import isomeria.aichat.ui.theme.WeMotion

/**
 * 展开、收起（0.9.1 审计统一）：[value] 不为 null 时显示，出现时高度从 0 展开并淡入，消失时收起并淡出；
 * 显示中内容变了（比如“正在翻译”换成译文）高度也跟着过渡。收起的过程中 [value] 已经是 null，接着画最后一次的内容。
 *
 * 用在气泡下面的转文字和译文、思考过程、设置页的「高级」、输入栏上面的引用条。
 * 第一次显示时（打开页面时就是展开的）不播动画。
 */
@Composable
fun <T : Any> WeExpand(
    value: T?,
    modifier: Modifier = Modifier,
    /** 宽度也从 0 长出来时，从哪个角开始（气泡下面的内容）；null 时只展开高度（整行宽的设置项） */
    growFrom: Alignment? = null,
    content: @Composable (T) -> Unit,
) {
    val last = remember { arrayOfNulls<Any>(1) }
    if (value != null) last[0] = value
    AnimatedVisibility(
        visible = value != null,
        modifier = modifier,
        enter = (if (growFrom != null) expandIn(tween(WeMotion.EXPAND, easing = WeMotion.Decelerate), expandFrom = growFrom) else expandVertically(tween(WeMotion.EXPAND, easing = WeMotion.Decelerate), expandFrom = Alignment.Top)) +
            fadeIn(tween(WeMotion.EXPAND)),
        exit = (if (growFrom != null) shrinkOut(tween(WeMotion.EXPAND), shrinkTowards = growFrom) else shrinkVertically(tween(WeMotion.EXPAND), shrinkTowards = Alignment.Top)) +
            fadeOut(tween(WeMotion.EXPAND)),
        label = "expand",
    ) {
        @Suppress("UNCHECKED_CAST")
        val shown = (value ?: last[0]) as T? ?: return@AnimatedVisibility
        Box(Modifier.animateContentSize(tween(WeMotion.EXPAND))) { content(shown) }
    }
}

/** 只有展开、收起两种状态时用：[visible] 为 true 时显示 [content] */
@Composable
fun WeExpand(visible: Boolean, modifier: Modifier = Modifier, growFrom: Alignment? = null, content: @Composable () -> Unit) {
    WeExpand(if (visible) Unit else null, modifier, growFrom) { content() }
}

/**
 * 浮在页面上的小部件（「N 条新消息」）出现、消失：淡入加轻微放大，消失时反过来（0.9.1 审计）。
 */
@Composable
fun FadeScaleVisibility(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(WeMotion.EXPAND)) + scaleIn(tween(WeMotion.EXPAND, easing = WeMotion.Decelerate), initialScale = FLOAT_START_SCALE),
        exit = fadeOut(tween(WeMotion.EXPAND)) + scaleOut(tween(WeMotion.EXPAND), targetScale = FLOAT_START_SCALE),
        label = "fadeScale",
    ) { content() }
}

private const val FLOAT_START_SCALE = 0.85f
