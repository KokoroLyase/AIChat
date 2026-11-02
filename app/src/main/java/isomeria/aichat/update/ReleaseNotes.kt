package isomeria.aichat.update

/**
 * 把 Release 的更新说明（Markdown）转成弹窗里显示的纯文本：
 * 去掉标题井号、加粗和代码标记，列表项换成「•」，链接只留文字，去掉图片和 HTML 注释。
 */
object ReleaseNotes {
    private val comment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val heading = Regex("""^\s{0,3}#{1,6}\s+""")
    private val bullet = Regex("""^(\s*)[-*+]\s+""")
    private val image = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val emphasis = Regex("""(\*\*|__|`)""")
    private val blankLines = Regex("""\n{3,}""")

    fun toPlainText(markdown: String): String {
        val text = comment.replace(markdown.replace("\r\n", "\n"), "")
        return text.lines()
            .joinToString("\n") { line ->
                var result = heading.replace(line, "")
                result = bullet.replace(result) { "${it.groupValues[1]}• " }
                result = image.replace(result, "")
                result = link.replace(result) { it.groupValues[1] }
                emphasis.replace(result, "").trimEnd()
            }
            .replace(blankLines, "\n\n")
            .trim()
    }
}
