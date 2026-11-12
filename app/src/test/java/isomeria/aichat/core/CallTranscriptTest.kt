package isomeria.aichat.core

import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.8.0：通话在上下文和记忆里展开成每一条话。 */
class CallTranscriptTest {
    private fun text(id: Long, role: Role, content: String, at: Long) =
        Message(id = id, contactId = 1, role = role, type = MessageType.TEXT, content = content, status = MessageStatus.SUCCESS, createdAt = at)

    private val call = Message(id = 10, contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, voiceMillis = 9_000, createdAt = 1_000)

    private fun turn(id: Long, seq: Int, role: Role, text: String, at: Long, status: CallTurnStatus = CallTurnStatus.OK) =
        CallTurn(id = id, messageId = 10, seq = seq, role = role, text = text, status = status, createdAt = at)

    @Test
    fun noCall_returnsSameList() {
        val history = listOf(text(1, Role.USER, "你好", 10), text(2, Role.ASSISTANT, "嗨", 20))
        assertSame("没有通话时原样返回，请求和 0.7.2 一样", history, CallTranscript.expand(history, emptyMap()))
    }

    @Test
    fun call_expandsIntoTurnsInOrder_skippingFailedRecognition() {
        val turns = listOf(
            turn(3, 2, Role.ASSISTANT, "在呢", 1_900),
            turn(1, 0, Role.USER, "在吗", 1_100),
            turn(2, 1, Role.USER, "", 1_500, CallTurnStatus.ASR_FAILED),
            turn(4, 3, Role.ASSISTANT, "怎么啦", 2_400, CallTurnStatus.INTERRUPTED),
            turn(5, 4, Role.USER, "没事", 3_000),
            turn(6, 5, Role.ASSISTANT, "合成失败但显示过", 3_500, CallTurnStatus.TTS_FAILED),
        )
        val history = listOf(text(1, Role.USER, "之前", 500), call, text(2, Role.USER, "之后", 20_000))
        val expanded = CallTranscript.expand(history, mapOf(10L to turns))
        assertEquals(listOf("之前", "在吗", "在呢", "怎么啦", "没事", "合成失败但显示过", "之后"), expanded.map { it.content })
        assertEquals(listOf(Role.USER, Role.USER, Role.ASSISTANT, Role.ASSISTANT, Role.USER, Role.ASSISTANT, Role.USER), expanded.map { it.role })
        val lines = expanded.subList(1, 6)
        assertTrue("展开的行都是文字消息", lines.all { it.type == MessageType.TEXT && it.status == MessageStatus.SUCCESS })
        assertTrue("展开的行 id 是负数，不会和真的消息撞上", lines.all { it.id < 0 })
        assertEquals(listOf(1_100L, 1_900L, 2_400L, 3_000L, 3_500L), lines.map { it.createdAt })
        // 联系人连着说的两句是同一次回复
        assertNotNull(lines[1].replyGroup)
        assertEquals(lines[1].replyGroup, lines[2].replyGroup)
        assertNull(lines[0].replyGroup)
        assertTrue("下一次回复是另一组", lines[4].replyGroup != lines[1].replyGroup)
    }

    @Test
    fun failedRecognition_separatesTwoReplies() {
        val turns = listOf(
            turn(1, 0, Role.ASSISTANT, "第一句", 1_100),
            turn(2, 1, Role.USER, "", 1_500, CallTurnStatus.ASR_FAILED),
            turn(3, 2, Role.ASSISTANT, "第二句", 1_900),
        )
        val lines = CallTranscript.lines(call, turns)
        assertEquals(2, lines.size)
        assertTrue(lines[0].replyGroup != lines[1].replyGroup)
    }

    @Test
    fun durations() {
        assertEquals("00:00", CallDurations.format(999))
        assertEquals("03:21", CallDurations.format(201_000))
        assertEquals("1:02:03", CallDurations.format(3_723_000))
        assertEquals("00:00", CallDurations.format(-5))
    }
}
