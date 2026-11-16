package isomeria.aichat.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0.0 真机：停在底部发语音，回复落在屏幕下面、也没有新消息浮标。
 * 原因是分页刷新时“最新一页已载入”短暂为 false，正好在滑动就被当成离开了底部。
 */
class AtBottomWhileScrollingTest {
    @Test fun refreshDuringScrollAtBottom_staysAtBottom() {
        assertTrue("刷新那一下最新一页暂时没载入，仍在底部", atBottomWhileScrolling(previous = true, index = 0, newestLoaded = false))
        assertTrue(atBottomWhileScrolling(previous = true, index = 2, newestLoaded = false))
    }

    @Test fun scrollingAwayOrBack_isJudgedAsBefore() {
        assertFalse("往上翻过 2 项就算离开底部", atBottomWhileScrolling(previous = true, index = 3, newestLoaded = true))
        assertFalse(atBottomWhileScrolling(previous = true, index = 3, newestLoaded = false))
        assertTrue("滑回底部并且最新一页已载入", atBottomWhileScrolling(previous = false, index = 0, newestLoaded = true))
    }

    @Test fun nearStartOfAnOlderWindow_isNotBottom() {
        // 跳到引用的原消息后，列表是历史中间的一段：第 0 项附近也不是最新的消息
        assertFalse(atBottomWhileScrolling(previous = false, index = 1, newestLoaded = false))
    }
}
