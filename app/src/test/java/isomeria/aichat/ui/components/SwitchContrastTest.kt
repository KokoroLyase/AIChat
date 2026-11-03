package isomeria.aichat.ui.components

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.DarkWeColors
import isomeria.aichat.ui.theme.LightWeColors
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class SwitchContrastTest {
    @get:Rule val compose = createComposeRule()
    private fun contrast(a: Color, b: Color) = (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    private fun verify(dark: Boolean) {
        val colors = if (dark) DarkWeColors else LightWeColors
        val checked = mutableStateOf(false)
        compose.setContent { AIChatTheme(darkTheme = dark) { WeSwitch(checked.value, { checked.value = it }, label = "fixture") } }
        val control = compose.onNodeWithContentDescription("fixture")
        control.assertIsOff()
        val pixels = control.captureToImage().toPixelMap()
        var indicators = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val pixel = pixels[x, y]
            if (pixel.alpha > .99f && contrast(pixel, colors.switchTrackOff) >= 3f && contrast(pixel, colors.listBackground) >= 3f) indicators++
        }
        assertTrue("The rendered off state needs a visible thumb and outline", indicators > 100)
        control.performClick().assertIsOn()
        control.performClick().assertIsOff()
    }

    @Test fun lightOffStateIsVisibleAndStillToggles() = verify(false)
    @Test fun darkOffStateIsVisibleAndStillToggles() = verify(true)
}
