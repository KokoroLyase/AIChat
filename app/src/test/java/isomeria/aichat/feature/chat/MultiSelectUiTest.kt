package isomeria.aichat.feature.chat

import android.app.Application
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
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
 * 0.9.0 多选：点整行切换选中，气泡、语音条、头像都不响应；等待回复中的消息和正在进行的通话点了不选中；
 * 长按菜单有“多选”（不能选的没有），通话的长按菜单有“复制”（正在进行的没有）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class MultiSelectUiTest {
    @get:Rule val compose = createComposeRule()

    private val contact = Contact(id = 1, name = "小林", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1)
    private val text = Message(id = 1, contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "周末去爬山吗", status = MessageStatus.SUCCESS, createdAt = 100)
    private val voice = Message(id = 2, contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "语音转写", voiceMillis = 2_000, voicePlayed = true, status = MessageStatus.SUCCESS, createdAt = 200)
    private val call = Message(id = 3, contactId = 1, role = Role.USER, type = MessageType.CALL, voiceMillis = 9_000, status = MessageStatus.SUCCESS, createdAt = 300)
    private val sending = Message(id = 4, contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "等待回复中", status = MessageStatus.SENDING, createdAt = 400)
    /** 自己的语音还在识别：也算等待回复中 */
    private val transcribing = Message(id = 5, contactId = 1, role = Role.USER, type = MessageType.VOICE, voiceMillis = 1_000, status = MessageStatus.SENDING, createdAt = 50)

    private class Calls {
        val toggled = mutableListOf<Long>()
        val voiceClicks = mutableListOf<Long>()
        val avatar = mutableListOf<Unit>()
        val started = mutableListOf<Long>()
        val copiedCalls = mutableListOf<Long>()
        val favorited = mutableListOf<Long>()
    }

    private val image = Message(id = 6, contactId = 1, role = Role.ASSISTANT, type = MessageType.IMAGE, imageWidth = 10, imageHeight = 10, status = MessageStatus.SUCCESS, createdAt = 60)

    private fun show(selection: Set<Long>?, calls: Calls) {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ChatMessageList(
                    rows = listOf(image, transcribing, text, voice, call, sending).map { ChatRow.Item(it) },
                    contact = contact,
                    myName = "我",
                    myAvatarPath = null,
                    waitingReply = true,
                    actions = MessageActions(
                        onCopy = {}, onDelete = {}, onRegenerate = {}, onCancelReply = {}, onFailedClick = {}, onImageClick = {},
                        onAvatarClick = { calls.avatar += Unit },
                        onVoiceClick = { calls.voiceClicks += it.id },
                        onStartSelection = { calls.started += it.id },
                        onCopyCall = { calls.copiedCalls += it.id },
                        onFavorite = { calls.favorited += it.id },
                    ),
                    selection = selection,
                    onToggleSelection = { calls.toggled += it.id },
                )
            }
        }
    }

    @Test
    fun selecting_tapTogglesWholeRow_bubblesDoNotReact() {
        val calls = Calls()
        show(setOf(1L), calls)
        compose.onNodeWithContentDescription("选择这条消息：周末去爬山吗").assertIsOn()
        compose.onNodeWithContentDescription("选择这条消息：语音转写").assertIsOff()
        compose.onNodeWithContentDescription("选择这条消息：语音转写").performClick()
        compose.onNodeWithContentDescription("选择这条消息：周末去爬山吗").performClick()
        assertEquals(listOf(2L, 1L), calls.toggled)
        assertTrue("语音条不播放", calls.voiceClicks.isEmpty())
        assertTrue(calls.avatar.isEmpty())
        // 长按也只是选择，不弹菜单
        compose.onNodeWithContentDescription("选择这条消息：周末去爬山吗").performTouchInput { longClick() }
        compose.onNodeWithText("多选").assertDoesNotExist()
    }

    @Test
    fun selecting_waitingMessageCannotBeSelected() {
        val calls = Calls()
        show(emptySet(), calls)
        compose.onNodeWithContentDescription("选择这条消息：等待回复中").assertIsNotEnabled()
        compose.onNodeWithContentDescription("选择这条消息：等待回复中").performClick()
        assertTrue(calls.toggled.toString(), calls.toggled.isEmpty())
        // 还在识别的语音：点在语音条上（自己的在右边，头像左边）也不选中、不播放
        compose.onNodeWithContentDescription("选择这条消息：[语音]").performTouchInput { click(Offset(width - 90.dp.toPx(), centerY)) }
        assertTrue(calls.toggled.toString(), calls.toggled.isEmpty())
        assertTrue(calls.voiceClicks.toString(), calls.voiceClicks.isEmpty())
        // 读屏：不能选的那条单独一个节点，范围就是这一行，不是整个列表
        val row = compose.onNodeWithContentDescription("选择这条消息：等待回复中").fetchSemanticsNode().boundsInRoot
        val other = compose.onNodeWithContentDescription("选择这条消息：周末去爬山吗").fetchSemanticsNode().boundsInRoot
        assertTrue("$row / $other", row.top >= other.bottom)
    }

    @Test
    fun notSelecting_menuHasSelect_callMenuHasCopy() {
        val calls = Calls()
        show(null, calls)
        compose.onNodeWithText("周末去爬山吗").performTouchInput { longClick() }
        compose.onNodeWithText("多选").performClick()
        assertEquals(listOf(1L), calls.started)
        // 通话：长按有“复制”，复制完整内容
        compose.onNodeWithText("00:09", substring = true, useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("复制").performClick()
        assertEquals(listOf(3L), calls.copiedCalls)
        // 等待回复中的消息：菜单里没有“多选”
        compose.onNodeWithText("等待回复中").performTouchInput { longClick() }
        compose.onNodeWithText("复制").assertExists()
        compose.onNodeWithText("多选").assertDoesNotExist()
    }

    /** 0.9.0 收藏：文字、语音、打完的通话的长按菜单有“收藏”；图片和等待回复中的没有 */
    @Test
    fun menu_favoriteForTextVoiceCall_notImageOrWaiting() {
        val calls = Calls()
        show(null, calls)
        compose.onNodeWithText("周末去爬山吗").performTouchInput { longClick() }
        compose.onNodeWithText("收藏").performClick()
        compose.onNodeWithContentDescription("语音转写", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("00:09", substring = true, useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText("收藏").performClick()
        assertEquals(listOf(1L, 3L), calls.favorited)
        compose.onNodeWithText("等待回复中").performTouchInput { longClick() }
        compose.onNodeWithText("收藏").assertDoesNotExist()
        compose.onNodeWithText("复制").performClick()
        compose.onNodeWithContentDescription("[图片]").performTouchInput { longClick() }
        compose.onNodeWithText("删除").assertExists()
        compose.onNodeWithText("收藏").assertDoesNotExist()
    }
}
