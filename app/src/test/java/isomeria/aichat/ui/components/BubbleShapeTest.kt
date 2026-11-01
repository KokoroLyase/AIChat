package isomeria.aichat.ui.components

import android.app.Application
import android.graphics.Region
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 气泡和尾巴必须是一个整体：接缝处不能有空洞（发出的气泡曾出现一条浅色细缝）。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class BubbleShapeTest {
    private val width = 200
    private val height = 100

    /** 密度取 1，dp 和像素相同：尾巴宽 5、高 9，中心在 y = 20 */
    private fun region(tailOnRight: Boolean): Region {
        val outline = BubbleShape(tailOnRight, 4.dp, 5.dp, 9.dp, 20.dp)
            .createOutline(Size(width.toFloat(), height.toFloat()), LayoutDirection.Ltr, Density(1f))
        val path = (outline as Outline.Generic).path.asAndroidPath()
        return Region().apply { setPath(path, Region(0, 0, width, height)) }
    }

    @Test
    fun sentBubbleHasNoGapBetweenBodyAndTail() {
        val r = region(tailOnRight = true)
        // 主体右边缘在 x = 195，接缝像素 194 必须填充
        for (y in 17..23) assertTrue("seam at y=$y", r.contains(194, y))
        assertTrue(r.contains(100, 50))
        assertTrue("tail", r.contains(197, 20))
        assertFalse("outside tail", r.contains(197, 5))
    }

    @Test
    fun receivedBubbleHasNoGapBetweenBodyAndTail() {
        val r = region(tailOnRight = false)
        // 主体左边缘在 x = 5，接缝像素 5 必须填充
        for (y in 17..23) assertTrue("seam at y=$y", r.contains(5, y))
        assertTrue(r.contains(100, 50))
        assertTrue("tail", r.contains(2, 20))
        assertFalse("outside tail", r.contains(2, 5))
    }
}
