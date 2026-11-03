package isomeria.aichat.feature.contactedit

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import isomeria.aichat.R
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ContextInputUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private var input by mutableStateOf("4000")
    private fun page() = compose.setContent { AIChatTheme(darkTheme = false) {
        ContextBudgetRow(parseContextInput(input) ?: 4000, { input = it.toString() }, input,
            { input = it }, { parseContextInput(input)?.let { input = it.toString() } })
    } }
    private fun field() = compose.onNodeWithContentDescription(app.getString(R.string.contact_context_tokens))
    @Test fun arbitraryNumberAndStepping() {
        page()
        field().performTextReplacement("4200")
        assertEquals("4200", input)
        compose.onNodeWithContentDescription(app.getString(R.string.contact_context_increase)).performClick()
        assertEquals("5000", input)
        compose.onNodeWithContentDescription(app.getString(R.string.contact_context_decrease)).performClick()
        assertEquals("4000", input)
    }
    @Test fun invalidDraftStaysAndDisablesButtons() {
        page()
        listOf("", "999", "200001", "999999999999999999999999").forEach {
            field().performTextReplacement(it)
            assertEquals(it, input)
            compose.onNodeWithText(app.getString(R.string.contact_context_invalid)).assertExists()
            compose.onNodeWithContentDescription(app.getString(R.string.contact_context_increase)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(app.getString(R.string.contact_context_decrease)).assertIsNotEnabled()
        }
    }
    @Test fun invalidPasteIsRejectedAndDoneNormalizes() {
        page()
        listOf("4.2", "-4200", "4 200", "abcd", "４２００").forEach {
            field().performTextReplacement(it)
            assertEquals("4000", input)
        }
        field().performTextReplacement("004200")
        field().performImeAction()
        assertEquals("4200", input)
    }
    @Test fun focusSelectsAllDigits() {
        page()
        field().performClick()
        compose.waitForIdle()
        assertEquals(androidx.compose.ui.text.TextRange(0, 4), field().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange])
    }
    @Test fun parserAndDirtyKeepInvalidDraft() {
        listOf("1000", "4200", "200000", "004200").forEach { assertNotNull(parseContextInput(it)) }
        listOf("", "999", "200001", "99999999999999999999", "+4000", " 4000").forEach { assertNull(parseContextInput(it)) }
        val form = ContactForm()
        assertFalse(ContactEditState(loaded = true, form = form, original = form).dirty)
        assertTrue(ContactEditState(loaded = true, form = form, original = form, contextInput = "").dirty)
    }
}
