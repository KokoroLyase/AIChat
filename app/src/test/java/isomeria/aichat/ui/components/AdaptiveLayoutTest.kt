package isomeria.aichat.ui.components

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.ModelConfig
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.llm.TemperatureMode
import isomeria.aichat.data.llm.TemperatureRule
import isomeria.aichat.data.llm.TemperatureSpec
import isomeria.aichat.data.llm.ThinkingMode
import isomeria.aichat.data.llm.ThinkingRule
import isomeria.aichat.feature.settings.ConfigActionRow
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 批次 C：配置行和表单行放不下时才换行；弹窗和菜单跟随 App 字体；温度控件。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun row(fontScale: Float, title: String, value: String) = compose.setContent {
        AIChatTheme(darkTheme = false, fontScale = fontScale) { ConfigActionRow(title, value) {} }
    }

    @Test
    fun configRowKeepsTitleLeftAndValueRightWhenItFits() {
        row(1f, "默认模型", "示例")
        val title = compose.onNodeWithText("默认模型", useUnmergedTree = true).getBoundsInRoot()
        val value = compose.onNodeWithText("示例", useUnmergedTree = true).getBoundsInRoot()
        assertEquals("同一行", title.top.value, value.top.value, 6f)
        assertTrue("值靠右", value.left > 300.dp)
    }

    @Test
    fun configRowMovesValueBelowWhenItDoesNotFit() {
        row(2.5f, "思考时使用不同规则", "单独设置思考温度规则")
        val title = compose.onNodeWithText("思考时使用不同规则", useUnmergedTree = true).getBoundsInRoot()
        val value = compose.onNodeWithText("单独设置思考温度规则", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("值在标题下方", value.top >= title.bottom)
        assertEquals("值靠左", title.left.value, value.left.value, 1f)
    }

    @Test
    fun valueIsVerticallyCenteredWhenThereIsADetailLine() {
        compose.setContent { AIChatTheme(darkTheme = false) { ConfigActionRow("示例模型", "已配置", detail = "example-id") {} } }
        val title = compose.onNodeWithText("示例模型", useUnmergedTree = true).getBoundsInRoot()
        val detail = compose.onNodeWithText("example-id", useUnmergedTree = true).getBoundsInRoot()
        val value = compose.onNodeWithText("已配置", useUnmergedTree = true).getBoundsInRoot()
        assertEquals("值的中线和左边两行的中线对齐", ((title.top + detail.bottom) / 2).value, ((value.top + value.bottom) / 2).value, 1f)
        assertTrue("小字在标题下方", detail.top >= title.bottom)
    }

    @Test
    fun longDetailDoesNotRunUnderTheValue() {
        val id = "vendor/extremely-long-model-identifier-2026-11-preview:thinking-extended"
        compose.setContent { AIChatTheme(darkTheme = false) { ConfigActionRow("示例模型", "已配置", detail = id) {} } }
        val detail = compose.onNodeWithText(id, useUnmergedTree = true).getBoundsInRoot()
        val value = compose.onNodeWithText("已配置", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("小字 ${detail.right} 不能压到值 ${value.left}", detail.right <= value.left)
    }

    private fun form(fontScale: Float) = compose.setContent {
        AIChatTheme(darkTheme = false, fontScale = fontScale) { FormRow("最低温度") { Text("内容") } }
    }

    @Test
    fun formLabelStaysLeftWithNormalFont() {
        form(1f)
        val label = compose.onNodeWithText("最低温度", useUnmergedTree = true).getBoundsInRoot()
        val content = compose.onNodeWithText("内容", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("标题和内容在同一行", content.top < label.bottom && content.left >= label.right)
    }

    @Test
    fun formLabelMovesAboveInsteadOfWrappingWithLargeFont() {
        form(2.5f)
        val label = compose.onNodeWithText("最低温度", useUnmergedTree = true).getBoundsInRoot()
        val content = compose.onNodeWithText("内容", useUnmergedTree = true).getBoundsInRoot()
        assertTrue("内容在标题下方", content.top >= label.bottom)
        // 17sp × 2.5 约 42sp，四个字放在一行时宽度超过 96dp，也不会被挤成两行
        assertTrue("标题只占一行，高度 ${label.height}", label.height < 80.dp)
    }

    private fun textHeight(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).getBoundsInRoot().height

    @Test
    fun dialogFollowsAppFontScale() {
        compose.setContent {
            AIChatTheme(darkTheme = false, fontScale = 2f) {
                Text("页面文字", style = isomeria.aichat.ui.theme.WeTheme.typography.listTitle)
                WeDialog(message = "弹窗文字", onDismiss = {}, onConfirm = {}, confirmText = "确认", dismissText = "取消")
            }
        }
        compose.onNodeWithText("确认").assertIsDisplayed()
        // 页面文字和取消按钮都是 listTitle，字号应当相同
        assertEquals(textHeight("页面文字").value, textHeight("取消").value, 1f)
    }

    @Test
    fun actionSheetFollowsAppFontScale() {
        compose.setContent {
            AIChatTheme(darkTheme = false, fontScale = 2f) {
                Column {
                    Text("页面文字", style = isomeria.aichat.ui.theme.WeTheme.typography.listTitle)
                    WeActionSheet(options = listOf(SheetOption("选项") {}), onDismiss = {})
                }
            }
        }
        assertEquals(textHeight("页面文字").value, textHeight("选项").value, 1f)
        compose.onNodeWithText("取消").assertIsDisplayed()
    }

    @Test
    fun temperatureSnapsToTenthsAndReachesTheEnds() {
        assertEquals(0.9f, snapTemperature(0.87f, 0f, 2f), 0.0001f)
        assertEquals(0.25f, snapTemperature(0.25f, 0.25f, 1.05f), 0.0001f)
        assertEquals(1.05f, snapTemperature(1.05f, 0.25f, 1.05f), 0.0001f)
        assertEquals("吸附后不越界", 0.98f, snapTemperature(0.96f, 0.25f, 0.98f), 0.0001f)
    }

    @Test
    fun temperatureTextShowsTheSavedValueAsIs() {
        assertEquals("1.0", temperatureText(1f))
        assertEquals("0.3", temperatureText(0.3f))
        assertEquals("0.99", temperatureText(0.99f))
        assertEquals("1.25", temperatureText(1.25f))
    }

    private val provider = ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = "openai", name = "示例", baseUrl = "https://a.example/v1", defaultModel = "", compatibility = "GENERIC")
    private fun model(spec: TemperatureSpec) = ProviderModel(1, "m", "示例", imageInputUserMark = false, legacy = false,
        userConfig = ModelRules.encode(ModelConfig(true, ThinkingRule(ThinkingMode.NONE), TemperatureRule(spec))))

    private fun parameters(spec: TemperatureSpec, temperature: Float, onChange: (Float) -> Unit = {}) = compose.setContent {
        AIChatTheme(darkTheme = false) { Column { ModelParameterControls(provider, model(spec), "off", temperature, {}, onChange) } }
    }

    @Test
    fun outOfRangeValueIsShownButNotRewritten() {
        var changes = 0
        parameters(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0.2f, 1.5f), 1.8f) { changes++ }
        compose.onNodeWithText("1.8").assertExists()
        compose.waitForIdle()
        assertEquals("没有拖动就不能改值", 0, changes)
    }

    @Test
    fun draggingSnapsToTenths() {
        var value = -1f
        parameters(TemperatureSpec(TemperatureMode.ADJUSTABLE), 1f) { value = it }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performSemanticsAction(SemanticsActions.SetProgress) { it(0.87f) }
        assertEquals(0.9f, value, 0.0001f)
    }

    @Test
    fun fixedTemperatureHasItsOwnRow() {
        parameters(TemperatureSpec(TemperatureMode.FIXED, fixed = 1f), 0.5f)
        compose.onNodeWithText("温度").assertExists()
        compose.onNodeWithText("固定 1.0").assertExists()
        compose.onNodeWithText("这个模型要求固定温度。已保存的联系人温度保留，不会被改写。").assertExists()
    }

    @Test
    fun omittedTemperatureHasItsOwnRow() {
        parameters(TemperatureSpec(TemperatureMode.UNSUPPORTED), 0.5f)
        compose.onNodeWithText("温度").assertExists()
        compose.onNodeWithText("不发送").assertExists()
    }
}
