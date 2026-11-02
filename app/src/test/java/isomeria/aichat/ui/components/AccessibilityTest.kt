package isomeria.aichat.ui.components

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import isomeria.aichat.R
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.ModelConfig
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.llm.NativeEffort
import isomeria.aichat.data.llm.ThinkingMode
import isomeria.aichat.data.llm.ThinkingRule
import isomeria.aichat.feature.contactedit.ContextBudgetRow
import isomeria.aichat.feature.settings.ConfigActionRow
import isomeria.aichat.feature.settings.ProviderEditSample
import isomeria.aichat.feature.settings.ProviderEditState
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.DarkWeColors
import isomeria.aichat.ui.theme.LightWeColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 对比度、点击区域和读屏线索。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance())
        val lo = minOf(a.luminance(), b.luminance())
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun roleIs(role: Role) = SemanticsMatcher("role=$role") { it.config.getOrNull(SemanticsProperties.Role) == role }

    private fun allText(): String = compose.onRoot().fetchSemanticsNode().let { root ->
        buildString {
            fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { append(it.text).append('\n') }
                node.config.getOrNull(SemanticsProperties.EditableText)?.let { append(it.text).append('\n') }
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { append(it).append('\n') }
                node.children.forEach(::walk)
            }
            walk(root)
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onRoot() = onNode(androidx.compose.ui.test.isRoot())

    // ---- V11 对比度 ----

    @Test
    fun statusTextIsReadableOnPageAndListInBothThemes() {
        listOf(LightWeColors, DarkWeColors).forEach { c ->
            listOf(c.dangerText, c.brandText).forEach { text ->
                assertTrue("${c.isDark} 页面底 ${contrast(text, c.pageBackground)}", contrast(text, c.pageBackground) >= 4.5f)
                assertTrue("${c.isDark} 列表底 ${contrast(text, c.listBackground)}", contrast(text, c.listBackground) >= 4.5f)
            }
            assertTrue("${c.isDark} 弹窗底", contrast(c.dangerText, c.dialogBackground) >= 4.5f)
        }
    }

    @Test
    fun darkSliderTrackIsVisible() {
        assertTrue(contrast(DarkWeColors.sliderTrackOff, DarkWeColors.listBackground) >= 3f)
    }

    @Test
    fun wechatBrandColorsAreUnchanged() {
        assertEquals(Color(0xFF07C160), LightWeColors.brand)
        assertEquals(Color(0xFFFA5151), LightWeColors.danger)
        assertEquals(Color(0xFFE5E5E5), LightWeColors.switchTrackOff)
        assertEquals(Color(0xFF3A3A3A), DarkWeColors.switchTrackOff)
    }

    // ---- V12 点击区域 ----

    @Test
    fun smallButtonTouchAreaIs48dpEvenThoughItLooksSmaller() {
        compose.setContent { AIChatTheme(darkTheme = false) { WeSmallButton("保存", {}) } }
        val bounds = compose.onNode(roleIs(Role.Button)).getBoundsInRoot()
        assertTrue("${bounds.height}", bounds.height >= 48.dp)
    }

    @Test
    fun keyToggleIs48dpAndSaysWhatItDoes() {
        compose.setContent { AIChatTheme(darkTheme = false) { ProviderEditSample(ProviderEditState(loaded = true, providerId = 1, name = "接口", baseUrl = "https://a.example/v1", savedKeyMasked = "sk-a••••••wxyz")) } }
        val show = app.getString(R.string.key_show)
        val hide = app.getString(R.string.key_hide)
        val bounds = compose.onNodeWithContentDescription(show).getBoundsInRoot()
        assertTrue(bounds.width >= 22.dp)
        compose.onNode(SemanticsMatcher("toggle") { it.config.getOrNull(SemanticsActions.OnClick) != null && it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(show) == true })
            .assertHasClickAction().also {
                val touch = it.getBoundsInRoot()
                assertTrue("${touch.width}x${touch.height}", touch.width >= 48.dp && touch.height >= 48.dp)
            }.performClick()
        compose.onNodeWithContentDescription(hide).assertExists()
    }

    @Test
    fun passwordToggleSaysWhatItDoes() {
        compose.setContent { AIChatTheme(darkTheme = false) { PasswordField("", {}, "输入密码") } }
        compose.onNodeWithContentDescription(app.getString(R.string.password_show)).assertExists().performClick()
        compose.onNodeWithContentDescription(app.getString(R.string.password_hide)).assertExists()
    }

    @Test
    fun stepperButtonsAre48dpAndNamed() {
        compose.setContent { AIChatTheme(darkTheme = false) { ContextBudgetRow(isomeria.aichat.data.db.Contact.DEFAULT_CONTEXT_TOKENS) {} } }
        listOf(R.string.contact_context_decrease, R.string.contact_context_increase).forEach {
            val bounds = compose.onNodeWithContentDescription(app.getString(it)).assertHasClickAction().assertIsEnabled().getBoundsInRoot()
            assertTrue("${bounds.width}x${bounds.height}", bounds.width >= 48.dp && bounds.height >= 48.dp)
        }
    }

    @Test
    fun stepperButtonIsDisabledAtTheLimit() {
        compose.setContent { AIChatTheme(darkTheme = false) { ContextBudgetRow(isomeria.aichat.data.db.Contact.MIN_CONTEXT_TOKENS) {} } }
        compose.onNodeWithContentDescription(app.getString(R.string.contact_context_decrease)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(app.getString(R.string.contact_context_increase)).assertIsEnabled()
    }

    // ---- V12 读屏线索 ----

    @Test
    fun configRowIsOneButtonWithTitleAndValue() {
        compose.setContent { AIChatTheme(darkTheme = false) { ConfigActionRow("思考模式", "可开关", onClick = {}) } }
        val node = compose.onNode(roleIs(Role.Button)).fetchSemanticsNode()
        val text = node.config.getOrNull(SemanticsProperties.Text)!!.joinToString { it.text }
        assertTrue(text, "思考模式" in text && "可开关" in text)
    }

    @Test
    fun disabledConfigRowIsAnnounced() {
        compose.setContent { AIChatTheme(darkTheme = false) { ConfigActionRow("思考模式", "可开关", enabled = false, onClick = {}) } }
        compose.onNode(roleIs(Role.Button)).assertIsNotEnabled()
    }

    @Test
    fun readOnlyRowReadsTitleAndValueTogether() {
        compose.setContent { AIChatTheme(darkTheme = false) { WeListRow("温度", value = "固定 0.7") } }
        val text = compose.onNodeWithText("温度").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.map { it.text }
        assertEquals(listOf("温度", "固定 0.7"), text)
    }

    @Test
    fun expandableRowAnnouncesItsState() {
        val open = mutableStateOf(false)
        compose.setContent { AIChatTheme(darkTheme = false) { WeListRow("待配置", onClick = { open.value = !open.value }, expanded = open.value) } }
        fun state() = compose.onNode(roleIs(Role.Button)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(app.getString(R.string.state_collapsed), state())
        compose.onNode(roleIs(Role.Button)).performClick()
        compose.waitForIdle()
        assertEquals(app.getString(R.string.state_expanded), state())
    }

    @Test
    fun switchSaysWhatItControls() {
        compose.setContent { AIChatTheme(darkTheme = false) { WeSwitch(true, {}, label = "自动记忆") } }
        compose.onNodeWithContentDescription("自动记忆").assertExists()
    }

    @Test
    fun sheetMarksTheCurrentOption() {
        compose.setContent { AIChatTheme(darkTheme = false) { WeActionSheet(options = listOf(SheetOption("甲", selected = true) {}, SheetOption("乙") {}), onDismiss = {}) } }
        compose.onNodeWithText("甲").assertIsSelected()
        assertFalse(compose.onNodeWithText("乙").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected) == true)
    }

    @Test
    fun segmentedMarksTheCurrentTab() {
        compose.setContent { AIChatTheme(darkTheme = false) { WeSegmented(listOf(1 to "今天", 2 to "全部"), 1, {}) } }
        compose.onNodeWithText("今天").assertIsSelected()
        compose.onNodeWithText("全部").assertIsNotSelected()
    }

    @Test
    fun dialogButtonsAreButtons() {
        compose.setContent { AIChatTheme(darkTheme = false) { WeDialog(title = "标题", message = "说明", onDismiss = {}, onConfirm = {}) { Text("内容") } } }
        assertTrue(compose.onAllNodes(roleIs(Role.Button)).fetchSemanticsNodes().size >= 2)
    }

    // ---- 已保存的 Key ----

    @Test
    fun fullKeyNeverReachesTheAccessibilityTree() {
        val secret = "sk-real-secret-value-12345678"
        compose.setContent { AIChatTheme(darkTheme = false) { ProviderEditSample(ProviderEditState(loaded = true, providerId = 1, name = "接口", baseUrl = "https://a.example/v1", savedKeyMasked = "sk-r••••••5678")) } }
        val tree = allText()
        assertFalse(secret in tree)
        // 手机上摘要被输入框盖住，读屏只读得到输入框的说明
        compose.onNodeWithContentDescription("sk-r••••••5678", substring = true).assertExists()
    }

    @Test
    fun typedKeyIsHiddenUntilTheUserAsksToSeeIt() {
        val secret = "sk-typed-secret-value-12345678"
        compose.setContent { AIChatTheme(darkTheme = false) { ProviderEditSample(ProviderEditState(loaded = true, providerId = 1, name = "接口", baseUrl = "https://a.example/v1", keyInput = secret)) } }
        assertFalse("打码时读屏拿不到明文", secret in allText())
        compose.onNodeWithContentDescription(app.getString(R.string.llm_api_key)).assertExists()
        compose.onAllNodesWithContentDescription(app.getString(R.string.key_show)).fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }

    // ---- R16 思考强度的说法 ----

    private fun model(thinking: ThinkingRule?) = ProviderModel(1, "m", "模型", legacy = false, userConfig = ModelRules.encode(ModelConfig(true, thinking)))

    @Test
    fun savedValueUsesFriendlyNameWhenNotInOptions() {
        compose.setContent { AIChatTheme(darkTheme = false) { ThinkingSelector(model(ThinkingRule(ThinkingMode.ALWAYS, listOf(NativeEffort("low", "低")))), "off", "思考强度") {} } }
        compose.onNodeWithText(app.getString(R.string.thinking_pending, app.getString(R.string.thinking_off))).assertExists()
        compose.onNodeWithText(app.getString(R.string.thinking_pending, "off")).assertDoesNotExist()
    }

    @Test
    fun detailPageNeverShowsRawKnownOption() {
        assertEquals(null, thinkingDisplayName(emptyList(), "off"))
        assertEquals(null, thinkingDisplayName(listOf(NativeEffort("off", "")), "off"))
        assertEquals("低", thinkingDisplayName(listOf(NativeEffort("low", "低")), "low"))
        assertEquals("retired", thinkingDisplayName(listOf(NativeEffort("low", "低")), "retired"))
    }

    @Test
    fun noOptionsMeansNoInvalidValueMessage() {
        compose.setContent { AIChatTheme(darkTheme = false) { ThinkingSelector(model(null), "off", "思考强度") {} } }
        compose.onNodeWithText(app.getString(R.string.thinking_pending, app.getString(R.string.thinking_off))).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.rule_unknown), substring = true).assertExists()
    }
}
