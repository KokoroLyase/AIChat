package isomeria.aichat.data.repo

import isomeria.aichat.core.CallTranscript
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageType

/**
 * 读聊天记录给模型（回复、主动聊天、记忆）时，把通话展开成每一条话（0.8.0，见 [CallTranscript]）。
 * 没有通话时结果和直接查 [isomeria.aichat.data.db.MessageDao] 完全一样（[recent]、[before] 不多查数据库）。
 */
class CallHistory(private val db: AppDatabase) {
    private val messages get() = db.messageDao()
    private val calls get() = db.callDao()

    /** 最近 [limit] 条（通话按一条算），展开后按时间正序 */
    suspend fun recent(contactId: Long, limit: Int): List<Message> = expand(messages.recent(contactId, limit))

    /** 晚于 [time] 的聊天：开始得更早、但后半段晚于 [time] 的通话，只取晚于 [time] 的那几条 */
    suspend fun after(contactId: Long, time: Long): List<Message> {
        val spanning = calls.callsSpanning(contactId, time)
        val rows = if (spanning.isEmpty()) messages.after(contactId, time) else (spanning + messages.after(contactId, time))
        if (spanning.isEmpty() && rows.none { it.type == MessageType.CALL }) return rows
        return expand(rows).filter { it.createdAt > time }.sortedBy { it.createdAt }
    }

    /** 早于 [time] 的最近 [limit] 条：跨过 [time] 的通话只取早于 [time] 的那几条 */
    suspend fun before(contactId: Long, time: Long, limit: Int): List<Message> {
        val rows = messages.before(contactId, time, limit)
        if (rows.none { it.type == MessageType.CALL }) return rows
        return expand(rows).filter { it.createdAt < time }
    }

    suspend fun expand(rows: List<Message>): List<Message> {
        val ids = rows.filter { it.type == MessageType.CALL }.map { it.id }
        if (ids.isEmpty()) return rows
        return CallTranscript.expand(rows, calls.turnsOf(ids).groupBy { it.messageId })
    }
}
