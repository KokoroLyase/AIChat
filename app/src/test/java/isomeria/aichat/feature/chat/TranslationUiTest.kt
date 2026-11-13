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
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.9.0 翻译：有字的文字和语音转写长按有“翻译”，只有表情的没有；显示译文时菜单换成“隐藏翻译”；
 * 译文带“翻译”标识，长按可复制、隐藏；正在翻译显示“正在翻译…”；失败显示原因，点一下重试。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class TranslationUiTest {
    @get:Rule val compose = createComposeRule()

    private val contact = Contact(id = 1, name = "小林", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1)
    private val text = Message(id = 1, contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "周末去爬山吗", status = MessageStatus.SUCCESS, createdAt = 100)
    private val emoji = Message(id = 2, contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "👍👍", status = MessageStatus.SUCCESS, createdAt = 200)
    private val translated = Message(
        id = 3, contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "Sure", status = MessageStatus.SUCCESS, createdAt = 300,
        translation = "好的", translationShown = true,
    )
    private val loading = Message(id = 4, contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "翻译中的那条", status = MessageStatus.SUCCESS, createdAt = 400)
    private val failed = Message(id = 5, contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "失败的那条", status = MessageStatus.SUCCESS, createdAt = 500)

    private class Calls {
        val translated = mutableListOf<Long>()
        val hidden = mutableListOf<Long>()
        val copied = mutableListOf<String>()
    }

    private fun show(calls: Calls) {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ChatMessageList(
                    rows = listOf(failed, loading, translated, emoji, text).map { ChatRow.Item(it) },
                    contact = contact,
                    myName = "阿哲",
                    myAvatarPath = null,
                    waitingReply = false,
                    actions = MessageActions(
                        onCopy = { calls.copied += it }, onDelete = {}, onRegenerate = {}, onCancelReply = {}, onFailedClick = {}, onImageClick = {}, onAvatarClick = {},
                        onTranslate = { calls.translated += it.id },
                        onHideTranslation = { calls.hidden += it.id },
                    ),
                    translating = mapOf(4L to TranslationUi.Loading, 5L to TranslationUi.Failed(FailReason.NETWORK)),
                )
            }
        }
    }

    @Test
    fun menu_translateForText_hideWhenShown() {
        val calls = Calls()
        show(calls)
        compose.onNodeWithText("周末去爬山吗").performTouchInput { longClick() }
        compose.onNodeWithText("翻译").performClick()
        compose.onNodeWithText("Sure").performTouchInput { longClick() }
        compose.onNodeWithText("隐藏翻译").performClick()
        assertEquals(listOf(1L), calls.translated)
        assertEquals(listOf(3L), calls.hidden)
    }

    @Test
    fun menu_noTranslateForEmojiOnly() {
        show(Calls())
        compose.onNodeWithText("👍👍").performTouchInput { longClick() }
        compose.onNodeWithText("删除").assertExists()
        compose.onNodeWithText("翻译").assertDoesNotExist()
    }

    @Test
    fun translationBlock_labelled_longPressCopyAndHide() {
        val calls = Calls()
        show(calls)
        compose.onNodeWithContentDescription("译文：好的").assertExists()
        compose.onNodeWithContentDescription("译文：好的").performTouchInput { longClick() }
        compose.onNodeWithText("复制").performClick()
        compose.onNodeWithContentDescription("译文：好的").performTouchInput { longClick() }
        compose.onNodeWithText("隐藏翻译").performClick()
        assertEquals(listOf("好的"), calls.copied)
        assertEquals(listOf(3L), calls.hidden)
    }

    @Test
    fun loadingAndFailure_tapRetries() {
        val calls = Calls()
        show(calls)
        compose.onNodeWithText("正在翻译…").assertExists()
        compose.onNodeWithText("翻译失败", substring = true).assertExists()
        compose.onNodeWithText("点一下重试", substring = true).performClick()
        assertEquals(listOf(5L), calls.translated)
    }
}
