package isomeria.aichat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contact ORDER BY name")
    fun observeAll(): Flow<List<Contact>>

    @Query("SELECT * FROM contact WHERE id = :id")
    fun observe(id: Long): Flow<Contact?>

    @Query("SELECT * FROM contact WHERE id = :id")
    suspend fun get(id: Long): Contact?

    @Query("SELECT * FROM contact WHERE name LIKE '%' || :query || '%' ORDER BY name LIMIT 50")
    suspend fun search(query: String): List<Contact>

    @Insert
    suspend fun insert(contact: Contact): Long

    @Update
    suspend fun update(contact: Contact)

    @Query("DELETE FROM contact WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ConversationDao {
    @Query(
        """
        SELECT conversation.*, contact.name AS name, contact.avatarPath AS avatarPath,
               contact.avatarColor AS avatarColor
        FROM conversation JOIN contact ON contact.id = conversation.contactId
        WHERE conversation.lastMessageTime > 0 OR conversation.draft != ''
        ORDER BY conversation.pinned DESC, conversation.lastMessageTime DESC
        """,
    )
    fun observeList(): Flow<List<ConversationItem>>

    @Query("SELECT * FROM conversation WHERE contactId = :contactId")
    fun observe(contactId: Long): Flow<Conversation?>

    @Query("SELECT * FROM conversation WHERE contactId = :contactId")
    suspend fun get(contactId: Long): Conversation?

    /** 未读总数：数字未读之和，加上只标了红点的会话数 */
    @Query(
        """
        SELECT COALESCE(SUM(unreadCount), 0)
             + COALESCE(SUM(CASE WHEN markedUnread = 1 AND unreadCount = 0 THEN 1 ELSE 0 END), 0)
        FROM conversation
        """,
    )
    fun observeTotalUnread(): Flow<Int>

    @Upsert
    suspend fun upsert(conversation: Conversation)

    @Query("UPDATE conversation SET pinned = :pinned WHERE contactId = :contactId")
    suspend fun setPinned(contactId: Long, pinned: Boolean)

    @Query("UPDATE conversation SET markedUnread = 1 WHERE contactId = :contactId")
    suspend fun markUnread(contactId: Long)

    @Query("UPDATE conversation SET unreadCount = 0, markedUnread = 0 WHERE contactId = :contactId")
    suspend fun markRead(contactId: Long)

    @Query("UPDATE conversation SET unreadCount = unreadCount + :count WHERE contactId = :contactId")
    suspend fun addUnread(contactId: Long, count: Int)

    @Query("UPDATE conversation SET draft = :draft WHERE contactId = :contactId")
    suspend fun setDraft(contactId: Long, draft: String)

    @Query(
        """
        UPDATE conversation SET lastMessageTime = :time, lastMessagePreview = :preview
        WHERE contactId = :contactId
        """,
    )
    suspend fun setLastMessage(contactId: Long, time: Long, preview: String)

    /** 删除聊天：只清会话状态，不删联系人 */
    @Query(
        """
        UPDATE conversation SET pinned = 0, unreadCount = 0, markedUnread = 0, draft = '',
               lastMessageTime = 0, lastMessagePreview = ''
        WHERE contactId = :contactId
        """,
    )
    suspend fun reset(contactId: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM message WHERE contactId = :contactId ORDER BY createdAt, id")
    fun observeByContact(contactId: Long): Flow<List<Message>>

    @Query("SELECT * FROM message WHERE contactId = :contactId ORDER BY createdAt, id")
    suspend fun listByContact(contactId: Long): List<Message>

    /** 最近 limit 条，按时间正序返回 */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM message WHERE contactId = :contactId ORDER BY createdAt DESC, id DESC LIMIT :limit
        ) ORDER BY createdAt, id
        """,
    )
    suspend fun recent(contactId: Long, limit: Int): List<Message>

    @Query("SELECT * FROM message WHERE contactId = :contactId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latest(contactId: Long): Message?

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: Long): Message?

    @Insert
    suspend fun insert(message: Message): Long

    @Insert
    suspend fun insertAll(messages: List<Message>): List<Long>

    @Query("SELECT id FROM message WHERE contactId = :contactId AND role = 'USER' AND status = 'SENDING'")
    suspend fun sendingIds(contactId: Long): List<Long>

    @Query("SELECT DISTINCT contactId FROM message WHERE status = 'SENDING'")
    suspend fun contactsWithSending(): List<Long>

    @Query("UPDATE message SET status = :status, errorCode = :errorCode WHERE id IN (:ids)")
    suspend fun setStatus(ids: List<Long>, status: MessageStatus, errorCode: Int)

    @Query(
        """
        UPDATE message SET status = 'FAILED', errorCode = :errorCode
        WHERE contactId = :contactId AND status = 'SENDING'
        """,
    )
    suspend fun failSending(contactId: Long, errorCode: Int)

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM message WHERE replyGroup = :group")
    suspend fun deleteGroup(group: String)

    @Query("DELETE FROM message WHERE contactId = :contactId")
    suspend fun deleteByContact(contactId: Long)

    @Query("SELECT imagePath FROM message WHERE contactId = :contactId AND imagePath IS NOT NULL")
    suspend fun imagePaths(contactId: Long): List<String>

    @Query("SELECT imagePath FROM message WHERE id = :id")
    suspend fun imagePath(id: Long): String?

    @Query(
        """
        SELECT message.*, contact.name AS contactName, contact.avatarPath AS contactAvatarPath,
               contact.avatarColor AS contactAvatarColor
        FROM message JOIN contact ON contact.id = message.contactId
        WHERE message.type = 'TEXT' AND message.content LIKE '%' || :query || '%'
        ORDER BY message.createdAt DESC LIMIT 50
        """,
    )
    suspend fun search(query: String): List<MessageSearchHit>
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM provider_config WHERE kind = 'LLM' ORDER BY id LIMIT 1")
    fun observeLlm(): Flow<ProviderConfig?>

    @Query("SELECT * FROM provider_config WHERE kind = 'LLM' ORDER BY id LIMIT 1")
    suspend fun getLlm(): ProviderConfig?

    @Query("SELECT * FROM provider_config WHERE id = :id")
    suspend fun get(id: Long): ProviderConfig?

    @Update
    suspend fun update(config: ProviderConfig)

    @Query("SELECT * FROM provider_model WHERE providerId = :providerId ORDER BY sortOrder, modelId")
    fun observeModels(providerId: Long): Flow<List<ProviderModel>>

    @Query("SELECT * FROM provider_model WHERE providerId = :providerId ORDER BY sortOrder, modelId")
    suspend fun models(providerId: Long): List<ProviderModel>

    @Query("SELECT * FROM provider_model WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun model(providerId: Long, modelId: String): ProviderModel?

    @Upsert
    suspend fun upsertModels(models: List<ProviderModel>)

    @Query("DELETE FROM provider_model WHERE providerId = :providerId AND modelId NOT IN (:keep)")
    suspend fun deleteModelsExcept(providerId: Long, keep: List<String>)

    @Query(
        """
        UPDATE provider_model SET imageInputUserMark = :mark
        WHERE providerId = :providerId AND modelId = :modelId
        """,
    )
    suspend fun setImageMark(providerId: Long, modelId: String, mark: Boolean)
}
