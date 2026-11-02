package isomeria.aichat.feature.chat

import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.core.TimeLabels
import isomeria.aichat.data.db.*
import isomeria.aichat.data.repo.ChatTimeline
import org.junit.Assert.*
import org.junit.Test

class ChatPagingRowsTest {
    private val formatter = ChatTimeFormatter(TimeLabels("yesterday", List(7) { "day$it" }, "%1\$d/%2\$d", "%1\$d/%2\$d/%3\$d"))
    private fun row(id: Long, time: Long, role: Role = Role.ASSISTANT, group: String? = null) = ChatRow.Item(
        Message(id = id, contactId = 1, role = role, type = MessageType.TEXT, status = MessageStatus.SUCCESS, createdAt = time, replyGroup = group))
    @Test fun separatorsUseOlderNeighborAndOldestTerminalEdge() {
        val newer = row(2, 500_000)
        val older = row(1, 100_000)
        assertEquals("t2", chatTimeSeparator(newer, older, formatter, 500_000)?.key)
        assertEquals("t1", chatTimeSeparator(older, null, formatter, 500_000)?.key)
        assertNull(chatTimeSeparator(null, newer, formatter, 500_000))
        assertNull(chatTimeSeparator(row(3, 600_000), newer, formatter, 600_000))
    }
    @Test fun regenerateOnlyAppliesToLastCompleteReplyGroup() {
        val latest = row(4, 4, group = "last").message
        val state = ChatTimeline(latest = latest)
        assertTrue(canRegenerate(latest, state))
        assertTrue(canRegenerate(row(3, 3, group = "last").message, state))
        assertFalse(canRegenerate(row(1, 1, group = "old").message, state))
        assertFalse(canRegenerate(latest, state.copy(waiting = true)))
        assertFalse(canRegenerate(latest, state.copy(pending = true)))
        assertFalse(canRegenerate(latest, state.copy(latest = row(5, 5, Role.USER).message)))
        assertEquals("m4", ChatRow.Item(latest).key)
    }
}
