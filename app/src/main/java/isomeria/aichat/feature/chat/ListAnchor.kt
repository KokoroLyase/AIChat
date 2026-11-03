package isomeria.aichat.feature.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * 分页刷新后窗口可能整个换掉：第一条可见消息的下标从 190 变成 15。
 * LazyColumn 只在当前下标附近约 100 项内按 key 找它，找不到就留在旧下标，列表被截到末尾，看起来就是跳走。
 * 这里记住第一条可见项的 key 和偏移，数据变化后、布局之前按 key 恢复。滑动过程中不干预。
 */
@Composable
internal fun KeepFirstVisibleItem(state: LazyListState, keys: List<Any>, enabled: Boolean) {
    val anchor = remember { ListAnchor() }
    val currentKeys by rememberUpdatedState(keys)
    LaunchedEffect(state) {
        snapshotFlow { Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.layoutInfo.visibleItemsInfo.firstOrNull()?.key) }
            .collect { (index, offset, laidOut) ->
                // 布局可能先于组合用上新数据：两边的 key 对不上时，这是刷新造成的位移，不能记成锚点
                if (laidOut != null && currentKeys.getOrNull(index) == laidOut) {
                    anchor.key = laidOut
                    anchor.offset = offset
                }
            }
    }
    SideEffect {
        val key = anchor.key
        if (enabled && key != null && !state.isScrollInProgress) {
            val index = keys.indexOf(key)
            if (index >= 0 && index != state.firstVisibleItemIndex) state.requestScrollToItem(index, anchor.offset)
        }
    }
}

private class ListAnchor {
    var key: Any? = null
    var offset: Int = 0
}
