package isomeria.aichat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 微信风格尺寸，按截图宽约 400dp 换算，误差约 1dp。页面里只从这里取值。 */
@Immutable
data class WeDimens(
    val topBarHeight: Dp = 50.dp,
    val bottomBarHeight: Dp = 56.dp,
    val topBarIcon: Dp = 24.dp,
    val topBarIconTouch: Dp = 48.dp,

    // 聊天列表
    val chatRowHeight: Dp = 72.dp,
    val chatRowAvatar: Dp = 48.dp,
    val chatRowTextStart: Dp = 76.dp,

    // 通讯录、设置
    val listRowHeight: Dp = 56.dp,
    val listRowAvatar: Dp = 40.dp,
    val listRowTextStart: Dp = 72.dp,
    val listRowIcon: Dp = 24.dp,
    val groupHeaderHeight: Dp = 32.dp,
    val groupSpacing: Dp = 8.dp,

    val horizontalPadding: Dp = 16.dp,
    val avatarCorner: Dp = 4.dp,
    val profileAvatar: Dp = 64.dp,
    val profileAvatarCorner: Dp = 6.dp,
    /** 聊天信息页顶部的联系人头像（估） */
    val chatInfoAvatar: Dp = 52.dp,
    val dividerThickness: Dp = 0.5.dp,

    // 聊天页
    val chatAvatar: Dp = 40.dp,
    val chatAvatarEdge: Dp = 12.dp,
    val bubbleCorner: Dp = 4.dp,
    val bubblePaddingHorizontal: Dp = 12.dp,
    val bubblePaddingVertical: Dp = 8.dp,
    val bubbleMinHeight: Dp = 40.dp,
    val bubbleTailWidth: Dp = 5.dp,
    val bubbleTailHeight: Dp = 9.dp,
    val bubbleTailCenterY: Dp = 20.dp,
    /** 头像到气泡主体的距离；气泡尾巴画在这段间隙里，尾尖离头像 = 间隙 - 尾巴宽度 */
    val bubbleAvatarGap: Dp = 12.dp,
    val bubbleOppositeMinGap: Dp = 62.dp,
    val messageSpacing: Dp = 12.dp,
    val imageMaxSide: Dp = 140.dp,
    val imageCorner: Dp = 4.dp,

    // 输入栏
    val inputMinHeight: Dp = 40.dp,
    val inputCorner: Dp = 5.dp,
    val inputMaxLines: Int = 5,
    val inputBarIcon: Dp = 30.dp,
    val inputBarVerticalPadding: Dp = 8.dp,
    /** 表情和「+」面板的默认高度；键盘弹出过一次后改用键盘高度，切换时输入栏不跳 */
    val panelHeight: Dp = 260.dp,
    val panelMinHeight: Dp = 220.dp,
    val panelMaxHeight: Dp = 420.dp,

    // 表情面板
    val emojiColumns: Int = 8,
    val emojiCellHeight: Dp = 48.dp,
    val emojiTabHeight: Dp = 44.dp,
    val emojiKeyWidth: Dp = 56.dp,
    val emojiKeyHeight: Dp = 40.dp,

    // 未读
    val badgeSize: Dp = 18.dp,
    val badgeDot: Dp = 10.dp,

    /** 返回、推入时页面左边缘的阴影宽度（按微信录屏测量） */
    val pageEdgeShadow: Dp = 16.dp,

    // 滑块（温度）
    val sliderThumb: Dp = 24.dp,
    val sliderTrack: Dp = 4.dp,

    // 按钮、对话框
    val buttonHeight: Dp = 48.dp,
    val buttonCorner: Dp = 8.dp,
    val dialogCorner: Dp = 12.dp,
    /** 对话框最大宽度（估） */
    val dialogMaxWidth: Dp = 320.dp,
    val popupCorner: Dp = 6.dp,
)
