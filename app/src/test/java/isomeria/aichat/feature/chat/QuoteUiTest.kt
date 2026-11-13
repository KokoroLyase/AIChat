package isomeria.aichat.feature.chat

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.ui.components.QuoteBar
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.9.0 引用：文字和有转写的语音的长按菜单有“引用”，失败的、图片、通话没有，通话中都没有；
 * 气泡下面的引用块显示“名字：内容”（引用自己的用我的名字，语音前面加[语音]），点它去找原消息；引用条点 × 取消。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class QuoteUiTest {
    @get:Rule val compose = createComposeRule()

    private val contact = Contact(id = 1, name = "小林", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1)
    private val text = Message(id = 1, contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "周末去爬山吗", status = MessageStatus.SUCCESS, createdAt = 100)
    private val voice = Message(id = 2, contactId = 1, role = Role.USER, type = MessageType.VOICE, content = "我说的语音", voiceMillis = 2_000, voicePlayed = true, status = MessageStatus.SUCCESS, createdAt = 200)
    private val failed = Message(id = 3, contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "没发出去", status = MessageStatus.FAILED, createdAt = 300)
    private val image = Message(id = 4, contactId = 1, role = Role.ASSISTANT, type = MessageType.IMAGE, imageWidth = 10, imageHeight = 10, status = MessageStatus.SUCCESS, createdAt = 400)
    private val replyToContact = Message(
        id = 5, contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "好呀", status = MessageStatus.SUCCESS, createdAt = 500,
        quoteId = 1, quoteRole = Role.ASSISTANT, quoteType = MessageType.TEXT, quoteText = "周末去\n爬山吗",
    )
    private val replyToSelf = Message(
        id = 6, contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "听到了", status = MessageStatus.SUCCESS, createdAt = 600,
        quoteId = 2, quoteRole = Role.USER, quoteType = MessageType.VOICE, quoteText = "我说的语音",
    )

    private class Calls {
        val quoted = mutableListOf<Long>()
        val opened = mutableListOf<Long>()
    }

    private fun show(calls: Calls, inCall: Boolean = false) {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ChatMessageList(
                    rows = listOf(replyToSelf, replyToContact, image, failed, voice, text).map { ChatRow.Item(it) },
                    contact = contact,
                    myName = "阿哲",
                    myAvatarPath = null,
                    waitingReply = false,
                    actions = MessageActions(
                        onCopy = {}, onDelete = {}, onRegenerate = {}, onCancelReply = {}, onFailedClick = {}, onImageClick = {}, onAvatarClick = {},
                        onQuote = if (inCall) null else { message -> calls.quoted += message.id },
                        onOpenQuote = { calls.opened += it.id },
                    ),
                )
            }
        }
    }

    @Test
    fun menu_quoteForTextAndTranscribedVoice() {
        val calls = Calls()
        show(calls)
        compose.onNodeWithText("周末去爬山吗").performTouchInput { longClick() }
        compose.onNodeWithText("引用").performClick()
        compose.onNodeWithContentDescription("语音，2 秒", substring = true).performTouchInput { longClick() }
        compose.onNodeWithText("引用").performClick()
        assertEquals(listOf(1L, 2L), calls.quoted)
    }

    @Test
    fun menu_noQuoteForFailed() {
        show(Calls())
        compose.onNodeWithText("没发出去").performTouchInput { longClick() }
        compose.onNodeWithText("删除").assertExists()
        compose.onNodeWithText("引用").assertDoesNotExist()
    }

    @Test
    fun menu_noQuoteForImage() {
        show(Calls())
        compose.onNodeWithContentDescription("[图片]").performTouchInput { longClick() }
        compose.onNodeWithText("删除").assertExists()
        compose.onNodeWithText("引用").assertDoesNotExist()
    }

    @Test
    fun menu_noQuoteDuringCall() {
        show(Calls(), inCall = true)
        compose.onNodeWithText("周末去爬山吗").performTouchInput { longClick() }
        compose.onNodeWithText("复制").assertExists()
        compose.onNodeWithText("引用").assertDoesNotExist()
    }

    @Test
    fun quoteBlock_nameAndOneLineText_tapOpensOriginal() {
        val calls = Calls()
        show(calls)
        compose.onNodeWithText("小林：周末去 爬山吗").performClick()
        compose.onNodeWithText("阿哲：[语音] 我说的语音").performClick()
        assertEquals(listOf(5L, 6L), calls.opened)
    }

    @Test
    fun quoteBlock_longPressOpensTheSameMenu() {
        val calls = Calls()
        show(calls)
        compose.onNodeWithText("小林：周末去 爬山吗").performTouchInput { longClick() }
        compose.onNodeWithText("复制").assertExists()
        assertEquals(emptyList<Long>(), calls.opened)
    }

    @Test
    fun quoteBar_closeCancels() {
        var closed = 0
        compose.setContent { AIChatTheme(darkTheme = false) { QuoteBar("小林：周末去爬山吗", onClose = { closed++ }) } }
        compose.onNodeWithText("小林：周末去爬山吗").assertExists()
        compose.onNodeWithContentDescription("取消引用").performClick()
        assertEquals(1, closed)
    }
}
