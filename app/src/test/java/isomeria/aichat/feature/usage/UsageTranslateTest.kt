package isomeria.aichat.feature.usage

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import isomeria.aichat.core.UsageStats
import isomeria.aichat.core.UsageTotal
import isomeria.aichat.data.db.UsageDaily
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 0.9.0 用量里的翻译：算进 token 和请求次数，并单独列出“其中翻译”；原来的记忆、主动聊天写法不变 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class UsageTranslateTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun translateTokens_countedInTotalsAndListedSeparately() {
        val rows = listOf(
            UsageDaily(day = 10, contactId = 1, purpose = UsagePurpose.CHAT, promptTokens = 1_000, completionTokens = 200, requests = 3),
            UsageDaily(day = 10, contactId = 1, purpose = UsagePurpose.TRANSLATE, promptTokens = 40, completionTokens = 12, requests = 2),
        )
        val total = UsageStats.total(rows)
        assertEquals(1_252L, total.totalTokens)
        assertEquals(5, total.requests)
        assertEquals(52L, total.translateTokens)
        assertEquals(0L, total.proactiveTokens)
        assertEquals(0L, total.memoryTokens)
    }

    private fun detailOf(total: UsageTotal): String {
        var text = ""
        compose.setContent { AIChatTheme(darkTheme = false) { text = usageDetail(total); Text(text) } }
        compose.waitForIdle()
        return text
    }

    @Test
    fun detail_allParts_inOrder() {
        val text = detailOf(UsageTotal(0, promptTokens = 58_320, completionTokens = 9_140, memoryTokens = 1_850, proactiveTokens = 300, translateTokens = 52))
        assertEquals("输入 58,320 · 输出 9,140 · 其中记忆 1,850、主动聊天 300、翻译 52", text)
        compose.onNodeWithText(text).assertExists()
    }

    @Test
    fun detail_oldCombinationsReadTheSame() {
        assertEquals("输入 1 · 输出 2 · 其中记忆 3、主动聊天 4", detailOf(UsageTotal(0, promptTokens = 1, completionTokens = 2, memoryTokens = 3, proactiveTokens = 4)))
    }

    @Test
    fun detail_memoryOnly() {
        assertEquals("输入 1 · 输出 2 · 其中记忆 3", detailOf(UsageTotal(0, promptTokens = 1, completionTokens = 2, memoryTokens = 3)))
    }

    @Test
    fun detail_none() {
        assertEquals("输入 1 · 输出 2", detailOf(UsageTotal(0, promptTokens = 1, completionTokens = 2)))
    }

    @Test
    fun detail_translateOnly() {
        assertEquals("输入 1 · 输出 2 · 其中翻译 5", detailOf(UsageTotal(0, promptTokens = 1, completionTokens = 2, translateTokens = 5)))
    }
}
