package isomeria.aichat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT avatarPath FROM contact WHERE avatarPath IS NOT NULL")
    suspend fun avatarPaths(): List<String>

    @Query("SELECT * FROM contact ORDER BY name")
    fun observeAll(): Flow<List<Contact>>

    @Query("SELECT * FROM contact WHERE id = :id")
    fun observe(id: Long): Flow<Contact?>

    @Query("SELECT * FROM contact WHERE id = :id")
    suspend fun get(id: Long): Contact?

    @Query("SELECT * FROM contact ORDER BY id")
    suspend fun listAll(): List<Contact>

    /** [query] 需先经 [escapeLike] 转义 */
    @Query("""SELECT * FROM contact WHERE name LIKE '%' || :query || '%' ESCAPE '\' ORDER BY name LIMIT 50""")
    suspend fun search(query: String): List<Contact>

    @Insert
    suspend fun insertAll(contacts: List<Contact>): List<Long>

    @Query("DELETE FROM contact")
    suspend fun deleteAll()

    @Insert
    suspend fun insert(contact: Contact): Long

    @Update
    suspend fun update(contact: Contact)

    @Query("DELETE FROM contact WHERE id = :id")
    suspend fun delete(id: Long)

    /** 「我 → 全局记忆」里一键全部开启或关闭 */
    @Query("UPDATE contact SET useGlobalMemory = :on")
    suspend fun setUseGlobalMemoryForAll(on: Boolean)

    /** 「我 → 回复偏好」里一键全部开启或关闭 */
    @Query("UPDATE contact SET useReplyPreferences = :on")
    suspend fun setUseReplyPreferencesForAll(on: Boolean)

    /** 开启主动聊天的联系人数；为 0 时不注册任何后台任务 */
    @Query("SELECT COUNT(*) FROM contact WHERE proactiveEnabled = 1")
    suspend fun proactiveCount(): Int

    @Query("SELECT * FROM contact WHERE proactiveEnabled = 1 ORDER BY id")
    suspend fun proactiveContacts(): List<Contact>

    @Query("SELECT * FROM contact WHERE proactiveEnabled = 1 ORDER BY name")
    fun observeProactiveContacts(): Flow<List<Contact>>
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

    @Query("SELECT * FROM conversation ORDER BY contactId")
    suspend fun listAll(): List<Conversation>

    @Upsert
    suspend fun upsert(conversation: Conversation)

    @Upsert
    suspend fun upsertAll(conversations: List<Conversation>)

    @Query("DELETE FROM conversation")
    suspend fun deleteAll()

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

    @Query("UPDATE conversation SET memoryCursor = :cursor, memoryStart = :start WHERE contactId = :contactId")
    suspend fun setMemoryRange(contactId: Long, cursor: Long?, start: Long)

    @Query(
        """
        UPDATE conversation SET lastMessageTime = :time, lastMessagePreview = :preview
        WHERE contactId = :contactId
        """,
    )
    suspend fun setLastMessage(contactId: Long, time: Long, preview: String)

    @Query("UPDATE conversation SET muted = :muted WHERE contactId = :contactId")
    suspend fun setMuted(contactId: Long, muted: Boolean)

    @Query("UPDATE conversation SET backgroundPath = :path WHERE contactId = :contactId")
    suspend fun setBackground(contactId: Long, path: String?)

    @Query("SELECT backgroundPath FROM conversation WHERE backgroundPath IS NOT NULL")
    suspend fun backgroundPaths(): List<String>

    /** 删除聊天：只清会话状态，不删联系人。聊天背景一起去掉（文件由调用方删除），免打扰保留 */
    @Query(
        """
        UPDATE conversation SET pinned = 0, unreadCount = 0, markedUnread = 0, draft = '',
               lastMessageTime = 0, lastMessagePreview = '', backgroundPath = NULL, proactiveUnanswered = 0
        WHERE contactId = :contactId
        """,
    )
    suspend fun reset(contactId: Long)

    /** 清空聊天记录：会话留在列表里（置顶、草稿、免打扰和聊天背景保留），只清掉预览和未读 */
    @Query(
        """
        UPDATE conversation SET unreadCount = 0, markedUnread = 0, lastMessagePreview = '', proactiveUnanswered = 0
        WHERE contactId = :contactId
        """,
    )
    suspend fun clearHistory(contactId: Long)

    /** 清空所有聊天记录：所有会话都像「删除该聊天」一样重置，联系人保留；免打扰和聊天背景属于聊天设置，保留 */
    @Query(
        """
        UPDATE conversation SET pinned = 0, unreadCount = 0, markedUnread = 0, draft = '',
               lastMessageTime = 0, lastMessagePreview = '', proactiveUnanswered = 0
        """,
    )
    suspend fun resetAll()

    /** 一次主动发送已经生成：连续未回复次数 +1，记下时间 */
    @Query("UPDATE conversation SET proactiveUnanswered = proactiveUnanswered + 1, lastProactiveAt = :time WHERE contactId = :contactId")
    suspend fun addProactiveSent(contactId: Long, time: Long)

    /** 发起过一次主动请求（[SKIP]、失败或被放弃也算）：只记时间，不算一次发送 */
    @Query("UPDATE conversation SET lastProactiveAt = :time WHERE contactId = :contactId")
    suspend fun setLastProactiveAt(contactId: Long, time: Long)

    /** 用户发言了：主动聊天从暂停中恢复 */
    @Query("UPDATE conversation SET proactiveUnanswered = 0 WHERE contactId = :contactId")
    suspend fun clearProactiveUnanswered(contactId: Long)
}

