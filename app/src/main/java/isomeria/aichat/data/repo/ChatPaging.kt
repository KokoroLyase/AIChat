package isomeria.aichat.data.repo

import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.room.withTransaction
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest

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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun timeline(contactId: Long): Flow<ChatTimeline> = messages.observeLatest(contactId).transformLatest {
        while (true) {
            val now = System.currentTimeMillis()
            val (snapshot, next) = db.withTransaction {
                val latest = messages.latest(contactId)
                val visible = messages.latestVisible(contactId, now)
                val waiting = messages.hasSending(contactId)
                val scheduled = messages.nextRevealTime(contactId, now)
                val previous = latest?.takeIf { it.role == isomeria.aichat.data.db.Role.ASSISTANT }?.let {
                    messages.previousAssistant(contactId, it.id, it.replyGroup)
                }
                val allowed = latest?.role == isomeria.aichat.data.db.Role.ASSISTANT && !waiting && scheduled == null &&
                    messages.hasUserAfter(contactId, previous?.createdAt ?: Long.MIN_VALUE, previous?.id ?: Long.MIN_VALUE)
                ChatTimeline(latest, visible, waiting, scheduled != null, now, allowed) to scheduled
            }
            emit(snapshot)
            if (next == null) break
            delay((next - System.currentTimeMillis()).coerceAtLeast(1))
        }
    }.flowOn(Dispatchers.IO)
}
