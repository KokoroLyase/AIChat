package isomeria.aichat.feature.contactedit

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import isomeria.aichat.R
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 编辑联系人的「系统提示词」改成一行入口：行里只显示开头（换行折成空格），没写时显示「未填写」；
 * 点进去单独一页编辑，改动回写到编辑页的草稿，「完成」和返回都回到编辑页。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ContactPromptUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun previewCollapsesLinesAndBlankIsEmpty() {
        assertEquals("你是小雨。 说话简短。", promptPreview("  你是小雨。\n\n  说话简短。\n"))
        assertNull(promptPreview(" \n "))
    }

    @Test
    fun rowShowsPreviewOrEmpty_andOpensEditor() {
        var opened = 0
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Column {
                    PromptRow("你是小雨。\n说话简短。") { opened++ }
                    PromptRow("") {}
                }
            }
        }
        compose.onNodeWithText("你是小雨。 说话简短。").assertExists()
        compose.onNodeWithText(app.getString(R.string.contact_prompt_empty)).assertExists()
        compose.onNodeWithText("你是小雨。 说话简短。").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun editorWritesBackToDraft_andDoneReturns() {
        var draft by mutableStateOf("旧的提示词")
        var backs = 0
        compose.setContent {
            AIChatTheme(darkTheme = false) { ContactPromptContent(draft, { draft = it }, { backs++ }) }
        }
        compose.onNodeWithText(app.getString(R.string.contact_prompt_hint)).assertExists()
        val field = compose.onNodeWithContentDescription(app.getString(R.string.contact_prompt))
        field.assertTextEquals("旧的提示词")
        field.performTextReplacement("新的提示词\n第二行")
        assertEquals("新的提示词\n第二行", draft)
        compose.onNodeWithText(app.getString(R.string.action_done)).performClick()
        assertEquals(1, backs)
    }
}
