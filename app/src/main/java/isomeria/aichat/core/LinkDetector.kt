package isomeria.aichat.core

/** 文字里识别出的一个链接。 */
data class DetectedLink(val start: Int, val end: Int, val url: String)

/**
 * 识别 http(s):// 和 www. 开头的链接。中文字符、全角标点和空白都视为链接结束；
 * 末尾的英文标点（句号、逗号、未配对的右括号等）不算进链接。
 */
object LinkDetector {
    private val candidate = Regex(
        """(?i)(?:https?://|www\.)[^\s<>"'`，。！？；：、（）【】《》「」『』“”‘’…　-〿一-鿿＀-￯]+""",
    )
    private const val TRAILING = ".,;:!?'\"*~_-"

    fun find(text: String): List<DetectedLink> = candidate.findAll(text).mapNotNull { match ->
        var end = match.range.last + 1
        val start = match.range.first
        // 链接前面紧贴英文字母或数字时不算（如 abcwww.x.com）；紧贴中文时照常识别
        if (start > 0 && text[start - 1].isAsciiLetterOrDigit()) return@mapNotNull null
        while (end > start) {
            val last = text[end - 1]
            val trim = when (last) {
                in TRAILING -> true
                ')' -> text.substring(start, end).count { it == '(' } < text.substring(start, end).count { it == ')' }
                ']' -> text.substring(start, end).count { it == '[' } < text.substring(start, end).count { it == ']' }
                else -> false
            }
            if (!trim) break
            end--
        }
        val raw = text.substring(start, end)
        val body = raw.substringAfter("://", raw)
        // 至少要有一个点后面跟着内容，排除 "http://" 或 "www." 这类残缺文字
        val host = body.removePrefix("www.").removePrefix("WWW.")
        if (host.isEmpty() || !body.contains('.') || body.endsWith(".")) return@mapNotNull null
        if (raw.startsWith("www.", ignoreCase = true) && !host.contains('.')) return@mapNotNull null
        val url = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
        DetectedLink(start, end, url)
    }.toList()

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'
}
