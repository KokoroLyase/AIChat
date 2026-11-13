package isomeria.aichat.feature.voice

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import isomeria.aichat.data.voice.ModelDownloadWork
import isomeria.aichat.data.voice.ModelFailure
import isomeria.aichat.data.voice.ModelState
import isomeria.aichat.data.voice.SystemTtsInfo
import isomeria.aichat.data.voice.SystemTtsStatus
import isomeria.aichat.data.voice.SystemTtsVoice
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.7.1 本机来源的两个页面：离线识别按模型状态显示下载、继续、暂停、删除，下载要先确认，模型没下好或手机不支持时开关打不开；
 * 系统语音在不能用时开关打不开、说明原因。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class LocalVoiceUiTest {
    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    private val actions = OfflineAsrActions(
        onUse = { calls += "use:$it" },
        onDownload = { calls += "download" },
        onPause = { calls += "pause" },
        onDelete = { calls += "delete" },
    )

    private fun asr(state: OfflineAsrUiState) = compose.setContent { AIChatTheme(darkTheme = false) { OfflineAsrContent(state, {}, actions) } }

    @Test
    fun missing_downloadNeedsConfirmation_switchDisabled() {
        asr(OfflineAsrUiState(model = ModelState.Missing))
        compose.onNodeWithContentDescription("使用离线识别").assertIsNotEnabled()
        compose.onNodeWithText("下载模型").performClick()
        assertEquals("点了还没确认，不下载", emptyList<String>(), calls)
        compose.onNodeWithText("下载").performClick()
        assertEquals(listOf("download"), calls)
        assertEquals(0, compose.onAllNodesWithTextCount("删除模型") + compose.onAllNodesWithTextCount("删除已下载的部分"))
    }

    @Test
    fun downloading_onlyPause() {
        asr(OfflineAsrUiState(model = ModelState.Downloading(10), work = ModelDownloadWork.RUNNING))
        compose.onNodeWithText("暂停下载").performClick()
        assertEquals(listOf("pause"), calls)
        assertEquals(0, compose.onAllNodesWithTextCount("下载模型") + compose.onAllNodesWithTextCount("删除已下载的部分"))
    }

    @Test
    fun waitingForNetwork_showsWaiting_andPause() {
        asr(OfflineAsrUiState(model = ModelState.Failed(ModelFailure.NETWORK, 10), work = ModelDownloadWork.WAITING))
        compose.onNodeWithText("等待网络连接…").assertExists()
        compose.onNodeWithText("暂停下载").assertExists()
        assertEquals("等着重试时不提示失败", 0, compose.onAllNodesWithTextCount("网络中断。已下载的部分留着，点“继续下载”接着下。"))
    }

    @Test
    fun partial_resumeOrDeletePart() {
        asr(OfflineAsrUiState(model = ModelState.Partial(5_000_000)))
        compose.onNodeWithText("继续下载").performClick()
        compose.onNodeWithText("下载").performClick()
        compose.onNodeWithText("删除已下载的部分").performClick()
        compose.onNodeWithText("删除").performClick()
        assertEquals(listOf("download", "delete"), calls)
    }

    @Test
    fun installed_switchEnabled_deleteNeedsConfirmation() {
        asr(OfflineAsrUiState(model = ModelState.Installed))
        compose.onNodeWithContentDescription("使用离线识别").assertIsEnabled().performClick()
        compose.onNodeWithText("删除模型").performClick()
        assertEquals(listOf("use:true"), calls)
        compose.onNodeWithText("删除").performClick()
        assertEquals(listOf("use:true", "delete"), calls)
    }

    @Test
    fun unsupported_switchDisabledEvenWithModel() {
        asr(OfflineAsrUiState(supported = false, model = ModelState.Installed))
        compose.onNodeWithText("这台手机不支持离线识别（需要 64 位 ARM 处理器）。").assertExists()
        compose.onNodeWithContentDescription("使用离线识别").assertIsNotEnabled()
    }

    @Test
    fun systemTts_notReady_switchDisabled_reasonShown_inUseCanTurnOff() {
        var use: Boolean? = null
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                SystemTtsContent(SystemTtsUiState(SystemTtsInfo(SystemTtsStatus.NO_ENGINE)), {}, { use = it }, {}, {})
            }
        }
        compose.onNodeWithContentDescription("使用系统语音合成").assertIsNotEnabled()
        compose.onNodeWithText("手机上没有语音合成引擎", substring = true).assertExists()
        compose.onNodeWithText("没有").assertExists()
        assertEquals(null, use)
    }

    @Test
    fun systemTts_ready_pickVoice() {
        var voice: String? = null
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                SystemTtsContent(
                    SystemTtsUiState(SystemTtsInfo(SystemTtsStatus.READY, "引擎", listOf(SystemTtsVoice("v1", "zh-CN"))), inUse = false),
                    {}, {}, { voice = it }, {},
                )
            }
        }
        compose.onNodeWithContentDescription("使用系统语音合成").assertIsEnabled()
        compose.onNodeWithText("引擎默认").performClick()
        compose.onNodeWithText("v1（zh-CN）").performClick()
        // 0.9.1 起底部菜单先滑下去再执行选项
        compose.waitForIdle()
        assertEquals("v1", voice)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
