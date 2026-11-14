package isomeria.aichat.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * 动画的统一参数（0.9.1）。页面切换的曲线和时长在 navigation/PageTransitions.kt（按微信实测），这里是页面内的：
 * 气泡出现、菜单、底部菜单、对话框、多选切换。
 *
 * 系统「移除动画」（开发者选项或省电，动画时长缩放为 0）时：Compose 自己会把有终点的动画缩成 0，转场直接切换；
 * 循环动效（通话的波纹、声波、跳动的点、呼吸）用 [loopingFloat]，显示静止状态，不跑动画。
 */
object WeMotion {
    /** 气泡出现：淡入加轻微上移 */
    const val BUBBLE_APPEAR = 180
    val BUBBLE_RISE = 12f

    /** 长按菜单、顶栏「+」菜单：缩放淡入 */
    const val MENU_ENTER = 150
    const val MENU_START_SCALE = 0.85f

    /** 对话框：淡入加轻微缩放 */
    const val DIALOG_ENTER = 180
    const val DIALOG_START_SCALE = 0.92f

    /** 底部菜单：从下往上滑出，背景渐暗 */
    const val SHEET_ENTER = 220
    const val SHEET_EXIT = 180

    /** 多选进出：勾选圈滑入、输入栏和操作栏交叉淡变 */
    const val SELECTION = 200

    /** 聊天页的表情、更多面板：像键盘一样从下往上升起、落下（0.9.1 真机反馈后加） */
    const val PANEL_OPEN = 250
    const val PANEL_CLOSE = 200

    /** 面板升降的曲线：和系统键盘动画用的曲线相同（先快后慢） */
    val Panel = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** 标准减速曲线（Material 的 emphasized decelerate） */
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

/** 系统「移除动画」时为 true。主题里按系统设置提供，截图和测试可以直接指定。 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** 读系统的动画时长缩放（和 Compose 自己判断时用的是同一项设置），设置变化时跟着变 */
@Composable
fun rememberSystemReduceMotion(): Boolean {
    val context = LocalContext.current
    var reduce by remember { mutableStateOf(isAnimationOff(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = isAnimationOff(context)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

internal fun isAnimationOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/**
 * 循环动画的当前值。系统移除动画时不跑动画，固定在 [still]（选一个能看出状态的静止样子）。
 * 返回 State，在绘制阶段读取时只重画、不重组。
 */
@Composable
fun loopingFloat(from: Float, to: Float, spec: InfiniteRepeatableSpec<Float>, still: Float, label: String): State<Float> {
    if (LocalReduceMotion.current) return remember(still) { mutableFloatStateOf(still) }
    val transition = rememberInfiniteTransition(label = label)
    return transition.animateFloat(from, to, spec, label = label)
}

/** 出现动画的进度：进入组合时从 0 走到 1（减速曲线） */
@Composable
fun rememberEnterProgress(durationMillis: Int): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(durationMillis, easing = WeMotion.Decelerate)) }
    return progress.asState()
}

/** 按 [progress] 从 [startScale] 放大到原大、同时淡入；[origin] 是放大的中心（比如菜单弹出的那个角） */
fun Modifier.scaleFadeIn(progress: State<Float>, startScale: Float, origin: TransformOrigin = TransformOrigin.Center): Modifier =
    graphicsLayer {
        val v = progress.value
        alpha = v
        val scale = startScale + (1f - startScale) * v
        scaleX = scale
        scaleY = scale
        transformOrigin = origin
    }
