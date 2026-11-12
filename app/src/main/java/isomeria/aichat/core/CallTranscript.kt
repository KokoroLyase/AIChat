package isomeria.aichat.core

import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role

/**
 * 通话在上下文和记忆里的样子（0.8.0）：一条通话消息展开成它的每一条话，像普通的聊天一样。
 *
 * - 用户的话按 user、联系人的话按 assistant，文字消息（TEXT），时间是这句开始的时间，顺序按 [CallTurn.seq]
 * - 没有文字的（识别失败）跳过；联系人放出来的句子（包括被打断的那句、合成失败但在通话页显示过的）都算
 * - 联系人连着说的几句是同一次回复：用同一个 replyGroup，按预算选上下文时要么都带、要么都不带
 * - 展开出来的行 id 是负数（-turn.id），只在内存里用，不写库
 * - 没有通话消息时原样返回同一个列表，聊天请求和 0.7.2 逐字一致
 */
object CallTranscript {
    fun expand(messages: List<Message>, turns: Map<Long, List<CallTurn>>): List<Message> {
        if (messages.none { it.type == MessageType.CALL }) return messages
        val result = ArrayList<Message>(messages.size)
        for (message in messages) {
            if (message.type != MessageType.CALL) {
                result += message
                continue
            }
            result += lines(message, turns[message.id].orEmpty())
        }
        return result
    }

    /** 一次通话展开成的几行 */
    fun lines(call: Message, turns: List<CallTurn>): List<Message> {
        val result = ArrayList<Message>(turns.size)
        var group: String? = null
        for (turn in turns.sortedWith(compareBy({ it.seq }, { it.id }))) {
            if (!spoken(turn)) {
                // 识别失败的那句也把联系人前后两次回复隔开
                group = null
                continue
            }
            group = if (turn.role == Role.ASSISTANT) group ?: "call-${call.id}-${turn.id}" else null
            result += Message(
                id = -turn.id,
                contactId = call.contactId,
                role = turn.role,
                type = MessageType.TEXT,
                content = turn.text,
                status = MessageStatus.SUCCESS,
                replyGroup = group,
                createdAt = turn.createdAt,
                origin = call.origin,
            )
        }
        return result
    }

    /** 进上下文和记忆的话：有文字，且不是识别失败的 */
    fun spoken(turn: CallTurn): Boolean = turn.text.isNotBlank() && turn.status != CallTurnStatus.ASR_FAILED
}
