package isomeria.aichat.testtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 1.0.0 测试工具“draft”：读 UTF-8 的中文、去掉结尾换行和 BOM、读完删文件；没有、为空、太长时不写 */
class DraftFixtureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun readsUtf8ChineseTrimsTrailingNewlineAndDeletes() {
        val file = temporary.newFile(DraftFixture.FILE)
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "明天下午三点\n见面吗？\r\n".toByteArray(Charsets.UTF_8))
        assertEquals("明天下午三点\n见面吗？", DraftFixture.take(file))
        assertFalse(file.exists())
    }

    @Test
    fun missingEmptyOrTooLongGivesNull() {
        assertNull(DraftFixture.take(temporary.root.resolve("none.txt")))
        val empty = temporary.newFile("empty.txt").apply { writeText("\n") }
        assertNull(DraftFixture.take(empty))
        assertFalse(empty.exists())
        val long = temporary.newFile("long.txt").apply { writeText("字".repeat(DraftFixture.MAX_CHARS + 1)) }
        assertNull(DraftFixture.take(long))
        assertFalse(long.exists())
    }
}
