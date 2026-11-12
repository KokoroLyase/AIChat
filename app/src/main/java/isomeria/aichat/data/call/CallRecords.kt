package isomeria.aichat.data.call

import androidx.room.withTransaction
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallEndReason
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.repo.MessagePreviewer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通话记录的读写（0.8.0）：开始时写一条通话消息，每说完一句写一条话，挂断时写总时长和原因；
 * App 在通话中被关掉时，下次启动按最后一条话的结束时间补完。
 */
@Singleton
class CallRecords @Inject constructor(
    private val db: AppDatabase,
    private val previews: MessagePreviewer,
) {
    private val messages get() = db.messageDao()
    private val conversations get() = db.conversationDao()
    private val calls get() = db.callDao()

    /** 开始一次通话：写通话消息（聊天里显示「通话中」），会话预览改成「[通话]」，未读清零；用户主动打电话，暂停的主动聊天恢复 */
    suspend fun start(contactId: Long, at: Long): Long = db.withTransaction {
        if (conversations.get(contactId) == null) conversations.upsert(Conversation(contactId = contactId))
        val message = Message(contactId = contactId, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, createdAt = at)
        val id = messages.insert(message)
        conversations.setLastMessage(contactId, at, previews.preview(message))
        conversations.markRead(contactId)
        conversations.clearProactiveUnanswered(contactId)
        id
    }

    suspend fun addTurn(messageId: Long, role: Role, text: String, audioPath: String?, audioMillis: Long, status: CallTurnStatus, at: Long): Long = db.withTransaction {
        calls.insert(CallTurn(messageId = messageId, seq = calls.nextSeq(messageId), role = role, text = text, audioPath = audioPath, audioMillis = audioMillis, status = status, createdAt = at))
    }

    suspend fun updateTurn(turn: CallTurn) = calls.update(turn.id, turn.text, turn.audioPath, turn.audioMillis, turn.status)

    suspend fun turns(messageId: Long): List<CallTurn> = calls.turns(messageId)

    /** 用户说了话：暂停的主动聊天恢复（和发消息一样） */
    suspend fun userSpoke(contactId: Long) = conversations.clearProactiveUnanswered(contactId)

    /** 挂断：写总时长（至少 1 毫秒）和原因，会话预览带上时长 */
    suspend fun finish(messageId: Long, millis: Long, reason: CallEndReason) = db.withTransaction {
        calls.finish(messageId, millis.coerceAtLeast(1), reason.code)
        val message = messages.get(messageId) ?: return@withTransaction
        val conversation = conversations.get(message.contactId) ?: return@withTransaction
        if (message.createdAt >= conversation.lastMessageTime) conversations.setLastMessage(message.contactId, message.createdAt, previews.preview(message))
    }

    /**
     * 启动时：还没写总时长、又不是正在进行的通话（App 在通话中被关掉），按最后一条话的结束时间补完，原因记为中断。
     * 返回补完的几次通话所属的联系人
     */
    suspend fun recoverInterrupted(active: Long?): List<Long> {
        val result = ArrayList<Long>()
        for (call in calls.unfinishedCalls()) {
            if (call.id == active) continue
            val end = calls.lastEndAt(call.id) ?: call.createdAt
            finish(call.id, end - call.createdAt, CallEndReason.INTERRUPTED)
            result += call.contactId
        }
        return result
    }
}
