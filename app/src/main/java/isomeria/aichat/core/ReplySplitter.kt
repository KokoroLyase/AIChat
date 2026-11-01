package isomeria.aichat.core

/**
 * 分条显示：回复里有空行时，按空行拆成多条气泡，像真人连发几条消息。
 * 只含空白的行也算空行；拆出来的每条去掉首尾空白，空的丢弃。
 */
object ReplySplitter {
    private val blankLine = Regex("""\n[ \t 　]*\n\s*""")

    /** 相邻两条出现的间隔 */
    const val PART_INTERVAL_MILLIS = 1000L

    fun split(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return normalized.split(blankLine).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
