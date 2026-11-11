package isomeria.aichat.feature.proactive

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import isomeria.aichat.R
import isomeria.aichat.core.ProactiveBlock
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.UsageStats
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.UsageDaily
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.feature.contactedit.ProactiveRows
import isomeria.aichat.feature.me.MeContent
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

/** 0.6.0 主动聊天的界面：「我」页入口、全局设置和状态页、联系人编辑页的开关和频率、用量里的区分。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ProactiveUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private fun text(id: Int, vararg args: Any) = app.getString(id, *args)

    @Test
    fun mePage_proactiveEntrySitsWithGlobalMemoryAndReplyPreferences_andOpensItsPage() {
        var opened = 0
        compose.setContent {
            AIChatTheme(darkTheme = false) { MeContent("Isomeria", null, {}, {}, {}, {}, { opened++ }) }
        }
        val reply = compose.onNodeWithText("回复偏好").getUnclippedBoundsInRoot().top
        val proactive = compose.onNodeWithText("主动聊天").getUnclippedBoundsInRoot().top
        val models = compose.onNodeWithText(text(SettingsSection.MODELS.title)).getUnclippedBoundsInRoot().top
        assertTrue(reply < proactive && proactive < models)
        compose.onNodeWithText("主动聊天").performClick()
        assertEquals(1, opened)
        // 设置项不铺在「我」页上
        compose.onNodeWithText(text(R.string.proactive_limit)).assertDoesNotExist()
    }

    private val contacts = listOf(
        ProactiveContactStatus(1, "小雨", ProactiveFrequency.MEDIUM, null, "今天 12:05", "明天 07:00"),
        ProactiveContactStatus(2, "阿哲", ProactiveFrequency.HIGH, ProactiveBlock.PAUSED, "昨天 21:40", lastResult = isomeria.aichat.core.ProactiveResult.TIMED_OUT),
        ProactiveContactStatus(3, "小满", ProactiveFrequency.LOW, ProactiveBlock.NO_KEY),
    )

    @Test
    fun page_showsUsedTodayLastRunTaskAndEachContactsPauseReason() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ProactiveContent(ProactiveScreenState(true, ProactiveSettings(), 3, "15:30", true, true, contacts), {}, {}, { opened += it }, {})
            }
        }
        compose.onNodeWithContentDescription("今日已用 3 / 20 次").assertExists()
        compose.onNodeWithContentDescription("最近检查 15:30").assertExists()
        compose.onNodeWithContentDescription("后台任务 已注册").assertExists()
        compose.onNodeWithText(text(R.string.proactive_notify_off)).assertDoesNotExist()
        compose.onNodeWithText("小雨").performScrollTo()
        compose.onNodeWithText("小雨").assertExists()
        compose.onNodeWithText("阿哲").performScrollTo().performClick()
        assertEquals(listOf(2L), opened)
        // 读屏读到的就是状态小字
        val root = compose.onNodeWithText("阿哲")
        root.assertExists()
        compose.onNodeWithText("频繁 · 已暂停：连续 2 次没有回复，你发消息后恢复\n上次主动请求：昨天 21:40（模型 4 分钟内没有答完，已放弃）").assertExists()
        compose.onNodeWithText("适中 · 正常\n上次主动请求：今天 12:05\n约好的提醒：明天 07:00").assertExists()
        compose.onNodeWithText("偶尔 · 无法运行：没有可用的 API Key").assertExists()
    }

    @Test
    fun page_neverRun_notRegistered_notificationsOff_andNoContacts() {
        var notificationSettings = 0
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ProactiveContent(ProactiveScreenState(loaded = true, canNotify = false), {}, {}, {}, { notificationSettings++ })
            }
        }
        compose.onNodeWithContentDescription("今日已用 0 / 20 次").assertExists()
        compose.onNodeWithContentDescription("最近检查 还没有运行过").assertExists()
        compose.onNodeWithContentDescription("后台任务 未注册").assertExists()
        compose.onNodeWithText(text(R.string.proactive_notify_off)).performClick()
        assertEquals(1, notificationSettings)
        compose.onNodeWithText(text(R.string.proactive_contacts_empty)).performScrollTo().assertExists()
    }

    @Test
    fun settings_quietHoursWrapAroundMidnight_limitStaysInRange_andResetRestoresDefaults() {
        var saved = ProactiveSettings(quietStartMinutes = 23 * 60 + 30, quietEndMinutes = 0, dailyLimit = 1)
        compose.setContent {
            var settings by remember { mutableStateOf(saved) }
            AIChatTheme(darkTheme = false) {
                ProactiveContent(ProactiveScreenState(loaded = true, settings = settings), {}, { change -> settings = change(settings).normalized(); saved = settings }, {}, {})
            }
        }
        val start = text(R.string.proactive_quiet_start)
        val end = text(R.string.proactive_quiet_end)
        val limit = text(R.string.proactive_limit)
        // 23:30 再加半小时绕到 00:00；00:00 再减半小时绕到 23:30
        compose.onNodeWithContentDescription(text(R.string.reply_pref_increase, start)).performScrollTo().performClick()
        assertEquals(0, saved.quietStartMinutes)
        compose.onNodeWithContentDescription(text(R.string.reply_pref_decrease, end)).performScrollTo().performClick()
        assertEquals(23 * 60 + 30, saved.quietEndMinutes)
        compose.onNodeWithContentDescription("$start 00:00").assertExists()
        compose.onNodeWithContentDescription("$end 23:30").assertExists()
        // 上限最少 1 次：减号不可用，点了也不变
        compose.onNodeWithContentDescription(text(R.string.reply_pref_decrease, limit)).performScrollTo().performClick()
        assertEquals(1, saved.dailyLimit)
        compose.onNodeWithContentDescription(text(R.string.reply_pref_increase, limit)).performClick()
        assertEquals(2, saved.dailyLimit)
        // 关掉免打扰时段：开始、结束两行收起
        compose.onNodeWithContentDescription(text(R.string.proactive_quiet)).performScrollTo().performClick()
        assertEquals(false, saved.quietEnabled)
        compose.onNodeWithContentDescription("$start 00:00").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.reply_pref_reset)).performScrollTo().performClick()
        assertEquals(ProactiveSettings(), saved)
    }

    @Test
    fun contactRows_offByDefaultHidesFrequency_onShowsThreeChoices() {
        val toggles = mutableListOf<Boolean>()
        val picked = mutableListOf<ProactiveFrequency>()
        compose.setContent {
            var enabled by remember { mutableStateOf(false) }
            var frequency by remember { mutableStateOf(ProactiveFrequency.MEDIUM) }
            AIChatTheme(darkTheme = false) {
                Column {
                    ProactiveRows(enabled, frequency, { enabled = it; toggles += it }, { frequency = it; picked += it })
                }
            }
        }
        compose.onNodeWithText(text(R.string.contact_proactive_frequency)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.contact_proactive_hint)).assertExists()
        compose.onNodeWithContentDescription(text(R.string.contact_proactive)).performClick()
        compose.onNodeWithText(text(R.string.contact_proactive_frequency)).assertExists()
        compose.onNodeWithText("频繁").performClick()
        compose.onNodeWithText("偶尔").performClick()
        assertEquals(listOf(true), toggles)
        assertEquals(listOf(ProactiveFrequency.HIGH, ProactiveFrequency.LOW), picked)
        compose.onNodeWithContentDescription(text(R.string.contact_proactive)).performClick()
        compose.onNodeWithText(text(R.string.contact_proactive_frequency)).assertDoesNotExist()
    }

    @Test
    fun usage_countsProactiveTokensSeparately_insideTheSameTotals() {
        val rows = listOf(
            UsageDaily(100, 1, UsagePurpose.CHAT, 100, 10, 3),
            UsageDaily(100, 1, UsagePurpose.MEMORY, 50, 5, 1),
            UsageDaily(100, 1, UsagePurpose.PROACTIVE, 70, 7, 2),
            UsageDaily(101, 2, UsagePurpose.PROACTIVE, 30, 3, 1),
        )
        val total = UsageStats.total(rows)
        assertEquals(275L, total.totalTokens)
        assertEquals(55L, total.memoryTokens)
        assertEquals(110L, total.proactiveTokens)
        assertEquals(7, total.requests)
        assertEquals(listOf(33L, 77L), UsageStats.byDay(rows).map { it.proactiveTokens })
        assertEquals(listOf(77L, 33L), UsageStats.byContact(rows).map { it.proactiveTokens })
    }
}
