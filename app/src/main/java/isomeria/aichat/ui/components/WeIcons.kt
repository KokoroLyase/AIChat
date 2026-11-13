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

    /** 电话听筒（0.8.0 语音通话）：左上是听筒、右下是话筒，中间是握把 */
    val Phone: ImageVector by lazy {
        lineIcon("Phone") {
            moveTo(5.2f, 4f)
            lineTo(8.4f, 4f)
            lineTo(10f, 8.1f)
            lineTo(8f, 9.7f)
            quadTo(9.6f, 13.6f, 14.3f, 16f)
            lineTo(15.9f, 14f)
            lineTo(20f, 15.6f)
            lineTo(20f, 18.8f)
            quadTo(20f, 20f, 18.8f, 20f)
            quadTo(11f, 19.6f, 7.3f, 16.7f)
            quadTo(4.4f, 13f, 4f, 5.2f)
            quadTo(4f, 4f, 5.2f, 4f)
            close()
        }
    }

    /** 麦克风（0.8.0 通话的静音按钮） */
    val Mic: ImageVector by lazy { lineIcon("Mic") { mic() } }

    /** 麦克风划掉（静音中） */
    val MicOff: ImageVector by lazy {
        lineIcon("MicOff") {
            mic()
            moveTo(4.5f, 4.5f)
            lineTo(19.5f, 19.5f)
        }
    }

    /** 扬声器（免提） */
    val Speaker: ImageVector by lazy {
        lineIcon("Speaker") {
            moveTo(4f, 9.2f)
            lineTo(7.8f, 9.2f)
            lineTo(12.4f, 5f)
            lineTo(12.4f, 19f)
            lineTo(7.8f, 14.8f)
            lineTo(4f, 14.8f)
            close()
            moveTo(15.6f, 9.3f)
            arcTo(3.6f, 3.6f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15.6f, y1 = 14.7f)
            moveTo(17.8f, 6.6f)
            arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17.8f, y1 = 17.4f)
        }
    }

    /** 挂断：横放的听筒 */
    val HangUp: ImageVector by lazy {
        lineIcon("HangUp") {
            moveTo(4.2f, 15.6f)
            lineTo(3.4f, 12.9f)
            quadTo(12f, 6.4f, 20.6f, 12.9f)
            lineTo(19.8f, 15.6f)
            lineTo(16.5f, 15f)
            lineTo(16f, 12.7f)
            quadTo(12f, 11.3f, 8f, 12.7f)
            lineTo(7.5f, 15f)
            close()
        }
    }

    /** 播放：实心三角（0.8.0 通话详情页） */
    val Play: ImageVector by lazy {
        solidIcon("Play") {
            moveTo(8.2f, 5.6f)
            lineTo(18.4f, 12f)
            lineTo(8.2f, 18.4f)
            close()
        }
    }

    /** 暂停：两道实心竖条 */
    val Pause: ImageVector by lazy {
        solidIcon("Pause") {
            moveTo(7.2f, 5.8f)
            lineTo(10f, 5.8f)
            lineTo(10f, 18.2f)
            lineTo(7.2f, 18.2f)
            close()
            moveTo(14f, 5.8f)
            lineTo(16.8f, 5.8f)
            lineTo(16.8f, 18.2f)
            lineTo(14f, 18.2f)
            close()
        }
    }

    /** 麦克风：上面的胶囊、下面的托和支架 */
    private fun PathBuilder.mic() {
        moveTo(9f, 6f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 15f, y1 = 6f)
        lineTo(15f, 11f)
        arcTo(3f, 3f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 9f, y1 = 11f)
        close()
        moveTo(5.8f, 11f)
        arcTo(6.2f, 6.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 18.2f, y1 = 11f)
        moveTo(12f, 17.2f)
        lineTo(12f, 20.5f)
        moveTo(9f, 20.5f)
        lineTo(15f, 20.5f)
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

    /** 实心图标：填满并描一圈圆角边，角不那么尖 */
    private fun solidIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = SolidColor(Color.Black),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.4f,
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
