package isomeria.aichat.ui.theme

import android.app.Activity
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

val LocalWeColors = staticCompositionLocalOf { LightWeColors }
val LocalWeDimens = staticCompositionLocalOf { WeDimens() }
val LocalWeTypography = staticCompositionLocalOf { WeTypography() }

/** 页面里通过 `WeTheme.colors / dimens / typography` 取值，不写死数值。 */
object WeTheme {
    val colors: WeColors
        @Composable @ReadOnlyComposable get() = LocalWeColors.current
    val dimens: WeDimens
        @Composable @ReadOnlyComposable get() = LocalWeDimens.current
    val typography: WeTypography
        @Composable @ReadOnlyComposable get() = LocalWeTypography.current
}

/**
 * 智信主题。Material3 只作底座（文字选择、光标等），颜色、尺寸、字号全部来自微信风格的自定义 token。
 *
 * @param fontScale 设置里「字体大小」的倍数，叠加在系统字体缩放之上。
 * @param reduceMotion 系统「移除动画」时为 true，循环动效显示静止状态（0.9.1，见 [WeMotion]）。
 */
@Composable
fun AIChatTheme(
    darkTheme: Boolean,
    fontScale: Float = 1f,
    reduceMotion: Boolean = rememberSystemReduceMotion(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkWeColors else LightWeColors
    val material = if (darkTheme) {
        darkColorScheme(
            primary = colors.brand,
            onPrimary = Color.White,
            background = colors.pageBackground,
            surface = colors.listBackground,
            onSurface = colors.textPrimary,
            onBackground = colors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = colors.brand,
            onPrimary = Color.White,
            background = colors.pageBackground,
            surface = colors.listBackground,
            onSurface = colors.textPrimary,
            onBackground = colors.textPrimary,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    val density = LocalDensity.current
    val scaledDensity = Density(density.density, density.fontScale * fontScale)

    MaterialTheme(colorScheme = material) {
        CompositionLocalProvider(
            LocalWeColors provides colors,
            LocalWeDimens provides WeDimens(),
            LocalWeTypography provides WeTypography(),
            LocalDensity provides scaledDensity,
            LocalReduceMotion provides reduceMotion,
            isomeria.aichat.ui.components.LocalIconScale provides isomeria.aichat.ui.components.iconScaleFor(fontScale),
            LocalIndication provides WePressIndication(colors.listPressed),
            LocalTextSelectionColors provides TextSelectionColors(
                handleColor = colors.brand,
                backgroundColor = colors.brand.copy(alpha = 0.3f),
            ),
            content = content,
        )
    }
}

/** 微信式按压效果：按下时整行变灰，没有涟漪。 */
class WePressIndication(private val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressNode(interactionSource, color)

    override fun equals(other: Any?): Boolean = other is WePressIndication && other.color == color
    override fun hashCode(): Int = color.hashCode()

    private class PressNode(
        private val interactionSource: InteractionSource,
        private val color: Color,
    ) : Modifier.Node(), DrawModifierNode {
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                var count = 0
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is PressInteraction.Press -> count++
                        is PressInteraction.Release, is PressInteraction.Cancel -> count = (count - 1).coerceAtLeast(0)
                    }
                    val now = count > 0
                    if (now != pressed) {
                        pressed = now
                        invalidateDraw()
                    }
                }
            }
        }

        override fun ContentDrawScope.draw() {
            if (pressed) drawRect(color)
            drawContent()
        }
    }
}
