package isomeria.aichat.core

import isomeria.aichat.data.db.Memory
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.UsageDaily
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.llm.ChatMessage
import isomeria.aichat.data.llm.ChatPart
import isomeria.aichat.data.llm.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** token 估算、上下文窗口、记忆策略、用量汇总、预算步进 */
class ContextWindowTest {
    private val estimator = TokenEstimator(1.0)
    private var nextId = 1L

    private fun message(
        text: String,
        role: Role = Role.USER,
        status: MessageStatus = MessageStatus.SUCCESS,
        group: String? = null,
    ) = Message(
        id = nextId++, contactId = 1, role = role, type = MessageType.TEXT, content = text, status = status,
        replyGroup = group, createdAt = nextId * 1000,
    )

    private fun image(role: Role = Role.USER) = Message(
        id = nextId++, contactId = 1, role = role, type = MessageType.IMAGE, imagePath = "p", status = MessageStatus.SUCCESS,
        createdAt = nextId * 1000,
    )

    /** 每条正好占 10 个汉字 + 4 = 14 token */
    private fun ten(role: Role = Role.USER, group: String? = null) = message("一二三四五六七八九十", role, group = group)

    // ---- 估算 ----

    @Test
    fun estimate_deepSeekUsesOfficialRatios() {
        val deepSeek = TokenEstimator.forProtocol(ProviderProtocol.DEEPSEEK)
        assertEquals(6, deepSeek.text("一二三四五六七八九十")) // 10 × 0.6
        assertEquals(3, deepSeek.text("abcdefghij")) // 10 × 0.3
    }

    @Test
    fun estimate_otherProvidersCountWideCharsAsOne() {
        val other = TokenEstimator.forProtocol(ProviderProtocol.OPENAI)
        assertEquals(10, other.text("一二三四五六七八九十"))
        assertEquals(3, other.text("abcdefghij"))
    }

    @Test
    fun estimate_emojiCountsOncePerCodePoint_andPunctuationAsWide() {
        assertEquals(1, estimator.text("😀"))
        assertEquals(1, estimator.text("，"))
        assertEquals(0, estimator.text(""))
    }

    @Test
    fun estimate_imageDataIsNotCountedAsText() {
        val request = listOf(ChatMessage(ChatRole.USER, listOf(ChatPart.Image("data:image/jpeg;base64," + "A".repeat(100_000)))))
        assertEquals(TokenEstimator.MESSAGE_OVERHEAD + TokenEstimator.IMAGE_TOKENS, estimator.request(request))
    }

    // ---- 窗口 ----

    @Test
    fun window_keepsNewestWithinBudget_dropsOldest() {
        val history = List(10) { ten() }
        val window = ContextWindow.select(history, budget = 14 * 3 + 5, estimator, supportsImage = false)
        assertEquals(history.takeLast(3), window)
    }

    @Test
    fun window_exactBudgetFits() {
        val history = List(3) { ten() }
        assertEquals(history, ContextWindow.select(history, budget = 14 * 3, estimator, false))
    }

    @Test
    fun window_waitingMessagesAlwaysKept_evenOverBudget() {
        val old = ten()
        val waiting = message("一".repeat(500), status = MessageStatus.SENDING)
        assertEquals(listOf(waiting), ContextWindow.select(listOf(old, waiting), budget = 10, estimator, false))
        assertEquals(listOf(waiting), ContextWindow.select(listOf(waiting), budget = -100, estimator, false))
    }

    @Test
    fun window_splitReplyIsKeptOrDroppedAsAWhole() {
        val user = ten()
        val a = ten(Role.ASSISTANT, "g1")
        val b = ten(Role.ASSISTANT, "g1")
        val last = ten()
        // 放得下最后一条和 b，放不下 a：整组都不带
        val window = ContextWindow.select(listOf(user, a, b, last), budget = 14 * 2, estimator, false)
        assertEquals(listOf(last), window)
        // 放得下整组：连同前面的用户消息
        assertEquals(listOf(user, a, b, last), ContextWindow.select(listOf(user, a, b, last), budget = 14 * 4, estimator, false))
    }

    @Test
    fun window_leadingAssistantMessagesAreDropped() {
        val reply = ten(Role.ASSISTANT)
        val user = ten()
        assertEquals(listOf(user), ContextWindow.select(listOf(reply, user), budget = 14 * 2, estimator, false))
    }

    @Test
    fun window_imagesCostMoreOnlyWhenTheyCarryData() {
        val pic = image()
        val text = ten()
        val small = 14 + 4 + TokenEstimator.MESSAGE_OVERHEAD + 1
        // 支持图片时这张图按 1000 算，放不下；不支持时按文字算，放得下
        assertEquals(listOf(text), ContextWindow.select(listOf(pic, text), small, estimator, supportsImage = true))
        assertEquals(listOf(pic, text), ContextWindow.select(listOf(pic, text), small, estimator, supportsImage = false))
    }

