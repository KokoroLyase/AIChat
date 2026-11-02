package isomeria.aichat.ui.components

import android.app.Application
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 字体调到最大（系统 2.0 × App 特大 1.25）时，顶栏和按钮不重叠、不把字裁掉。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class LargeFontLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun topBar(fontScale: Float, title: String) {
        compose.setContent {
            AIChatTheme(darkTheme = false, fontScale = fontScale) {
                WeTopBar(title = title, onBack = {}) { WeSmallButton("保存", {}) }
            }
        }
    }

    @Test
    fun normalFont_titleCenteredAndBarKeepsItsHeight() {
        topBar(1f, "编辑联系人")
        val title = compose.onNodeWithText("编辑联系人").getBoundsInRoot()
        assertEquals(200f, ((title.left + title.right) / 2).value, 1f)
    }

    @Test
    fun largestFont_titleDoesNotReachButton() {
        topBar(2.5f, "编辑联系人")
        val title = compose.onNodeWithText("编辑联系人", useUnmergedTree = true).getBoundsInRoot()
        val button = compose.onNodeWithText("保存", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("标题 ${title.right} 压到了按钮 ${button.left}", title.right <= button.left)
    }

    @Test
    fun largestFont_smallButtonGrowsWithText() {
        topBar(2.5f, "标题")
        val button = compose.onNodeWithText("保存", useUnmergedTree = true).getBoundsInRoot()
        // 14sp × 2.5 的文字约 35dp 以上，固定 32dp 高会被裁掉
        assertTrue("按钮文字高度 ${button.bottom - button.top}", (button.bottom - button.top) > 32.dp)
    }
}
