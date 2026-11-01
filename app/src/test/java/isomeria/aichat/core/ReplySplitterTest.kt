package isomeria.aichat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplySplitterTest {
    @Test
    fun blankLine_splitsIntoParts() {
        assertEquals(listOf("在吗", "今天好累"), ReplySplitter.split("在吗\n\n今天好累"))
    }

    @Test
    fun singleNewline_staysInOneBubble() {
        assertEquals(listOf("第一行\n第二行"), ReplySplitter.split("第一行\n第二行"))
    }

    @Test
    fun severalBlankLinesAndWhitespaceOnlyLines_countAsOneSeparator() {
        assertEquals(listOf("a", "b", "c"), ReplySplitter.split("a\n \nb\n\n\n\nc"))
        assertEquals(listOf("a", "b"), ReplySplitter.split("a\n\t\nb"))
        assertEquals(listOf("a", "b"), ReplySplitter.split("a\n　\nb"))
    }

    @Test
    fun windowsNewlines() {
        assertEquals(listOf("a", "b"), ReplySplitter.split("a\r\n\r\nb"))
    }

    @Test
    fun partsAreTrimmed_andEmptyPartsDropped() {
        assertEquals(listOf("a", "b"), ReplySplitter.split("\n\n  a  \n\n\n  b \n\n"))
        assertTrue(ReplySplitter.split("  \n\n  ").isEmpty())
        assertTrue(ReplySplitter.split("").isEmpty())
    }

    @Test
    fun noBlankLine_returnsWholeText() {
        assertEquals(listOf("好的"), ReplySplitter.split("好的"))
    }
}
