package isomeria.aichat.data.repo

import androidx.room.withTransaction
import isomeria.aichat.core.Clock
import isomeria.aichat.core.GlobalMemoryEntry
import isomeria.aichat.core.PromptGlobals
import isomeria.aichat.core.ReplyPreferencesSource
import isomeria.aichat.core.forContact
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.GlobalMemoryCategory
import isomeria.aichat.data.db.GlobalMemoryItem
import isomeria.aichat.data.db.GlobalMemoryTopic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** 一个联系人的请求要带哪些 App 统一加的内容（全局记忆、回复偏好要求）。 */
fun interface PromptGlobalsSource {
    suspend fun forContact(contact: Contact): PromptGlobals

    companion object {
        /** 0.4.2 的行为：什么都不加 */
        val NONE = PromptGlobalsSource { PromptGlobals.NONE }
    }
}

/** 全局记忆的全部数据，各层都已按 sortOrder、id 排好。 */
data class GlobalMemoryTree(
    val categories: List<GlobalMemoryCategory> = emptyList(),
    val topics: List<GlobalMemoryTopic> = emptyList(),
    val items: List<GlobalMemoryItem> = emptyList(),
) {
    fun topicsOf(categoryId: Long): List<GlobalMemoryTopic> = topics.filter { it.categoryId == categoryId }

    fun itemsOf(topicId: Long): List<GlobalMemoryItem> = items.filter { it.topicId == topicId }

    fun itemsInCategory(categoryId: Long): List<GlobalMemoryItem> {
        val ids = topicsOf(categoryId).mapTo(HashSet()) { it.id }
        return items.filter { it.topicId in ids }
    }

    /** 发给模型的条目：已启用、不为空，按类别 → 主题 → 条目的顺序 */
    fun entries(): List<GlobalMemoryEntry> = categories.flatMap { category ->
        topicsOf(category.id).flatMap { topic ->
            itemsOf(topic.id).filter { it.enabled && it.content.isNotBlank() }
                .map { GlobalMemoryEntry(category.name, topic.name, it.content.trim()) }
        }
    }
}

/**
 * 「我 → 全局记忆」的增删改、排序，以及每次请求要带的全局内容。
 * 联系人开了「使用全局记忆」才带全局记忆，开了「使用回复偏好」才带回复要求，开了主动聊天才带约定时间的写法。
 */
