package isomeria.aichat.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import isomeria.aichat.feature.chatlist.ChatListContent
import isomeria.aichat.feature.chatlist.ConversationUi
import isomeria.aichat.feature.settings.CrashLogsContent
import isomeria.aichat.feature.settings.CrashLogsState
import isomeria.aichat.navigation.SubPageHost
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
 * 0.9.1 发布前的全面审计里修正的问题：聊天列表新排到第一位的会话看不到；
 * 页面里的子页面（选音色、回复偏好、崩溃日志详情）没有推入、滑出的动画，返回后下层页面回到顶部。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class AuditFixTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun bounds(text: String): DpRect = compose.onAllNodesWithText(text)[0].getBoundsInRoot()
    private fun frames(n: Int = 2) = repeat(n) { compose.mainClock.advanceTimeByFrame() }
    private fun settle() = compose.mainClock.advanceTimeBy(1_000)
    private fun change(block: () -> Unit) {
        block()
        Snapshot.sendApplyNotifications()
    }

    // ---------- 聊天列表 ----------

    private fun row(id: Long) = ConversationUi(id, "会话$id", null, 0, "内容$id", false, "12:00", 0, false, false)

    @Test fun conversationMovedToTopIsVisibleWhenListAtTop() {
        var items by mutableStateOf((1L..15L).map(::row))
        compose.setContent { AIChatTheme(darkTheme = false) { ChatListContent(items, 0, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("会话1").assertIsDisplayed()
        // 已有的会话收到新消息、排到第一位
        items = listOf(row(9)) + items.filter { it.contactId != 9L }
        compose.waitForIdle()
        compose.onNodeWithText("会话9").assertIsDisplayed()
        assertEquals("排到第一位的会话在最上面", bounds("会话9").top, bounds("会话9").top.coerceAtMost(bounds("会话1").top))
        // 新会话（新建联系人后第一条消息）
        items = listOf(row(20)) + items
        compose.waitForIdle()
        compose.onNodeWithText("会话20").assertIsDisplayed()
    }

    @Test fun listScrolledDownKeepsItsPlace() {
        var items by mutableStateOf((1L..30L).map(::row))
        compose.setContent { AIChatTheme(darkTheme = false) { ChatListContent(items, 0, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNode(hasScrollAction()).performScrollToIndex(10)
        compose.waitForIdle()
        val before = bounds("会话11").top
        items = listOf(row(25)) + items.filter { it.contactId != 25L }
        compose.waitForIdle()
        compose.onNodeWithText("会话1").assertIsNotDisplayed()
        assertEquals("往下翻着看时位置不变", before, bounds("会话11").top)
    }

    // ---------- 页面里的子页面 ----------

    @Test fun subPageSlidesInAndOutAndBaseKeepsScroll() {
        var sub by mutableStateOf<String?>(null)
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                SubPageHost(sub, subPage = { text -> Text(text, Modifier.fillMaxSize().background(Color.White)) }) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        repeat(40) { Text("行$it", Modifier.fillMaxWidth().height(60.dp)) }
                    }
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("行25"))
        compose.waitForIdle()
        val firstShown = (0 until 40).first { i -> bounds("行$i").let { it.bottom - it.top > 50.dp } }
        val rowTop = bounds("行$firstShown").top
        assertTrue("先往下滚了一段", firstShown > 0)

        compose.mainClock.autoAdvance = false
        change { sub = "子页面" }
        frames(4)
        val midIn = bounds("子页面").left
        assertTrue("子页面从右边推进来，中途还没到位：$midIn", midIn > 0.dp && midIn < 400.dp)
        settle()
        assertEquals(0.dp, bounds("子页面").left)

        change { sub = null }
        frames(4)
        val midOut = bounds("子页面").left
        assertTrue("返回时子页面向右滑走，中途还看得到：$midOut", midOut > 0.dp && midOut < 400.dp)
        settle()
        compose.onAllNodesWithText("子页面").fetchSemanticsNodes().let { assertTrue("滑走后子页面不在了", it.isEmpty()) }
        assertEquals("回到下层页面时还停在原来的位置", rowTop, bounds("行$firstShown").top)
    }

    @Test fun crashLogDetailSlidesInLikeAPage() {
        var state by mutableStateOf(CrashLogsState(loading = false, names = listOf("1700000000000-a.txt")))
        compose.setContent { AIChatTheme(darkTheme = false) { CrashLogsContent(state, {}, {}, {}, {}, {}) } }
        compose.mainClock.autoAdvance = false
        change { state = state.copy(selected = "1700000000000-a.txt", detail = "time=1700000000000\njava.lang.IllegalStateException") }
        frames(4)
        val mid = bounds("java.lang.IllegalStateException").left
        assertTrue("详情页从右边推进来：$mid", mid > 100.dp)
        settle()
        assertTrue(bounds("java.lang.IllegalStateException").left < 100.dp)
        // 返回：详情还在、向右滑走
        change { state = state.copy(selected = null, detail = null) }
        frames(4)
        assertTrue("返回时详情页滑走的途中还画着内容", bounds("java.lang.IllegalStateException").left > 0.dp)
        settle()
        compose.onAllNodesWithText("java.lang.IllegalStateException").fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    // ---------- 对话框、加载提示的关闭动画 ----------

    private fun width(text: String) = bounds(text).let { it.right - it.left }
    private fun gone(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    private fun dialogDim(): Float = org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.attributes.dimAmount

    @Test fun dialogFadesOutAndDimFollowsBeforeActing() {
        var shown by mutableStateOf(true)
        var confirmed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                if (shown) isomeria.aichat.ui.components.WeDialog(message = "确定删除？", onDismiss = { shown = false }, onConfirm = { confirmed++; shown = false }, confirmText = "删除")
            }
        }
        frames(2)
        val dimEarly = dialogDim()
        settle()
        val full = width("删除")
        val dimFull = dialogDim()
        assertTrue("背后的变暗渐渐加深：$dimEarly -> $dimFull", dimEarly < dimFull && dimFull > 0.3f)

        compose.onAllNodesWithText("删除")[0].performClick()
        frames(4)
        assertEquals("淡出之前不执行", 0, confirmed)
        assertTrue("正在缩小、淡出", width("删除") < full)
        assertTrue("变暗也在变浅：${dialogDim()}", dialogDim() < dimFull)
        settle()
        assertEquals(1, confirmed)
        assertTrue(gone("确定删除？"))
    }

    @Test fun dialogThatCannotConfirmDoesNotClose() {
        var confirmed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                isomeria.aichat.ui.components.WeDialog(message = "名称", onDismiss = {}, onConfirm = { confirmed++ }, canConfirm = false, confirmText = "确定")
            }
        }
        settle()
        val full = width("确定")
        compose.onAllNodesWithText("确定")[0].performClick()
        frames(4)
        assertEquals("名称是空的：不缩小", full, width("确定"))
        settle()
        assertEquals(0, confirmed)
    }

    @Test fun dialogKeptOpenByCallerComesBack() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                // 调用方点了确定也不关（等后台、校验没通过）
                isomeria.aichat.ui.components.WeDialog(message = "继续？", onDismiss = {}, onConfirm = {}, confirmText = "继续")
            }
        }
        settle()
        val full = width("继续")
        compose.onAllNodesWithText("继续")[0].performClick()
        settle()
        assertEquals("重新显示、不会留下一个看不见的对话框", full, width("继续"))
    }

    @Test fun loadingFadesOutWhenDone() {
        var running by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AIChatTheme(darkTheme = false) { isomeria.aichat.ui.components.WeLoading("正在导出", visible = running) } }
        settle()
        val full = width("正在导出")
        change { running = false }
        frames(4)
        assertTrue("做完时先淡出、缩小，还在", !gone("正在导出") && width("正在导出") < full)
        settle()
        assertTrue("淡出后移除", gone("正在导出"))
        // 再开始一次：重新出现
        change { running = true }
        settle()
        assertEquals(full, width("正在导出"))
    }

    // ---------- 菜单的关闭动画 ----------

    @Test fun bubbleMenuActsAtOnceThenFadesOut() {
        var open by mutableStateOf(true)
        var picked = 0
        var dismissed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(top = 400.dp, start = 100.dp).height(30.dp).fillMaxWidth(0.3f)) {
                        if (open) isomeria.aichat.ui.components.WeBubbleMenu(listOf("复制", "收藏").map { isomeria.aichat.ui.components.MenuItem(it, isomeria.aichat.ui.components.WeIcons.Copy) { picked++ } }) { dismissed++; open = false }
                    }
                }
            }
        }
        settle()
        val full = width("收藏")
        compose.onAllNodesWithText("收藏")[0].performClick()
        frames(2)
        assertEquals("点的那一项马上执行", 1, picked)
        assertEquals("菜单还在淡出", 0, dismissed)
        assertTrue("正在缩回去", width("收藏") < full)
        settle()
        assertEquals(1, dismissed)
        assertTrue(gone("收藏"))
    }

    @Test fun dropdownAndContextMenuFadeOutWhenItemTapped() {
        var drop by mutableStateOf(true)
        var context by mutableStateOf(true)
        var dropDismissed = 0
        var contextDismissed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Column(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(48.dp)) {
                        if (drop) isomeria.aichat.ui.components.WeDropdownMenu(listOf(isomeria.aichat.ui.components.MenuItem("新的朋友") {}), onDismiss = { dropDismissed++; drop = false })
                    }
                    androidx.compose.foundation.layout.Box(Modifier.padding(top = 300.dp).fillMaxWidth().height(64.dp)) {
                        if (context) isomeria.aichat.ui.components.WeContextMenu(androidx.compose.ui.unit.IntOffset(100, 20), listOf(isomeria.aichat.ui.components.MenuItem("置顶该聊天") {}), onDismiss = { contextDismissed++; context = false })
                    }
                }
            }
        }
        settle()
        val dropFull = width("新的朋友")
        compose.onAllNodesWithText("新的朋友")[0].performClick()
        frames(2)
        assertTrue("「+」菜单正在缩回去", dropDismissed == 0 && width("新的朋友") < dropFull)
        settle()
        assertEquals(1, dropDismissed)
        val contextFull = width("置顶该聊天")
        compose.onAllNodesWithText("置顶该聊天")[0].performClick()
        frames(2)
        assertTrue("长按菜单正在缩回去", contextDismissed == 0 && width("置顶该聊天") < contextFull)
        settle()
        assertEquals(1, contextDismissed)
    }

    private fun screenTop(text: String) = compose.onAllNodesWithText(text)[0].fetchSemanticsNode().positionOnScreen.y

    /** 真机复核发现：点“置顶”马上就置顶了，淡出的菜单里这一项变成“取消置顶”，菜单还跟着滑到最上面的那一行走 */
    @Test fun menuKeepsItsLabelAndPlaceWhileFading() {
        var pinned by mutableStateOf(false)
        var translated by mutableStateOf(false)
        var context by mutableStateOf(true)
        var bubble by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Column(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(top = if (pinned) 0.dp else 300.dp).fillMaxWidth().height(64.dp)) {
                        if (context) isomeria.aichat.ui.components.WeContextMenu(
                            androidx.compose.ui.unit.IntOffset(100, 20),
                            listOf(isomeria.aichat.ui.components.MenuItem(if (pinned) "取消置顶" else "置顶该聊天") { pinned = !pinned }),
                            onDismiss = { context = false },
                        )
                    }
                    androidx.compose.foundation.layout.Box(Modifier.padding(top = if (translated) 20.dp else 300.dp, start = 100.dp).height(30.dp).fillMaxWidth(0.3f)) {
                        if (bubble) isomeria.aichat.ui.components.WeBubbleMenu(
                            listOf(isomeria.aichat.ui.components.MenuItem(if (translated) "隐藏翻译" else "翻译", isomeria.aichat.ui.components.WeIcons.Copy) { translated = !translated }),
                        ) { bubble = false }
                    }
                }
            }
        }
        settle()
        val contextTop = screenTop("置顶该聊天")
        compose.onAllNodesWithText("置顶该聊天")[0].performClick()
        frames(3)
        assertTrue("已经置顶", pinned)
        assertTrue("淡出时还是点的那一项", !gone("置顶该聊天") && gone("取消置顶"))
        assertEquals("菜单不跟着那一行走（锚点上移了 300dp，菜单只有缩小带来的几个像素）", contextTop, screenTop("置顶该聊天"), 20f)
        settle()
        assertTrue(gone("置顶该聊天"))

        val bubbleTop = screenTop("翻译")
        compose.onAllNodesWithText("翻译")[0].performClick()
        frames(3)
        assertTrue(translated)
        assertTrue("气泡菜单淡出时也不变", !gone("翻译") && gone("隐藏翻译"))
        assertEquals("气泡菜单也不跟着走（锚点上移了 280dp）", bubbleTop, screenTop("翻译"), 20f)
        settle()
        assertTrue(gone("翻译"))
    }

    // ---------- 展开、收起 ----------

    private fun height(text: String) = bounds(text).let { it.bottom - it.top }

    @Test fun expandGrowsAndCollapseFadesOut() {
        var value by mutableStateOf<String?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Column {
                    isomeria.aichat.ui.components.WeExpand(value) { Text(it, Modifier.height(80.dp)) }
                    Text("下面的内容")
                }
            }
        }
        settle()
        val belowTop = bounds("下面的内容").top
        change { value = "高级设置" }
        frames(4)
        val mid = bounds("下面的内容").top - belowTop
        assertTrue("展开到一半：下面的内容往下移了一部分 $mid", mid > 5.dp && mid < 75.dp)
        settle()
        assertEquals(80.dp, bounds("下面的内容").top - belowTop)
        change { value = null }
        frames(4)
        assertTrue("收起的途中还画着内容", !gone("高级设置"))
        val midCollapse = bounds("下面的内容").top - belowTop
        assertTrue("正在收起 $midCollapse", midCollapse > 5.dp && midCollapse < 75.dp)
        settle()
        assertTrue(gone("高级设置"))
        assertEquals(belowTop, bounds("下面的内容").top)
    }

    private fun descNodes(text: String) = compose.onAllNodesWithContentDescription(text, substring = true)
    private fun descHeight(text: String) = descNodes(text)[0].getBoundsInRoot().let { it.bottom - it.top }
    private fun descGone(text: String) = descNodes(text).fetchSemanticsNodes().isEmpty()

    @Test fun translationUnderBubbleExpandsAndHides() {
        val base = isomeria.aichat.data.db.Message(id = 1, contactId = 1, role = isomeria.aichat.data.db.Role.ASSISTANT, type = isomeria.aichat.data.db.MessageType.TEXT,
            content = "Hello", status = isomeria.aichat.data.db.MessageStatus.SUCCESS, createdAt = 1, translation = "你好")
        var message by mutableStateOf(base)
        val contact = isomeria.aichat.data.db.Contact(id = 1, name = "小林", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1)
        val actions = isomeria.aichat.feature.chat.MessageActions(onCopy = {}, onDelete = {}, onRegenerate = {}, onCancelReply = {}, onFailedClick = {}, onImageClick = {}, onAvatarClick = {})
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                isomeria.aichat.feature.chat.MessageRow(message, "小林", null, 0, "我", null, 0, canRegenerate = false, waitingReply = false, maxBubbleWidth = 1000.dp, actions = actions)
            }
        }
        settle()
        change { message = base.copy(translationShown = true) }
        frames(3)
        val midOpen = descHeight("你好")
        settle()
        val full = descHeight("你好")
        assertTrue("译文正在展开：$midOpen / $full", midOpen < full)
        change { message = base.copy(translationShown = false) }
        frames(3)
        assertTrue("隐藏翻译时先收起、淡出", !descGone("你好") && descHeight("你好") < full)
        settle()
        assertTrue(descGone("你好"))
    }

    @Test fun floatingChipFadesInAndOut() {
        var visible by mutableStateOf(false)
        compose.mainClock.autoAdvance = false
        compose.setContent { AIChatTheme(darkTheme = false) { isomeria.aichat.ui.components.FadeScaleVisibility(visible) { Text("3条新消息") } } }
        settle()
        change { visible = true }
        frames(3)
        val early = width("3条新消息")
        settle()
        val full = width("3条新消息")
        assertTrue("出现时轻微放大：$early -> $full", early < full)
        change { visible = false }
        frames(3)
        assertTrue("消失时淡出、缩小，还在", !gone("3条新消息") && width("3条新消息") < full)
        settle()
        assertTrue(gone("3条新消息"))
    }

    // ---------- 聊天列表删除、收藏搜索 ----------

    @Test fun deletedConversationFadesOut() {
        var items by mutableStateOf((1L..5L).map(::row))
        compose.mainClock.autoAdvance = false
        compose.setContent { AIChatTheme(darkTheme = false) { ChatListContent(items, 0, {}, {}, {}, {}, {}, {}, {}) } }
        settle()
        val below = bounds("会话4").top
        change { items = items.filter { it.contactId != 3L } }
        frames(4)
        val moving = bounds("会话4").top
        assertTrue("下面的会话滑上来，中途还没到位：$below -> $moving", moving < below && !gone("会话4"))
        settle()
        assertTrue(gone("会话3"))
        assertTrue("滑到删掉的那一项原来的位置", bounds("会话4").top < moving)
    }

    @Test fun favoritesSearchBarCrossfades() {
        var searching by mutableStateOf(false)
        val formatter = isomeria.aichat.core.ChatTimeFormatter(isomeria.aichat.core.TimeLabels("昨天", List(7) { "星期$it" }, "%1\$d月%2\$d日", "%1\$d年%2\$d月%3\$d日"))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                isomeria.aichat.feature.favorites.FavoritesContent(
                    entries = emptyList(), query = "", searching = searching, playingId = null, formatter = formatter,
                    onBack = {}, onStartSearch = {}, onQueryChange = {}, onCancelSearch = {}, onOpen = {}, onToggleVoice = {}, onCopy = {}, onDelete = {},
                )
            }
        }
        settle()
        change { searching = true }
        frames(3)
        assertTrue("切换的途中顶栏和搜索框都在（交叉淡变）", !gone("收藏") && !gone("搜索收藏"))
        settle()
        assertTrue(gone("收藏"))
    }
}
