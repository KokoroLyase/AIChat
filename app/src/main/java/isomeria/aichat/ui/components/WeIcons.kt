package isomeria.aichat.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自绘细线图标：返回箭头、右箭头、「···」、搜索、加号。
 * 其余图标先用 Material Icons 占位，1.0.0 前统一换成自绘线性图标；不使用微信的图标素材。
 */
object WeIcons {
    val Back: ImageVector by lazy {
        lineIcon("Back") {
            moveTo(19.5f, 12f)
            lineTo(4.5f, 12f)
            moveTo(10.5f, 5.5f)
            lineTo(4f, 12f)
            lineTo(10.5f, 18.5f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        lineIcon("ChevronRight", strokeWidth = 1.4f) {
            moveTo(9f, 5.5f)
            lineTo(15.5f, 12f)
            lineTo(9f, 18.5f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        lineIcon("ChevronDown", strokeWidth = 1.4f) {
            moveTo(6f, 9f)
            lineTo(12f, 15f)
            lineTo(18f, 9f)
        }
    }

    val ChevronUp: ImageVector by lazy {
        lineIcon("ChevronUp", strokeWidth = 1.4f) {
            moveTo(6f, 15f)
            lineTo(12f, 9f)
            lineTo(18f, 15f)
        }
    }

    val More: ImageVector by lazy {
        ImageVector.Builder("More", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                circle(5f, 12f, 1.7f)
                circle(12f, 12f, 1.7f)
                circle(19f, 12f, 1.7f)
            }
        }.build()
    }

    val Search: ImageVector by lazy {
        lineIcon("Search") {
            circle(10.8f, 10.8f, 6.6f)
            moveTo(15.6f, 15.6f)
            lineTo(20.5f, 20.5f)
        }
    }

    val AddCircle: ImageVector by lazy {
        lineIcon("AddCircle") {
            circle(12f, 12f, 9f)
            moveTo(12f, 7.5f)
            lineTo(12f, 16.5f)
            moveTo(7.5f, 12f)
            lineTo(16.5f, 12f)
        }
    }

    /** 输入栏左侧的「切换到语音」：圆圈里一个点和两道声波 */
    val Voice: ImageVector by lazy {
        ImageVector.Builder("Voice", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                circle(12f, 12f, 9.5f)
                // 小声波
                moveTo(11.2f, 9.6f)
                arcTo(3.4f, 3.4f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 11.2f, y1 = 14.4f)
                // 大声波
                moveTo(13.4f, 7.2f)
                arcTo(6.6f, 6.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 13.4f, y1 = 16.8f)
            }
            path(fill = SolidColor(Color.Black)) {
                circle(8.6f, 12f, 1.1f)
            }
        }.build()
    }

    /** 输入栏的表情按钮：圆圈笑脸 */
    val Emoji: ImageVector by lazy {
        ImageVector.Builder("Emoji", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                circle(12f, 12f, 9.5f)
                moveTo(8f, 13.6f)
                arcTo(4.6f, 4.6f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 16f, y1 = 13.6f)
            }
            path(fill = SolidColor(Color.Black)) {
                circle(9f, 9.6f, 1.1f)
                circle(15f, 9.6f, 1.1f)
            }
        }.build()
    }

    /** 输入栏的「+」：圆圈加号，比顶栏的略粗 */
    val PlusCircle: ImageVector by lazy {
        lineIcon("PlusCircle", strokeWidth = 1.5f) {
            circle(12f, 12f, 9.5f)
            moveTo(12f, 7.6f)
            lineTo(12f, 16.4f)
            moveTo(7.6f, 12f)
            lineTo(16.4f, 12f)
        }
    }

    val Close: ImageVector by lazy {
        lineIcon("Close") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    private fun lineIcon(name: String, strokeWidth: Float = 1.6f, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + r, y1 = cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - r, y1 = cy)
        close()
    }
}
