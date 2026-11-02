package isomeria.aichat.core

import java.text.BreakIterator

/** 输入框编辑：表情面板的「删除」键和插入表情。 */
object TextEditing {
    /**
     * 删除光标前的一个字符（按用户看到的字符，即字素簇）：
     * 👨‍👩‍👧、🇨🇳、带变体选择符的 ❤️ 都整个删掉，不会只删半个。
     * 有选中文字时删除选中部分。返回新文字和新的光标位置。
     */
    fun deleteBackward(text: String, selectionStart: Int, selectionEnd: Int): Pair<String, Int> {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        if (start != end) return text.removeRange(start, end) to start
        if (start == 0) return text to 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val previous = iterator.preceding(start).takeIf { it != BreakIterator.DONE } ?: 0
        return text.removeRange(previous, start) to previous
    }

    /** 在光标处插入（替换选中部分），返回新文字和新的光标位置 */
    fun insert(text: String, selectionStart: Int, selectionEnd: Int, value: String): Pair<String, Int> {
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        return text.substring(0, start) + value + text.substring(end) to start + value.length
    }
}
