package isomeria.aichat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.8.0：通话里把边生成边返回的回复切成一句一句。 */
class SpeechSentencesTest {
    private fun split(vararg deltas: String, softLimit: Int = 40): List<String> {
        val splitter = SpeechSentences(softLimit)
        return deltas.flatMap { splitter.accept(it) } + splitter.finish()
    }

    @Test
    fun splitsAtSentenceEndsAndNewlines_evenAcrossDeltas() {
        assertEquals(listOf("好呀。", "你想去哪？", "我都行"), split("好", "呀。你想", "去哪？\n我都", "行"))
        assertEquals(listOf("真的吗！", "太好了~"), split("真的吗！太好了~"))
    }

    @Test
    fun aSentenceIsHandedOutAsSoonAsItEnds() {
        val splitter = SpeechSentences()
        assertTrue(splitter.accept("今天天气").isEmpty())
        assertEquals(listOf("今天天气不错。"), splitter.accept("不错。明天"))
        assertEquals(listOf("明天"), splitter.finish())
    }

    @Test
    fun longSentence_isCutAtACommaAfterTheSoftLimit() {
        val result = split("一二三四五六七八九十，一二三四五六七八九十，一二三四五六七八九十。", softLimit = 15)
        assertEquals(listOf("一二三四五六七八九十，一二三四五六七八九十，", "一二三四五六七八九十。"), result)
        assertEquals("短的不在逗号处切", listOf("你好，我是小雨。"), split("你好，我是小雨。", softLimit = 15))
    }

    @Test
    fun reminderMarkerStaysInOneSentence() {
        val result = split("好的。\n[提醒 2026-11-11 07:00 叫他起床。别忘了]")
        assertEquals(listOf("好的。", "[提醒 2026-11-11 07:00 叫他起床。别忘了]"), result)
    }

    @Test
    fun markdownEmojiAndEmptyPiecesAreDropped() {
        assertEquals(listOf("今天 很开心！", "列表一", "列表二"), split("**今天** 很开心！😄\n- 列表一\n- 列表二"))
        assertEquals("只有标点和表情的不要", emptyList<String>(), split("……😊\n\n。"))
        assertEquals("句子里的表情去掉，不读出来", listOf("好开心！"), split("好开心😄！"))
    }
}