    @Test
    fun window_maxRowsCoversBudget() {
        // 最短的消息（1 个字）也要能填满预算
        assertTrue(ContextWindow.maxRows(4000) * (TokenEstimator.MESSAGE_OVERHEAD + 1) >= 4000)
        assertTrue(ContextWindow.maxRows(-5) > 0)
    }

    // ---- 记忆策略 ----

    private fun memory(content: String, edited: Boolean = false) = Memory(
        id = nextId++, contactId = 1, content = content, fromTime = nextId, toTime = nextId, edited = edited,
        createdAt = 0, updatedAt = 0,
    )

    @Test
    fun plan_notTriggeredBelowEightyPercent() {
        val messages = List(10) { ten() } // 140 token，预算 300 的 80% 是 240
        assertTrue(MemoryPolicy.planBatch(messages, historyBudget = 300, estimator).isEmpty())
    }

    @Test
    fun plan_triggeredAboveEightyPercent_takesOldestHalf() {
        val messages = List(20) { ten() } // 280 token，预算 300 的 80% 是 240
        val batch = MemoryPolicy.planBatch(messages, historyBudget = 300, estimator)
        assertEquals(messages.take(10), batch)
    }

    @Test
    fun plan_neverSplitsReplyGroup() {
        val messages = listOf(ten()) + List(7) { ten(Role.ASSISTANT, "g") } + List(5) { ten() }
        // 总量 182，最早的一半是 91，落在这组回复中间：要把整组带上
        val batch = MemoryPolicy.planBatch(messages, historyBudget = 200, estimator)
        assertEquals(messages.take(8), batch)
    }

    @Test
    fun plan_excludesWaitingMessages() {
        val done = List(20) { ten() }
        val waiting = message("一".repeat(200), status = MessageStatus.SENDING)
        val batch = MemoryPolicy.planBatch(done + waiting, historyBudget = 200, estimator)
        assertTrue(waiting !in batch)
        assertEquals(done.take(10), batch)
    }

    @Test
    fun plan_batchIsCappedByMaxTokens() {
        val messages = List(1000) { ten() } // 14000 token，一半是 7000，超过上限 6000
        val batch = MemoryPolicy.planBatch(messages, historyBudget = 1000, estimator)
        val tokens = batch.sumOf { estimator.message(it) }
        assertTrue("实际 $tokens", tokens in MemoryPolicy.BATCH_MAX_TOKENS..MemoryPolicy.BATCH_MAX_TOKENS + 14)
    }

    @Test
    fun plan_personaEatingWholeBudget_doesNotSummarizeEveryMessage() {
        // 系统提示词把预算占满（可用预算为负）时，也至少按 200 算：160 token 以内不总结
        assertTrue(MemoryPolicy.planBatch(List(11) { ten() }, historyBudget = -500, estimator).isEmpty())
        assertTrue(MemoryPolicy.planBatch(List(12) { ten() }, historyBudget = -500, estimator).isNotEmpty())
    }

    @Test
    fun backlog_takesNewestPartWithinLimit() {
        val older = List(1000) { ten() }
        val batch = MemoryPolicy.planBacklog(older, estimator)
        assertEquals(older.last(), batch.last())
        assertTrue(batch.sumOf { estimator.message(it) } <= MemoryPolicy.BATCH_MAX_TOKENS)
        assertTrue(batch.size < older.size)
    }

    @Test
    fun backlog_singleHugeMessageIsStillTaken_andEmptyIsEmpty() {
        val huge = message("一".repeat(MemoryPolicy.BATCH_MAX_TOKENS * 2))
        assertEquals(listOf(huge), MemoryPolicy.planBacklog(listOf(ten(), huge), estimator))
        assertTrue(MemoryPolicy.planBacklog(emptyList(), estimator).isEmpty())
    }

    @Test
    fun forRequest_keepsNewestWithinBudget_inTimeOrder() {
        val a = memory("一二三四五")
        val b = memory("一二三四五")
        val c = memory("一二三四五")
        assertEquals(listOf(b, c), MemoryPolicy.forRequest(listOf(a, b, c), budget = 12, estimator))
        assertTrue(MemoryPolicy.forRequest(listOf(a), budget = 1, estimator).isEmpty())
    }

