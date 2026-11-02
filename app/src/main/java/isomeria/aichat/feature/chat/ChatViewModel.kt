package isomeria.aichat.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.reply.ActiveChatTracker
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.di.ApplicationScope
import isomeria.aichat.navigation.ChatRoute
import isomeria.aichat.ui.components.RECENT_EMOJI_LIMIT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ChatRow {
    val key: String

    data class TimeBar(val text: String, override val key: String) : ChatRow

    data class Item(
        val message: Message,
        /** 只对最后一次回复提供「重新生成」 */
        val canRegenerate: Boolean = false,
        override val key: String = "m${message.id}",
    ) : ChatRow
}

data class ChatUiState(
    val loaded: Boolean = false,
    val contact: Contact? = null,
    /** 按时间正序 */
    val rows: List<ChatRow> = emptyList(),
    /** 等待回复中，或分条回复还没全部出现：标题显示「对方正在输入...」 */
    val typing: Boolean = false,
    val supportsImage: Boolean = false,
    val hasApiKey: Boolean = true,
    val myAvatarPath: String? = null,
    val myName: String = "",
    val lastMessageMine: Boolean = false,
)

sealed interface ChatEvent {
    data object ImageImportFailed : ChatEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val chats: ChatRepository,
    contacts: ContactRepository,
    private val providers: ProviderRepository,
    private val settings: SettingsStore,
    private val files: FileStorage,
    private val formatter: ChatTimeFormatter,
    private val activeChat: ActiveChatTracker,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {
    private val route = savedState.toRoute<ChatRoute>()
    val contactId: Long = route.contactId

    /** 从搜索结果进来时要定位并高亮的消息；0 表示没有 */
    val focusMessageId: Long = route.focusMessageId

    private val events = Channel<ChatEvent>(Channel.BUFFERED)
    val eventFlow: Flow<ChatEvent> = events.receiveAsFlow()

    private val contactFlow = contacts.observeContact(contactId)

    /**
     * 分条回复写库时各条的时间依次相隔约 1 秒；这里只放出时间已到的消息，
     * 到点再放出下一条，聊天页上就像对方连发几条消息。
     */
    private val revealed: Flow<Pair<List<Message>, Boolean>> = chats.observeMessages(contactId).transformLatest { list ->
        while (true) {
            val now = System.currentTimeMillis()
            val visible = list.filter { it.createdAt <= now }
            val next = list.asSequence().filter { it.createdAt > now }.minOfOrNull { it.createdAt }
            emit(visible to (next != null))
            if (next == null) break
            delay((next - now).coerceAtLeast(1))
        }
    }

    private val supportsImage: Flow<Boolean> = contactFlow.flatMapLatest { contact ->
        if (contact == null) {
            flowOf(false)
        } else {
            providers.observeModels(contact.providerId).map { models ->
                models.firstOrNull { it.modelId == contact.model }?.supportsImage == true
            }
        }
    }

    /** 这个联系人用的服务商有没有 Key */
    private val hasApiKey: Flow<Boolean> = contactFlow.flatMapLatest { contact ->
        if (contact == null) {
            flowOf(true)
        } else {
            providers.observeConfig(contact.providerId).map { !it?.apiKeyEncrypted.isNullOrEmpty() }
        }
    }

    private val me = combine(settings.avatarPath, settings.nickname) { path, name -> path to name }

    val state: StateFlow<ChatUiState> = combine(
        contactFlow,
        revealed,
        supportsImage,
        hasApiKey,
        me,
    ) { contact, (messages, pending), image, hasKey, (avatar, nickname) ->
        val waiting = messages.any { it.role == Role.USER && it.status == MessageStatus.SENDING }
        val typing = waiting || pending
        ChatUiState(
            loaded = true,
            contact = contact,
            rows = buildRows(messages, typing),
            typing = typing,
            supportsImage = image,
            hasApiKey = hasKey,
            myAvatarPath = avatar,
            myName = nickname,
            lastMessageMine = messages.lastOrNull()?.role == Role.USER,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

    private fun buildRows(messages: List<Message>, typing: Boolean): List<ChatRow> {
        val now = System.currentTimeMillis()
        val lastReply = messages.lastOrNull { it.role == Role.ASSISTANT }
        val replyIsLast = lastReply != null && messages.last().role == Role.ASSISTANT
        val rows = ArrayList<ChatRow>(messages.size + 8)
        var previousTime: Long? = null
        for (message in messages) {
            if (ChatTimeFormatter.needsTimeBar(previousTime, message.createdAt)) {
                rows += ChatRow.TimeBar(formatter.forChat(message.createdAt, now), "t${message.id}")
            }
            previousTime = message.createdAt
            val inLastReply = replyIsLast && message.role == Role.ASSISTANT &&
                (message.id == lastReply.id || (message.replyGroup != null && message.replyGroup == lastReply.replyGroup))
            rows += ChatRow.Item(message, canRegenerate = inLastReply && !typing)
        }
        return rows
    }

    /** 进入时载入草稿 */
    suspend fun loadDraft(): String = chats.observeConversation(contactId).first()?.draft.orEmpty()

    fun onVisible() {
        activeChat.enter(contactId)
        markRead()
    }

    fun onHidden() {
        activeChat.leave(contactId)
    }

    fun markRead() {
        viewModelScope.launch { chats.markRead(contactId) }
    }

    fun saveDraft(text: String) {
        appScope.launch { chats.saveDraft(contactId, text) }
    }

    fun sendText(text: String) {
        appScope.launch { chats.sendText(contactId, text) }
    }

    fun sendImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        appScope.launch {
            val sent = chats.sendImages(contactId, uris)
            if (sent < uris.size) events.send(ChatEvent.ImageImportFailed)
        }
    }

    fun newCameraUri(): Uri = files.newCameraUri()

    /** 配置错误的原因在模型规则（没配完或字段不兼容）时返回该模型 id；其余情况在联系人页修正，返回 null。 */
    suspend fun modelToConfigure(): String? {
        val contact = state.value.contact ?: return null
        val config = providers.config(contact.providerId) ?: return null
        val model = providers.model(contact.providerId, contact.model) ?: return null
        val option = contact.thinkingOption ?: ModelRules.legacyOption(contact.thinking)
        return ModelRules.validate(config, model, option, contact.temperature)?.takeIf(ModelRules::fixedInModel)?.let { contact.model }
    }

    fun retry() {
        appScope.launch { chats.retry(contactId) }
    }

    fun regenerate() {
        appScope.launch { chats.regenerate(contactId) }
    }

    fun cancelReply() {
        appScope.launch { chats.cancelReply(contactId) }
    }

    fun delete(messageId: Long) {
        appScope.launch { chats.deleteMessage(messageId) }
    }

    /** 表情面板「最近使用」，最近的在前 */
    fun recentEmoji(): List<String> = settings.recentEmoji

    fun addRecentEmoji(emoji: String) {
        settings.recentEmoji = (listOf(emoji) + settings.recentEmoji.filter { it != emoji }).take(RECENT_EMOJI_LIMIT)
    }

    /** 上次键盘高度（px），面板用同样的高度 */
    var keyboardHeightPx: Int
        get() = settings.keyboardHeightPx
        set(value) {
            settings.keyboardHeightPx = value
        }

    /** 第一次发送消息时申请通知权限，只申请一次 */
    fun shouldAskNotificationPermission(): Boolean {
        if (settings.notificationPermissionAsked) return false
        settings.notificationPermissionAsked = true
        return true
    }

    override fun onCleared() {
        activeChat.leave(contactId)
    }
}
