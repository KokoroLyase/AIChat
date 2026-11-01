package isomeria.aichat.data.llm

/**
 * Server-Sent Events 的最小解析器：逐行喂入，遇到空行时产出一个事件的 data。
 *
 * - `data:` 行累积成事件数据，多行 data 用换行连接
 * - `:` 开头的是注释（DeepSeek 等待期间会发 keep-alive），忽略
 * - 其他字段（event、id、retry）对 Chat Completions 无用，忽略
 */
class SseParser {
    private val data = StringBuilder()
    private var hasData = false

    /** 喂入一行（不含换行符）。返回完整事件的数据，事件未结束时返回 null。 */
    fun feed(line: String): String? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        var value = if (colon == -1) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        if (field == "data") {
            if (hasData) data.append('\n')
            data.append(value)
            hasData = true
        }
        return null
    }

    /** 流结束时取出最后一个没有以空行结尾的事件 */
    fun flush(): String? {
        if (!hasData) return null
        val result = data.toString()
        data.setLength(0)
        hasData = false
        return result
    }

    companion object {
        const val DONE = "[DONE]"
    }
}
