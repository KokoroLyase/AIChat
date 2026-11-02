package isomeria.aichat.core

import isomeria.aichat.data.db.escapeLike
import org.junit.Assert.assertEquals
import org.junit.Test

class TextEditingTest {
    private fun delete(text: String, cursor: Int = text.length) = TextEditing.deleteBackward(text, cursor, cursor)

    @Test
    fun deletesOneCharacter() {
        assertEquals("你" to 1, delete("你好"))
        assertEquals("ab" to 2, delete("abc"))
    }

    @Test
    fun deletesWholeEmoji() {
        // 代理对
        assertEquals("好" to 1, delete("好😀"))
        // 带变体选择符
        assertEquals("a" to 1, delete("a❤️"))
        // 国旗（两个区域指示符）
        assertEquals("a" to 1, delete("a🇨🇳"))
        // ZWJ 组合：一家人
        assertEquals("a" to 1, delete("a👨‍👩‍👧"))
        // 带肤色
        assertEquals("a" to 1, delete("a👍🏽"))
    }

    @Test
    fun deletesAtCursorAndSelection() {
        assertEquals("ac" to 1, delete("abc", 2))
        assertEquals("abc" to 0, delete("abc", 0))
        assertEquals("ad" to 1, TextEditing.deleteBackward("abcd", 3, 1))
    }

    @Test
    fun insertsAtCursorOrReplacesSelection() {
        assertEquals("a😀bc" to 3, TextEditing.insert("abc", 1, 1, "😀"))
        assertEquals("a😀" to 3, TextEditing.insert("abc", 1, 3, "😀"))
        assertEquals("😀" to 2, TextEditing.insert("", 5, 5, "😀"))
    }

    @Test
    fun likeEscape() {
        assertEquals("100\\%", escapeLike("100%"))
        assertEquals("a\\_b", escapeLike("a_b"))
        assertEquals("c:\\\\x", escapeLike("c:\\x"))
        assertEquals("你好", escapeLike("你好"))
    }
}
