package isomeria.aichat.feature.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.ModelConfig
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.llm.NativeEffort
import isomeria.aichat.data.llm.TemperatureMode
import isomeria.aichat.data.llm.TemperatureRule
import isomeria.aichat.data.llm.TemperatureSpec
import isomeria.aichat.data.llm.ThinkingMode
import isomeria.aichat.data.llm.ThinkingRule
import isomeria.aichat.data.repo.ModelReference
import isomeria.aichat.data.repo.ReferencePurpose
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 审计里在真机上复现过的几个问题：弹窗按钮被挤出屏幕、清空温度存成 0、未保存直接返回、点档位就删除。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ModelEditUiTest {
    @get:Rule val compose = createComposeRule()

    private val provider = ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = "openai", name = "示例", baseUrl = "https://a.example/v1", defaultModel = "", compatibility = "GENERIC")
    private val original = ProviderModel(1, "m", "示例模型", imageInputUserMark = false, legacy = false,
        userConfig = ModelRules.encode(ModelConfig(true, ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("low", "低"), NativeEffort("high", "高"))),
            TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0.2f, 1.5f)))))

    private var current by mutableStateOf(original)
    private var saves = 0
    private var backs = 0
    private var removes = 0

    private fun page() = compose.setContent {
        AIChatTheme(darkTheme = false) {
            ModelEditContent(ModelEditState(current, provider, original = original), { backs++ }, { current = it }, { saves++ }, { removes++ })
        }
    }

    private fun normalTemperature() = ModelRules.decode(current.userConfig).temperature!!.normal
    private fun efforts() = ModelRules.decode(current.userConfig).thinking!!.efforts.map { it.value }

    @Test fun addEffortActionHasNoNavigationArrow() {
        page()
        val pixels = compose.onNodeWithText("添加强度档位").performScrollTo().captureToImage().toPixelMap()
        // The title ends well before this strip. A navigation chevron would draw here.
        var colored = 0
        for (x in (pixels.width * .91f).toInt() until (pixels.width * .96f).toInt()) {
            for (y in 0 until pixels.height) {
                val p = pixels[x, y]
                if (p.red < .97f || p.green < .97f || p.blue < .97f) colored++
            }
        }
        assertEquals("An immediate action must leave the arrow strip empty", 0, colored)
    }

    @Test
    fun conflictButtonsStayOnScreenWithLongList() {
        var confirmed = false
        val refs = (1..60).flatMap { listOf(ModelReference("联系人$it", ReferencePurpose.CHAT), ModelReference("联系人$it", ReferencePurpose.MEMORY)) }
        compose.setContent { AIChatTheme(darkTheme = false) { ConflictDialog(refs, {}) { confirmed = true } } }
        compose.onNodeWithText("取消").assertIsDisplayed()
        compose.onNodeWithText("仍然保存").assertIsDisplayed().performClick()
        compose.waitForIdle() // 对话框先淡出再执行（0.9.1 审计）
        assertTrue(confirmed)
        // 同一联系人合成一行，名单末尾滚动后能看到
        compose.onNodeWithText("联系人60：聊天、记忆").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearingTemperatureKeepsLastValidValueAndBlocksSave() {
        page()
        compose.onNodeWithText("0.2").performScrollTo().performTextReplacement("")
        compose.onNodeWithText("请输入有效的数字，例如 0.7").assertExists()
        assertEquals(0.2f, normalTemperature().min)
        compose.onNodeWithText("保存").performClick()
        assertEquals(0, saves)
    }

    @Test
    fun intermediateTemperatureIsNotSaved() {
        page()
        compose.onNodeWithText("0.2").performScrollTo().performTextReplacement("0.")
        assertEquals(0.2f, normalTemperature().min)
        compose.onNodeWithText("0.").performTextReplacement("0.7")
        assertEquals(0.7f, normalTemperature().min)
        compose.onNodeWithText("请输入有效的数字，例如 0.7").assertDoesNotExist()
        compose.onNodeWithText("保存").performClick()
        assertEquals(1, saves)
    }

    @Test
    fun switchingModeClearsPendingInputError() {
        page()
        compose.onNodeWithText("0.2").performScrollTo().performTextReplacement("")
        compose.onNodeWithText("默认温度规则").performScrollTo().performClick()
        compose.onNodeWithText("不发送温度参数").performClick()
        compose.waitForIdle()
        assertEquals(TemperatureMode.UNSUPPORTED, normalTemperature().mode)
        compose.onNodeWithText("保存").performClick()
        assertEquals(1, saves)
    }

    @Test
    fun backAsksBeforeDiscardingChanges() {
        page()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals("没有改动时直接返回", 1, backs)
        compose.onNodeWithText("0.2").performScrollTo().performTextReplacement("0.3")
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backs)
        compose.onNodeWithText("放弃").performClick()
        compose.waitForIdle() // 对话框先淡出再执行（0.9.1 审计）
        assertEquals(2, backs)
    }

    @Test
    fun invalidInputAlsoCountsAsUnsaved() {
        page()
        compose.onNodeWithText("0.2").performScrollTo().performTextReplacement("abc")
        assertEquals("规则没有变化", original, current)
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(0, backs)
        compose.onNodeWithText("放弃").assertExists()
    }

    @Test
    fun tappingEffortRowDoesNotRemoveIt() {
        page()
        compose.onNodeWithText("低").performScrollTo().performClick()
        assertEquals(listOf("low", "high"), efforts())
        compose.onAllNodesWithText("移除").onFirst().performScrollTo().performClick()
        assertEquals(listOf("high"), efforts())
    }

    @Test
    fun removingModelNeedsConfirmation() {
        page()
        compose.onNodeWithText("移除模型").performScrollTo().performClick()
        assertEquals(0, removes)
        compose.onNodeWithText("移除模型“示例模型”？已填写的配置会一起删除。").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, removes)
        compose.onNodeWithText("移除模型").performScrollTo().performClick()
        compose.onAllNodesWithText("移除").onLast().performClick()
        compose.waitForIdle() // 对话框先淡出再执行（0.9.1 审计）
        assertEquals(1, removes)
    }

    @Test
    fun effortAddShowsReason() {
        page()
        compose.onNodeWithText("如 low", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("如 低", useUnmergedTree = true).assertExists()
        // 没输入时不提前报错
        compose.onNodeWithText("请填写原生强度值").assertDoesNotExist()
        val value = compose.onAllNodes(hasSetTextAction())[0]
        val label = compose.onAllNodes(hasSetTextAction())[1]
        value.performTextInput("off")
        compose.onNodeWithText("off、on、none 已有专门含义，不能作为强度值").assertExists()
        compose.onNodeWithText("添加强度档位").assertIsNotEnabled()
        value.performTextReplacement("medium")
        compose.onNodeWithText("请填写显示名称").assertExists()
        label.performTextInput("中")
        compose.onNodeWithText("添加强度档位").assertIsEnabled().performClick()
        assertEquals(listOf("low", "high", "medium"), efforts())
    }

    @Test
    fun summaryShowsNameIdAndStatus() {
        page()
        compose.onNodeWithText("示例模型").assertExists()
        compose.onNodeWithText("m").assertExists()
        compose.onNodeWithText("已配置").assertExists()
    }

    @Test
    fun unknownSourceIsNotShownTwice() {
        val pending = ProviderModel(1, "p", "待配置模型", legacy = false)
        compose.setContent { AIChatTheme(darkTheme = false) { ModelEditContent(ModelEditState(pending, provider, original = pending), {}, {}, {}) } }
        compose.onNodeWithText("待确认 · 待确认").assertDoesNotExist()
        compose.onNodeWithText("聊天用途", substring = true).assertExists()
    }

    @Test
    fun sourceFollowsTheValue() {
        page()
        compose.onNodeWithText("确认可聊天 · 用户设置").performScrollTo().assertExists()
    }

    @Test
    fun separateThinkingTemperatureWaitsForAConfirmedMode() {
        val noMode = ModelRules.encode(ModelConfig(true, null, TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE))))
        val pending = ProviderModel(1, "p", "待配置模型", imageInputUserMark = false, legacy = false, userConfig = noMode)
        compose.setContent { AIChatTheme(darkTheme = false) { ModelEditContent(ModelEditState(pending, provider, original = pending), {}, {}, {}) } }
        compose.onNodeWithText("思考时使用不同规则", substring = true).assertDoesNotExist()
    }

    @Test
    fun advancedShowsRealFieldNamesAndRestoreIsDisabledWithoutOverride() {
        page()
        compose.onNodeWithText("模型级高级设置").performScrollTo().performClick()
        compose.onNodeWithText("REASONING_EFFORT", substring = true).assertDoesNotExist()
        compose.onNodeWithText("思考开关字段", substring = true).performScrollTo().assertExists()
        compose.onNodeWithText("恢复继承").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun restoreIsEnabledWithOverride() {
        val overridden = original.copy(compatibilityOverride = ModelRules.encode(isomeria.aichat.data.llm.CompatibilityOverride(streamOptions = false)))
        current = overridden
        page()
        compose.onNodeWithText("模型级高级设置").performScrollTo().performClick()
        compose.onNodeWithText("恢复继承").performScrollTo().assertIsEnabled().performClick()
        assertEquals(null, current.compatibilityOverride)
    }

    @Test
    fun defaultReferenceKeepsItsOwnLine() {
        compose.setContent {
            AIChatTheme(darkTheme = false) { ConflictDialog(listOf(ModelReference(null, ReferencePurpose.DEFAULT), ModelReference("小雨", ReferencePurpose.CHAT)), {}) {} }
        }
        compose.onNodeWithText("服务商默认模型").assertIsDisplayed()
        compose.onNodeWithText("小雨：聊天").assertIsDisplayed()
    }
}
