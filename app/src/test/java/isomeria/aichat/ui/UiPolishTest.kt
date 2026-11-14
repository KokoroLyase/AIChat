package isomeria.aichat.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import isomeria.aichat.BuildConfig
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ReplyType
import isomeria.aichat.data.db.Role
import isomeria.aichat.feature.call.CallActions
import isomeria.aichat.feature.call.CallContent
import isomeria.aichat.feature.call.CallDetail
import isomeria.aichat.feature.call.CallDetailContent
import isomeria.aichat.feature.call.CallInfo
import isomeria.aichat.feature.call.CallPlayerUi
import isomeria.aichat.feature.call.CallUi
import isomeria.aichat.feature.call.isTurnPlaceholder
import isomeria.aichat.feature.chat.ChatPageSample
import isomeria.aichat.feature.chat.longVoiceRows
import isomeria.aichat.feature.chat.selectRows
import isomeria.aichat.feature.chat.sampleContact
import isomeria.aichat.feature.chatinfo.ChatInfoContent
import isomeria.aichat.feature.chatinfo.previewChatInfo
import isomeria.aichat.feature.contactedit.ContactDetailContent
import isomeria.aichat.feature.contactedit.ContactForm
import isomeria.aichat.feature.contactedit.ContactVoiceRows
import isomeria.aichat.feature.contactedit.previewState
import isomeria.aichat.feature.me.MeContent
import isomeria.aichat.ui.components.MorePanel
import isomeria.aichat.ui.components.RecordMode
import isomeria.aichat.ui.components.RecordZone
import isomeria.aichat.ui.components.RecordingOverlay
import isomeria.aichat.ui.components.RecordingUi
import isomeria.aichat.ui.components.TOP_BAR_MIN_TITLE_SCALE
import isomeria.aichat.ui.components.TitleSlot
import isomeria.aichat.ui.components.TopBarIcon
import isomeria.aichat.ui.components.VoiceBubble
import isomeria.aichat.ui.components.voiceRowBubbleWidth
import isomeria.aichat.ui.components.WeActionRow
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeIcon
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSearchBar
import isomeria.aichat.ui.components.WeSegmented
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.components.dialogMessageAlign
import isomeria.aichat.ui.components.iconScaleFor
import isomeria.aichat.ui.components.listRowValueFitsBeside
import isomeria.aichat.ui.components.segmentedPerRow
import isomeria.aichat.ui.components.topBarTitleSlot
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.AvatarPalette
import isomeria.aichat.ui.theme.WeColors
import isomeria.aichat.ui.theme.WeDimens
import isomeria.aichat.ui.theme.WeTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.9.1 界面清单：大字体、窄屏时文字换行或缩小而不是截断，图标跟着字体变大，动作行和对话框的对齐，
 * 「没有识别出文字」的占位样式。最大字体用 App 字体 2.5 倍（和截图里的「特大」一样），窄屏是 320dp。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class UiPolishTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var colors: WeColors
    private lateinit var dimens: WeDimens
    private var titleSize: TextUnit = TextUnit.Unspecified

    private fun show(fontScale: Float = LARGEST, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            AIChatTheme(darkTheme = false, fontScale = fontScale) {
                colors = WeTheme.colors
                dimens = WeTheme.dimens
                titleSize = WeTheme.typography.topBarTitle.fontSize
                content()
            }
        }
        compose.waitForIdle()
    }

    private fun layoutOf(text: String, substring: Boolean = false): TextLayoutResult {
        val node = compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).onFirst().fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.first()
    }

    private fun bounds(text: String, substring: Boolean = false): DpRect =
        compose.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).onFirst().getBoundsInRoot()

    private fun rootWidth(): Dp = compose.onAllNodes(isRoot()).onFirst().getBoundsInRoot().right

    /**
     * 被省略号收尾，或某一行比文字框宽（被裁掉）。不用 hasVisualOverflow：Robolectric 里一行字
     * 即使放得下 didOverflowWidth 也会是 true。
     */
    /** 在屏幕上露出来的宽高不小于文字本身（位置会被外层裁剪，被挤出去或盖住的部分露不出来） */
    private fun fullyShown(text: String): Boolean {
        val b = bounds(text)
        val size = layoutOf(text).size
        return with(compose.density) { (b.right - b.left).toPx() >= size.width - 1f && (b.bottom - b.top).toPx() >= size.height - 1f }
    }

    private fun TextLayoutResult.cut(): Boolean =
        didOverflowHeight || (0 until lineCount).any { isLineEllipsized(it) || getLineRight(it) - getLineLeft(it) > size.width + 1f }

    // ---------- 1. 顶栏标题：放不下先缩小，最小到 0.6 倍，再放不下才省略 ----------

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun topBar_narrowLargest_titleShrinksInsteadOfEllipsis() {
        show { WeTopBar(title = "系统提示词", onBack = {}) { WeSmallButton("完成", {}) } }
        val title = layoutOf("系统提示词")
        assertFalse("标题被省略或裁掉", title.cut())
        assertTrue("标题字号 ${title.layoutInput.style.fontSize} 应小于 $titleSize", title.layoutInput.style.fontSize.value < titleSize.value)
        assertTrue("标题 ${bounds("系统提示词").right} 压到了按钮 ${bounds("完成").left}", bounds("系统提示词").right <= bounds("完成").left)
    }

    @Test
    fun topBar_normalFont_titleKeepsItsSize() {
        show(1f) { WeTopBar(title = "编辑联系人", onBack = {}) { WeSmallButton("保存", {}) } }
        assertEquals(titleSize, layoutOf("编辑联系人").layoutInput.style.fontSize)
    }

    @Test
    fun topBarTitleSlot_centeredWhenItFits_otherwiseUsesAllFreeSpace() {
        // 放得下：两侧对称留出较宽一侧（至少 96）
        assertEquals(TitleSlot(120, 280), topBarTitleSlot(400, 120, 60, 100, 96, 12))
        // 放不下：左边从返回按钮后开始，右边到按钮前，各留 12
        assertEquals(TitleSlot(132, 328), topBarTitleSlot(400, 120, 60, 300, 96, 12))
        assertEquals(0.6f, TOP_BAR_MIN_TITLE_SCALE)
    }

    // ---------- 2. 我页：名字不截断、英文单词不从中间断开；版本号只占一行 ----------

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun me_narrowLargest_nameOnOneLineAndVersionNotBroken() {
        show { MeContent("Isomeria", null, {}, {}) }
        val name = layoutOf("Isomeria")
        assertEquals("“Isomeria”被拆成了几行", 1, name.lineCount)
        assertFalse(name.cut())
        assertEquals("版本号折行了", 1, layoutOf(BuildConfig.VERSION_NAME, substring = true).lineCount)
    }

    @Test
    fun me_largest_chineseNameStillWraps() {
        show { MeContent("一个特别特别长的中文名字用来测试换行", null, {}, {}) }
        val name = layoutOf("一个特别特别长的中文名字用来测试换行")
        assertTrue("中文名应换行：${name.lineCount}", name.lineCount > 1)
        assertFalse(name.cut())
    }

    // ---------- 3. 联系人详情：头像顶部对齐，服务商不截断 ----------

    @Test
    fun contactDetail_largest_avatarTopAlignedAndProviderNotCut() {
        show { ContactDetailContent(previewState, {}, {}, {}, {}) }
        val letter = bounds("小")
        val name = bounds("小雨")
        val avatarCenter = (letter.top + letter.bottom) / 2
        assertEquals("头像没有和名字顶部对齐", (name.top + dimens.profileAvatar / 2).value, avatarCenter.value, 4f)
        assertFalse("服务商被截断", layoutOf("服务商：DeepSeek").cut())
    }

    // ---------- 4. 通话字幕：大字体时整张字幕卡片露出来 ----------

    @Test
    fun call_largest_captionCardFullyVisible() {
        val ui = CallUi(
            name = "小雨", avatarPath = null, avatarColor = AvatarPalette[0], status = "03:21",
            stage = isomeria.aichat.data.call.CallPhase.THINKING, phase = "对方正在想… 6 秒",
            subtitle = "我" to "周末天气好的话，我们去爬山吧",
            notice = "有几句没有合成出来，只显示了文字",
            hints = listOf("这台手机用音量判断你是否在说话，环境吵时，说完可以点「说完了」", "这个联系人开着思考，每轮可能要等较久，可以在联系人资料里关掉"),
            muted = true,
        )
        show { CallContent(ui, CallActions()) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val caption = bounds("周末天气好的话，我们去爬山吧")
        val buttons = bounds("静音")
        assertTrue("字幕被裁掉一部分", fullyShown("周末天气好的话，我们去爬山吧"))
        assertTrue("字幕顶部 ${caption.top} 在屏幕外", caption.top >= 0.dp)
        assertTrue("字幕底部 ${caption.bottom} 被下面的按钮 ${buttons.top} 挡住", caption.bottom <= buttons.top)
    }

    // ---------- 5. 聊天信息：名字不截断 ----------

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun chatInfo_narrowLargest_nameNotCut() {
        show { ChatInfoContent(previewChatInfo, {}, {}, {}, {}, {}, {}, {}) }
        assertFalse("名字被省略", layoutOf(previewChatInfo.contact!!.name).cut())
    }

    // ---------- 6. 图标随字体放大（最多 1.2 倍），触控区不小于 48dp ----------

    @Test
    fun iconScale_followsFontUpTo120Percent() {
        assertEquals(1f, iconScaleFor(1f), 0.001f)
        assertEquals(1f, iconScaleFor(0.85f), 0.001f)
        assertEquals(1.2f, iconScaleFor(1.25f), 0.001f)
        assertEquals(1.2f, iconScaleFor(2.5f), 0.001f)
    }

    @Test
    fun weIcon_atLargeFont_drawsBigger() {
        show(1.25f) { Box(Modifier.testTag("icon")) { WeIcon(WeIcons.Back, null) } }
        val icon = compose.onNodeWithTag("icon").getBoundsInRoot()
        assertEquals(24f * 1.2f, (icon.right - icon.left).value, 0.5f)
    }

    @Test
    fun topBarIcon_largest_touchAreaAtLeast48dp() {
        show { TopBarIcon(WeIcons.Back, "返回", {}) }
        val b = compose.onNodeWithContentDescription("返回").getBoundsInRoot()
        assertTrue("触控区 ${b.right - b.left}", b.right - b.left >= 48.dp && b.bottom - b.top >= 48.dp)
    }

    @Test
    fun onlyWeIconUsesMaterialIcon() {
        val root = File("src/main/java")
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.name != "WeIcon.kt" }
            .filter { f -> f.readLines().any { it.trim() == "import androidx.compose.material3.Icon" } }
            .map { it.name }.toList()
        assertTrue("这些文件直接用 Material 的 Icon，不随字体放大：$offenders", offenders.isEmpty())
    }

    // ---------- 8. 对话框正文两行以上左对齐 ----------

    @Test
    fun dialogMessageAlign_multiLineStart_singleLineCenter() {
        assertEquals(TextAlign.Center, dialogMessageAlign(1))
        assertEquals(TextAlign.Start, dialogMessageAlign(2))
    }

    @Test
    fun dialog_longMessageIsLeftAligned() {
        val long = "安装包校验不通过，已删除。没有安装。可以在浏览器里打开发布页下载，或者稍后再检查更新。"
        show(1f) { WeDialog(long, {}, {}, title = "更新失败") }
        assertEquals(TextAlign.Start, layoutOf(long).layoutInput.style.textAlign)
    }

    @Test
    fun dialog_shortMessageStaysCentered() {
        show(1f) { WeDialog("确定删除？", {}, {}) }
        assertEquals(TextAlign.Center, layoutOf("确定删除？").layoutInput.style.textAlign)
    }

    // ---------- 9. 动作行：普通链接色左对齐，危险红色居中 ----------

    @Test
    fun actionRow_normalIsLinkColoredAndStartAligned() {
        show(1f) { WeActionRow("恢复默认", {}) }
        val text = bounds("恢复默认")
        assertEquals(dimens.horizontalPadding.value, text.left.value, 1f)
        assertEquals(colors.link, layoutOf("恢复默认").layoutInput.style.color)
    }

    @Test
    fun actionRow_dangerIsRedAndCentered() {
        show(1f) { WeActionRow("删除联系人", {}, danger = true) }
        val text = bounds("删除联系人")
        assertEquals((rootWidth() / 2).value, ((text.left + text.right) / 2).value, 1f)
        assertEquals(colors.danger, layoutOf("删除联系人").layoutInput.style.color)
    }

    // ---------- 10. 没有识别出文字：灰色占位 ----------

    @Test
    fun turnPlaceholder_onlyForFailedOrBlankTurns() {
        assertTrue(isTurnPlaceholder(turn(1, "", CallTurnStatus.ASR_FAILED)))
        assertTrue(isTurnPlaceholder(turn(1, " ", CallTurnStatus.OK)))
        assertFalse(isTurnPlaceholder(turn(1, "好呀", CallTurnStatus.OK)))
    }

    @Test
    fun callDetail_unrecognizedTurnIsGray() {
        val detail = CallDetail(
            call = Message(id = 23, contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, voiceMillis = 10_000, createdAt = 0),
            contact = sampleContact,
            turns = listOf(turn(1, "周末去爬山吧", CallTurnStatus.OK), turn(2, "", CallTurnStatus.ASR_FAILED)),
        )
        show(1f) {
            CallDetailContent(detail = detail, info = CallInfo("昨天 21:31", "00:10", null), player = CallPlayerUi(), playable = emptySet(), onBack = {}, onToggleTurn = {}, onTogglePlay = {}, onSeek = {})
        }
        assertEquals(colors.textTertiary, layoutOf("（没有识别出文字）").layoutInput.style.color)
        assertTrue("真的话不该是灰色", layoutOf("周末去爬山吧").layoutInput.style.color != colors.textTertiary)
    }

    private fun turn(id: Long, text: String, status: CallTurnStatus) =
        CallTurn(id = id, messageId = 23, seq = id.toInt(), role = Role.USER, text = text, audioPath = null, audioMillis = 1_000, status = status, createdAt = id)

    // ---------- 11. 语音秒数只占一行 ----------

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun voiceBubble_narrowLargest_secondsOnOneLine() {
        // 多选时左边多了勾选圈，语音条能占的宽度最少（0.9.0 截图里秒数在这里折行）
        show { ChatPageSample(rows = selectRows, selection = setOf(31L, 33L, 34L)) }
        val seconds = layoutOf("6″")
        assertEquals(1, seconds.lineCount)
        assertFalse("秒数被裁掉", seconds.cut())
    }

    @Test
    fun voiceBubble_largest_growsToFitSeconds() {
        // 语音条按秒数定宽（防回归：特大字体时两位数的秒数和声波也放得下，不折行）
        show { Box { VoiceBubble(millis = 10_000, sent = true, playing = false, unplayed = false, onClick = {}, onLongClick = {}) } }
        val seconds = layoutOf("10″")
        assertEquals(1, seconds.lineCount)
        assertFalse(seconds.cut())
        assertTrue("秒数被裁掉", fullyShown("10″"))
    }

    // ---------- 第 11 条（真机验证时补）：长语音旁边的「转文字」 ----------
    // 真机（411dp 宽、系统字体 1.1 × App 特大）上没听过的 60 秒语音旁边「转文字」折成两行，多选时一字一行；
    // 窄屏时整行比屏幕宽、居中摆放，语音条的尾巴和「转文字」都看不见。改成先缩短语音条，最短到秒数放得下一行

    @Test
    fun voiceRowBubbleWidth_shrinksBubbleBeforeToText() {
        // 放得下：按秒数的宽度
        assertEquals(200, voiceRowBubbleWidth(available = 400, desired = 200, minimum = 90, besides = 80))
        // 放不下：语音条缩短，旁边拿到要的宽度
        assertEquals(220, voiceRowBubbleWidth(available = 300, desired = 250, minimum = 90, besides = 80))
        // 最短到声波和秒数放得下一行
        assertEquals(90, voiceRowBubbleWidth(available = 150, desired = 250, minimum = 90, besides = 80))
        // 特大字体的短语音：秒数比按秒数的宽度还宽时放宽
        assertEquals(120, voiceRowBubbleWidth(available = 400, desired = 80, minimum = 120, besides = 80))
        // 不超过整行
        assertEquals(60, voiceRowBubbleWidth(available = 60, desired = 250, minimum = 90, besides = 80))
    }

    @Test @Config(qualifiers = "w411dp-h860dp-xxhdpi")
    fun longVoice_phoneLarge_toTextOnOneLine() {
        show(PHONE_LARGE) { ChatPageSample(rows = longVoiceRows) }
        assertVoiceRowsFit(toTextOneLine = true)
    }

    @Test @Config(qualifiers = "w411dp-h860dp-xxhdpi")
    fun longVoice_phoneLargeSelecting_toTextOnOneLine() {
        show(PHONE_LARGE) { ChatPageSample(rows = longVoiceRows, selection = setOf(61L)) }
        assertVoiceRowsFit(toTextOneLine = true)
    }

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun longVoice_narrowLarge_rowInsideScreen() {
        show(PHONE_LARGE) { ChatPageSample(rows = longVoiceRows) }
        assertVoiceRowsFit(toTextOneLine = true)
    }

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun longVoice_narrowLargestSelecting_rowInsideScreen() {
        show { ChatPageSample(rows = longVoiceRows, selection = setOf(61L)) }
        assertVoiceRowsFit(toTextOneLine = false)
    }

    /**
     * 每条长语音：语音条在屏幕里、秒数一行不被裁；两条对方的语音左边对齐（整行没有被居中挤歪）；
     * 显示出来的「转文字」完整、不压语音条
     */
    private fun assertVoiceRowsFit(toTextOneLine: Boolean) {
        val width = rootWidth()
        val bubbles = listOf("语音，60 秒", "语音，42 秒").flatMap { desc ->
            compose.onAllNodesWithContentDescription(desc, useUnmergedTree = true).fetchSemanticsNodes().map { node ->
                with(compose.density) { node.boundsInRoot.let { DpRect(it.left.toDp(), it.top.toDp(), it.right.toDp(), it.bottom.toDp()) } }
            }
        }
        assertEquals("应有三条长语音", 3, bubbles.size)
        bubbles.forEach { b -> assertTrue("语音条超出屏幕 $b / $width", b.left >= 0.dp && b.right <= width + 0.5.dp) }
        val received = bubbles.sortedBy { it.top }.take(2)
        assertTrue("两条对方的语音没有左对齐：$received", kotlin.math.abs((received[0].left - received[1].left).value) < 1f)
        listOf("60″", "42″").forEach { seconds ->
            compose.onAllNodesWithText(seconds, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                val layout = textLayout(node)
                assertEquals("$seconds 折行", 1, layout.lineCount)
                assertFalse("$seconds 被裁", layout.cut())
            }
        }
        // 极窄时连一个字都放不下的「转文字」不显示（不放到屏幕上），只看显示出来的
        val labels = compose.onAllNodesWithText("转文字", useUnmergedTree = true).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
        if (toTextOneLine) assertEquals("「转文字」应该三个都显示", 3, labels.size)
        labels.forEach { node ->
            val layout = textLayout(node)
            val shown = node.boundsInRoot
            assertTrue("「转文字」没有完整显示 ${shown.width}x${shown.height} / ${layout.size}", shown.width >= layout.size.width - 1f && shown.height >= layout.size.height - 1f)
            assertFalse("「转文字」的字比文字框宽，被裁掉", layout.cut())
            if (toTextOneLine) assertEquals("「转文字」折行", 1, layout.lineCount)
            val box = with(compose.density) { DpRect(shown.left.toDp(), shown.top.toDp(), shown.right.toDp(), shown.bottom.toDp()) }
            assertTrue("「转文字」超出屏幕 $box", box.left >= 0.dp && box.right <= width + 0.5.dp)
            bubbles.filter { it.top < box.bottom && box.top < it.bottom }.forEach { b ->
                assertTrue("「转文字」$box 压到语音条 $b", box.left >= b.right - 0.5.dp || box.right <= b.left + 0.5.dp)
            }
        }
    }

    private fun textLayout(node: androidx.compose.ui.semantics.SemanticsNode): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.first()
    }

    // ---------- ① 窄屏大字体：列表行和下一行不重叠 ----------
    // 0.9.0 截图里「选择音色」和「语气和风格」叠在一起，查下来是截图样例的问题：样例的 Column 不能滚动，
    // 内容比屏幕高时最后几行被压缩、居中摆放，和上一行重叠。真实页面可以滚动，不会这样。这里按真实页面放在可滚动的列里，
    // 防止以后真的重叠

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun listRowThenFormRow_narrowLargest_doNotOverlap() {
        show {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ContactVoiceRows(ContactForm(), ReplyType.FOLLOW, "mimo_default", {}, {}, canPickVoice = true)
            }
        }
        val upper = compose.onAllNodesWithText("选择音色").onFirst().getBoundsInRoot()
        val lower = bounds("语气和风格")
        assertTrue("「语气和风格」${lower.top} 压到了「选择音色」这一行 ${upper.bottom}", lower.top >= upper.bottom)
    }

    // ---------- ② 搜索栏随字体变高，正常字体保持 50dp ----------

    @Test
    fun searchBar_normalFontKeepsTopBarHeight() {
        show(1f) { Box(Modifier.testTag("bar")) { WeSearchBar("火星", {}, {}, remember { FocusRequester() }) } }
        val bar = compose.onNodeWithTag("bar").getBoundsInRoot()
        assertEquals(dimens.topBarHeight.value, (bar.bottom - bar.top).value, 0.5f)
    }

    @Test
    fun searchBar_largestGrowsAndCancelNotCut() {
        show { Box(Modifier.testTag("bar")) { WeSearchBar("火星", {}, {}, remember { FocusRequester() }) } }
        val bar = compose.onNodeWithTag("bar").getBoundsInRoot()
        assertTrue("搜索栏高 ${bar.bottom - bar.top}", bar.bottom - bar.top > dimens.topBarHeight)
        val cancel = bounds("取消")
        assertTrue("「取消」被裁掉", cancel.top >= bar.top && cancel.bottom <= bar.bottom)
        assertFalse(layoutOf("取消").cut())
    }

    // ---------- ③ 分段选择放不下时分行，最后一项不被挤出去 ----------

    @Test
    fun segmentedPerRow_splitsEvenly() {
        assertEquals(3, segmentedPerRow(listOf(50, 50, 50), 200, 10))
        assertEquals(2, segmentedPerRow(listOf(100, 100, 100), 250, 0))
        assertEquals(1, segmentedPerRow(listOf(300, 100), 250, 0))
        assertEquals(2, segmentedPerRow(listOf(90, 90, 90, 90), 200, 10))
    }

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun segmented_narrowLargest_everyOptionInside() {
        show { WeSegmented(listOf(1 to "近 7 天", 2 to "近 30 天", 3 to "全部"), 1, {}) }
        val width = rootWidth()
        listOf("近 7 天", "近 30 天", "全部").forEach {
            assertTrue("「$it」超出屏幕：${bounds(it).right} > $width", bounds(it).right <= width)
            assertFalse("「$it」被截断", layoutOf(it).cut())
            assertEquals("「$it」被挤得折行", 1, layoutOf(it).lineCount)
            assertTrue("「$it」被裁掉一部分", fullyShown(it))
        }
    }

    // ---------- ④ 右侧的值和长标题不截断 ----------

    @Test
    fun listRowValueFitsBeside_measuresBothWithGap() {
        assertTrue(listRowValueFitsBeside(100, 80, 200, 16))
        assertFalse(listRowValueFitsBeside(100, 90, 200, 16))
    }

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun listRow_narrowLargest_valueAndTitleNotCut() {
        show {
            Column {
                WeListRow("离线识别模型", value = "239.5 MB", onClick = {})
                WeListRow("使用离线识别", trailing = { WeSwitch(true, {}, label = "使用离线识别") })
            }
        }
        assertFalse("值被截断", layoutOf("239.5 MB").cut())
        assertTrue("值超出屏幕", bounds("239.5 MB").right <= rootWidth())
        assertFalse("标题被截断", layoutOf("使用离线识别").cut())
    }

    // ---------- ⑤ 「转文字」「语音通话」不折行 ----------

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun morePanel_narrowLargest_labelsOnOneLine() {
        show { MorePanel(imageEnabled = true, onAlbum = {}, onCamera = {}) }
        assertEquals(1, layoutOf("语音通话").lineCount)
        assertFalse(layoutOf("语音通话").cut())
    }

    @Test
    fun morePanel_normalWidthLargest_callLabelKeepsFullSize() {
        show { MorePanel(imageEnabled = true, onAlbum = {}, onCamera = {}) }
        assertEquals(layoutOf("相册").layoutInput.style.fontSize, layoutOf("语音通话").layoutInput.style.fontSize)
    }

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi")
    fun recordingOverlay_narrowLargest_toTextOnOneLine() {
        show {
            Box(Modifier.fillMaxSize()) {
                RecordingOverlay(RecordingUi(RecordMode.VOICE_MESSAGE, true, 4_000, 0.6f, RecordZone.SEND), 60_000, {}, {}, {})
            }
        }
        assertEquals(1, layoutOf("转文字").lineCount)
        assertFalse(layoutOf("转文字").cut())
    }

    private companion object {
        const val LARGEST = 2.5f

        /** 测试手机上的设置：系统字体 1.1 × App「特大」1.25 */
        const val PHONE_LARGE = 1.1f * 1.25f
    }
}
