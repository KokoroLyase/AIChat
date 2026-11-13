package isomeria.aichat.core

import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.9.0 多选复制和长按通话复制的文字 */
class MessageCopyTest {
    private val labels = CopyLabels(
        me = "我",
        contact = "小林",
        image = "[图片]",
        voice = "[语音]",
        asrFailed = "（没有识别出文字）",
        callHeader = { "语音通话 #${it.id} · ${it.voiceMillis / 1000}秒" },
        speaker = { name, text -> "$name：$text" },
    )

    private fun message(id: Long, at: Long, type: MessageType, content: String = "", role: Role = Role.USER, millis: Long = 0) =
        Message(id = id, contactId = 1, role = role, type = type, content = content, status = MessageStatus.SUCCESS, createdAt = at, voiceMillis = millis)

    private fun turn(seq: Int, role: Role, text: String, status: CallTurnStatus = CallTurnStatus.OK, id: Long = seq.toLong() + 100) =
        CallTurn(id = id, messageId = 9, seq = seq, role = role, text = text, status = status, createdAt = seq.toLong())

    @Test
    fun oneLinePerMessage_inTimeOrder_noSpeakerOrTime_newlinesBecomeSpaces() {
        val text = MessageCopy.text(
            listOf(
                message(3, 300, MessageType.TEXT, "第三条", role = Role.ASSISTANT),
                message(1, 100, MessageType.TEXT, "第一行\n第二行\r\n\r\n第三行  \n  末尾"),
                message(2, 200, MessageType.IMAGE),
                // 同一时间按 id
                message(5, 300, MessageType.TEXT, "id 更大的在后"),
                message(4, 300, MessageType.TEXT, "id 更小的在前 分隔符"),
            ),
            emptyMap(),
            labels,
        )
        assertEquals("第一行 第二行 第三行 末尾\n[图片]\n第三条\nid 更小的在前 分隔符\nid 更大的在后", text)
    }

    @Test
    fun voice_copiesTranscript_orVoicePlaceholder() {
        val text = MessageCopy.text(
            listOf(message(1, 1, MessageType.VOICE, "转写\n出来的字"), message(2, 2, MessageType.VOICE, "  ")),
            emptyMap(),
            labels,
        )
        assertEquals("转写 出来的字\n[语音]", text)
    }

    @Test
    fun call_expandsInPlace_headerThenEachTurnWithSpeaker_asrFailedWritten() {
        val call = message(9, 200, MessageType.CALL, millis = 65_000)
        val turns = mapOf(
            9L to listOf(
                turn(2, Role.ASSISTANT, "放到一半被打断的这句", CallTurnStatus.INTERRUPTED),
                turn(0, Role.USER, "在吗"),
                turn(1, Role.USER, "", CallTurnStatus.ASR_FAILED),
                turn(3, Role.ASSISTANT, "合成失败也有字\n第二行", CallTurnStatus.TTS_FAILED),
                turn(4, Role.USER, "有字但识别失败的不算", CallTurnStatus.ASR_FAILED),
            ),
        )
        val text = MessageCopy.text(
            listOf(message(10, 300, MessageType.TEXT, "通话之后"), call, message(8, 100, MessageType.TEXT, "通话之前")),
            turns,
            labels,
        )
        assertEquals(
            listOf(
                "通话之前",
                "语音通话 #9 · 65秒",
                "我：在吗",
                "我：（没有识别出文字）",
                "小林：放到一半被打断的这句",
                "小林：合成失败也有字 第二行",
                "我：（没有识别出文字）",
                "通话之后",
            ).joinToString("\n"),
            text,
        )
    }

    @Test
    fun callWithoutTurns_onlyHeader() {
        assertEquals(listOf("语音通话 #9 · 3秒"), MessageCopy.lines(message(9, 1, MessageType.CALL, millis = 3_000), emptyList(), labels))
    }

    @Test
    fun translationNotCopied() {
        val translated = message(1, 1, MessageType.TEXT, "你好").copy(translation = "Hello", translationShown = true)
        assertEquals("你好", MessageCopy.text(listOf(translated), emptyMap(), labels))
    }
}
