package isomeria.aichat.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/*
 * 全局记忆（「我 → 全局记忆」，0.5.0）：类别 → 主题 → 条目三层，用户手写，
 * 开启「使用全局记忆」的联系人每次请求都带上已启用的条目。
 * 删除类别会级联删除其中的主题和条目（界面上先确认）。
 */

@Entity(tableName = "global_memory_category")
data class GlobalMemoryCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val NAME_MAX_LENGTH = 20
    }
}

@Entity(
    tableName = "global_memory_topic",
    foreignKeys = [
        ForeignKey(
            entity = GlobalMemoryCategory::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class GlobalMemoryTopic(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long,
    val name: String,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "global_memory_item",
    foreignKeys = [
        ForeignKey(
            entity = GlobalMemoryTopic::class,
            parentColumns = ["id"],
            childColumns = ["topicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["topicId"])],
)
data class GlobalMemoryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: Long,
    val content: String,
    /** 关闭的条目保留，但不发给模型 */
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val CONTENT_MAX_LENGTH = 2000
    }
}

@Dao
interface GlobalMemoryDao {
    @Query("SELECT * FROM global_memory_category ORDER BY sortOrder, id")
    fun observeCategories(): Flow<List<GlobalMemoryCategory>>

    @Query("SELECT * FROM global_memory_topic ORDER BY sortOrder, id")
    fun observeTopics(): Flow<List<GlobalMemoryTopic>>

    @Query("SELECT * FROM global_memory_item ORDER BY sortOrder, id")
    fun observeItems(): Flow<List<GlobalMemoryItem>>

    @Query("SELECT * FROM global_memory_category ORDER BY sortOrder, id")
    suspend fun categories(): List<GlobalMemoryCategory>

    @Query("SELECT * FROM global_memory_topic ORDER BY sortOrder, id")
    suspend fun topics(): List<GlobalMemoryTopic>

    @Query("SELECT * FROM global_memory_item ORDER BY sortOrder, id")
    suspend fun items(): List<GlobalMemoryItem>

    @Query("SELECT * FROM global_memory_topic WHERE categoryId = :categoryId ORDER BY sortOrder, id")
    suspend fun topicsOf(categoryId: Long): List<GlobalMemoryTopic>

    @Query("SELECT * FROM global_memory_item WHERE topicId = :topicId ORDER BY sortOrder, id")
    suspend fun itemsOf(topicId: Long): List<GlobalMemoryItem>

    @Query("SELECT * FROM global_memory_category WHERE id = :id")
    suspend fun category(id: Long): GlobalMemoryCategory?

    @Query("SELECT * FROM global_memory_topic WHERE id = :id")
    suspend fun topic(id: Long): GlobalMemoryTopic?

    @Query("SELECT * FROM global_memory_item WHERE id = :id")
    suspend fun item(id: Long): GlobalMemoryItem?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM global_memory_category")
    suspend fun maxCategoryOrder(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM global_memory_topic WHERE categoryId = :categoryId")
    suspend fun maxTopicOrder(categoryId: Long): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM global_memory_item WHERE topicId = :topicId")
    suspend fun maxItemOrder(topicId: Long): Int

    @Insert
    suspend fun insertCategory(category: GlobalMemoryCategory): Long

    @Insert
    suspend fun insertTopic(topic: GlobalMemoryTopic): Long

    @Insert
    suspend fun insertItem(item: GlobalMemoryItem): Long

    @Insert
    suspend fun insertCategories(categories: List<GlobalMemoryCategory>): List<Long>

    @Insert
    suspend fun insertTopics(topics: List<GlobalMemoryTopic>): List<Long>

    @Insert
    suspend fun insertItems(items: List<GlobalMemoryItem>): List<Long>

    @Update
    suspend fun updateCategories(categories: List<GlobalMemoryCategory>)

    @Update
    suspend fun updateTopics(topics: List<GlobalMemoryTopic>)

    @Update
    suspend fun updateItems(items: List<GlobalMemoryItem>)

    @Query("DELETE FROM global_memory_category WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("DELETE FROM global_memory_topic WHERE id = :id")
    suspend fun deleteTopic(id: Long)

    @Query("DELETE FROM global_memory_item WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("DELETE FROM global_memory_item")
    suspend fun deleteAllItems()

    @Query("DELETE FROM global_memory_topic")
    suspend fun deleteAllTopics()

    @Query("DELETE FROM global_memory_category")
    suspend fun deleteAllCategories()
}
