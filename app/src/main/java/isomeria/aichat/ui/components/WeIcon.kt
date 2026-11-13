package isomeria.aichat.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * 图标跟着 App 的字体大小放大的倍数（0.9.1）。主题按设置里的字体大小提供，见 [iconScaleFor]。
 */
val LocalIconScale = staticCompositionLocalOf { 1f }

/**
 * App 字体大小对应的图标倍数：放大的部分按字号的 8 成跟，最多 1.2 倍（特大 1.25 → 1.2）；标准和偏小不缩小。
 * 截图里的“特大叠大字体”（2.5）也封顶 1.2 倍。
 */
fun iconScaleFor(fontScale: Float): Float = (1f + (fontScale - 1f) * 0.8f).coerceIn(1f, ICON_SCALE_MAX)

const val ICON_SCALE_MAX = 1.2f

/**
 * App 里所有图标都用它（0.9.1）：和 Material 的 Icon 一样，但 App 字体调大时按 [LocalIconScale] 一起放大。
 * 做法是给图标换一个按倍数放大的 density，调用处写的尺寸（size、padding）都按同样倍数放大，周围布局跟着让位；
 * 能点的图标外面的点按区域（顶栏按钮等）本来就不小于 48dp，放大后仍然装得下。
 */
@Composable
fun WeIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val scale = LocalIconScale.current
    if (scale == 1f) {
        Icon(imageVector, contentDescription, modifier, tint)
        return
    }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
        Icon(imageVector, contentDescription, modifier, tint)
    }
}
