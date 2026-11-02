package isomeria.aichat.feature.settings

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.Compatibility
import isomeria.aichat.data.llm.ModelConfig
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.llm.TemperatureMode
import isomeria.aichat.data.llm.TemperatureRule
import isomeria.aichat.data.llm.TemperatureSpec
import isomeria.aichat.data.llm.ThinkingMode
import isomeria.aichat.data.llm.ThinkingRule
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 服务商页：模型分组、折叠和搜索，兼容方案的友好名称和 LEGACY 的限制，页面顺序。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ProviderModelListUiTest {
    @get:Rule val compose = createComposeRule()

    private val rules = ModelRules.encode(ModelConfig(true, ThinkingRule(ThinkingMode.NONE), TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE))))
    private val ready = ProviderModel(2, "vendor/ready", "可用模型", imageInputUserMark = false, legacy = false, userConfig = rules)
    private fun pending(count: Int) = (1..count).map { ProviderModel(2, "vendor/model-$it", "候选$it", legacy = false) }
    private val state = ProviderEditState(loaded = true, providerId = 2, name = "接口", baseUrl = "https://a.example/v1", compatibility = "GENERIC", savedForm = ProviderSavedForm("接口", "https://a.example/v1", "GENERIC"))
    private var opened: String? = null

    private fun page(models: List<ProviderModel>, s: ProviderEditState = state) = compose.setContent {
        AIChatTheme(darkTheme = false) {
            ProviderEditContent(s, models, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, onConfigureModel = { opened = it.modelId })
        }
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun fewModelsAreExpandedAndHaveNoSearch() {
        page(listOf(ready) + pending(3))
        compose.onNodeWithText("已配置（1）").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("待配置（3）").performScrollTo().assertIsDisplayed()
        assertTrue(exists("候选3"))
        assertFalse(exists("搜索模型名称或 ID"))
    }

    @Test
    fun manyModelsCollapsePendingButKeepConfiguredVisible() {
        page(listOf(ready) + pending(30))
        assertTrue(exists("可用模型"))
        assertFalse(exists("候选1"))
        compose.onNodeWithText("待配置（30）").performScrollTo().performClick()
        assertTrue(exists("候选30"))
        compose.onNodeWithText("待配置（30）").performScrollTo().performClick()
        assertFalse(exists("候选30"))
    }

    @Test
    fun searchFindsByNameOrIdInBothGroupsEvenWhenCollapsed() {
        page(listOf(ready) + pending(30))
        compose.onNodeWithText("搜索模型名称或 ID", useUnmergedTree = true).assertExists()
        searchField().performScrollTo().performTextInput("VENDOR/MODEL-7")
        assertTrue("折叠时也能搜到", exists("候选7"))
        assertFalse(exists("候选8"))
        assertFalse(exists("可用模型"))
        compose.onNodeWithText("待配置（1）").assertExists()
    }

    @Test
    fun searchByNameAndClearRestoresTheList() {
        page(listOf(ready) + pending(30))
        searchField().performScrollTo().performTextInput("可用")
        assertTrue(exists("可用模型"))
        assertFalse(exists("待配置（"))
        compose.onNode(hasContentDescription("清空")).performClick()
        assertTrue(exists("待配置（30）"))
    }

    @Test
    fun noMatchSaysSo() {
        page(pending(30))
        searchField().performScrollTo().performTextInput("不存在")
        assertTrue(exists("没有匹配的模型"))
    }

    @Test
    fun rowOpensTheModelAndDoesNotRepeatTheId() {
        val same = ProviderModel(2, "same-id", "same-id", legacy = false)
        page(listOf(same))
        assertEquals(1, compose.onAllNodes(hasText("same-id", substring = true)).fetchSemanticsNodes().size)
        compose.onNodeWithText("same-id", substring = true).performScrollTo().performClick()
        assertEquals("same-id", opened)
    }

    @Test
    fun missingFromListStaysWithConfiguredAndIsMarked() {
        page(listOf(ready.copy(missingFromList = true)))
        compose.onNodeWithText("已配置（1）").performScrollTo().assertIsDisplayed()
        assertTrue(exists("vendor/ready · 本次接口未返回"))
    }

    @Test
    fun addModelComesBeforeTheList() {
        page(listOf(ready))
        val add = compose.onNodeWithText("添加模型").performScrollTo().fetchSemanticsNode().boundsInRoot
        val group = compose.onNodeWithText("已配置（1）").performScrollTo().fetchSemanticsNode().boundsInRoot
        assertTrue("添加模型应在列表之前", add.top < group.top)
    }

    @Test
    fun rowsStayShortWithNormalFont() {
        page(listOf(ready))
        val row = compose.onNodeWithText("可用模型", substring = true).performScrollTo().fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        assertTrue("一个模型原来约占 161dp，现在 ${row.height / density}dp", row.height / density < 90f)
    }

    @Test
    fun legacyIsOnlyOfferedWhenAlreadySaved() {
        assertFalse(Compatibility.LEGACY in compatibilityChoices("GENERIC"))
        assertTrue(Compatibility.LEGACY in compatibilityChoices("LEGACY"))
        assertEquals("其他方案都还在", Compatibility.entries.size - 1, compatibilityChoices("DEEPSEEK").size)
    }

    @Test
    fun compatibilitySheetUsesFriendlyNamesAndHidesLegacy() {
        page(emptyList())
        compose.onNodeWithText("高级连接设置").performScrollTo().performClick()
        compose.onNodeWithText("协议：OpenAI Chat Completions").assertExists()
        compose.onNodeWithText("兼容方案", substring = true).performScrollTo().performClick()
        assertTrue(exists("OpenRouter（reasoning.enabled、reasoning.effort）"))
        assertFalse(exists("升级保留（旧规则）"))
        assertFalse(exists("OPENROUTER"))
    }

    @Test
    fun legacyProviderKeepsItsOptionAndIsWarned() {
        page(emptyList(), state.copy(compatibility = "LEGACY", savedForm = state.savedForm.copy(compatibility = "LEGACY")))
        compose.onNodeWithText("高级连接设置").performScrollTo().performClick()
        assertTrue(exists("保存成其他方案后不能再选回"))
        assertTrue(exists("升级保留（旧规则）"))
    }

    @Test
    fun protocolIsNotOnTheMainPage() {
        page(emptyList())
        assertFalse(exists("协议：OpenAI Chat Completions"))
    }

    @Test
    fun docsLinkAppearsForAPreset() {
        page(emptyList(), state.copy(presetId = "deepseek", compatibility = "DEEPSEEK"))
        assertTrue(exists("官方文档"))
    }

    @Test
    fun customProviderHasNoDocsLink() {
        page(emptyList())
        assertFalse(exists("官方文档"))
        assertNull(docsPreset(null, "GENERIC"))
    }

    /** 页面上最后一个输入框：名称、地址、Key、添加模型之后才是搜索框。 */
    private fun searchField() = compose.onAllNodes(hasSetTextAction()).onLast()
}
