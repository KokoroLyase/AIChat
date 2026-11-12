package isomeria.aichat.core

/**
 * 通话里把模型边生成边返回的回复切成一句一句（0.8.0），凑满一句就拿去合成，第一句合成好就开始播放。
 *
 * - 在句末标点（。！？!?；;…～~）和换行处切开；一句太长（超过 [softLimit] 字）时也在逗号处切开
 * - 方括号还没闭合时不切（约定提醒的标记 `[提醒 …]` 要整个留在一句里，调用方再把它去掉）
 * - 去掉朗读时会念出来的 Markdown 记号（`*`、`#`、`` ` ``、行首的 `-`、`>`）和 emoji；去掉后没有字的句子不要
 * - 生成完后调用 [finish] 取最后剩下的半句
 *
 * 纯逻辑，便于测试。
 */
class SpeechSentences(private val softLimit: Int = 40) {
    private val buffer = StringBuilder()
    private var depth = 0

    /** 收到一段新生成的文字，返回这时已经完整的几句 */
    fun accept(delta: String): List<String> {
        val result = ArrayList<String>()
        for (ch in delta) {
            buffer.append(ch)
            when (ch) {
                '[', '【', '［' -> depth++
                ']', '】', '］' -> if (depth > 0) depth--
            }
            if (depth > 0) continue
            val end = ch in ENDINGS || ch == '\n' || (ch in SOFT && buffer.length >= softLimit)
            if (end) take()?.let(result::add)
        }
        return result
    }

    /** 生成完了：剩下的半句（没有时为空） */
    fun finish(): List<String> {
        depth = 0
        return listOfNotNull(take())
    }

    private fun take(): String? {
        val sentence = clean(buffer.toString())
        buffer.setLength(0)
        return sentence.takeIf { text -> text.any { it.isLetterOrDigit() } }
    }

    companion object {
        private const val ENDINGS = "。！？!?；;…～~"
        private const val SOFT = "，,、"

        /** 去掉朗读时会念出来的记号和 emoji，合并多余的空白 */
        fun clean(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (isEmoji(cp) || cp == '*'.code || cp == '#'.code || cp == '`'.code || cp == 0xFE0F || cp == 0x200D) continue
                out.appendCodePoint(cp)
            }
            return out.lines()
                .map { line -> line.trim().trimStart('-', '>', '•').trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .trim()
        }

        private fun isEmoji(cp: Int): Boolean =
            cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x1F900..0x1F9FF || cp in 0x2B00..0x2BFF
    }
}