@Singleton
class GlobalMemoryRepository @Inject constructor(
    private val db: AppDatabase,
    private val preferences: ReplyPreferencesSource,
    private val clock: Clock,
) : PromptGlobalsSource {
    private val dao get() = db.globalMemoryDao()

    val tree: Flow<GlobalMemoryTree> =
        combine(dao.observeCategories(), dao.observeTopics(), dao.observeItems()) { c, t, i -> GlobalMemoryTree(c, t, i) }

    suspend fun snapshot(): GlobalMemoryTree = db.withTransaction { GlobalMemoryTree(dao.categories(), dao.topics(), dao.items()) }

    override suspend fun forContact(contact: Contact): PromptGlobals = PromptGlobals(
        globalMemory = if (contact.useGlobalMemory) snapshot().entries() else emptyList(),
        replyStyle = preferences.forContact(contact),
        reminderHint = contact.proactiveEnabled,
    )

    // ---------- 类别 ----------

    suspend fun addCategory(name: String): Long = db.withTransaction {
        val now = clock.now()
        dao.insertCategory(GlobalMemoryCategory(name = name.clean(), sortOrder = dao.maxCategoryOrder() + 1, createdAt = now, updatedAt = now))
    }

    suspend fun renameCategory(id: Long, name: String) = db.withTransaction {
        val category = dao.category(id) ?: return@withTransaction
        dao.updateCategories(listOf(category.copy(name = name.clean(), updatedAt = clock.now())))
    }

    /** 删除类别：其中的主题和条目一起删（界面上先确认） */
    suspend fun deleteCategory(id: Long) = dao.deleteCategory(id)

    suspend fun moveCategory(id: Long, delta: Int) = db.withTransaction {
        val moved = reorder(dao.categories(), id, delta) ?: return@withTransaction
        dao.updateCategories(moved.mapIndexed { index, it -> it.copy(sortOrder = index) })
    }

    // ---------- 主题 ----------

    suspend fun addTopic(categoryId: Long, name: String): Long = db.withTransaction {
        val now = clock.now()
        dao.insertTopic(
            GlobalMemoryTopic(categoryId = categoryId, name = name.clean(), sortOrder = dao.maxTopicOrder(categoryId) + 1, createdAt = now, updatedAt = now),
        )
    }

    suspend fun renameTopic(id: Long, name: String) = db.withTransaction {
        val topic = dao.topic(id) ?: return@withTransaction
        dao.updateTopics(listOf(topic.copy(name = name.clean(), updatedAt = clock.now())))
    }

    suspend fun deleteTopic(id: Long) = dao.deleteTopic(id)

    suspend fun moveTopic(id: Long, delta: Int) = db.withTransaction {
        val topic = dao.topic(id) ?: return@withTransaction
        val moved = reorder(dao.topicsOf(topic.categoryId), id, delta) ?: return@withTransaction
        dao.updateTopics(moved.mapIndexed { index, it -> it.copy(sortOrder = index) })
    }

    // ---------- 条目 ----------

    suspend fun item(id: Long): GlobalMemoryItem? = dao.item(id)

    suspend fun topic(id: Long): GlobalMemoryTopic? = dao.topic(id)

    suspend fun category(id: Long): GlobalMemoryCategory? = dao.category(id)

    suspend fun addItem(topicId: Long, content: String, enabled: Boolean = true): Long = db.withTransaction {
        val now = clock.now()
        dao.insertItem(
            GlobalMemoryItem(
                topicId = topicId, content = content.cleanContent(), enabled = enabled,
                sortOrder = dao.maxItemOrder(topicId) + 1, createdAt = now, updatedAt = now,
            ),
        )
    }

    suspend fun updateItem(id: Long, content: String, enabled: Boolean) = db.withTransaction {
        val item = dao.item(id) ?: return@withTransaction
        dao.updateItems(listOf(item.copy(content = content.cleanContent(), enabled = enabled, updatedAt = clock.now())))
    }

    suspend fun setItemEnabled(id: Long, enabled: Boolean) = db.withTransaction {
        val item = dao.item(id) ?: return@withTransaction
        if (item.enabled != enabled) dao.updateItems(listOf(item.copy(enabled = enabled, updatedAt = clock.now())))
    }

    suspend fun deleteItem(id: Long) = dao.deleteItem(id)

    suspend fun moveItem(id: Long, delta: Int) = db.withTransaction {
        val item = dao.item(id) ?: return@withTransaction
        val moved = reorder(dao.itemsOf(item.topicId), id, delta) ?: return@withTransaction
        dao.updateItems(moved.mapIndexed { index, it -> it.copy(sortOrder = index) })
    }

    // ---------- 联系人开关 ----------

    suspend fun setUseGlobalMemoryForAll(on: Boolean) = db.contactDao().setUseGlobalMemoryForAll(on)

    suspend fun setUseReplyPreferencesForAll(on: Boolean) = db.contactDao().setUseReplyPreferencesForAll(on)

    private fun String.clean() = trim().take(GlobalMemoryCategory.NAME_MAX_LENGTH)

    private fun String.cleanContent() = trim().take(GlobalMemoryItem.CONTENT_MAX_LENGTH)

    companion object {
        /** 把 [id] 在同级里挪 [delta] 位；挪不动时返回 null。返回挪完后的完整顺序，调用方按下标重写 sortOrder */
        internal fun <T> reorder(siblings: List<T>, id: Long, delta: Int, idOf: (T) -> Long): List<T>? {
            val from = siblings.indexOfFirst { idOf(it) == id }
            if (from < 0) return null
            val to = (from + delta).coerceIn(0, siblings.lastIndex)
            if (to == from) return null
            return siblings.toMutableList().apply { add(to, removeAt(from)) }
        }

        private fun reorder(siblings: List<GlobalMemoryCategory>, id: Long, delta: Int) = reorder(siblings, id, delta) { it.id }

        @JvmName("reorderTopics")
        private fun reorder(siblings: List<GlobalMemoryTopic>, id: Long, delta: Int) = reorder(siblings, id, delta) { it.id }

        @JvmName("reorderItems")
        private fun reorder(siblings: List<GlobalMemoryItem>, id: Long, delta: Int) = reorder(siblings, id, delta) { it.id }
    }
}
