package isomeria.aichat.feature.favorites

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.core.TimeLabels
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Favorite
import isomeria.aichat.data.db.FavoriteTurn
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.repo.FavoriteEntry
import isomeria.aichat.feature.me.MeContent
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 0.9.0 收藏页：卡片显示文字、语音条和转写、通话的时长和前两句、来源联系人（不在了用存下的名字）；
 * 点开详情、点语音条播放、长按复制和删除；搜索文字、转写、通话里的话和联系人名字；“我”页的“收藏”在个人卡片下面单独一组。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class FavoritesUiTest {
    @get:Rule val compose = createComposeRule()

    companion object {
        private val contact = Contact(id = 1, name = "小林", avatarColor = 3, providerId = 1, model = "m", createdAt = 10, updatedAt = 10)

        private fun favorite(id: Long, type: MessageType, content: String = "", role: Role = Role.ASSISTANT, audio: String? = null, millis: Long = 0) =
            Favorite(id = id, type = type, role = role, contactId = 1, contactCreatedAt = 10, contactName = "旧名字", contactAvatarColor = 3, content = content, audioPath = audio, audioMillis = millis, messageId = id, messageCreatedAt = id * 100, createdAt = 1_000 - id)

        val text = FavoriteEntry(favorite(1, MessageType.TEXT, "周末去爬山吗"), contact, emptyList())
        val voice = FavoriteEntry(favorite(2, MessageType.VOICE, "语音的转写", Role.USER, audio = "/f/v.m4a", millis = 3_000), null, emptyList())
        val call = FavoriteEntry(
            favorite(3, MessageType.CALL, millis = 65_000),
            contact,
            listOf(
                FavoriteTurn(id = 1, favoriteId = 3, seq = 0, role = Role.USER, text = "在吗", createdAt = 1),
                FavoriteTurn(id = 2, favoriteId = 3, seq = 1, role = Role.ASSISTANT, text = "", status = CallTurnStatus.ASR_FAILED, createdAt = 2),
                FavoriteTurn(id = 3, favoriteId = 3, seq = 2, role = Role.ASSISTANT, text = "第三句不在预览里", createdAt = 3),
            ),
        )
    }

    private val formatter = ChatTimeFormatter(TimeLabels("昨天", List(7) { "星期$it" }, "%1\$d月%2\$d日", "%1\$d年%2\$d月%3\$d日"))

    private class Calls {
        val opened = mutableListOf<Long>()
        val played = mutableListOf<Long>()
        val copied = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()
    }

    private fun show(entries: List<FavoriteEntry>?, calls: Calls, query: String = "") {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                FavoritesContent(
                    entries = entries, query = query, searching = query.isNotEmpty(), playingId = null, formatter = formatter,
                    onBack = {}, onStartSearch = {}, onQueryChange = {}, onCancelSearch = {},
                    onOpen = { calls.opened += it }, onToggleVoice = { calls.played += it },
                    onCopy = { calls.copied += it.favorite.id }, onDelete = { calls.deleted += it.favorite.id },
                    now = 2_000,
                )
            }
        }
    }

    @Test
    fun cards_showPreviewAndSource() {
        show(listOf(text, voice, call), Calls())
        compose.onNodeWithText("周末去爬山吗").assertExists()
        compose.onNodeWithText("语音的转写").assertExists()
        compose.onNodeWithText("语音通话 · 01:05").assertExists()
        compose.onNodeWithText("我：在吗").assertExists()
        compose.onNodeWithText("小林：（没有识别出文字）").assertExists()
        compose.onNodeWithText("第三句不在预览里", substring = true).assertDoesNotExist()
        // 来源：还在的用现在的名字，不在的用存下的名字
        compose.onNodeWithText("旧名字").assertExists()
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("小林")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun click_opensDetail_longPress_copyAndDelete() {
        val calls = Calls()
        show(listOf(text, call), calls)
        compose.onNodeWithText("周末去爬山吗").performClick()
        assertEquals(listOf(1L), calls.opened)
        compose.onNodeWithText("周末去爬山吗").performTouchInput { longClick() }
        compose.onNodeWithText("复制").performClick()
        compose.onNodeWithText("语音通话 · 01:05").performTouchInput { longClick() }
        compose.onNodeWithText("删除").performClick()
        assertEquals(listOf(1L), calls.copied)
        assertEquals(listOf(3L), calls.deleted)
    }

    @Test
    fun emptyAndNoResult() {
        show(emptyList(), Calls())
        compose.onNodeWithText("还没有收藏", substring = true).assertExists()
    }

    @Test
    fun search_noResult() {
        show(emptyList(), Calls(), query = "火星")
        compose.onNodeWithText("没有找到相关的收藏").assertExists()
    }

    @Test
    fun matches_textTranscriptTurnsAndNames() {
        assertTrue(matches(text, "  爬山 "))
        assertTrue(matches(voice, "转写"))
        assertTrue(matches(call, "第三句"))
        assertTrue("现在的名字", matches(text, "小林"))
        assertTrue("收藏时的名字", matches(voice, "旧名"))
        assertFalse(matches(text, "火星"))
        assertTrue(matches(text, ""))
        assertTrue("不分大小写", matches(FavoriteEntry(text.favorite.copy(content = "Hello"), null, emptyList()), "hello"))
    }

    @Test
    fun voiceTurns_onlyVoiceWithAudio_idIsFavoriteId() {
        val noAudio = FavoriteEntry(voice.favorite.copy(id = 9, audioPath = null), null, emptyList())
        val turns = voiceTurns(listOf(text, voice, call, noAudio))
        assertEquals(listOf(2L), turns.map { it.id })
        assertEquals(3_000L, turns.single().audioMillis)
    }

    @Test
    fun callDetailOf_usesSnapshotWhenSourceGone() {
        val detail = callDetailOf(call.copy(source = null))
        assertEquals("旧名字", detail.contact?.name)
        assertNull(detail.contact?.avatarPath)
        assertEquals(65_000L, detail.call.voiceMillis)
        assertFalse("收藏的通话不是“通话中”", detail.call.callOngoing)
        assertEquals(listOf("在吗", "", "第三句不在预览里"), detail.turns.map { it.text })
    }

    @Test
    fun mePage_favoritesInOwnGroupBelowProfile() {
        var opened = 0
        compose.setContent { AIChatTheme(darkTheme = false) { MeContent("我", null, {}, {}, onOpenFavorites = { opened++ }) } }
        val favorites = compose.onNodeWithText("收藏").getUnclippedBoundsInRoot()
        val memory = compose.onNodeWithText("全局记忆").getUnclippedBoundsInRoot()
        assertTrue(favorites.bottom < memory.top)
        compose.onNodeWithText("收藏").performClick()
        assertEquals(1, opened)
    }
}
