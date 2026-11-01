package isomeria.aichat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/** 微信风格字号，跟随系统字体缩放（以及设置里的「字体大小」）。 */
@Immutable
data class WeTypography(
    /** 顶栏标题：17sp 中粗 */
    val topBarTitle: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    /** 消息正文：17sp，行高 24sp */
    val message: TextStyle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 24.sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    ),
    /** 列表名称、设置项：17sp */
    val listTitle: TextStyle = TextStyle(fontSize = 17.sp),
    /** 消息预览、分组标题、聊天时间条：14sp */
    val small: TextStyle = TextStyle(fontSize = 14.sp),
    /** 列表时间：13sp */
    val listTime: TextStyle = TextStyle(fontSize = 13.sp),
    /** Tab 文字：12sp */
    val tab: TextStyle = TextStyle(fontSize = 12.sp),
    /** 「我」页昵称：24sp 粗 */
    val profileName: TextStyle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    /** 联系人详情页名称（估） */
    val contactName: TextStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    /** 「我」页账号行：16sp */
    val profileAccount: TextStyle = TextStyle(fontSize = 16.sp),
    /** 未读数字 */
    val badge: TextStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    /** 按钮文字 */
    val button: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    /** 对话框标题 */
    val dialogTitle: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    /** 对话框说明文字 */
    val dialogMessage: TextStyle = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    /** 思考过程正文 */
    val thinking: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    /** 索引条字母 */
    val indexLetter: TextStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium),
    /** 索引滑动时屏幕中间的放大字母 */
    val indexOverlay: TextStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Medium),
    /** 表情面板里的表情 */
    val emoji: TextStyle = TextStyle(fontSize = 24.sp),
    /** 默认头像上的首字（相对头像大小在组件里再缩放） */
    val avatarLetter: TextStyle = TextStyle(fontWeight = FontWeight.Medium),
)
