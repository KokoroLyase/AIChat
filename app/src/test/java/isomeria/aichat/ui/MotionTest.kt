package isomeria.aichat.ui

import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.feature.chat.BubbleAppearTracker
import isomeria.aichat.feature.chat.ChatMessageList
import isomeria.aichat.feature.chat.ChatRow
import isomeria.aichat.feature.chat.MessageActions
import isomeria.aichat.ui.components.BubbleMenuArrow
import isomeria.aichat.ui.components.MenuItem
import isomeria.aichat.ui.components.SelectionCrossfade
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeBubbleMenu
import isomeria.aichat.ui.components.WeContextMenu
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeDropdownMenu
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.bubbleMenuArrow
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.LocalReduceMotion
import isomeria.aichat.ui.theme.isAnimationOff
import isomeria.aichat.ui.theme.loopingFloat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.9.1 动画：气泡出现只给新消息、长按菜单的小三角对准气泡、菜单和对话框缩放淡入、底部菜单滑出、
 * 多选的勾选圈滑入和底栏交叉淡变、被按的气泡变暗、系统移除动画时循环动效静止。
 * 时钟手动推进，看动画中途和结束时的位置、大小。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class MotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun bounds(text: String): DpRect = compose.onAllNodesWithText(text)[0].getBoundsInRoot()
    private fun frames(n: Int = 2) = repeat(n) { compose.mainClock.advanceTimeByFrame() }
    private fun settle() = compose.mainClock.advanceTimeBy(1_000)

    /** 时钟停着时改状态：要手动通知，下一帧才会重组 */
    private fun change(block: () -> Unit) {
        block()
        Snapshot.sendApplyNotifications()
    }

    // ---------- 气泡出现 ----------

    @Test fun appearTrackerOnlyClaimsNewMessagesOnce() {
        val tracker = BubbleAppearTracker(openedAt = 1_000)
        assertFalse("打开时已有的不播", tracker.claim(1, 500))
        assertTrue("打开后才有的播", tracker.claim(2, 1_000))
        assertFalse("同一条只播一次（滚出去再滚回来）", tracker.claim(2, 1_000))
        assertFalse("往上翻加载的历史不播", tracker.claim(3, 10))
    }

    private val contact = Contact(id = 1, name = "小林", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1)
    private fun msg(id: Long, text: String, createdAt: Long, role: Role = Role.ASSISTANT) =
        Message(id = id, contactId = 1, role = role, type = MessageType.TEXT, content = text, status = MessageStatus.SUCCESS, createdAt = createdAt)
    private val noActions = MessageActions(onCopy = {}, onDelete = {}, onRegenerate = {}, onCancelReply = {}, onFailedClick = {}, onImageClick = {}, onAvatarClick = {})

    @Test fun newBubbleRisesInButHistoryDoesNot() {
        var rows by mutableStateOf(listOf(msg(1, "打开时就有", 500)))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ChatMessageList(
                    rows = rows.map { ChatRow.Item(it) },
                    contact = contact, myName = "我", myAvatarPath = null, waitingReply = false, actions = noActions,
                    openedAt = 1_000,
                )
            }
        }
        frames()
        val oldFirst = bounds("打开时就有")
        settle()
        assertEquals("打开时已有的消息直接显示", oldFirst.top, bounds("打开时就有").top)

        // 新到的回复：先在下面一点，再升到位
        change { rows = listOf(msg(2, "新到的回复", 2_000)) + rows }
        frames()
        val newEarly = bounds("新到的回复")
        settle()
        val newSettled = bounds("新到的回复")
        assertTrue("新消息应从下面升上来：${newEarly.top} -> ${newSettled.top}", newEarly.top > newSettled.top + 1.dp)

        // 往上翻加载的历史：不动
        change { rows = rows + msg(3, "更早的历史", 100) }
        frames()
        val historyEarly = bounds("更早的历史")
        settle()
        assertEquals(historyEarly.top, bounds("更早的历史").top)
    }

    // ---------- 长按菜单 ----------

    @Test fun menuArrowPointsAtBubble() {
        val bubble = IntRect(100, 500, 300, 560)
        val menu = IntSize(400, 120)
        // 菜单在上方：三角在下沿，对准气泡中间
        val above = bubbleMenuArrow(bubble, IntOffset(20, 360), menu, inset = 30)
        assertEquals(BubbleMenuArrow(180f, pointsDown = true), above)
        // 菜单在下方：三角在上沿
        val below = bubbleMenuArrow(bubble, IntOffset(20, 580), menu, inset = 30)
        assertEquals(BubbleMenuArrow(180f, pointsDown = false), below)
        // 气泡贴着屏幕边：三角不进菜单的圆角
        val edge = bubbleMenuArrow(IntRect(0, 500, 20, 560), IntOffset(20, 360), menu, inset = 30)
        assertEquals(30f, edge.x)
        // 菜单盖住了长消息：不画
        assertEquals(BubbleMenuArrow.None, bubbleMenuArrow(IntRect(100, 100, 300, 900), IntOffset(20, 400), menu, inset = 30))
    }

    @Test fun dropdownGrowsFromTopRight() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
                    Box(Modifier.size(48.dp)) { WeDropdownMenu(listOf(MenuItem("发起群聊") {}, MenuItem("添加朋友") {}), onDismiss = {}) }
                }
            }
        }
        frames()
        val early = bounds("添加朋友")
        settle()
        val settled = bounds("添加朋友")
        assertTrue("一开始应缩小：${early.right - early.left} vs ${settled.right - settled.left}", (early.right - early.left) < (settled.right - settled.left))
        assertTrue("从右上角展开，右边先到位", (settled.right - early.right) < (early.left - settled.left))
        assertTrue("上边先到位", (early.top - settled.top) < (early.bottom - settled.bottom).let { if (it < 0.dp) -it else it } + 1.dp)
    }

    // ---------- 对话框、底部菜单 ----------

    @Test fun dialogFadesInWithSlightScale() {
        compose.mainClock.autoAdvance = false
        compose.setContent { AIChatTheme(darkTheme = false) { WeDialog(message = "确定删除？", onDismiss = {}, onConfirm = {}, confirmText = "删除") } }
        frames()
        val early = bounds("删除")
        settle()
        val settled = bounds("删除")
        assertTrue("一开始略小：${early.right - early.left} vs ${settled.right - settled.left}", (early.right - early.left) < (settled.right - settled.left))
        assertTrue("只是轻微缩放", (early.right - early.left) > (settled.right - settled.left) * 0.85f)
    }

    @Test fun actionSheetSlidesUpAndActsAfterSlidingDown() {
        var shown by mutableStateOf(true)
        var picked = 0
        var dismissed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                if (shown) WeActionSheet(listOf(SheetOption("从相册选择") { picked++ }), onDismiss = { dismissed++; shown = false })
            }
        }
        frames()
        val early = bounds("从相册选择")
        settle()
        val settled = bounds("从相册选择")
        assertTrue("从下往上滑出：${early.top} -> ${settled.top}", early.top > settled.top + 20.dp)

        compose.onAllNodesWithText("从相册选择")[0].performClick()
        frames(3)
        assertEquals("滑下去之前不执行", 0, picked)
        assertEquals(0, dismissed)
        assertTrue("正在滑下去", bounds("从相册选择").top > settled.top)
        settle()
        assertEquals(1, picked)
        assertEquals(1, dismissed)
    }

    // ---------- 多选 ----------

    @Test fun selectCirclesSlideInAndOut() {
        var selection by mutableStateOf<Set<Long>?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ChatMessageList(
                    rows = listOf(ChatRow.Item(msg(1, "周末去爬山吗", 10))),
                    contact = contact, myName = "我", myAvatarPath = null, waitingReply = false, actions = noActions,
                    selection = selection, openedAt = 1_000,
                )
            }
        }
        settle()
        val normal = bounds("周末去爬山吗").left
        change { selection = emptySet() }
        frames(4)
        val entering = bounds("周末去爬山吗").left
        settle()
        val selecting = bounds("周末去爬山吗").left
        assertTrue("勾选圈占了位置：$normal -> $selecting", selecting > normal + 10.dp)
        assertTrue("滑入中途在两者之间：$entering", entering > normal && entering < selecting)
        change { selection = null }
        frames(4)
        val leaving = bounds("周末去爬山吗").left
        settle()
        assertTrue("滑出中途在两者之间：$leaving", leaving > normal && leaving < selecting)
        assertEquals(normal, bounds("周末去爬山吗").left)
    }

    @Test fun bottomBarsCrossfade() {
        var selection by mutableStateOf<Set<Long>?>(null)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                SelectionCrossfade(selection) { selected -> Text(if (selected == null) "输入栏" else "操作栏 ${selected.size}") }
            }
        }
        settle()
        change { selection = setOf(1L, 2L) }
        frames(4)
        assertEquals("交叉淡变时两个都在", 1, compose.onAllNodesWithText("输入栏").fetchSemanticsNodes().size)
        assertEquals(1, compose.onAllNodesWithText("操作栏 2").fetchSemanticsNodes().size)
        settle()
        assertEquals(0, compose.onAllNodesWithText("输入栏").fetchSemanticsNodes().size)
        // 退出时淡出的操作栏还是最后的选中数，不变成空
        change { selection = null }
        frames(4)
        assertEquals(1, compose.onAllNodesWithText("操作栏 2").fetchSemanticsNodes().size)
        settle()
        assertEquals(0, compose.onAllNodesWithText("操作栏 2").fetchSemanticsNodes().size)
        compose.onNodeWithText("输入栏").assertExists()
    }

    // ---------- 被按的气泡变暗 ----------

    @Test fun pressedBubbleStaysDarkWhileMenuOpen() {
        val rows = listOf(
            msg(1, "周末去爬山吗", 10),
            Message(id = 2, contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "", voiceMillis = 2_000, voicePlayed = true, status = MessageStatus.SUCCESS, createdAt = 20),
            Message(id = 3, contactId = 1, role = Role.USER, type = MessageType.CALL, voiceMillis = 9_000, status = MessageStatus.SUCCESS, createdAt = 30),
            Message(id = 4, contactId = 1, role = Role.ASSISTANT, type = MessageType.IMAGE, imageWidth = 10, imageHeight = 10, status = MessageStatus.SUCCESS, createdAt = 40),
        )
        var openId by mutableStateOf<Long?>(null)
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                // 换 key 重建列表，菜单按 menuOpenId 开或不开
                androidx.compose.runtime.key(openId) {
                    ChatMessageList(
                        rows = rows.reversed().map { ChatRow.Item(it) },
                        contact = contact, myName = "我", myAvatarPath = null, waitingReply = false, actions = noActions,
                        menuOpenId = openId, openedAt = 1_000,
                    )
                }
            }
        }
        // 每种消息找到可长按的那个节点（气泡含尖角；图片是缩略图本身）
        val finders: Map<Long, () -> DpRect> = mapOf(
            1L to { compose.onAllNodesWithText("周末去爬山吗")[0].getBoundsInRoot() },
            2L to { compose.onAllNodesWithContentDescription("语音，2 秒")[0].getBoundsInRoot() },
            3L to { compose.onAllNodesWithContentDescription("通话时长", substring = true)[0].getBoundsInRoot() },
            4L to { compose.onAllNodesWithContentDescription("[图片]")[0].getBoundsInRoot() },
        )
        fun shade(id: Long, open: Boolean): Int {
            openId = if (open) id else null
            compose.waitForIdle()
            val box = finders.getValue(id)()
            val image = compose.onAllNodes(isRoot())[0].captureToImage().toPixelMap()
            val density = compose.activity.resources.displayMetrics.density
            // 左边往里一点（气泡在尖角和文字之间的内边距，只有底色）；图片取中间
            val x = if (id == 4L) (box.left.value + box.right.value) / 2 else box.left.value + 8
            val pixel = image[(x * density).toInt(), ((box.top.value + box.bottom.value) / 2 * density).toInt()]
            return ((pixel.red + pixel.green + pixel.blue) * 255).toInt()
        }
        for (id in finders.keys) {
            val normal = shade(id, open = false)
            val held = shade(id, open = true)
            assertTrue("消息 $id 菜单开着时应变暗：$normal -> $held", held < normal - 10)
        }
    }

    @Test fun bubbleMenuScalesInFromBubble() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(x = 100.dp, y = 400.dp).size(80.dp, 30.dp)) {
                        WeBubbleMenu(listOf("复制", "收藏", "删除").map { MenuItem(it, WeIcons.Copy) {} }) {}
                    }
                }
            }
        }
        frames(3)
        val early = bounds("收藏")
        settle()
        val settled = bounds("收藏")
        assertTrue("一开始缩小：$early vs $settled", (early.right - early.left) < (settled.right - settled.left))
    }

    @Test fun contextMenuGrowsFromPressPoint() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.offset(y = 200.dp).size(400.dp, 64.dp)) {
                        WeContextMenu(IntOffset(150, 30), listOf(MenuItem("置顶") {}, MenuItem("删除该聊天") {}), onDismiss = {})
                    }
                }
            }
        }
        frames()
        val early = bounds("删除该聊天")
        settle()
        val settled = bounds("删除该聊天")
        assertTrue("一开始缩小：$early vs $settled", (early.right - early.left) < (settled.right - settled.left))
        assertTrue("从按下的左上角展开，左边先到位", (early.left - settled.left) < (settled.right - early.right))
    }

    // ---------- 移除动画 ----------

    @Test fun loopsStayStillWhenAnimationsRemoved() {
        lateinit var still: State<Float>
        lateinit var moving: State<Float>
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                still = loopingFloat(0f, 1f, infiniteRepeatable(tween(1_000)), still = 0.3f, label = "still")
            }
            CompositionLocalProvider(LocalReduceMotion provides false) {
                moving = loopingFloat(0f, 1f, infiniteRepeatable(tween(1_000)), still = 0.3f, label = "moving")
            }
        }
        frames()
        val first = moving.value
        compose.mainClock.advanceTimeBy(400)
        assertEquals("移除动画时固定在静止值", 0.3f, still.value)
        assertNotEquals("正常时在动", first, moving.value, 0.05f)
    }

    @Test fun reduceMotionFollowsSystemSetting() {
        val resolver = compose.activity.contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        assertTrue(isAnimationOff(compose.activity))
        var seen = false
        compose.setContent { AIChatTheme(darkTheme = false) { seen = LocalReduceMotion.current } }
        compose.waitForIdle()
        assertTrue("系统移除动画时主题给出 reduceMotion", seen)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        compose.waitForIdle()
        assertFalse(isAnimationOff(compose.activity))
        assertFalse("恢复动画后跟着变", seen)
    }
}
