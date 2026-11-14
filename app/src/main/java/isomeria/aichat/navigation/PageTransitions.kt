package isomeria.aichat.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.navigation.NavBackStackEntry
import isomeria.aichat.ui.theme.WeTheme
import kotlin.math.roundToInt

/*
 * 页面切换动画。返回动画按微信实测（HyperOS 录屏逐帧测量）：
 * - 侧边返回手势拖动时页面不动，松手后才播放返回动画，和点返回箭头一样。0.9.1 开发时试过预测性返回（跟手），
 *   NavHost 默认把页面缩小成卡片，用户 2026-11-14 实测后决定不要，保持这里的做法
 * - 上层页面向右整页滑出，减速三次曲线，约 250ms
 * - 下层页面从左侧 37.5% 处滑回，减速二次曲线，约 230ms
 * - 上层页面左边缘外侧有约 16dp 的阴影投在下层页面上，随页面离开从 0.35 淡到 0.05
 * 进入动画沿用：新页面从右侧推入，下层页面左移 1/4。
 */

private const val PUSH_DURATION = 300
private const val POP_TOP_DURATION = 250
private const val POP_UNDER_DURATION = 230
private const val POP_UNDER_OFFSET = 0.375f
private const val PUSH_UNDER_OFFSET = 0.25f
private const val SHADOW_ALPHA_COVERING = 0.35f
private const val SHADOW_ALPHA_AWAY = 0.05f

/** 减速三次曲线，对应 Android 的 decelerate_cubic */
private val DecelerateCubic = Easing { x -> 1f - (1f - x) * (1f - x) * (1f - x) }

/** 减速二次曲线，对应 Android 的 decelerate_quad */
private val DecelerateQuad = Easing { x -> 1f - (1f - x) * (1f - x) }

internal val pushEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(PUSH_DURATION, easing = FastOutSlowInEasing)) { it }
}
internal val pushExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(PUSH_DURATION, easing = FastOutSlowInEasing)) { -(it * PUSH_UNDER_OFFSET).roundToInt() }
}
internal val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(POP_UNDER_DURATION, easing = DecelerateQuad)) { -(it * POP_UNDER_OFFSET).roundToInt() }
}
internal val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(POP_TOP_DURATION, easing = DecelerateCubic)) { it }
}

/**
 * 二级页面外框：接管返回并画左边缘阴影。
 *
 * 返回：先于页面内容注册 BackHandler，所以页面里的 BackHandler（收键盘、关面板、放弃编辑）仍然优先；
 * 它又比 NavHost 自带的预测性返回优先，侧边手势拖动时页面不跟手，松手后走普通返回动画。
 */
@Composable
internal fun PageFrame(
    transition: Transition<EnterExitState>,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    val shadowAlpha = transition.animateFloat(
        transitionSpec = {
            if (targetState == EnterExitState.PostExit) {
                tween(POP_TOP_DURATION, easing = DecelerateCubic)
            } else {
                tween(PUSH_DURATION, easing = FastOutSlowInEasing)
            }
        },
        label = "edgeShadow",
    ) { state -> if (state == EnterExitState.Visible) SHADOW_ALPHA_COVERING else SHADOW_ALPHA_AWAY }
    Box(Modifier.fillMaxSize().edgeShadow(WeTheme.dimens.pageEdgeShadow) { shadowAlpha.value }) {
        content()
    }
}

/** 在自身左边缘外侧画一条由深到透明的阴影；页面停在原位时阴影在屏幕外，看不到。 */
private fun Modifier.edgeShadow(width: Dp, alpha: () -> Float): Modifier = drawBehind {
    val w = width.toPx()
    drawRect(
        brush = Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = alpha()),
            startX = -w,
            endX = 0f,
        ),
        topLeft = Offset(-w, 0f),
        size = Size(w, size.height),
    )
}
