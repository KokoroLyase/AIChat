package isomeria.aichat.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 列表行右侧的值和控件要靠右对齐，不能挤在标题后面，也不能只占一半宽度被截断。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h860dp", application = Application::class)
class WeListRowTest {
    @get:Rule
    val compose = createComposeRule()

    private val rowWidth = 400.dp
    private val padding = 16.dp

    @Test
    fun trailingControlSitsAtRightEdge() {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Column(Modifier.width(rowWidth)) {
                    WeListRow(
                        title = "新消息通知",
                        showArrow = false,
                        trailing = { Box(Modifier.size(40.dp).testTag("switch")) },
                    )
                    WeListRow(
                        title = "头像",
                        onClick = {},
                        trailing = { Box(Modifier.size(40.dp).testTag("avatar")) },
                    )
                }
            }
        }
        assertDpEquals(rowWidth - padding, compose.onNodeWithTag("switch", useUnmergedTree = true).getBoundsInRoot().right)
        // 有箭头时：控件在箭头（4dp 间距 + 18dp）左边
        assertDpEquals(rowWidth - padding - ARROW, compose.onNodeWithTag("avatar", useUnmergedTree = true).getBoundsInRoot().right)
    }

    @Test
    fun valueSitsAtRightEdgeBesideTitle() {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Column(Modifier.width(rowWidth)) {
                    WeListRow(title = "模型", value = "DeepSeek-V4.1-Flash", onClick = {})
                }
            }
        }
        val bounds = compose.onNodeWithText("DeepSeek-V4.1-Flash", useUnmergedTree = true).getBoundsInRoot()
        assertDpEquals(rowWidth - padding - ARROW, bounds.right)
        // 0.9.1 起值按自身宽度靠右（以前是文字框占满标题后面的全部宽度）；放得下时和标题在同一行
        val title = compose.onNodeWithText("模型", useUnmergedTree = true).getBoundsInRoot()
        assertDpEquals(title.top, bounds.top)
        assertTrue("值 ${bounds.left} 挤到了标题 ${title.right} 上", bounds.left > title.right)
    }

    private fun assertDpEquals(expected: Dp, actual: Dp) = assertEquals(expected.value, actual.value, 0.5f)

    private companion object {
        val ARROW = 22.dp
    }
}
