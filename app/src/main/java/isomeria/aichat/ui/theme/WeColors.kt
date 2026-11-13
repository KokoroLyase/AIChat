package isomeria.aichat.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 微信风格配色。色值来自安卓微信浅色和深色截图采样（JPEG 误差约 ±2），
 * 注释里标「估」的是推算值，开发中再校准。
 *
 * 层级：页面最深，列表卡片浅一级，Tab 栏和输入栏再浅一级。品牌绿不随模式变化，变的是发出的气泡。
 */
@Immutable
data class WeColors(
    val isDark: Boolean,
    /** 页面、顶栏背景 */
    val pageBackground: Color,
    /** 列表、卡片背景 */
    val listBackground: Color,
    /** 列表行按下时的背景（估） */
    val listPressed: Color,
    /** 置顶聊天行背景（估） */
    val pinnedBackground: Color,
    /** Tab 栏、输入栏背景 */
    val barBackground: Color,
    /** 输入框背景 */
    val inputBackground: Color,
    /** 列表分割线 */
    val divider: Color,
    /** 顶栏下沿线 */
    val topBarLine: Color,
    /** 主文字、未选中 Tab */
    val textPrimary: Color,
    /** 次要文字（消息预览、时间） */
    val textSecondary: Color,
    /** 辅助文字（分组标题、账号行） */
    val textTertiary: Color,
    /** 聊天时间条文字 */
    val chatTimeText: Color,
    /** 右侧箭头 */
    val arrow: Color,
    /** 通讯录索引字母 */
    val indexLetter: Color,
    /** 输入框内麦克风等图标 */
    val inputIcon: Color,
    /** 品牌绿（选中 Tab、按钮） */
    val brand: Color,
    /** 品牌绿按下（估） */
    val brandPressed: Color,
    /** 发出的气泡 */
    val bubbleSent: Color,
    /** 发出气泡里的文字（估：90% 不透明黑） */
    val bubbleSentText: Color,
    /** 收到的气泡 */
    val bubbleReceived: Color,
    /** 收到气泡里的文字 */
    val bubbleReceivedText: Color,
    /** 链接（收到的气泡、普通文字） */
    val link: Color,
    /** 链接（发出的气泡；深色为估值） */
    val linkInSent: Color,
    /** 未读红点 */
    val badge: Color,
    /** 危险操作文字，如「删除联系人」（估） */
    val danger: Color,
    /** 错误和待修正的说明文字：比 [danger] 深，在页面底色上也读得清 */
    val dangerText: Color,
    /** 成功提示、选中分段的文字：[brand] 在浅色底上太淡，不能用来写字 */
    val brandText: Color,
    /** 长按菜单、「+」菜单背景（估） */
    val popupBackground: Color,
    /** 长按菜单文字（估） */
    val popupText: Color,
    /** 对话框背景（估） */
    val dialogBackground: Color,
    /** 遮罩（估） */
    val scrim: Color,
    /** 思考过程区域背景（估） */
    val thinkingBackground: Color,
    /** 思考过程文字（估） */
    val thinkingText: Color,
    /** 开关关闭时的轨道（估） */
    val switchTrackOff: Color,
    /** 滑杆未选中的轨道 */
    val sliderTrackOff: Color,
    /** 按钮置灰（估） */
    val disabled: Color,
    /** 从搜索跳到某条消息时，这条消息整行的高亮底色（估） */
    val messageHighlight: Color,
    /** 表情面板里「删除」键和选中分类的底色（估） */
    val emojiKey: Color,
    /** 加载提示框的底色（估） */
    val loadingBackground: Color,
    /** 通话页（0.8.0）：浅色、深色模式下都是深色底 */
    val callBackground: Color = Color(0xFF262626),
    val callText: Color = Color(0xFFFFFFFF),
    val callTextSecondary: Color = Color(0xB3FFFFFF),
    /** 通话页的圆形按钮 */
    val callButton: Color = Color(0x33FFFFFF),
    /** 开着的按钮（静音、免提）和上面的图标 */
    val callButtonOn: Color = Color(0xFFFFFFFF),
    val callButtonOnIcon: Color = Color(0xFF191919),
    /** 挂断 */
    val callHangUp: Color = Color(0xFFFA5151),
    val callHangUpPressed: Color = Color(0xFFD94848),
    /** 通话页的失败提示：偏暖的深色底，白字读得清 */
    val callNotice: Color = Color(0xFF4A3B24),
)

