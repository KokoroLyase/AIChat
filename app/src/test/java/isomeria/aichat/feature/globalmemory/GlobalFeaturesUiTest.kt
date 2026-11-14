package isomeria.aichat.feature.globalmemory

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import isomeria.aichat.R
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.db.GlobalMemoryCategory
import isomeria.aichat.data.db.GlobalMemoryItem
import isomeria.aichat.data.db.GlobalMemoryTopic
import isomeria.aichat.data.repo.GlobalMemoryTree
import isomeria.aichat.feature.contactedit.PromptRow
import isomeria.aichat.feature.contactedit.PromptUsage
import isomeria.aichat.feature.me.MeContent
import isomeria.aichat.feature.replyprefs.ReplyPreferencesContent
import isomeria.aichat.feature.replyprefs.ReplyPreferencesState
import isomeria.aichat.feature.settings.SettingsSection
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 0.5.0「我」页的两个新入口、全局记忆页、回复偏好页，以及编辑联系人的系统提示词占用提示。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class GlobalFeaturesUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)

    @Test
    fun mePage_twoNewEntriesBetweenProfileAndSettings_openTheirPages() {
        val calls = mutableListOf<String>()
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                MeContent("Isomeria", null, { calls += "profile" }, { calls += it.name }, { calls += "global" }, { calls += "reply" })
            }
        }
        val global = compose.onNodeWithText("全局记忆").getUnclippedBoundsInRoot().top
        val reply = compose.onNodeWithText("回复偏好").getUnclippedBoundsInRoot().top
        val models = compose.onNodeWithText(text(SettingsSection.MODELS.title)).getUnclippedBoundsInRoot().top
        val name = compose.onNodeWithText("Isomeria").getUnclippedBoundsInRoot().top
        assertTrue(name < global && global < reply && reply < models)
        compose.onNodeWithText("全局记忆").performClick()
        compose.onNodeWithText("回复偏好").performClick()
        assertEquals(listOf("global", "reply"), calls)
        // 条目本身不铺在「我」页上
        compose.onNodeWithText(text(R.string.global_memory_category_add)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.reply_pref_wait)).assertDoesNotExist()
    }

    private val tree = GlobalMemoryTree(
        categories = listOf(
            GlobalMemoryCategory(1, "个人信息", 0, 1, 1),
            GlobalMemoryCategory(2, "空类别", 1, 2, 2),
        ),
        topics = listOf(GlobalMemoryTopic(10, 1, "基本情况", 0, 1, 1), GlobalMemoryTopic(11, 1, "饮食", 1, 1, 1)),
        items = listOf(
            GlobalMemoryItem(100, 10, "住在杭州", true, 0, 1, 1),
            GlobalMemoryItem(101, 10, "生日 3 月 5 日", false, 1, 1, 1),
            GlobalMemoryItem(102, 11, "不吃香菜", true, 0, 1, 1),
        ),
    )
    private val state = GlobalMemoryState(true, tree, 2, 8, 60, 3, 4, listOf("DeepSeek", "OpenRouter"))

    @Test
    fun globalMemory_showsWhoReceivesIt_usage_andCascadeConfirmBeforeDeleting() {
        val deleted = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                GlobalMemoryContent(state, GlobalMemoryActions(deleteCategory = { deleted += it }), {}, { opened += it })
            }
        }
        compose.onNodeWithText(text(R.string.global_memory_notice)).assertExists()
        compose.onNodeWithText("会发给：DeepSeek、OpenRouter").assertExists()
        compose.onNodeWithText("已启用 2 条 · 8 字 · 约 60 token").assertExists()
        compose.onNodeWithText("3/4").assertExists()
        compose.onNodeWithText("2 个主题 · 3 条").assertExists()
        compose.onNodeWithText("个人信息").performClick()
        assertEquals(listOf(1L), opened)

        // 长按 → 删除类别 → 先确认级联删除，写明会一起删掉多少
        compose.onNodeWithText("个人信息").performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.global_memory_delete_category)).performClick()
        compose.onNodeWithText("删除类别「个人信息」？其中的 2 个主题和 3 条记忆会一起删除，无法撤销。").assertExists()
        assertTrue(deleted.isEmpty())
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.waitForIdle() // 对话框先淡出再执行（0.9.1 审计）
        assertEquals(listOf(1L), deleted)
    }

    @Test
    fun globalMemory_noContactUsingIt_saysNothingIsSent() {
        compose.setContent {
            AIChatTheme(darkTheme = true) { GlobalMemoryContent(state.copy(contactsUsing = 0, providerNames = emptyList()), GlobalMemoryActions(), {}, {}) }
        }
        compose.onNodeWithText(text(R.string.global_memory_providers_none)).assertExists()
    }

    @Test
    fun topicPage_switchTurnsItemOffWithoutOpeningIt_andDeleteAsksFirst() {
        val enabled = mutableListOf<Pair<Long, Boolean>>()
        val deleted = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                GlobalMemoryTopicContent(
                    10, state,
                    GlobalMemoryActions(setItemEnabled = { id, on -> enabled += id to on }, deleteItem = { deleted += it }),
                    {}, { _, item -> opened += item },
                )
            }
        }
        compose.onNodeWithText(text(R.string.global_memory_item_disabled)).assertExists()
        compose.onAllNodesWithText("住在杭州")[0].performClick()
        assertEquals(listOf(100L), opened)
        compose.onAllNodesWithText("住在杭州")[0].performTouchInput { longClick() }
        compose.onNodeWithText(text(R.string.global_memory_item_delete)).performClick()
        compose.onNodeWithText(text(R.string.global_memory_item_delete_confirm)).assertExists()
        compose.onNodeWithText(text(R.string.action_delete)).performClick()
        compose.waitForIdle() // 对话框先淡出再执行（0.9.1 审计）
        assertEquals(listOf(100L), deleted)
        compose.onNodeWithText(text(R.string.global_memory_item_add)).performClick()
        assertEquals(listOf(100L, -1L), opened)
    }

    @Test
    fun replyPreferences_minAndMaxStayOrdered_andResetRestoresDefaults() {
        compose.setContent {
            var prefs by remember { mutableStateOf(ReplyPreferences(minCount = 3, maxCount = 3, minIntervalSeconds = 10, maxIntervalSeconds = 10)) }
            AIChatTheme(darkTheme = false) {
                ReplyPreferencesContent(ReplyPreferencesState(true, prefs, 2, 2), {}, { prefs = it(prefs).normalized() }, {})
            }
        }
        // 最少调大，最多跟着变大；最长调小，最短跟着变小
        compose.onNodeWithContentDescription(text(R.string.reply_pref_increase, text(R.string.reply_pref_min_count))).performScrollTo().performClick()
        compose.onNodeWithContentDescription("${text(R.string.reply_pref_max_count)} 4 条").assertExists()
        compose.onNodeWithContentDescription(text(R.string.reply_pref_decrease, text(R.string.reply_pref_max_interval))).performScrollTo().performClick()
        compose.onNodeWithContentDescription("${text(R.string.reply_pref_min_interval)} 9 秒").assertExists()
        compose.onNodeWithText(text(R.string.reply_pref_reset)).performScrollTo().performClick()
        compose.onNodeWithContentDescription("${text(R.string.reply_pref_wait)} 1 秒").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("${text(R.string.reply_pref_max_count)} 3 条").assertExists()
    }

    @Test
    fun promptRow_showsUsage_andWarnsWhenOverHalfTheBudget() {
        var usage by mutableStateOf(PromptUsage(1_200, 4_000))
        compose.setContent { AIChatTheme(darkTheme = false) { PromptRow("人设", usage) {} } }
        compose.onNodeWithText(text(R.string.contact_prompt_usage, 1_200, 30)).assertExists()
        usage = PromptUsage(2_100, 4_000)
        compose.onNodeWithText(text(R.string.contact_prompt_usage_warning, 2_100)).assertExists()
        compose.onNodeWithText(text(R.string.contact_prompt_usage, 2_100, 52)).assertDoesNotExist()
        // 刚好一半不提醒
        assertTrue(!PromptUsage(2_000, 4_000).overHalf && PromptUsage(2_001, 4_000).overHalf)
    }
}
