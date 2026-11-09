package isomeria.aichat.feature.contactedit

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import isomeria.aichat.R
import isomeria.aichat.data.llm.RequestTemperature
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 联系人详情 0.4.2：温度参数行、「思考强度」、「朋友资料」在「记忆」上方并进入编辑、不再有「编辑」按钮。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ContactDetailProfileUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()

    private fun page(state: ContactDetailState = previewState, onEdit: () -> Unit = {}, onMemory: () -> Unit = {}) = compose.setContent {
        AIChatTheme(darkTheme = false) { ContactDetailContent(state, {}, {}, onEdit, onMemory) }
    }

    @Test
    fun temperatureLineFollowsRequest() {
        val cases = listOf(
            RequestTemperature.Sent(1.3f) to "温度参数：1.3",
            RequestTemperature.Sent(0.73f) to "温度参数：0.73",
            RequestTemperature.Fixed(1f) to "温度参数：固定 1.0",
            RequestTemperature.NotSent to "温度参数：不发送",
            RequestTemperature.Blocked(0.7f) to "温度参数：0.7",
        )
        var state by androidx.compose.runtime.mutableStateOf(previewState)
        compose.setContent { AIChatTheme(darkTheme = false) { ContactDetailContent(state, {}, {}, {}, {}) } }
        for ((value, text) in cases) {
            state = previewState.copy(temperature = value)
            compose.onNodeWithText(text).assertExists()
        }
    }

    @Test
    fun thinkingLabelIsThinkingStrength() {
        page(previewState.copy(thinkingName = "高"))
        compose.onNodeWithText("思考强度：高").assertExists()
        compose.onNodeWithText("思考：高").assertDoesNotExist()
    }

    @Test
    fun profileRowAboveMemory_opensEdit_andEditButtonIsGone() {
        var edits = 0
        var memories = 0
        page(onEdit = { edits++ }, onMemory = { memories++ })
        val profile = compose.onNodeWithText("朋友资料").getUnclippedBoundsInRoot().top
        val memory = compose.onNodeWithText(app.getString(R.string.memory_title)).getUnclippedBoundsInRoot().top
        assertTrue(profile < memory)
        compose.onNodeWithText("编辑朋友的头像、名称、服务商、模型、思考强度、温度、系统提示词、上下文上限、全局记忆、回复偏好、是否分条显示、自动记忆等").assertExists()
        compose.onNodeWithText("朋友资料").performClick()
        assertEquals(1, edits)
        assertEquals(0, memories)
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.contact_send_message)).assertExists()
    }
}
