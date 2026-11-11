package isomeria.aichat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音回复分条：短的一条，较长的两条（在一半附近断开），很长的按上限装满，不丢字。 */
class VoiceChunksTest {
    @Test
    fun shortReply_oneVoice_keepsLines_dropsBlankLines() {
        assertEquals(listOf("在呢\n刚下班"), VoiceChunks.split("在呢\n\n刚下班\n"))
        assertEquals(emptyList<String>(), VoiceChunks.split(" \n "))
    }

    @Test
    fun mediumReply_twoVoices_splitAtLineNearestHalf() {
        val lines = listOf("今天加班到很晚，回来路上又下起了大雨", "伞还忘在公司了，只好一路跑回家", "整个人都淋透了，现在刚洗完澡出来", "你吃饭了没有呀，要不要一起点外卖")
        val parts = VoiceChunks.split(lines.joinToString("\n"))
        assertEquals(2, parts.size)
        assertEquals(lines.joinToString(""), parts.joinToString("").replace("\n", ""))
        assertEquals("${lines[0]}\n${lines[1]}", parts[0])
    }

    @Test
    fun mediumSingleLine_splitsAtSentenceEndNearMiddle() {
        val first = "今天的天气真不错，阳光很好，风也很温柔，我们一起去公园散步吧。"
        val second = "公园里的花都开了，湖边还有很多人在放风筝，一定很热闹好玩呢！"
        assertEquals(listOf(first, second), VoiceChunks.split(first + second))
    }

    @Test
    fun longReply_packsUnderLimit_noTextLost() {
        val sentence = "这是一句比较长的话，用来测试分段。"
        val text = List(60) { sentence }.joinToString("")
        val parts = VoiceChunks.split(text)
        assertTrue(parts.size >= 3)
        assertTrue(parts.all { it.replace("\n", "").length <= VoiceChunks.MAX_CHARS })
        assertEquals(text, parts.joinToString("").replace("\n", ""))
    }

    @Test
    fun voiceReplyRule_followTextVoice_andNoTts() {
        val f = isomeria.aichat.data.db.ReplyType.FOLLOW
        val t = isomeria.aichat.data.db.ReplyType.TEXT
        val v = isomeria.aichat.data.db.ReplyType.VOICE
        // 跟随：看这次等待回复的最后一条；主动消息（null）回文字
        assertTrue(ReplyTypeRule.voiceReply(null, f, lastWaitingIsVoice = true, ttsAvailable = true))
        assertEquals(false, ReplyTypeRule.voiceReply(null, f, lastWaitingIsVoice = false, ttsAvailable = true))
        assertEquals(false, ReplyTypeRule.voiceReply(null, f, lastWaitingIsVoice = null, ttsAvailable = true))
        // 联系人自己的设置优先于全局
        assertTrue(ReplyTypeRule.voiceReply(v, t, lastWaitingIsVoice = false, ttsAvailable = true))
        assertTrue("语音模式下主动消息也回语音", ReplyTypeRule.voiceReply(null, v, lastWaitingIsVoice = null, ttsAvailable = true))
        assertEquals(false, ReplyTypeRule.voiceReply(t, v, lastWaitingIsVoice = true, ttsAvailable = true))
        // 没有可用的语音合成：一律文字
        assertEquals(false, ReplyTypeRule.voiceReply(v, v, lastWaitingIsVoice = true, ttsAvailable = false))
    }
}
