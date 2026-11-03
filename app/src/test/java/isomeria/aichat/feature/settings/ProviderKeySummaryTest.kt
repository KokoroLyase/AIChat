package isomeria.aichat.feature.settings

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
class ProviderKeySummaryTest {
    @get:Rule val compose = createComposeRule()

    private fun verify(scale: Float) {
        val summary = "已保存：sk-f••••••5678"
        compose.setContent { AIChatTheme(darkTheme = false, fontScale = scale) {
            ProviderEditSample(ProviderEditState(loaded = true, providerId = 1, name = "接口", baseUrl = "https://example.invalid/v1", savedKeyMasked = "sk-f••••••5678"))
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(summary, useUnmergedTree = true).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        assertEquals(summary, layout.layoutInput.text.text)
        assertFalse("The complete masked summary must be visible: size=${layout.size} paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height} lines=${layout.lineCount} ends=${(0 until layout.lineCount).map { layout.getLineEnd(it, true) }}", layout.hasVisualOverflow)
        assertFalse(layout.isLineEllipsized(layout.lineCount - 1))
    }

    @Test fun appExtraLargeKeepsTheWholeMaskedSummary() = verify(1.375f)
    @Test fun largestLayoutWrapsTheSameMaskedSummary() = verify(2.5f)
}
