package isomeria.aichat.feature.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationUi(
    val contactId: Long,
    val name: String,
    val avatarPath: String?,
    val avatarColor: Int,
    val preview: String,
    val isDraft: Boolean,
    val time: String,
    val unread: Int,
    val markedUnread: Boolean,
    val pinned: Boolean,
)

@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chats: ChatRepository,
    private val formatter: ChatTimeFormatter,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    /** 每分钟刷新一次，让「今天/昨天」等相对时间跟着变化 */
    private val clock = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    /** null 表示还在加载 */
    val items: StateFlow<List<ConversationUi>?> = combine(chats.observeConversations(), clock) { list, now ->
        list.map { item ->
            val c = item.conversation
            val hasDraft = c.draft.isNotBlank()
            ConversationUi(
                contactId = c.contactId,
                name = item.name,
                avatarPath = item.avatarPath,
                avatarColor = item.avatarColor,
                preview = (if (hasDraft) c.draft else c.lastMessagePreview).replace('\n', ' '),
                isDraft = hasDraft,
                time = if (c.lastMessageTime > 0) formatter.forList(c.lastMessageTime, now) else "",
                unread = c.unreadCount,
                markedUnread = c.markedUnread,
                pinned = c.pinned,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun togglePin(item: ConversationUi) {
        viewModelScope.launch { chats.setPinned(item.contactId, !item.pinned) }
    }

    fun toggleUnread(item: ConversationUi) {
        viewModelScope.launch {
            if (item.unread > 0 || item.markedUnread) chats.markRead(item.contactId) else chats.markUnread(item.contactId)
        }
    }

    fun delete(contactId: Long) {
        appScope.launch { chats.deleteConversation(contactId) }
    }
}