val LightWeColors = WeColors(
    isDark = false,
    pageBackground = Color(0xFFEDEDED),
    listBackground = Color(0xFFFFFFFF),
    listPressed = Color(0xFFE5E5E5),
    pinnedBackground = Color(0xFFF3F3F3),
    barBackground = Color(0xFFF7F7F7),
    inputBackground = Color(0xFFFFFFFF),
    divider = Color(0xFFE6E6E6),
    topBarLine = Color(0xFFD5D5D5),
    textPrimary = Color(0xFF191919),
    textSecondary = Color(0xFFB0B0B0),
    textTertiary = Color(0xFF6E6E6E),
    chatTimeText = Color(0xFFA6A6A6),
    arrow = Color(0xFFB2B2B2),
    indexLetter = Color(0xFF191919),
    inputIcon = Color(0xFF727272),
    brand = Color(0xFF07C160),
    brandPressed = Color(0xFF06AD56),
    bubbleSent = Color(0xFF95EC69),
    bubbleSentText = Color(0xE6000000),
    bubbleReceived = Color(0xFFFFFFFF),
    bubbleReceivedText = Color(0xFF191919),
    link = Color(0xFF576B95),
    linkInSent = Color(0xFF576B95),
    badge = Color(0xFFFE3E3E),
    danger = Color(0xFFFA5151),
    dangerText = Color(0xFFC62828),
    brandText = Color(0xFF05793C),
    popupBackground = Color(0xFF4C4C4C),
    popupText = Color(0xFFFFFFFF),
    dialogBackground = Color(0xFFFFFFFF),
    scrim = Color(0x80000000),
    thinkingBackground = Color(0xFFE3E3E3),
    thinkingText = Color(0xFF808080),
    switchTrackOff = Color(0xFFE5E5E5),
    sliderTrackOff = Color(0xFFE5E5E5),
    disabled = Color(0xFFC6C6C6),
    messageHighlight = Color(0x1F000000),
    emojiKey = Color(0xFFFFFFFF),
    loadingBackground = Color(0xE64C4C4C),
)

val DarkWeColors = WeColors(
    isDark = true,
    pageBackground = Color(0xFF111111),
    listBackground = Color(0xFF191919),
    listPressed = Color(0xFF242424),
    pinnedBackground = Color(0xFF1F1F1F),
    barBackground = Color(0xFF1E1E1E),
    inputBackground = Color(0xFF292929),
    divider = Color(0xFF232323),
    topBarLine = Color(0xFF212121),
    textPrimary = Color(0xFFD0D0D0),
    textSecondary = Color(0xFF5C5C5C),
    textTertiary = Color(0xFF8A8A8A),
    chatTimeText = Color(0xFF565656),
    arrow = Color(0xFF5A5A5A),
    indexLetter = Color(0xFF464646),
    inputIcon = Color(0xFF969696),
    brand = Color(0xFF07C160),
    brandPressed = Color(0xFF06AD56),
    bubbleSent = Color(0xFF3EB575),
    bubbleSentText = Color(0xE6000000),
    bubbleReceived = Color(0xFF2C2C2C),
    bubbleReceivedText = Color(0xFFD4D4D4),
    link = Color(0xFF7F8C9E),
    linkInSent = Color(0xFF1F4E79),
    badge = Color(0xFFFE3E3E),
    danger = Color(0xFFFA5151),
    dangerText = Color(0xFFFA5151),
    brandText = Color(0xFF07C160),
    popupBackground = Color(0xFF2C2C2C),
    popupText = Color(0xFFD0D0D0),
    dialogBackground = Color(0xFF262626),
    scrim = Color(0x99000000),
    thinkingBackground = Color(0xFF1E1E1E),
    thinkingText = Color(0xFF7A7A7A),
    switchTrackOff = Color(0xFF3A3A3A),
    sliderTrackOff = Color(0xFF707070),
    disabled = Color(0xFF3A3A3A),
    messageHighlight = Color(0x24FFFFFF),
    emojiKey = Color(0xFF2C2C2C),
    loadingBackground = Color(0xE62C2C2C),
)
