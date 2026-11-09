package isomeria.aichat.data.repo

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.withTransaction
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest

/**
 * Room 的分页源把 anchorPosition 当成数据库偏移，但关闭占位后它只是已加载窗口里的下标。
 * 窗口不在最新一端时（搜索定位、往上翻很远），一次消息表写入就会刷新到错误的位置，列表跳走。
 * 这里用 Page.itemsBefore 换算成数据库偏移，其余全部交给 Room 生成的分页源。
 */
class AbsoluteRefreshPagingSource<V : Any>(private val source: PagingSource<Int, V>) : PagingSource<Int, V>() {
    init {
        source.registerInvalidatedCallback(::invalidate)
        registerInvalidatedCallback(source::invalidate)
    }

    override val jumpingSupported: Boolean get() = source.jumpingSupported
    override val keyReuseSupported: Boolean get() = source.keyReuseSupported

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, V> = source.load(params)

    override fun getRefreshKey(state: PagingState<Int, V>): Int? {
        val anchor = state.anchorPosition ?: return null
        var skipped = 0
        for (page in state.pages) {
            if (anchor < skipped + page.data.size) {
                if (page.itemsBefore == LoadResult.Page.COUNT_UNDEFINED) return null
                return maxOf(0, page.itemsBefore + (anchor - skipped) - state.config.initialLoadSize / 2)
            }
            skipped += page.data.size
        }
        return null
    }
}

data class ChatEntry(val offset: Int = 0, val targetId: Long = 0)
data class ChatTimeline(
    val latest: Message? = null,
    val visibleLatest: Message? = null,
    val waiting: Boolean = false,
    val pending: Boolean = false,
    val now: Long = 0,
    val regenerationAllowed: Boolean = false,
)

/** Entry and reveal queries contain no unbounded message list. Schema and request encoding stay unchanged. */
class ChatPaging(private val db: AppDatabase) {
    private val messages get() = db.messageDao()

    suspend fun entry(contactId: Long, focusId: Long): ChatEntry = db.withTransaction {
        val unread = db.conversationDao().get(contactId)?.unreadCount ?: 0
        val target = if (focusId != 0L) messages.get(focusId)?.takeIf { it.contactId == contactId }
        else if (unread > 0) messages.unreadAnchor(contactId, unread - 1) else null
        if (target == null) ChatEntry() else ChatEntry(messages.countNewer(contactId, target.createdAt, target.id), target.id)
    }

    /**
     * 聊天页顶部和列表需要的状态。0.5.0 起待投递的回复是 PENDING，送达时写库会触发重新计算，
     * 不再按时间轮询；[ChatTimeline.pending] 表示还有没送达的回复（标题显示「对方正在输入…」）。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun timeline(contactId: Long): Flow<ChatTimeline> = messages.observeLatest(contactId).mapLatest {
        db.withTransaction {
            val visible = messages.latestVisible(contactId)
            val waiting = messages.hasSending(contactId)
            val pending = messages.hasPending(contactId)
            val allowed = visible?.role == isomeria.aichat.data.db.Role.ASSISTANT && !waiting && !pending &&
                ChatMessageActions(db).regenerationTurn(contactId, visible).isNotEmpty()
            ChatTimeline(visible, visible, waiting, pending, System.currentTimeMillis(), allowed)
        }
    }.flowOn(Dispatchers.IO)
}
