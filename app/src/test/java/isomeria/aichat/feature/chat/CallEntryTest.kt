package isomeria.aichat.feature.chat

import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 点聊天里的通话消息（0.8.0）：只有正在进行的这通电话回到通话页，别的都看详情 */
class CallEntryTest {
    private val ongoing = Message(id = 1, contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, createdAt = 1)
    private val finished = ongoing.copy(voiceMillis = 9_000)

    @Test
    fun ongoingCallOfThisChat_backToTheCallPage() {
        assertTrue(opensCallPage(ongoing, inCall = true))
    }

    @Test
    fun finishedCall_opensDetail() {
        assertFalse(opensCallPage(finished, inCall = true))
        assertFalse(opensCallPage(finished, inCall = false))
    }

    @Test
    fun staleOngoingCall_notInCall_opensDetail_doesNotStartANewCall() {
        assertFalse(opensCallPage(ongoing, inCall = false))
    }
}
