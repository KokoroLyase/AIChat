package isomeria.aichat.core

import kotlin.random.Random

/**
 * 回复偏好开启时，模型回复怎么拆条、去标点、每条什么时候出现。都是纯函数，时间和随机数由调用方传入。
 *
 * - 拆条：按任意换行拆，去掉空行；超过最多条数时，多出来的尾部接进最后一条（用换行连接）。字数不截断
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

    fun split(text: String, preferences: ReplyPreferences): List<String> {
        val p = preferences.normalized()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.trim() }
            .map { if (p.stripPunctuation) stripEnd(it) else it }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return listOfNotNull(text.trim().takeIf { it.isNotEmpty() })
        if (lines.size <= p.maxCount) return lines
        return lines.take(p.maxCount - 1) + lines.drop(p.maxCount - 1).joinToString("\n")
    }

    /** 去掉末尾的「。」「，」；去完是空的就是空 */
    fun stripEnd(line: String): String = line.trimEnd(*trailing).trimEnd()

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
