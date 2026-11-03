package isomeria.aichat.feature.memory

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import isomeria.aichat.R
import isomeria.aichat.data.db.Contact
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
import isomeria.aichat.data.memory.MemoryTaskState
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 记忆页：只说实际的原因，“配置模型”直达实际使用的模型，总结进行中不能重复点。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class MemoryStatusUiTest {
    @get:Rule val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private fun text(res: Int) = app.getString(res)
    private val provider = ProviderConfig(id = 7, kind = ProviderKind.LLM, provider = "openai", name = "接口", baseUrl = "https://a.example/v1", compatibility = "GENERIC", defaultModel = "")
    private val rules = ModelConfig(true, ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("low", "低"))), TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0f, 1f)))
    private val ready = ProviderModel(7, "ready", "可用模型", imageInputUserMark = false, legacy = false, userConfig = ModelRules.encode(rules))
    private val contact = Contact(id = 1, name = "小雨", avatarColor = 0, providerId = 7, model = "ready", memoryModel = "ready", memoryThinkingOption = "low", createdAt = 1, updatedAt = 1)
    private val base = MemoryListState(loaded = true, contact = contact, provider = provider, models = listOf(ready))

    private var providerOpened = -1L
    private var modelOpened: Pair<Long, String>? = null
    private var openedMemory: Long? = null
    private var clearClicks = 0
    private var summarizeClicks = 0

    private fun page(state: MemoryListState) = compose.setContent {
        AIChatTheme(darkTheme = false) {
            MemoryContent(state, {}, { openedMemory = it }, { summarizeClicks++ }, { clearClicks++ }, onOpenProvider = { providerOpened = it },
                onOpenModel = { id, model -> modelOpened = id to model })
        }
    }

    /** 五种原因的提示，页面上只能出现 [expected] 这一条。 */
    private fun assertOnlyIssue(expected: Int?) {
        listOf(R.string.memory_issue_model, R.string.config_issue_incomplete, R.string.memory_issue_temperature, R.string.config_issue_encoding).forEach { res ->
            val nodes = compose.onAllNodes(hasText(text(res))).fetchSemanticsNodes()
            assertEquals(app.getString(res), if (res == expected) 1 else 0, nodes.size)
        }
    }

    @Test
    fun healthyConfigShowsNoIssue() {
        page(base)
        assertOnlyIssue(null)
        compose.onNodeWithText(app.getString(R.string.thinking_pending, "low")).assertDoesNotExist()
    }

    @Test
    fun retiredEffortShowsOnlyThinkingReason() {
        page(base.copy(contact = contact.copy(memoryThinkingOption = "retired")))
        compose.onNodeWithText(app.getString(R.string.thinking_pending, "retired")).assertExists()
        assertOnlyIssue(null)
    }

    @Test
    fun missingModelShowsOnlyModelReasonAndNoInventedEfforts() {
        page(base.copy(contact = contact.copy(memoryModel = "gone")))
        assertOnlyIssue(R.string.memory_issue_model)
        compose.onNodeWithText(text(R.string.memory_thinking)).assertDoesNotExist()
    }

    @Test
    fun legacyProviderWithoutModelKeepsItsOptions() {
        page(base.copy(provider = provider.copy(compatibility = "LEGACY"), contact = contact.copy(memoryModel = "gone", memoryThinkingOption = "off")))
        assertOnlyIssue(null)
        compose.onNodeWithText(text(R.string.memory_thinking)).assertExists()
    }

    @Test
    fun incompleteModelShowsOnlyIncompleteReason() {
        val pending = ready.copy(userConfig = ModelRules.encode(rules.copy(thinking = null)))
        page(base.copy(models = listOf(pending)))
        assertOnlyIssue(R.string.config_issue_incomplete)
        compose.onNodeWithText(app.getString(R.string.thinking_pending, "low")).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.rule_unknown)).assertExists()
    }

    @Test
    fun temperatureProblemShowsOnlyTemperatureReason() {
        val narrow = ready.copy(userConfig = ModelRules.encode(rules.copy(temperature = TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0.5f, 1f)))))
        page(base.copy(models = listOf(narrow)))
        assertOnlyIssue(R.string.memory_issue_temperature)
    }

    @Test
    fun encodingProblemShowsOnlyEncodingReason() {
        page(base.copy(provider = provider.copy(compatibility = "ZHIPU")))
        assertOnlyIssue(R.string.config_issue_encoding)
    }

    @Test
    fun configureOpensTheModelActuallyUsed() {
        page(base)
        compose.onNodeWithText(text(R.string.model_configure)).performClick()
        assertEquals(7L to "ready", modelOpened)
        assertEquals(-1L, providerOpened)
    }

    @Test
    fun followingDefaultOpensTheDefaultModel() {
        val second = ready.copy(modelId = "second", displayName = "默认模型")
        page(base.copy(contact = contact.copy(memoryModel = null), provider = provider.copy(defaultModel = "second"), models = listOf(ready, second)))
        compose.onNodeWithText(text(R.string.model_configure)).performClick()
        assertEquals(7L to "second", modelOpened)
    }

    @Test
    fun followingEmptyDefaultOpensTheChatModel() {
        page(base.copy(contact = contact.copy(memoryModel = null)))
        compose.onNodeWithText(text(R.string.model_configure)).performClick()
        assertEquals(7L to "ready", modelOpened)
    }

    @Test
    fun missingModelGoesToProviderWithHonestLabel() {
        page(base.copy(contact = contact.copy(memoryModel = "gone")))
        compose.onNodeWithText(text(R.string.model_configure)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.memory_open_provider)).performClick()
        assertEquals(7L, providerOpened)
        assertEquals(null, modelOpened)
    }

    @Test
    fun backlogRowIsDisabledWhileSummarizing() {
        page(base.copy(backlog = 5, task = MemoryTaskState(manual = true)))
        val row = compose.onNode(hasText(text(R.string.memory_backlog_running)), useUnmergedTree = true)
        row.assertExists()
        compose.onNode(hasText(text(R.string.memory_backlog)) and hasClickAction()).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(0, summarizeClicks)
    }

    @Test
    fun rowShowsTheModelActuallyUsed() {
        page(base)
        compose.onNodeWithText("可用模型").assertExists()
        compose.onNodeWithText(text(R.string.memory_section_settings)).assertExists()
        compose.onNodeWithText(text(R.string.memory_section_empty)).performScrollTo().assertExists()
    }

    @Test
    fun followingDefaultSaysSoWithoutFallbackNote() {
        val second = ready.copy(modelId = "second", displayName = "默认模型")
        page(base.copy(contact = contact.copy(memoryModel = null), provider = provider.copy(defaultModel = "second"), models = listOf(ready, second)))
        compose.onNodeWithText(app.getString(R.string.memory_value_follow, "默认模型")).assertExists()
        compose.onNodeWithText(text(R.string.memory_follow_fallback)).assertDoesNotExist()
    }

    @Test
    fun fallbackNoteOnlyWhenItReallyFallsBack() {
        page(base.copy(contact = contact.copy(memoryModel = null)))
        compose.onNodeWithText(app.getString(R.string.memory_value_fallback, "可用模型")).assertExists()
        compose.onNodeWithText(text(R.string.memory_follow_fallback)).assertExists()
    }

    @Test
    fun noTaskGroupWithoutRecords() {
        page(base)
        compose.onNodeWithText(text(R.string.memory_section_tasks)).assertDoesNotExist()
    }

    @Test
    fun taskGroupAppearsWithRecords() {
        page(base.copy(task = MemoryTaskState(generating = true)))
        compose.onNodeWithText(text(R.string.memory_section_tasks)).assertExists()
        compose.onNodeWithText(text(R.string.memory_generating), useUnmergedTree = true).assertExists()
    }

    @Test
    fun modelSheetShowsIdSoSameNamesCanBeTold() {
        val twin = ready.copy(modelId = "twin")
        page(base.copy(models = listOf(ready, twin)))
        compose.onNodeWithText(text(R.string.memory_model)).performClick()
        compose.onNodeWithText("twin").assertExists()
        // 当前选中的 ready 在行上不显示 ID，在菜单里才有
        compose.onNodeWithText("ready").assertExists()
    }

    @Test
    fun backlogRowComesBeforeTheMemories() {
        page(base.copy(backlog = 5, rows = listOf(MemoryRow(1, "10月1日", "一条记忆", false))))
        val entry = compose.onNode(hasText(text(R.string.memory_backlog)) and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val memory = compose.onNodeWithText("一条记忆").fetchSemanticsNode().boundsInRoot
        assert(entry.top < memory.top) { "总结入口应在记忆列表之前" }
    }

    @Test
    fun backlogRowWorksWhenIdle() {
        page(base.copy(backlog = 5))
        compose.onNodeWithText(app.getString(R.string.memory_backlog_count, 5)).assertExists()
        compose.onNode(hasText(text(R.string.memory_backlog)) and hasClickAction()).performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, summarizeClicks)
    }

    @Test fun emptyMemoryIsCompactAndHonest() {
        page(base)
        compose.onNodeWithText(text(R.string.memory_empty_title)).assertExists()
        compose.onNodeWithText(text(R.string.memory_empty_on)).assertExists()
        compose.onNodeWithText(text(R.string.memory_section_list)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.memory_hint)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.memory_clear)).assertDoesNotExist()
    }
    @Test fun emptyMemoryOffDoesNotPromiseAutomaticSummary() {
        page(base.copy(autoMemory = false))
        compose.onNodeWithText(text(R.string.memory_empty_off)).assertExists()
        compose.onNodeWithText(text(R.string.memory_empty_on)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.memory_hint_off)).assertDoesNotExist()
    }
    @Test fun existingMemoryKeepsContentAndActions() {
        page(base.copy(rows = listOf(MemoryRow(1, "今天", "虚构记忆", false))))
        compose.onNodeWithText("虚构记忆").performClick()
        assertEquals(1L, openedMemory)
        compose.onNodeWithText(text(R.string.memory_hint)).assertExists()
        compose.onNodeWithText(text(R.string.memory_clear)).performScrollTo().performClick()
        assertEquals(1, clearClicks)
        compose.onNodeWithText(text(R.string.memory_empty_title)).assertDoesNotExist()
    }
}
