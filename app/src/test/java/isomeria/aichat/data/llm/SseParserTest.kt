package isomeria.aichat.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    private fun parseAll(vararg lines: String): List<String> {
        val parser = SseParser()
        val events = lines.mapNotNull { parser.feed(it) }.toMutableList()
        parser.flush()?.let { events += it }
        return events
    }

    @Test
    fun eventsEndAtBlankLine() {
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), parseAll("data: {\"a\":1}", "", "data: {\"b\":2}", ""))
    }

    @Test
    fun commentsAndOtherFields_areIgnored() {
        assertEquals(listOf("x"), parseAll(": keep-alive", "", "event: message", "id: 3", "data: x", ""))
    }

    @Test
    fun keepAliveAlone_producesNoEvent() {
        val parser = SseParser()
        assertNull(parser.feed(": keep-alive"))
        assertNull(parser.feed(""))
    }

    @Test
    fun dataWithoutSpace_andMultiLineData() {
        assertEquals(listOf("x"), parseAll("data:x", ""))
        assertEquals(listOf("line1\nline2"), parseAll("data: line1", "data: line2", ""))
    }

    @Test
    fun lastEventWithoutTrailingBlankLine_isFlushed() {
        assertEquals(listOf("[DONE]"), parseAll("data: [DONE]"))
    }
}
