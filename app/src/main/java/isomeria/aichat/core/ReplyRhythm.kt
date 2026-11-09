package isomeria.aichat.core

import kotlin.random.Random

/**
 * 回复偏好开启时，模型回复怎么拆条、去标点、每条什么时候出现。都是纯函数，时间和随机数由调用方传入。
 *
 * - 拆条：按任意换行拆，去掉空行；超过最多条数时，多出来的尾部接进最后一条（用换行连接）。字数不截断
 * - 断句兜底：模型没按要求换行时，某一条超过每条字数的两倍，就在句末（。！？!?… 和后面跟空格的英文句号）
 *   拆开，再把相邻短句按顺序合并到接近每条字数。不在逗号处拆；回复里有代码块（```）时不拆
 * - 标点：只去掉每条末尾的「。」和「，」（连续几个也一起去），保留「？」「！」「…」和句中的标点
 * - 间隔：按字数估算打字时长再加一点随机，限制在最短～最长间隔内；
 *   第一条的等待扣掉模型生成已经用掉的时间，生成够久时第一条立刻出现
 */
object ReplyRhythm {
    /** 估算打字速度：每个字的毫秒数 */
    const val TYPING_MILLIS_PER_CHAR = 300L

    /** 打字时长上额外加的随机量上限 */
    const val JITTER_MILLIS = 1500L

    private val trailing = charArrayOf('。', '，')

    /** 句末标点；英文句号另算（后面要跟空白才算句末，避免拆开网址、小数和版本号） */
    private const val SENTENCE_ENDS = "。！？!?…"

    /** 紧跟在句末标点后、仍属于这一句的右引号和右括号 */
    private const val CLOSERS = "”’」』）)】》\"'"

    fun split(text: String, preferences: ReplyPreferences): List<String> {
        val p = preferences.normalized()
        val fallback = "```" !in text
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { if (fallback && length(it) > p.targetLength * 2) sentences(it, p.targetLength) else listOf(it) }
            .map { if (p.stripPunctuation) stripEnd(it) else it }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return listOfNotNull(text.trim().takeIf { it.isNotEmpty() })
        if (lines.size <= p.maxCount) return lines
        return lines.take(p.maxCount - 1) + lines.drop(p.maxCount - 1).joinToString("\n")
    }

    /** 去掉末尾的「。」「，」；去完是空的就是空 */
    fun stripEnd(line: String): String = line.trimEnd(*trailing).trimEnd()

    private fun length(text: String) = text.codePointCount(0, text.length)

    /** 在句末拆开一行，再把相邻的句子按顺序合并：凑满每条字数就另起一条。没有句末标点时原样返回 */
    private fun sentences(line: String, target: Int): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < line.length) {
            val c = line[i]
            val end = c in SENTENCE_ENDS || (c == '.' && (i + 1 == line.length || line[i + 1].isWhitespace()))
            if (!end) {
                i++
                continue
            }
            // 连着的句末标点（如「？！」「……」）和紧跟的右引号、右括号都留在这一句
            var j = i + 1
            while (j < line.length && (line[j] in SENTENCE_ENDS || line[j] in CLOSERS)) j++
            line.substring(start, j).trim().takeIf { it.isNotEmpty() }?.let(pieces::add)
            start = j
            i = j
        }
        line.substring(start).trim().takeIf { it.isNotEmpty() }?.let(pieces::add)
        if (pieces.size <= 1) return listOf(line)

        val merged = mutableListOf<String>()
        var current = ""
        for (piece in pieces) {
            current = when {
                current.isEmpty() -> piece
                length(current) >= target -> { merged += current; piece }
                else -> current + joiner(current, piece) + piece
            }
        }
        if (current.isNotEmpty()) merged += current
        return merged
    }

    /** 英文句子之间补回拆开时去掉的空格；中文直接连 */
    private fun joiner(before: String, after: String): String =
        if (before.last().code < 0x80 && after.first().code < 0x80) " " else ""

    /**
     * 每条的出现时间。
     *
     * @param startedAt 开始请求模型的时间
     * @param finishedAt 模型返回完的时间
     */
    fun schedule(
        parts: List<String>,
        preferences: ReplyPreferences,
        startedAt: Long,
        finishedAt: Long,
        random: Random,
    ): List<Long> {
        val p = preferences.normalized()
        val generation = (finishedAt - startedAt).coerceAtLeast(0)
        var at = finishedAt
        return parts.mapIndexed { index, part ->
            val wait = interval(part, p, random)
            at += if (index == 0) (wait - generation).coerceAtLeast(0) else wait
            at
        }
    }

    /** 一条的打字间隔，限制在设定范围内 */
    fun interval(part: String, preferences: ReplyPreferences, random: Random): Long {
        val p = preferences.normalized()
        val typing = part.codePointCount(0, part.length) * TYPING_MILLIS_PER_CHAR + random.nextLong(0, JITTER_MILLIS + 1)
        return typing.coerceIn(p.minIntervalSeconds * 1000L, p.maxIntervalSeconds * 1000L)
    }

    /** 用户最后一条消息之后还要等多久才开始回复；已经够久时为 0 */
    fun waitBeforeReply(lastUserMessageAt: Long, now: Long, preferences: ReplyPreferences): Long =
        (lastUserMessageAt + preferences.normalized().waitSeconds * 1000L - now).coerceAtLeast(0)
}

/** 可取消的等待。测试里换成推进假时钟。 */
fun interface Sleeper {
    suspend fun sleep(millis: Long)

    companion object {
        val DELAY = Sleeper { kotlinx.coroutines.delay(it) }
    }
}
