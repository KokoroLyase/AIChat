package isomeria.aichat.feature.contactedit

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import isomeria.aichat.R
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.llm.ConfigurationIssue
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 联系人详情要能看出参数有问题，并直达修正的地方。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ContactDetailStatusUiTest {
    @get:Rule val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val contact = Contact(id = 1, name = "小雨", avatarColor = 0, providerId = 7, model = "m", createdAt = 1, updatedAt = 1)
    private var edits = 0
    private var memories = 0
    private var model: Pair<Long, String>? = null

    private fun page(chat: ConfigurationIssue? = null, memory: ConfigurationIssue? = null) = compose.setContent {
        AIChatTheme(darkTheme = false) {
            ContactDetailContent(
                ContactDetailState(loaded = true, contact = contact, modelName = "m", chatIssue = chat, memoryIssue = memory),
                {}, {}, { edits++ }, { memories++ }, { id, m -> model = id to m },
            )
        }
    }

    @Test
    fun actionsStayReachableWithLargeFontAndIssues() {
        compose.setContent {
            AIChatTheme(darkTheme = false, fontScale = 2.5f) {
                ContactDetailContent(
                    ContactDetailState(loaded = true, contact = contact, modelName = "m", chatIssue = ConfigurationIssue.TEMPERATURE, memoryIssue = ConfigurationIssue.THINKING),
                    {}, {}, { edits++ }, { memories++ },
                )
            }
        }
        compose.onNodeWithText(app.getString(R.string.contact_edit)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, edits)
    }

    @Test
    fun noRowsWithoutIssues() {
        page()
        compose.onNodeWithText(app.getString(R.string.contact_chat_issue)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.contact_memory_issue)).assertDoesNotExist()
    }

    @Test
    fun contactParameterProblemOpensContactEdit() {
        page(chat = ConfigurationIssue.THINKING)
        compose.onNodeWithText(app.getString(R.string.config_issue_thinking)).assertExists()
        compose.onNodeWithText(app.getString(R.string.contact_chat_issue)).performClick()
        assertEquals(1, edits)
        assertEquals(null, model)
    }

    @Test
    fun missingModelOpensContactEdit() {
        page(chat = ConfigurationIssue.MODEL)
        compose.onNodeWithText(app.getString(R.string.contact_chat_issue)).performClick()
        assertEquals(1, edits)
    }

    @Test
    fun ruleProblemOpensModelPage() {
        page(chat = ConfigurationIssue.INCOMPLETE)
        compose.onNodeWithText(app.getString(R.string.contact_chat_issue)).performClick()
        assertEquals(7L to "m", model)
        assertEquals(0, edits)
    }

    @Test
    fun memoryProblemOpensMemoryPage() {
        page(memory = ConfigurationIssue.TEMPERATURE)
        compose.onNodeWithText(app.getString(R.string.memory_issue_temperature)).assertExists()
        compose.onNodeWithText(app.getString(R.string.contact_memory_issue)).performClick()
        assertEquals(1, memories)
    }

    @Test
    fun bothProblemsAreShownSeparately() {
        page(chat = ConfigurationIssue.TEMPERATURE, memory = ConfigurationIssue.THINKING)
        compose.onNodeWithText(app.getString(R.string.config_issue_temperature)).assertExists()
        compose.onNodeWithText(app.getString(R.string.config_issue_thinking)).assertExists()
    }
}
