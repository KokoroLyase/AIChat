package isomeria.aichat.feature.chatinfo

import android.app.Application
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 聊天信息页：消息免打扰在置顶上方，聊天背景在清空上方；开关和点击走到对应回调。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ChatInfoUiTest {
    @get:Rule val compose = createComposeRule()

    private fun top(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    @Test
    fun rowsAreInPlannedOrder() {
        compose.setContent { AIChatTheme(darkTheme = false) { ChatInfoContent(previewChatInfo, {}, {}, {}, {}, {}, {}, {}) } }
        val order = listOf("查找聊天记录", "消息免打扰", "置顶聊天", "设置当前聊天背景", "清空聊天记录").map(::top)
        assertEquals(order.sorted(), order)
        assertTrue(order.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun muteSwitchShowsStateAndReportsChange() {
        val muted = mutableListOf<Boolean>()
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ChatInfoContent(previewChatInfo.copy(muted = false, pinned = true), {}, {}, {}, { muted += it }, {}, {}, {})
            }
        }
        val switches = compose.onAllNodes(isToggleable())
        switches[0].assertIsOff() // 免打扰
        switches[1].assertIsOn() // 置顶
        switches[0].performClick()
        assertEquals(listOf(true), muted)
    }

    @Test
    fun backgroundRowInvokesPicker() {
        var opened = 0
        compose.setContent { AIChatTheme(darkTheme = true) { ChatInfoContent(previewChatInfo, {}, {}, {}, {}, {}, { opened++ }, {}) } }
        compose.onNodeWithText("设置当前聊天背景").performClick()
        assertEquals(1, opened)
    }
}
