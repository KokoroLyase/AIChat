package isomeria.aichat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 回复偏好的纯逻辑：拆条、尾部合并、句尾标点、间隔、第一条扣掉生成时间、稍等几秒。 */
class ReplyRhythmTest {
    private val defaults = ReplyPreferences()

    /** 随机量恒为 0，间隔只看字数 */
    private val noJitter = object : Random() {
        override fun nextBits(bitCount: Int) = 0
    }

    // ---------- 拆条 ----------

    @Test
    fun splitsOnAnyNewline_dropsBlankLines_andTrims() {
        assertEquals(listOf("在呀", "怎么啦", "刚下班"), ReplyRhythm.split("  在呀 \n\n 　\n怎么啦\r\n刚下班\r", defaults))
        assertEquals(listOf("一条"), ReplyRhythm.split("一条", defaults))
    }

    @Test
    fun overMaxCount_mergesTailIntoLast_withNewlines_noTruncation() {
        val prefs = defaults.copy(maxCount = 3)
        assertEquals(listOf("1", "2", "3\n4\n5"), ReplyRhythm.split("1\n2\n3\n4\n5", prefs))
        assertEquals(listOf("1\n2"), ReplyRhythm.split("1\n2", prefs.copy(minCount = 1, maxCount = 1)))
        val long = "长".repeat(500)
        assertEquals(listOf(long), ReplyRhythm.split(long, prefs))
    }

    @Test
    fun stripsOnlyTrailingFullStopAndComma_keepsQuestionExclamationEllipsis() {
        val parts = ReplyRhythm.split("好的。\n马上就到，，\n你在哪？\n太好了！\n嗯…\n他说：「好。」\n句中，标点。保留\n。", defaults.copy(maxCount = 10))
        assertEquals(listOf("好的", "马上就到", "你在哪？", "太好了！", "嗯…", "他说：「好。」", "句中，标点。保留"), parts)
        // 关掉时原样保留
        assertEquals(listOf("好的。", "马上就到，"), ReplyRhythm.split("好的。\n马上就到，", defaults.copy(stripPunctuation = false)))
        // 每行只剩标点时退回原文，不会变成空回复
        assertEquals(listOf("。"), ReplyRhythm.split("。", defaults))
    }

    @Test
    fun mergedTailAlsoLosesTrailingPunctuationPerLine() {
        assertEquals(listOf("一", "二\n三"), ReplyRhythm.split("一。\n二。\n三。", defaults.copy(maxCount = 2)))
    }

    // ---------- 间隔 ----------

    @Test
    fun interval_followsLength_clampedToRange() {
        assertEquals(3_000, ReplyRhythm.interval("好的", defaults, noJitter)) // 600ms，不到最短
        assertEquals(6_000, ReplyRhythm.interval("一".repeat(20), defaults, noJitter))
        assertEquals(10_000, ReplyRhythm.interval("一".repeat(100), defaults, noJitter)) // 超过最长
        assertEquals(5_000, ReplyRhythm.interval("好", defaults.copy(minIntervalSeconds = 5, maxIntervalSeconds = 5), noJitter))
        // 随机量在 0～1.5 秒之间，结果仍在范围内
        val random = Random(42)
        repeat(200) {
            val ms = ReplyRhythm.interval("一".repeat(15), defaults, random)
            assertTrue(ms in 4_500..6_000)
        }
    }

    @Test
    fun schedule_firstWaitMinusGenerationTime_thenIntervalsAdd() {
        val parts = listOf("好的", "马上就到", "一".repeat(20))
        val start = 1_000_000L
        // 生成用了 1.5 秒：第一条再等 1.5 秒（3 秒 - 1.5 秒）
        assertEquals(listOf(start + 3_000, start + 6_000, start + 12_000), ReplyRhythm.schedule(parts, defaults, start, start + 1_500, noJitter))
        // 生成用了 5 秒，已经超过第一条该等的时间：第一条立刻出现
        val finished = start + 5_000
        assertEquals(listOf(finished, finished + 3_000, finished + 9_000), ReplyRhythm.schedule(parts, defaults, start, finished, noJitter))
        // 时间不会倒退：生成时间异常（结束早于开始）按 0 算
        assertEquals(start + 3_000, ReplyRhythm.schedule(parts.take(1), defaults, start, start, noJitter).single())
        assertEquals(start + 3_000, ReplyRhythm.schedule(parts.take(1), defaults, start + 9_999, start, noJitter).single())
    }

    @Test
    fun schedule_isStrictlyIncreasing_withRealRandom() {
        val times = ReplyRhythm.schedule(List(5) { "第 $it 条消息" }, defaults, 0, 2_000, Random(7))
        assertTrue(times.zipWithNext().all { (a, b) -> b - a in 3_000..10_000 })
    }

    // ---------- 稍等几秒 ----------

    @Test
    fun waitBeforeReply_countsFromLastUserMessage() {
        assertEquals(2_000, ReplyRhythm.waitBeforeReply(lastUserMessageAt = 10_000, now = 11_000, defaults))
        assertEquals(0, ReplyRhythm.waitBeforeReply(10_000, 13_000, defaults))
        assertEquals(0, ReplyRhythm.waitBeforeReply(10_000, 20_000, defaults))
        assertEquals(0, ReplyRhythm.waitBeforeReply(10_000, 10_000, defaults.copy(waitSeconds = 0)))
        assertEquals(30_000, ReplyRhythm.waitBeforeReply(10_000, 10_000, defaults.copy(waitSeconds = 99)))
    }
}
