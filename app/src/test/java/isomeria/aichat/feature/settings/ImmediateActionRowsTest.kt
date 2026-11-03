package isomeria.aichat.feature.settings

import android.app.Application
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import isomeria.aichat.feature.chatinfo.ChatInfoContent
import isomeria.aichat.feature.chatinfo.previewChatInfo
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
class ImmediateActionRowsTest {
    @get:Rule val compose = createComposeRule()

    private fun noArrow(text: String) {
        val pixels = compose.onNodeWithText(text).captureToImage().toPixelMap()
        var colored = 0
        for (x in (pixels.width * .91f).toInt() until (pixels.width * .96f).toInt()) {
            for (y in 3 until pixels.height - 3) {
                val p = pixels[x, y]
                if (p.red < .97f || p.green < .97f || p.blue < .97f) colored++
            }
        }
        assertEquals("$text has no navigation arrow", 0, colored)
    }

    @Test fun storageActionsHaveNoArrowAndStillConfirmOrPreview() {
        var previews = 0
        compose.setContent { AIChatTheme(darkTheme = false) {
            StorageContent(StorageState(loading = false), {}, {}, { previews++ }, {}, {}, {})
        } }
        noArrow("清理缓存")
        noArrow("清理孤立文件")
        compose.onNodeWithText("清理孤立文件").performClick()
        assertEquals(1, previews)
        compose.onNodeWithText("清理缓存").performClick()
        compose.onNodeWithText("清理").assertExists()
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun clearChatHasNoArrowAndStillInvokesConfirmation() {
        var clears = 0
        compose.setContent { AIChatTheme(darkTheme = false) {
            ChatInfoContent(previewChatInfo, {}, {}, {}, {}, { clears++ })
        } }
        noArrow("清空聊天记录")
        compose.onNodeWithText("清空聊天记录").performClick()
        assertEquals(1, clears)
    }
}
