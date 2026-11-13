package isomeria.aichat.ui.components

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.9.0 真机反馈后修正的消息长按菜单：
 * - 菜单窗口不抢焦点（键盘开着时长按消息，键盘不再被收起）；返回键、点菜单外面都只关菜单；
 * - 左右离屏幕边缘至少 12dp；
 * - 每格等宽，每行最多 5 项且各行均匀（11 项 4 + 4 + 3），不再出现最后一行只剩一个。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class BubbleMenuTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 和截图里那条语音消息一样的 11 项 */
    private val labels = listOf("复制", "收藏", "翻译", "引用", "删除", "多选", "听筒播放", "后台播放", "取消转文字", "连续朗读", "重新生成")
    private val picked = mutableListOf<String>()
    private var open by mutableStateOf(true)

    private fun show(labels: List<String> = this.labels, anchorX: Dp = 0.dp, alignEnd: Boolean = false) {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart) {
                    Box(Modifier.offset(x = anchorX, y = 400.dp).size(40.dp, 30.dp)) {
                        if (open) WeBubbleMenu(labels.map { label -> MenuItem(label, Icons.Outlined.ContentCopy) { picked += label } }) { open = false }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun elevenItems_equalCells_rowsOfFourFourThree() {
        show()
        val bounds = labels.map { compose.onNodeWithText(it).getBoundsInRoot() }
        val widths = bounds.map { it.right - it.left }.distinct()
        assertEquals("每格一样宽：$widths", 1, widths.size)
        val rows = bounds.groupBy { it.top }.toSortedMap().values.map { row -> row.size }
        assertEquals(listOf(4, 4, 3), rows)
        // 各行左对齐，上下对得齐
        val lefts = bounds.groupBy { it.top }.values.map { row -> row.map { it.left }.sorted() }
        assertEquals(lefts.first().take(3), lefts.last())
    }

    @Test
    fun staysAwayFromScreenEdges() {
        show(anchorX = 0.dp)
        val left = labels.minOf { compose.onNodeWithText(it).getBoundsInRoot().left }
        assertTrue("左边离屏幕边缘 $left", left >= 12.dp)
    }

    @Test
    fun staysAwayFromRightEdge() {
        show(anchorX = 0.dp, alignEnd = true)
        val right = labels.maxOf { compose.onNodeWithText(it).getBoundsInRoot().right }
        assertTrue("右边到 $right", right <= 400.dp - 12.dp)
    }

    @Test
    fun menuWindowNeverTakesFocus_soTheKeyboardStays() {
        show()
        val popup = windowParams().last()
        assertTrue("菜单窗口要带 FLAG_NOT_FOCUSABLE，否则会抢走输入框的焦点、收起键盘", popup.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
    }

    @Test
    fun backClosesTheMenu() {
        show()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("复制").fetchSemanticsNodes().size)
        assertTrue(picked.isEmpty())
    }

    @Test
    fun tapOutsideClosesWithoutPicking_tapItemPicks() {
        show()
        compose.onAllNodes(isRoot())[1].performTouchInput { click(Offset(5f, 5f)) }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("复制").fetchSemanticsNodes().size)
        assertTrue(picked.isEmpty())

        open = true
        compose.waitForIdle()
        compose.onNodeWithText("引用").performClick()
        compose.waitForIdle()
        assertEquals(listOf("引用"), picked)
        assertEquals(0, compose.onAllNodesWithText("复制").fetchSemanticsNodes().size)
    }

    @Test
    fun rows_balanced() {
        val cases = mapOf(
            11 to listOf(4, 4, 3), 10 to listOf(5, 5), 9 to listOf(5, 4), 8 to listOf(4, 4), 7 to listOf(4, 3),
            6 to listOf(3, 3), 5 to listOf(5), 3 to listOf(3), 1 to listOf(1),
        )
        cases.forEach { (count, rows) -> assertEquals("$count 项", rows, bubbleMenuRows(count, cellWidth = 60, maxWidth = 360)) }
        // 字号大、一行只放得下 3 格时：10 项 → 3 + 3 + 2 + 2，11 项 → 3 + 3 + 3 + 2
        assertEquals(listOf(3, 3, 2, 2), bubbleMenuRows(10, cellWidth = 110, maxWidth = 360))
        assertEquals(listOf(3, 3, 3, 2), bubbleMenuRows(11, cellWidth = 110, maxWidth = 360))
        // 一格都放不下也至少一列
        assertEquals(listOf(1, 1), bubbleMenuRows(2, cellWidth = 500, maxWidth = 360))
        assertEquals(emptyList<Int>(), bubbleMenuRows(0, cellWidth = 60, maxWidth = 360))
    }

    /** 截图里发现：特大字号时语音消息的 10 项排成了 3 + 3 + 3 + 1 */
    @Test
    fun largeFont_tenItems_noLoneItemOnLastRow() {
        val ten = labels.dropLast(1)
        compose.setContent {
            AIChatTheme(darkTheme = false, fontScale = 1.6f) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(y = 400.dp).size(40.dp, 30.dp)) {
                        WeBubbleMenu(ten.map { label -> MenuItem(label, Icons.Outlined.ContentCopy) {} }) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        val rows = ten.map { compose.onNodeWithText(it).getBoundsInRoot() }.groupBy { it.top }.toSortedMap().values.map { it.size }
        assertEquals(10, rows.sum())
        assertTrue("各行：$rows", rows.last() >= 2 && rows.max() - rows.min() <= 1)
    }

    @Test
    fun position_marginsAboveBelowAndClamped() {
        val visible = IntRect(0, 100, 1000, 2000)
        val margins = BubbleMenuMargins(side = 36, vertical = 24, topBar = 150, gap = 18)
        val menu = IntSize(600, 300)
        fun at(anchor: IntRect) = bubbleMenuPosition(anchor, menu, visible, margins)

        // 贴左边的消息：菜单离左边缘 side；贴右边：离右边缘 side
        assertEquals(36, at(IntRect(0, 1000, 100, 1100)).x)
        assertEquals(1000 - 36 - 600, at(IntRect(900, 1000, 1000, 1100)).x)
        // 上面放得下就放在消息上方
        assertEquals(IntOffset(200, 1000 - 18 - 300), at(IntRect(400, 1000, 600, 1100)))
        // 上面会压住标题栏就放到消息下方
        assertEquals(400 + 18, at(IntRect(400, 300, 600, 400)).y)
        // 很长的消息上下都放不下：贴着标题栏下面，不超出可见区域
        assertEquals(100 + 150 + 24, at(IntRect(400, 300, 600, 1990)).y)
        // 可见区域（键盘以上）底部附近：放上方，不会压到键盘
        val low = at(IntRect(400, 1900, 600, 1990))
        assertTrue(low.y + menu.height <= visible.bottom - margins.vertical)
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowParams(): List<WindowManager.LayoutParams> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val field = global.javaClass.getDeclaredField("mParams").apply { isAccessible = true }
        return (field.get(global) as List<WindowManager.LayoutParams>).toList()
    }
}
