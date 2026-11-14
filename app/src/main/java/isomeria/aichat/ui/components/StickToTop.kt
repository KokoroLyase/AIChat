package isomeria.aichat.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * 列表停在最顶上时，有一项新排到第一位（[firstKey] 变了，比如收到新消息、置顶），列表仍停在最顶上，能看到它。
 *
 * LazyColumn 默认按原来第一项的 key 保持位置：新排到第一位的那项会在屏幕上方，要往下拉才看得到（0.9.1 审计发现）。
 * 用户往下翻着看时不动，保持原来的位置。
 */
@Composable
fun StickToTop(state: LazyListState, firstKey: Any?) {
    val last = remember { arrayOf(firstKey) }
    // 在这一帧排版之前执行：这时列表的位置还是上一帧的，看得出改动前是不是停在最顶上
    SideEffect {
        if (firstKey != last[0]) {
            last[0] = firstKey
            if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) state.requestScrollToItem(0)
        }
    }
}
