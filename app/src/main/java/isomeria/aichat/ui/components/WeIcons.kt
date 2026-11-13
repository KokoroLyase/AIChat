package isomeria.aichat.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自绘线性图标：24×24 网格，圆头圆角描边，线宽默认 1.6。
 * App 里的图标都从这里取（0.9.1 起不再用 Material Icons）；不使用微信的图标素材。
 * Tab 栏选中时用对应的实心版（名字带 Filled）。
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
    val Phone: ImageVector by lazy { lineIcon("Phone") { phone(dx = 4f, dy = 4f) } }

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

    /** 勾（0.9.0 多选的勾选圈里） */
    val Check: ImageVector by lazy {
        lineIcon("Check", strokeWidth = 2f) {
            moveTo(5.5f, 12.5f)
            lineTo(10f, 16.8f)
            lineTo(18.5f, 7.6f)
        }
    }

    /** 五角星（0.9.0 收藏） */
    val Star: ImageVector by lazy {
        lineIcon("Star") {
            for (i in 0..9) {
                val angle = Math.toRadians(-90.0 + i * 36.0)
                val radius = if (i % 2 == 0) 9f else 4f
                val x = 12f + radius * kotlin.math.cos(angle).toFloat()
                val y = 12.8f + radius * kotlin.math.sin(angle).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
    }

    /** 多选（0.9.0）：左边两个勾，右边三行 */
    val MultiSelect: ImageVector by lazy {
        lineIcon("MultiSelect") {
            moveTo(3.5f, 6.8f)
            lineTo(5.2f, 8.4f)
            lineTo(8f, 5.4f)
            moveTo(3.5f, 12.8f)
            lineTo(5.2f, 14.4f)
            lineTo(8f, 11.4f)
            moveTo(11f, 7f)
            lineTo(20.5f, 7f)
            moveTo(11f, 13f)
            lineTo(20.5f, 13f)
            moveTo(11f, 19f)
            lineTo(20.5f, 19f)
            moveTo(4f, 19f)
            lineTo(7.5f, 19f)
        }
    }

    /** 引用（0.9.0）：一对左引号 */
    val Quote: ImageVector by lazy {
        lineIcon("Quote") {
            circle(7.6f, 14.6f, 2.6f)
            moveTo(5f, 14.6f)
            quadTo(5f, 8.6f, 10f, 6.6f)
            circle(16.4f, 14.6f, 2.6f)
            moveTo(13.8f, 14.6f)
            quadTo(13.8f, 8.6f, 18.8f, 6.6f)
        }
    }

    /** 翻译（0.9.0）：左上一个“文”，右下一个 A */
    val Translate: ImageVector by lazy {
        lineIcon("Translate") {
            moveTo(3f, 6.5f)
            lineTo(12f, 6.5f)
            moveTo(7.5f, 4f)
            lineTo(7.5f, 6.5f)
            moveTo(10.2f, 6.5f)
            quadTo(8.6f, 12.4f, 3.6f, 14.6f)
            moveTo(5f, 9f)
            quadTo(7f, 12.6f, 11f, 14f)
            moveTo(12.4f, 20.5f)
            lineTo(16.5f, 10f)
            lineTo(20.6f, 20.5f)
            moveTo(13.9f, 16.8f)
            lineTo(19.1f, 16.8f)
        }
    }

    /** 复制（0.9.1）：前后两张叠着的纸 */
    val Copy: ImageVector by lazy {
        lineIcon("Copy") {
            roundRect(4.5f, 8.5f, 14.5f, 19.5f, 1.8f)
            moveTo(9.5f, 8.5f)
            lineTo(9.5f, 6.3f)
            quadTo(9.5f, 4.5f, 11.3f, 4.5f)
            lineTo(17.7f, 4.5f)
            quadTo(19.5f, 4.5f, 19.5f, 6.3f)
            lineTo(19.5f, 12.7f)
            quadTo(19.5f, 14.5f, 17.7f, 14.5f)
            lineTo(14.5f, 14.5f)
        }
    }

    /** 删除（0.9.1）：垃圾桶 */
    val Delete: ImageVector by lazy {
        lineIcon("Delete") {
            moveTo(4.5f, 6.5f)
            lineTo(19.5f, 6.5f)
            moveTo(9.5f, 6.5f)
            lineTo(9.5f, 5f)
            quadTo(9.5f, 4f, 10.5f, 4f)
            lineTo(13.5f, 4f)
            quadTo(14.5f, 4f, 14.5f, 5f)
            lineTo(14.5f, 6.5f)
            moveTo(6.4f, 6.5f)
            lineTo(7.2f, 18.4f)
            quadTo(7.3f, 20f, 8.9f, 20f)
            lineTo(15.1f, 20f)
            quadTo(16.7f, 20f, 16.8f, 18.4f)
            lineTo(17.6f, 6.5f)
            moveTo(10.2f, 10f)
            lineTo(10.2f, 16.4f)
            moveTo(13.8f, 10f)
            lineTo(13.8f, 16.4f)
        }
    }

    /** 保存到相册（0.9.1）：向下的箭头和托盘 */
    val Download: ImageVector by lazy {
        lineIcon("Download") {
            moveTo(12f, 4f)
            lineTo(12f, 15f)
            moveTo(7.5f, 10.5f)
            lineTo(12f, 15f)
            lineTo(16.5f, 10.5f)
            moveTo(4.5f, 15.5f)
            lineTo(4.5f, 18.2f)
            quadTo(4.5f, 20f, 6.3f, 20f)
            lineTo(17.7f, 20f)
            quadTo(19.5f, 20f, 19.5f, 18.2f)
            lineTo(19.5f, 15.5f)
        }
    }

    /** 听筒播放（0.9.1）：缩小的听筒，右上两道声波 */
    val Earpiece: ImageVector by lazy {
        lineIcon("Earpiece") {
            phone(scale = 0.78f, dx = 3.4f, dy = 6.6f)
            moveTo(14f, 6.6f)
            arcTo(3.8f, 3.8f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17.8f, y1 = 10.4f)
            moveTo(14f, 3.2f)
            arcTo(7.2f, 7.2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21.2f, y1 = 10.4f)
        }
    }

    /** 后台播放（0.9.1）：耳机 */
    val Headphones: ImageVector by lazy {
        lineIcon("Headphones") {
            moveTo(4.5f, 14.5f)
            lineTo(4.5f, 12f)
            arcTo(7.5f, 7.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19.5f, y1 = 12f)
            lineTo(19.5f, 14.5f)
            roundRect(4.5f, 13.5f, 8.5f, 20f, 1.6f)
            roundRect(15.5f, 13.5f, 19.5f, 20f, 1.6f)
        }
    }

    /** 转文字（0.9.1）：一大一小两个 T */
    val TextFields: ImageVector by lazy {
        lineIcon("TextFields") {
            moveTo(3.5f, 5.5f)
            lineTo(13f, 5.5f)
            moveTo(8.25f, 5.5f)
            lineTo(8.25f, 19f)
            moveTo(12.5f, 11f)
            lineTo(20f, 11f)
            moveTo(16.25f, 11f)
            lineTo(16.25f, 19f)
        }
    }

    /** 连续朗读（0.9.1）：三行字和右下的播放三角 */
    val PlayList: ImageVector by lazy {
        lineIcon("PlayList") {
            moveTo(4f, 6f)
            lineTo(17f, 6f)
            moveTo(4f, 11f)
            lineTo(17f, 11f)
            moveTo(4f, 16f)
            lineTo(10f, 16f)
            moveTo(14f, 13.8f)
            lineTo(14f, 19.8f)
            lineTo(19f, 16.8f)
            close()
        }
    }

    /** 重新生成（0.9.1）：缺口在右上的圆圈箭头 */
    val Refresh: ImageVector by lazy {
        lineIcon("Refresh") {
            moveTo(19f, 12f)
            arcTo(7f, 7f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 16.95f, y1 = 7.05f)
            moveTo(17.2f, 3.4f)
            lineTo(17.2f, 7.3f)
            lineTo(13.3f, 7.3f)
        }
    }

    /** 取消回复（0.9.1）：圆圈加斜线 */
    val Block: ImageVector by lazy {
        lineIcon("Block") {
            circle(12f, 12f, 8.5f)
            moveTo(6f, 6f)
            lineTo(18f, 18f)
        }
    }

    /** 新建联系人（0.9.1）：人像和右上的加号 */
    val PersonAdd: ImageVector by lazy {
        lineIcon("PersonAdd") {
            circle(9.5f, 8.5f, 3.5f)
            moveTo(3.5f, 19.5f)
            quadTo(4f, 14f, 9.5f, 14f)
            quadTo(15f, 14f, 15.5f, 19.5f)
            moveTo(18.5f, 7f)
            lineTo(18.5f, 13f)
            moveTo(15.5f, 10f)
            lineTo(21.5f, 10f)
        }
    }

    /** Tab「智信」：带尾巴的圆角气泡；选中时用实心版 */
    val Chats: ImageVector by lazy { lineIcon("Chats") { chatBubble() } }
    val ChatsFilled: ImageVector by lazy { solidIcon("ChatsFilled", strokeWidth = 1.6f) { chatBubble() } }

    /** Tab「通讯录」：卡片上一个人像 */
    val Contacts: ImageVector by lazy {
        lineIcon("Contacts") {
            roundRect(4f, 3.5f, 20f, 20.5f, 2.5f)
            circle(12f, 10f, 2.6f)
            moveTo(7.6f, 16.6f)
            quadTo(8.2f, 13.8f, 12f, 13.8f)
            quadTo(15.8f, 13.8f, 16.4f, 16.6f)
        }
    }

    /** 实心版只填不描边，外框按描边的一半放大，和线性版一样大；人像挖空 */
    val ContactsFilled: ImageVector by lazy {
        ImageVector.Builder("ContactsFilled", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
                roundRect(3.2f, 2.7f, 20.8f, 21.3f, 3.3f)
                circle(12f, 10f, 3.2f)
                moveTo(6.9f, 17.4f)
                quadTo(7.6f, 13.1f, 12f, 13.1f)
                quadTo(16.4f, 13.1f, 17.1f, 17.4f)
                close()
            }
        }.build()
    }

    /** Tab「我」：人像 */
    val Person: ImageVector by lazy { lineIcon("Person") { person() } }
    val PersonFilled: ImageVector by lazy { solidIcon("PersonFilled", strokeWidth = 1.6f) { person() } }

    /** 显示密码（0.9.1）：眼睛 */
    val Visibility: ImageVector by lazy {
        lineIcon("Visibility") {
            eye()
            circle(12f, 12f, 3.2f)
        }
    }

    /** 隐藏密码：眼睛加斜线 */
    val VisibilityOff: ImageVector by lazy {
        lineIcon("VisibilityOff") {
            eye()
            circle(12f, 12f, 3.2f)
            moveTo(4.5f, 4.5f)
            lineTo(19.5f, 19.5f)
        }
    }

    /** 表情面板的删除键（0.9.1）：向左的标签里一个叉 */
    val Backspace: ImageVector by lazy {
        lineIcon("Backspace") {
            moveTo(8.6f, 5.5f)
            lineTo(19f, 5.5f)
            quadTo(21f, 5.5f, 21f, 7.5f)
            lineTo(21f, 16.5f)
            quadTo(21f, 18.5f, 19f, 18.5f)
            lineTo(8.6f, 18.5f)
            lineTo(3f, 12f)
            close()
            moveTo(11.6f, 9.2f)
            lineTo(16.6f, 14.8f)
            moveTo(16.6f, 9.2f)
            lineTo(11.6f, 14.8f)
        }
    }

    /** 表情面板的「最近使用」（0.9.1）：钟 */
    val Clock: ImageVector by lazy {
        lineIcon("Clock") {
            circle(12f, 12f, 8.5f)
            moveTo(12f, 7.2f)
            lineTo(12f, 12f)
            lineTo(15.4f, 14f)
        }
    }

    /** 切回键盘（0.9.1）：圆角键盘，两排按键和空格键 */
    val Keyboard: ImageVector by lazy {
        lineIcon("Keyboard") {
            roundRect(2.5f, 6f, 21.5f, 18f, 2f)
            for (x in listOf(6.5f, 9.5f, 12.5f, 15.5f, 17.5f)) dot(x, 9.6f)
            for (x in listOf(6.5f, 9.5f, 12.5f, 15.5f, 17.5f)) dot(x, 12.4f)
            moveTo(8f, 15.2f)
            lineTo(16f, 15.2f)
        }
    }

    /** 相册（0.9.1）：画框里的太阳和山 */
    val Image: ImageVector by lazy {
        lineIcon("Image") {
            roundRect(3.5f, 4.5f, 20.5f, 19.5f, 2f)
            circle(8.8f, 9.3f, 1.6f)
            moveTo(3.5f, 17f)
            lineTo(9f, 12f)
            lineTo(12.5f, 15f)
            lineTo(15.5f, 12.2f)
            lineTo(20.5f, 16.6f)
        }
    }

    /** 拍照（0.9.1）：相机 */
    val Camera: ImageVector by lazy {
        lineIcon("Camera") {
            moveTo(3.5f, 8.8f)
            quadTo(3.5f, 7f, 5.3f, 7f)
            lineTo(7.8f, 7f)
            lineTo(9.4f, 4.8f)
            lineTo(14.6f, 4.8f)
            lineTo(16.2f, 7f)
            lineTo(18.7f, 7f)
            quadTo(20.5f, 7f, 20.5f, 8.8f)
            lineTo(20.5f, 17.7f)
            quadTo(20.5f, 19.5f, 18.7f, 19.5f)
            lineTo(5.3f, 19.5f)
            quadTo(3.5f, 19.5f, 3.5f, 17.7f)
            close()
            circle(12f, 13f, 3.6f)
        }
    }

    /** 电话听筒的轮廓（16×16），按 [scale] 缩放后平移 */
    private fun PathBuilder.phone(scale: Float = 1f, dx: Float = 0f, dy: Float = 0f) {
        fun x(v: Float) = dx + v * scale
        fun y(v: Float) = dy + v * scale
        moveTo(x(1.2f), y(0f))
        lineTo(x(4.4f), y(0f))
        lineTo(x(6f), y(4.1f))
        lineTo(x(4f), y(5.7f))
        quadTo(x(5.6f), y(9.6f), x(10.3f), y(12f))
        lineTo(x(11.9f), y(10f))
        lineTo(x(16f), y(11.6f))
        lineTo(x(16f), y(14.8f))
        quadTo(x(16f), y(16f), x(14.8f), y(16f))
        quadTo(x(7f), y(15.6f), x(3.3f), y(12.7f))
        quadTo(x(0.4f), y(9f), x(0f), y(1.2f))
        quadTo(x(0f), y(0f), x(1.2f), y(0f))
        close()
    }

    private fun PathBuilder.chatBubble() {
        moveTo(8f, 4.5f)
        lineTo(16f, 4.5f)
        quadTo(20.5f, 4.5f, 20.5f, 9f)
        lineTo(20.5f, 12.5f)
        quadTo(20.5f, 17f, 16f, 17f)
        lineTo(11.6f, 17f)
        lineTo(7.2f, 20.2f)
        lineTo(7.6f, 17f)
        quadTo(3.5f, 16.8f, 3.5f, 12.5f)
        lineTo(3.5f, 9f)
        quadTo(3.5f, 4.5f, 8f, 4.5f)
        close()
    }

    private fun PathBuilder.person() {
        circle(12f, 8f, 4f)
        moveTo(4.5f, 20f)
        quadTo(5f, 13.6f, 12f, 13.6f)
        quadTo(19f, 13.6f, 19.5f, 20f)
        close()
    }

    private fun PathBuilder.eye() {
        moveTo(2.5f, 12f)
        quadTo(6.8f, 5.2f, 12f, 5.2f)
        quadTo(17.2f, 5.2f, 21.5f, 12f)
        quadTo(17.2f, 18.8f, 12f, 18.8f)
        quadTo(6.8f, 18.8f, 2.5f, 12f)
        close()
    }

    /** 线宽大小的圆点（极短线段加圆头） */
    private fun PathBuilder.dot(x: Float, y: Float) {
        moveTo(x, y)
        lineTo(x + 0.01f, y)
    }

    private fun PathBuilder.roundRect(left: Float, top: Float, right: Float, bottom: Float, r: Float) {
        moveTo(left + r, top)
        lineTo(right - r, top)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = right, y1 = top + r)
        lineTo(right, bottom - r)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = right - r, y1 = bottom)
        lineTo(left + r, bottom)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = left, y1 = bottom - r)
        lineTo(left, top + r)
        arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = left + r, y1 = top)
        close()
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
    private fun solidIcon(name: String, strokeWidth: Float = 1.4f, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = SolidColor(Color.Black),
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