    @Test
    fun merge_onlyOverBudget_oldestUneditedHalf() {
        val edited = memory("改过的", edited = true)
        val a = memory("一二三四五")
        val b = memory("一二三四五")
        val c = memory("一二三四五")
        val all = listOf(edited, a, b, c)
        assertTrue(MemoryPolicy.planMerge(all, budget = 100, estimator).isEmpty())
        assertEquals(listOf(a, b), MemoryPolicy.planMerge(all, budget = 5, estimator))
    }

    @Test
    fun merge_needsAtLeastTwoUnedited() {
        val edited = memory("一二三四五", edited = true)
        val only = memory("一二三四五")
        assertTrue(MemoryPolicy.planMerge(listOf(edited, only), budget = 1, estimator).isEmpty())
    }

    @Test
    fun transcript_onlySuccessfulMessages_imagesAsText_noReasoning() {
        val messages = listOf(
            message("你好"),
            message("没发出去", status = MessageStatus.FAILED),
            message("在呀", Role.ASSISTANT).copy(reasoning = "思考内容"),
            image(),
        )
        assertEquals("阿哲：你好\n小雨：在呀\n阿哲：[图片]", MemoryPolicy.transcript(messages, "阿哲", "小雨", "[图片]"))
    }

    // ---- 用量 ----

    private fun usage(day: Long, contact: Long, prompt: Long, completion: Long, purpose: UsagePurpose = UsagePurpose.CHAT) =
        UsageDaily(day, contact, purpose, prompt, completion, 1)

    @Test
    fun usage_rangeIncludesToday_andCountsBackNDays() {
        val rows = listOf(usage(100, 1, 1, 1), usage(94, 1, 1, 1), usage(93, 1, 1, 1), usage(70, 1, 1, 1))
        assertEquals(listOf(100L, 94L), UsageStats.inRange(rows, UsageRange.WEEK, today = 100).map { it.day })
        assertEquals(listOf(100L, 94L, 93L), UsageStats.inRange(rows, UsageRange.MONTH, today = 100).map { it.day })
        assertEquals(4, UsageStats.inRange(rows, UsageRange.ALL, today = 100).size)
    }

    @Test
    fun usage_groupsByDayNewestFirst_andByContactBiggestFirst() {
        val rows = listOf(
            usage(1, 1, 10, 1),
            usage(2, 1, 20, 2),
            usage(2, 2, 100, 10),
            usage(2, 1, 5, 5, UsagePurpose.MEMORY),
        )
        val days = UsageStats.byDay(rows)
        assertEquals(listOf(2L, 1L), days.map { it.key })
        assertEquals(125L, days[0].promptTokens)
        assertEquals(10L, days[0].memoryTokens)
        assertEquals(3, days[0].requests)

        val contacts = UsageStats.byContact(rows)
        assertEquals(listOf(2L, 1L), contacts.map { it.key })
        assertEquals(110L, contacts[0].totalTokens)
        assertEquals(43L, contacts[1].totalTokens)
        assertEquals(153L, UsageStats.total(rows).totalTokens)
    }

    @Test
    fun usage_epochDayUsesLocalZone() {
        // 北京时间 2026-11-01 23:30，东京已经是 11 月 2 日，UTC 还是 11 月 1 日
        val time = java.time.ZonedDateTime.of(2026, 11, 1, 23, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val day = java.time.LocalDate.of(2026, 11, 1).toEpochDay()
        assertEquals(day, UsageStats.epochDay(time, ZoneId.of("Asia/Shanghai")))
        assertEquals(day + 1, UsageStats.epochDay(time, ZoneId.of("Asia/Tokyo")))
        assertEquals(day, UsageStats.epochDay(time, ZoneId.of("UTC")))
    }

    // ---- 预算步进 ----

    @Test
    fun budgetSteps_snapToRoundValues_andStayInRange() {
        assertEquals(5000, ContextBudgetSteps.increase(4000))
        assertEquals(10_000, ContextBudgetSteps.increase(9000))
        assertEquals(20_000, ContextBudgetSteps.increase(10_000))
        assertEquals(Contact.MAX_CONTEXT_TOKENS, ContextBudgetSteps.increase(Contact.MAX_CONTEXT_TOKENS))
        assertEquals(3000, ContextBudgetSteps.decrease(4000))
        assertEquals(9000, ContextBudgetSteps.decrease(10_000))
        assertEquals(10_000, ContextBudgetSteps.decrease(20_000))
        assertEquals(Contact.MIN_CONTEXT_TOKENS, ContextBudgetSteps.decrease(Contact.MIN_CONTEXT_TOKENS))
        // 迁移来的非整数档也能落回整数档
        assertEquals(5000, ContextBudgetSteps.increase(4200))
        assertEquals(4000, ContextBudgetSteps.decrease(4200))
    }
}
