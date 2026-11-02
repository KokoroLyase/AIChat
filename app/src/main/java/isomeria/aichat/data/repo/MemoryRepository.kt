package isomeria.aichat.data.repo

import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.data.db.ConversationDao
import isomeria.aichat.data.db.Memory
import isomeria.aichat.data.db.MemoryDao
import isomeria.aichat.data.db.MessageDao
import isomeria.aichat.data.memory.BacklogResult
import isomeria.aichat.data.memory.MemoryEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/** 长期记忆：查看、编辑、删除，手动总结更早的聊天。页面只经过这里访问记忆。 */
@Singleton
class MemoryRepository @Inject constructor(
    private val memoryDao: MemoryDao,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val engine: MemoryEngine,
) {
    fun observe(contactId: Long): Flow<List<Memory>> = memoryDao.observeByContact(contactId)

    fun observeCount(contactId: Long): Flow<Int> = memoryDao.observeCount(contactId)

    /** 还没总结的更早消息有多少条 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBacklog(contactId: Long): Flow<Int> = conversationDao.observe(contactId)
        .flatMapLatest { conversation ->
            val start = conversation?.memoryStart ?: 0L
            when {
                conversation == null -> flowOf(0)
                // 还没确定起点时，所有消息都算；按钮会先确定起点再总结
                conversation.memoryCursor == null -> messageDao.observeCountBefore(contactId, Long.MAX_VALUE)
                start > 0 -> messageDao.observeCountBefore(contactId, start)
                else -> flowOf(0)
            }
        }
        .distinctUntilChanged()

    suspend fun get(id: Long): Memory? = memoryDao.get(id)

    /** 保存用户的修改；改过的记忆不会被自动合并 */
    suspend fun updateContent(id: Long, content: String) {
        val current = memoryDao.get(id) ?: return
        val text = content.trim().take(MemoryPolicy.CONTENT_MAX_LENGTH)
        if (text.isEmpty() || text == current.content) return
        memoryDao.update(current.copy(content = text, edited = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = memoryDao.delete(id)

    suspend fun clear(contactId: Long) = memoryDao.deleteByContact(contactId)

    suspend fun summarizeBacklog(contactId: Long): BacklogResult = engine.summarizeBacklog(contactId)
}
