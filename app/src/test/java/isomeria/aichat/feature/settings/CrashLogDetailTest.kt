package isomeria.aichat.feature.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class CrashLogDetailTest {
    @get:Rule val compose = createComposeRule()
    private val raw = "time=1793664000000\napp=fixture\njava.lang.IllegalStateException\n  at fixture.VeryLongClassNameForStackReadabilityAndKeepingTheFrameTogether.invoke(Source.kt:42)"

    private fun page(scale: Float) {
        compose.setContent { AIChatTheme(darkTheme = false, fontScale = scale) { Column { CrashLogDetail(raw) } } }
    }

    private fun checkDisplay() {
        val layout = mutableListOf<TextLayoutResult>()
        val timeLayout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("时间：", substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(timeLayout) }
        assertTrue(timeLayout.single().layoutInput.text.text.startsWith("时间：2026年"))
        assertFalse(timeLayout.single().hasVisualOverflow)
        compose.onNodeWithText("Source.kt:42)", substring = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        val result = layout.single()
        assertEquals("One visual line per raw stack line", raw.lines().size - 1, result.lineCount)
        assertFalse(result.layoutInput.text.text.contains("1793664000000"))
        assertTrue(result.layoutInput.text.text.contains("Source.kt:42)"))
        assertTrue(result.size.width > 400) // The frame is scrollable rather than clipped or wrapped.
        assertEquals("time=1793664000000", raw.lineSequence().first())
    }

    @Test fun normalFontShowsReadableTimeAndUnbrokenFrames() { page(1f); checkDisplay() }
    @Test fun extraLargeFontKeepsFramesOnOneScrollableLine() { page(2.5f); checkDisplay() }
}