@Dao
interface MessageDao {
    /** 聊天页分页：不含待投递的回复 */
    @Query("SELECT * FROM message WHERE contactId = :contactId AND status != 'PENDING' ORDER BY createdAt DESC, id DESC")
    fun pagingByContact(contactId: Long): androidx.paging.PagingSource<Int, Message>

    @Query("SELECT * FROM message WHERE contactId = :contactId ORDER BY createdAt DESC, id DESC LIMIT 1")
    fun observeLatest(contactId: Long): Flow<Message?>

    /** 已经出现在聊天里的最新一条（不含待投递的） */
    @Query("SELECT * FROM message WHERE contactId = :contactId AND status != 'PENDING' ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestVisible(contactId: Long): Message?

    @Query("SELECT EXISTS(SELECT 1 FROM message WHERE contactId = :contactId AND status = 'PENDING')")
    suspend fun hasPending(contactId: Long): Boolean

    @Query("SELECT MIN(createdAt) FROM message WHERE contactId = :contactId AND status = 'PENDING'")
    suspend fun nextPendingAt(contactId: Long): Long?

    /** 最后一条待投递消息的计划时间；比现在晚就说明还要等 */
    @Query("SELECT MAX(createdAt) FROM message WHERE contactId = :contactId AND status = 'PENDING'")
    suspend fun lastPendingAt(contactId: Long): Long?

    /** 到了计划时间、该送达的待投递回复，按时间正序 */
    @Query("SELECT * FROM message WHERE contactId = :contactId AND status = 'PENDING' AND createdAt <= :now ORDER BY createdAt, id")
    suspend fun duePending(contactId: Long, now: Long): List<Message>

    @Query("SELECT DISTINCT contactId FROM message WHERE status = 'PENDING'")
    suspend fun contactsWithPending(): List<Long>

    /** 同一次回复里已经送达的几条，按时间正序：通知内容随投递更新 */
    @Query("SELECT content FROM message WHERE contactId = :contactId AND replyGroup = :group AND status = 'SUCCESS' ORDER BY createdAt, id")
    suspend fun deliveredParts(contactId: Long, group: String): List<String>

    /** 同一次回复的第一条（不论是否已送达） */
    @Query("SELECT * FROM message WHERE contactId = :contactId AND replyGroup = :group ORDER BY createdAt, id LIMIT 1")
    suspend fun firstOfGroup(contactId: Long, group: String): Message?

    /** 已经得到回复的最新一条用户消息：它之后失败的消息才需要重试 */
    @Query("SELECT * FROM message WHERE contactId = :contactId AND role = 'USER' AND status = 'SUCCESS' ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestAnsweredUser(contactId: Long): Message?

    @Query("SELECT EXISTS(SELECT 1 FROM message WHERE contactId = :contactId AND role = 'USER' AND status = 'SENDING')")
    suspend fun hasSending(contactId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM message WHERE status = 'SENDING')")
    suspend fun hasAnySending(): Boolean

    @Query("SELECT * FROM message WHERE contactId = :contactId AND role = 'ASSISTANT' ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestAssistant(contactId: Long): Message?

    @Query("SELECT * FROM message WHERE contactId = :contactId AND role = 'ASSISTANT' AND status != 'PENDING' AND id != :lastId AND (:group IS NULL OR replyGroup IS NULL OR replyGroup != :group) ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun previousAssistant(contactId: Long, lastId: Long, group: String?): Message?

    @Query("SELECT EXISTS(SELECT 1 FROM message WHERE contactId = :contactId AND role = 'USER' AND (createdAt > :time OR (createdAt = :time AND id > :id)))")
    suspend fun hasUserAfter(contactId: Long, time: Long, id: Long): Boolean

    /** 第一条未读：倒数第 offset+1 条已送达的回复 */
    @Query("SELECT * FROM message WHERE contactId = :contactId AND role = 'ASSISTANT' AND status != 'PENDING' ORDER BY createdAt DESC, id DESC LIMIT 1 OFFSET :offset")
    suspend fun unreadAnchor(contactId: Long, offset: Int): Message?

    /** 比某条新的条数，和聊天页分页同一个可见范围（不含待投递），用作定位偏移 */
    @Query("SELECT COUNT(*) FROM message WHERE contactId = :contactId AND status != 'PENDING' AND (createdAt > :time OR (createdAt = :time AND id > :id))")
    suspend fun countNewer(contactId: Long, time: Long, id: Long): Int

    /** 新消息浮标的条数：比看过的那条新、已经出现的 */
    @Query("SELECT COUNT(*) FROM message WHERE contactId = :contactId AND status != 'PENDING' AND (createdAt > :time OR (createdAt = :time AND id > :id))")
    suspend fun countNewerVisible(contactId: Long, time: Long, id: Long): Int

    @Query("SELECT id FROM message WHERE contactId = :contactId AND role = 'USER' AND (createdAt > :time OR (createdAt = :time AND id > :id)) AND (:status IS NULL OR status = :status) ORDER BY createdAt, id")
    suspend fun trailingUserIds(contactId: Long, time: Long, id: Long, status: MessageStatus?): List<Long>

    @Query("DELETE FROM message WHERE contactId = :contactId AND replyGroup = :group")
    suspend fun deleteReplyGroup(contactId: Long, group: String)

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

    /** 比 [time] 新的消息，按时间正序：还没总结进记忆的部分 */
    @Query("SELECT * FROM message WHERE contactId = :contactId AND createdAt > :time ORDER BY createdAt, id")
    suspend fun after(contactId: Long, time: Long): List<Message>

    /** 比 [time] 早的最近 [limit] 条，按时间正序：记忆页「总结更早的聊天」 */
    @Query(
        """
        SELECT * FROM (
            SELECT * FROM message WHERE contactId = :contactId AND createdAt < :time
            ORDER BY createdAt DESC, id DESC LIMIT :limit
        ) ORDER BY createdAt, id
        """,
    )
    suspend fun before(contactId: Long, time: Long, limit: Int): List<Message>

    @Query("SELECT COUNT(*) FROM message WHERE contactId = :contactId AND status != 'PENDING' AND createdAt < :time")
    fun observeCountBefore(contactId: Long, time: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM message WHERE contactId = :contactId AND status != 'PENDING' AND createdAt < :time")
    suspend fun countBefore(contactId: Long, time: Long): Int

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: Long): Message?

    @Insert
    suspend fun insert(message: Message): Long

    @Insert
    suspend fun insertAll(messages: List<Message>): List<Long>

    @Query("SELECT id FROM message WHERE contactId = :contactId AND role = 'USER' AND status = 'SENDING'")
    suspend fun sendingIds(contactId: Long): List<Long>

    /** 等待回复的消息里最新一条的时间：回复偏好的「稍等几秒」从这里算 */
    @Query("SELECT MAX(createdAt) FROM message WHERE contactId = :contactId AND role = 'USER' AND status = 'SENDING'")
    suspend fun latestSendingAt(contactId: Long): Long?

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

    @Query("SELECT imagePath FROM message WHERE imagePath IS NOT NULL")
    suspend fun allImagePaths(): List<String>

    @Query("SELECT * FROM message ORDER BY id")
    suspend fun listAll(): List<Message>

    @Query("SELECT id, contactId, createdAt FROM message")
    suspend fun allKeys(): List<MessageKey>

    @Query("DELETE FROM message")
    suspend fun deleteAll()

    @Query("UPDATE message SET status = 'FAILED', errorCode = :errorCode WHERE status = 'SENDING'")
    suspend fun failAllSending(errorCode: Int)

    @Query("SELECT imagePath FROM message WHERE id = :id")
    suspend fun imagePath(id: Long): String?

    /** 全局搜索：命中的聊天记录按联系人分组，最近命中的在前。[query] 需先经 [escapeLike] 转义 */
    @Query(
        """
        SELECT message.contactId AS contactId, COUNT(*) AS hitCount, MAX(message.createdAt) AS lastTime,
               contact.name AS contactName, contact.avatarPath AS contactAvatarPath,
               contact.avatarColor AS contactAvatarColor
        FROM message JOIN contact ON contact.id = message.contactId
        WHERE message.type = 'TEXT' AND message.status != 'PENDING' AND message.content LIKE '%' || :query || '%' ESCAPE '\'
        GROUP BY message.contactId
        ORDER BY lastTime DESC
        """,
    )
    suspend fun searchGrouped(query: String): List<MessageSearchGroup>

    /** 某个聊天里命中的文字消息，最新的在前。[query] 需先经 [escapeLike] 转义 */
    @Query(
        """
        SELECT * FROM message
        WHERE contactId = :contactId AND type = 'TEXT' AND status != 'PENDING' AND content LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY createdAt DESC, id DESC LIMIT :limit
        """,
    )
    suspend fun searchInChat(contactId: Long, query: String, limit: Int = 500): List<Message>
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM provider_config WHERE kind = 'LLM' ORDER BY id")
    fun observeLlmList(): Flow<List<ProviderConfig>>

    @Query("SELECT * FROM provider_config WHERE kind = 'LLM' ORDER BY id")
    suspend fun llmList(): List<ProviderConfig>

    @Query("SELECT * FROM provider_config WHERE id = :id")
    fun observe(id: Long): Flow<ProviderConfig?>

    @Query("SELECT * FROM provider_config WHERE id = :id")
    suspend fun get(id: Long): ProviderConfig?

    @Query("SELECT * FROM provider_config ORDER BY id")
    suspend fun listConfigs(): List<ProviderConfig>

    @Insert
    suspend fun insert(config: ProviderConfig): Long

    @Update
    suspend fun update(config: ProviderConfig)

    @Query("DELETE FROM provider_config WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM provider_config WHERE kind = 'LLM'")
    suspend fun deleteAllLlm()

    @Query("SELECT COUNT(*) FROM contact WHERE providerId = :providerId")
    suspend fun contactCount(providerId: Long): Int

    @Query("SELECT * FROM provider_model WHERE providerId = :providerId ORDER BY sortOrder, modelId")
    fun observeModels(providerId: Long): Flow<List<ProviderModel>>

    @Query("SELECT * FROM provider_model")
    fun observeAllModels(): Flow<List<ProviderModel>>

    @Query("SELECT * FROM provider_model WHERE providerId = :providerId ORDER BY sortOrder, modelId")
    suspend fun models(providerId: Long): List<ProviderModel>

    @Query("SELECT * FROM provider_model WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun model(providerId: Long, modelId: String): ProviderModel?

    @Upsert
    suspend fun upsertModels(models: List<ProviderModel>)

    @Query("DELETE FROM provider_model WHERE providerId = :providerId")
    suspend fun deleteModels(providerId: Long)

    /** 刷新模型列表：接口不再返回的模型删掉，手动添加的保留 */
    @Query("DELETE FROM provider_model WHERE providerId = :providerId AND manual = 0 AND modelId NOT IN (:keep)")
    suspend fun deleteModelsExcept(providerId: Long, keep: List<String>)

    @Query("DELETE FROM provider_model WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun deleteModel(providerId: Long, modelId: String)

    @Query(
        """
        UPDATE provider_model SET imageInputUserMark = :mark
        WHERE providerId = :providerId AND modelId = :modelId
        """,
    )
    suspend fun setImageMark(providerId: Long, modelId: String, mark: Boolean)

    @Query(
        """
        UPDATE provider_model SET thinkingUserMark = :mark
        WHERE providerId = :providerId AND modelId = :modelId
        """,
    )
    suspend fun setThinkingMark(providerId: Long, modelId: String, mark: Boolean)
}

@Dao
interface MemoryDao {
    /** 按聊天时间正序 */
    @Query("SELECT * FROM memory WHERE contactId = :contactId ORDER BY fromTime, id")
    fun observeByContact(contactId: Long): Flow<List<Memory>>

    @Query("SELECT * FROM memory WHERE contactId = :contactId ORDER BY fromTime, id")
    suspend fun listByContact(contactId: Long): List<Memory>

    @Query("SELECT COUNT(*) FROM memory WHERE contactId = :contactId")
    fun observeCount(contactId: Long): Flow<Int>

    @Query("SELECT * FROM memory WHERE id = :id")
    suspend fun get(id: Long): Memory?

    @Query("SELECT * FROM memory ORDER BY id")
    suspend fun listAll(): List<Memory>

    @Insert
    suspend fun insert(memory: Memory): Long

    @Insert
    suspend fun insertAll(memories: List<Memory>): List<Long>

    @Update
    suspend fun update(memory: Memory)

    @Query("DELETE FROM memory WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM memory WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("DELETE FROM memory WHERE contactId = :contactId")
    suspend fun deleteByContact(contactId: Long)

    @Query("DELETE FROM memory")
    suspend fun deleteAll()
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage_daily ORDER BY day DESC, contactId, purpose")
    fun observeAll(): Flow<List<UsageDaily>>

    @Query("SELECT * FROM usage_daily ORDER BY day, contactId, purpose")
    suspend fun listAll(): List<UsageDaily>

    /** 累加一次请求的用量 */
    @Query(
        """
        INSERT INTO usage_daily (day, contactId, purpose, promptTokens, completionTokens, requests)
        VALUES (:day, :contactId, :purpose, :promptTokens, :completionTokens, 1)
        ON CONFLICT (day, contactId, purpose) DO UPDATE SET
            promptTokens = promptTokens + excluded.promptTokens,
            completionTokens = completionTokens + excluded.completionTokens,
            requests = requests + 1
        """,
    )
    suspend fun add(day: Long, contactId: Long, purpose: UsagePurpose, promptTokens: Long, completionTokens: Long)

    /** 删除联系人时，把它的用量并到「已删除的联系人」，总量不变 */
    @Query(
        """
        INSERT INTO usage_daily (day, contactId, purpose, promptTokens, completionTokens, requests)
        SELECT day, 0, purpose, promptTokens, completionTokens, requests
        FROM usage_daily WHERE contactId = :contactId
        ON CONFLICT (day, contactId, purpose) DO UPDATE SET
            promptTokens = promptTokens + excluded.promptTokens,
            completionTokens = completionTokens + excluded.completionTokens,
            requests = requests + excluded.requests
        """,
    )
    suspend fun mergeIntoDeleted(contactId: Long)

    @Query("DELETE FROM usage_daily WHERE contactId = :contactId")
    suspend fun deleteByContact(contactId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(rows: List<UsageDaily>)

    @Query("DELETE FROM usage_daily")
    suspend fun deleteAll()
}

/** 主动聊天（0.6.0）的设备状态和约定提醒。 */
@Dao
interface ProactiveDao {
    @Query("SELECT * FROM proactive_state WHERE id = 1")
    suspend fun state(): ProactiveState?

    @Query("SELECT * FROM proactive_state WHERE id = 1")
    fun observeState(): Flow<ProactiveState?>

    @Upsert
    suspend fun saveState(state: ProactiveState)

    @Insert
    suspend fun insertReminder(reminder: ProactiveReminder): Long

    /** 到点的约定提醒，早的在前 */
    @Query("SELECT * FROM proactive_reminder WHERE dueAt <= :now ORDER BY dueAt, id")
    suspend fun dueReminders(now: Long): List<ProactiveReminder>

    @Query("SELECT MIN(dueAt) FROM proactive_reminder")
    suspend fun nextReminderAt(): Long?

    @Query("SELECT * FROM proactive_reminder WHERE contactId = :contactId ORDER BY dueAt, id")
    suspend fun remindersOf(contactId: Long): List<ProactiveReminder>

    @Query("SELECT * FROM proactive_reminder ORDER BY dueAt, id")
    fun observeReminders(): Flow<List<ProactiveReminder>>

    @Query("SELECT COUNT(*) FROM proactive_reminder")
    suspend fun reminderCount(): Int

    @Query("DELETE FROM proactive_reminder WHERE id = :id")
    suspend fun deleteReminder(id: Long)

    @Query("DELETE FROM proactive_reminder WHERE contactId = :contactId")
    suspend fun deleteRemindersOf(contactId: Long)

    /** 关闭主动聊天的联系人不再保留约定提醒 */
    @Query("DELETE FROM proactive_reminder WHERE contactId IN (SELECT id FROM contact WHERE proactiveEnabled = 0)")
    suspend fun deleteRemindersOfDisabled()

    @Query("DELETE FROM proactive_reminder")
    suspend fun deleteAllReminders()
}

/**
 * LIKE 查询的转义：把用户输入里的 %、_ 和反斜杠当作普通字符，配合 SQL 里的 ESCAPE（转义符为反斜杠）。
 * 否则搜「100%」会把所有含「100」的消息都搜出来。
 */
fun escapeLike(query: String): String = buildString(query.length) {
    for (c in query) {
        if (c == LIKE_ESCAPE || c == '%' || c == '_') append(LIKE_ESCAPE)
        append(c)
    }
}

private const val LIKE_ESCAPE = '\\'
