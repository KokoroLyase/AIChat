package isomeria.aichat.core

/**
 * 语音回复怎么分条（0.7.0，纯函数）：回复偏好拆出的多条合并成 1～2 条较长的语音（路线图原计划），太长时按句子分段，
 * 每段不超过 [MAX_CHARS] 字，避免超过服务商的文字上限（MiMo 没写上限）。
 *
 * - 去掉空行，保留换行（合成时是停顿，「转文字」也按行显示）
 * - 不超过 [ONE_VOICE_CHARS] 字：一条
 * - 不超过 2×[MAX_CHARS]：两条，在最接近一半的行尾断开；只有一行时在最接近一半的句末（。！？!?…）断开，没有句末就在逗号，再没有就从中间断
 * - 更长：按行（行太长再按句子）依次装满每段
 */
object VoiceChunks {
    const val ONE_VOICE_CHARS = 60
    const val MAX_CHARS = 300

    fun split(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val total = lines.sumOf { it.length }
        if (total <= ONE_VOICE_CHARS) return listOf(lines.joinToString("\n"))
        if (total <= 2 * MAX_CHARS) return halves(lines)
        return pack(lines.flatMap { if (it.length > MAX_CHARS) sentences(it) else listOf(it) })
    }

    private fun halves(lines: List<String>): List<String> {
        if (lines.size >= 2) {
            val total = lines.sumOf { it.length }
            var best = 1
            var bestDiff = Int.MAX_VALUE
            var running = 0
            for (i in 1 until lines.size) {
                running += lines[i - 1].length
                val diff = kotlin.math.abs(total - 2 * running)
                if (diff < bestDiff) { bestDiff = diff; best = i }
            }
            val first = lines.take(best).joinToString("\n")
            val second = lines.drop(best).joinToString("\n")
            if (first.length <= MAX_CHARS && second.length <= MAX_CHARS) return listOf(first, second)
            return pack(lines.flatMap { if (it.length > MAX_CHARS) sentences(it) else listOf(it) })
        }
        val line = lines.single()
        val cut = cutNearMiddle(line)
        return listOf(line.substring(0, cut).trim(), line.substring(cut).trim()).filter { it.isNotEmpty() }
    }

    /** 最接近一半的断点：句末优先，其次逗号，再没有就正中间 */
    private fun cutNearMiddle(line: String): Int {
        val middle = line.length / 2
        for (marks in listOf(SENTENCE_END, COMMA)) {
            val positions = line.indices.filter { line[it] in marks && it < line.lastIndex }.map { it + 1 }
            positions.minByOrNull { kotlin.math.abs(it - middle) }?.let { return it }
        }
        return middle
    }

    /** 按句末拆开（句末标点留在这一句），一句还超过上限的硬切 */
    private fun sentences(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (c in line) {
            current.append(c)
            if (c in SENTENCE_END) { result += current.toString(); current.clear() }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result.flatMap { it.chunked(MAX_CHARS) }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 依次装满：每段不超过上限，段内按原来的换行连接 */
    private fun pack(pieces: List<String>): List<String> {
        val chunks = mutableListOf<String>()
        val current = mutableListOf<String>()
        var length = 0
        for (piece in pieces) {
            if (current.isNotEmpty() && length + piece.length > MAX_CHARS) {
                chunks += current.joinToString("\n")
                current.clear()
                length = 0
            }
            current += piece
            length += piece.length
        }
        if (current.isNotEmpty()) chunks += current.joinToString("\n")
        return chunks
    }

    private val SENTENCE_END = setOf('。', '！', '？', '!', '?', '…', '~', '～')
    private val COMMA = setOf('，', ',', '；', ';')
}
