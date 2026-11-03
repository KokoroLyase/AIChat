package isomeria.aichat.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.filter
import androidx.paging.insertSeparators
import androidx.paging.map
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
import isomeria.aichat.data.repo.ChatEntry
import isomeria.aichat.data.repo.ChatTimeline
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.di.ApplicationScope
import isomeria.aichat.navigation.ChatRoute
import isomeria.aichat.ui.components.RECENT_EMOJI_LIMIT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
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
    /** 等待回复中，或分条回复还没全部出现：标题显示「对方正在输入...」 */
    val typing: Boolean = false,
    val waitingReply: Boolean = false,
    val supportsImage: Boolean = false,
    val hasApiKey: Boolean = true,
    val myAvatarPath: String? = null,
    val myName: String = "",
    val lastMessageMine: Boolean = false,
    val latestVisibleId: Long = 0,
)

sealed interface ChatEvent {
    data object ImageImportFailed : ChatEvent
    data class ScrollToLatest(val messageId: Long) : ChatEvent
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

    private val entryDeferred = viewModelScope.async(Dispatchers.IO) { chats.chatEntry(contactId, focusMessageId) }
    val entry: StateFlow<ChatEntry?> = flow { emit(entryDeferred.await()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val pageVersion = MutableStateFlow(0L)
    private val timeline = chats.timeline(contactId).stateIn(viewModelScope, SharingStarted.Eagerly, ChatTimeline())
    private val seenLatest = MutableStateFlow<Message?>(null)
    init { viewModelScope.launch { seenLatest.value = chats.latestVisible(contactId) } }

    val newMessageCount = combine(timeline, seenLatest) { current, seen ->
        if (seen == null) 0 else chats.newerVisibleCount(contactId, seen, current.now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val messages = pageVersion.flatMapLatest { version ->
        flow {
            val start = entryDeferred.await()
            emitAll(Pager(PagingConfig(pageSize = PAGE_SIZE, initialLoadSize = PAGE_SIZE, prefetchDistance = PAGE_SIZE, maxSize = 200, enablePlaceholders = false),
                initialKey = if (version == 0L) pagingStartKey(start.offset) else 0,
            ) { chats.pagedMessages(contactId) }.flow)
        }
    }.cachedIn(viewModelScope)

    val rows: Flow<PagingData<ChatRow>> = combine(messages, timeline) { data, current ->
        data.filter { it.createdAt <= current.now }
            .map { ChatRow.Item(it, canRegenerate(it, current)) }
            .insertSeparators<ChatRow.Item, ChatRow> { newer, older -> chatTimeSeparator(newer, older, formatter, current.now) }
    }.cachedIn(viewModelScope)

    fun markLatestSeen() { seenLatest.value = timeline.value.visibleLatest }

    fun goToLatest() { viewModelScope.launch { requestLatest() } }
    private suspend fun requestLatest() {
        val latest = chats.latestVisible(contactId)
        pageVersion.update { it + 1 }
        events.send(ChatEvent.ScrollToLatest(latest?.id ?: 0))
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
        timeline,
        supportsImage,
        hasApiKey,
        me,
    ) { contact, current, image, hasKey, (avatar, nickname) ->
        val typing = current.waiting || current.pending
        ChatUiState(
            loaded = true,
            contact = contact,
            typing = typing,
            waitingReply = current.waiting,
            supportsImage = image,
            hasApiKey = hasKey,
            myAvatarPath = avatar,
            myName = nickname,
            lastMessageMine = current.visibleLatest?.role == Role.USER,
            latestVisibleId = current.visibleLatest?.id ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatUiState())

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
        viewModelScope.launch { entryDeferred.await(); chats.markRead(contactId) }
    }

    fun saveDraft(text: String) {
        appScope.launch { chats.saveDraft(contactId, text) }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        appScope.launch { chats.sendText(contactId, text); requestLatest() }
    }

    fun sendImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        appScope.launch {
            try {
                val sent = chats.sendImages(contactId, uris)
                if (sent > 0) requestLatest()
                if (sent < uris.size) events.send(ChatEvent.ImageImportFailed)
            } finally { files.finishCamera() }
        }
    }

    fun newCameraUri(): Uri = files.newCameraUri()
    fun finishCamera() = files.finishCamera()

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

internal fun canRegenerate(message: Message, current: ChatTimeline): Boolean {
    val latest = current.latest ?: return false
    return current.regenerationAllowed && !current.waiting && !current.pending && latest.role == Role.ASSISTANT && message.role == Role.ASSISTANT &&
        (message.id == latest.id || (message.replyGroup != null && message.replyGroup == latest.replyGroup))
}

private const val PAGE_SIZE = 40

/** 首屏把定位目标放在窗口中间：目标下面也要有内容，才能滚到屏幕中间。 */
internal fun pagingStartKey(targetOffset: Int): Int = maxOf(0, targetOffset - PAGE_SIZE / 2)

/** Descending data: the time bar goes after its message, including at the oldest terminal edge. */
internal fun chatTimeSeparator(newer: ChatRow.Item?, older: ChatRow.Item?, formatter: ChatTimeFormatter, now: Long): ChatRow.TimeBar? {
    if (newer == null) return null
    return if (ChatTimeFormatter.needsTimeBar(older?.message?.createdAt, newer.message.createdAt))
        ChatRow.TimeBar(formatter.forChat(newer.message.createdAt, now), "t${newer.message.id}") else null
}
