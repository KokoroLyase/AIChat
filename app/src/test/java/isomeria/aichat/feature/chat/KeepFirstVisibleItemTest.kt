package isomeria.aichat.feature.chat

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 窗口从 200 条换成 40 条：和聊天页一样用倒序列表和 key，第一条可见项不能变。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class KeepFirstVisibleItemTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var state: LazyListState

    private fun show(initial: List<String>, enabled: Boolean, padding: Dp = 0.dp, setKeys: ((List<String>) -> Unit) -> Unit) {
        compose.setContent {
            state = rememberLazyListState()
            var keys by androidx.compose.runtime.remember { mutableStateOf(initial) }
            setKeys { keys = it }
            KeepFirstVisibleItem(state, keys, enabled)
            LazyColumn(state = state, reverseLayout = true, contentPadding = PaddingValues(vertical = padding)) {
                items(keys, key = { it }) { Box(Modifier.fillMaxWidth().height(50.dp)) }
            }
        }
        compose.waitForIdle()
    }

    private fun window(from: Int, size: Int) = (from until from + size).map { "m$it" }

    @Test fun itemVisibleOnlyInPaddingCannotLeaveAStaleAnchor() {
        var change: (List<String>) -> Unit = {}
        show(window(0, 200), enabled = true, padding = 8.dp) { change = it }
        compose.runOnUiThread { runBlocking { state.scrollToItem(170, 60) } }
        compose.waitForIdle()
        compose.runOnUiThread { runBlocking { state.scrollToItem(171, 8) } }
        compose.waitForIdle()
        val index = state.firstVisibleItemIndex
        val offset = state.firstVisibleItemScrollOffset
        assertEquals(171, index)
        // The previous row is still visible in content padding, exactly like the real chat.
        assertEquals(170, state.layoutInfo.visibleItemsInfo.first().index)
        compose.runOnUiThread { change(window(155, 40)) }
        compose.waitForIdle()
        assertEquals(16, state.firstVisibleItemIndex)
        assertEquals(offset, state.firstVisibleItemScrollOffset)
    }

    @Test fun refreshToSmallerWindowKeepsTheFirstVisibleItem() {
        var change: (List<String>) -> Unit = {}
        show(window(0, 200), enabled = true) { change = it }
        runBlocking { compose.runOnIdle { } }
        compose.runOnUiThread { runBlocking { state.scrollToItem(170, 30) } }
        compose.waitForIdle()
        assertEquals(170, state.firstVisibleItemIndex)

        // 刷新后只剩 40 条，m170 排到第 15 位：离旧下标太远，靠 LazyColumn 自己的 key 查找找不到
        compose.runOnUiThread { change(window(155, 40)) }
        compose.waitForIdle()
        assertEquals(15, state.firstVisibleItemIndex)
        assertEquals(30, state.firstVisibleItemScrollOffset)
    }

    @Test fun withoutTheAnchorTheSameRefreshLosesThePosition() {
        var change: (List<String>) -> Unit = {}
        show(window(0, 200), enabled = false) { change = it }
        compose.runOnUiThread { runBlocking { state.scrollToItem(170, 30) } }
        compose.waitForIdle()
        compose.runOnUiThread { change(window(155, 40)) }
        compose.waitForIdle()
        assertNotEquals(15, state.firstVisibleItemIndex)
    }

    @Test fun missingKeyLeavesTheListAlone() {
        var change: (List<String>) -> Unit = {}
        show(window(0, 50), enabled = true) { change = it }
        compose.runOnUiThread { runBlocking { state.scrollToItem(20) } }
        compose.waitForIdle()
        compose.runOnUiThread { change(window(100, 50)) }
        compose.waitForIdle()
        assertEquals(20, state.firstVisibleItemIndex)
    }
}
