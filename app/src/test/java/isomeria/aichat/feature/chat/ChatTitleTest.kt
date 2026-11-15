package isomeria.aichat.feature.chat

import isomeria.aichat.data.db.Contact
import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.9.2：等待回复、分条回复还没送完时，聊天页标题照旧是联系人名称，不再显示“对方正在输入…” */
class ChatTitleTest {
    private val contact = Contact(id = 1, name = "小林", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1)

    @Test fun waitingReply_titleStaysContactName() {
        val state = ChatUiState(loaded = true, contact = contact, typing = true, waitingReply = true)
        assertEquals("小林", chatTitle(state, "联系人已删除"))
    }

    @Test fun pendingDelivery_titleStaysContactName() {
        val state = ChatUiState(loaded = true, contact = contact, typing = true, waitingReply = false)
        assertEquals("小林", chatTitle(state, "联系人已删除"))
    }

    @Test fun missingContact_andLoading() {
        assertEquals("联系人已删除", chatTitle(ChatUiState(loaded = true, typing = true), "联系人已删除"))
        assertEquals("", chatTitle(ChatUiState(loaded = false, typing = true), "联系人已删除"))
    }
}
