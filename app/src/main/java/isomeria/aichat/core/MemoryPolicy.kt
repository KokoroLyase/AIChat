package isomeria.aichat.core

import isomeria.aichat.data.db.Memory
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role

/**
 * 长期记忆什么时候总结、什么时候整理、每次请求带哪些（0.7.2 起分两层）。
 *
 * - 未总结的消息超过可用预算的 [TRIGGER_RATIO] 时，把最早的一半总结成一条近期记忆：这些消息被丢弃前已经进了记忆；
 *   这段没有值得记的内容时模型回答「无」，不生成记忆，只推进进度
 * - 记忆预算是上下文上限的 [MEMORY_SHARE]，但最多 [MEMORY_MAX_TOKENS]：不随上下文上限增长，免得记忆太长分散模型注意力
 * - 近期记忆超出 [recentBudget] 时，把最早的一半没被用户改过的交给模型，和核心记忆一起整理成新的核心记忆（[planFold]）
 * - 每次请求先带核心记忆，再从最新的近期记忆往前放，放不下就不带（不会删除）；核心记忆超出预算时只带前面放得下的几行
 * - 核心记忆约 [CORE_MAX_CHARS] 字；上下文上限小时按预算缩短（[coreMaxChars]），免得整理出来的核心记忆放不进请求
 */
object MemoryPolicy {
    const val TRIGGER_RATIO = 0.8
    const val MEMORY_SHARE = 0.25

    /** 每次请求带的记忆最多多少 token（核心加近期） */
    const val MEMORY_MAX_TOKENS = 1500

    /** 近期记忆最多多少 token；超出时最早的整理进核心记忆 */
    const val RECENT_MAX_TOKENS = 800

    /** 核心记忆最多约多少字（整理指令里的要求） */
    const val CORE_MAX_CHARS = 500

    /** 预算再小，整理指令也至少允许这么多字 */
    const val CORE_MIN_CHARS = 50

    /** 一次整理最多处理多少 token 的近期记忆：升级前积累的大量记忆分几次整理完 */
    const val FOLD_MAX_TOKENS = 6000

    /** 一次总结最多处理多少 token 的聊天记录 */
    const val BATCH_MAX_TOKENS = 6000

    /** 「总结更早的聊天」一次最多读多少条：每条至少占 5 token */
    const val BACKLOG_MAX_ROWS = BATCH_MAX_TOKENS / 5

    /** 记忆内容的长度上限（字符）：模型超长输出时截断，手动编辑时限制 */
    const val CONTENT_MAX_LENGTH = 2000

    /** 系统提示词把预算占满时，也至少按这个可用预算算，避免每条消息都触发总结 */
    private const val MIN_HISTORY_BUDGET = 200

    fun memoryBudget(contextTokens: Int): Int = minOf((contextTokens * MEMORY_SHARE).toInt(), MEMORY_MAX_TOKENS)

    /**
     * 整理指令要求的核心记忆字数：约 [CORE_MAX_CHARS] 字，但不超过预算的三分之二（另外三分之一留给近期记忆，
     * 和 [recentBudget] 一致）。按这家服务商的汉字比例换算
     */
    fun coreMaxChars(budget: Int, estimator: TokenEstimator): Int =
        estimator.wideCharsWithin(budget * 2 / 3).coerceIn(CORE_MIN_CHARS, CORE_MAX_CHARS)

    /**
     * 这次请求带哪些记忆：先放核心记忆（超出预算时只放前面放得下的几行，见 [fitCore]），
     * 再从最新的近期记忆往前放，放不下就不带（不会删除）。结果是核心记忆在前，近期记忆按时间正序
     */
    fun forRequest(memories: List<Memory>, budget: Int, estimator: TokenEstimator): List<Memory> {
        val core = memories.firstOrNull { it.core }?.let { fitCore(it, budget, estimator) }
        var used = core?.let { estimator.text(it.content) } ?: 0
        val kept = ArrayDeque<Memory>()
        for (memory in memories.asReversed()) {
            if (memory.core) continue
            used += estimator.text(memory.content)
            if (used > budget) break
            kept.addFirst(memory)
        }
        return listOfNotNull(core) + kept
    }

    /**
     * 放进 [budget] 的核心记忆：放得下就原样；放不下时从头按行取（核心记忆一行一件事），
     * 第一行就放不下时取开头放得下的部分。只影响这次请求，不改保存的内容；什么都放不下时为 null
     */
    private fun fitCore(core: Memory, budget: Int, estimator: TokenEstimator): Memory? {
        if (estimator.text(core.content) <= budget) return core
        val kept = ArrayList<String>()
        for (line in core.content.lines()) {
            if (estimator.text((kept + line).joinToString("\n")) > budget) break
            kept += line
        }
        var text = kept.joinToString("\n").trim()
        if (text.isEmpty()) {
            val first = core.content.trim()
            var end = 0
            while (end < first.length) {
                val next = first.offsetByCodePoints(end, 1)
                if (estimator.text(first.substring(0, next)) > budget) break
                end = next
            }
            text = first.substring(0, end).trim()
        }
        return if (text.isEmpty()) null else core.copy(content = text)
    }

    /** 近期记忆能用多少：最多 [RECENT_MAX_TOKENS]；核心记忆很长时也至少留预算的三分之一，免得每次都要整理 */
    fun recentBudget(budget: Int, coreTokens: Int): Int = minOf(RECENT_MAX_TOKENS, maxOf(budget - coreTokens, budget / 3))

    /**
     * 要整理进核心记忆的近期记忆：近期记忆超出 [recentBudget] 时，最早的一半没被改过的（至少 1 条，最多 [FOLD_MAX_TOKENS]）。
     * 不用整理时为空
     */
    fun planFold(memories: List<Memory>, budget: Int, estimator: TokenEstimator): List<Memory> {
        val coreTokens = memories.firstOrNull { it.core }?.let { estimator.text(it.content) } ?: 0
        val recent = memories.filterNot { it.core }
        if (recent.sumOf { estimator.text(it.content) } <= recentBudget(budget, coreTokens)) return emptyList()
        val candidates = recent.filterNot { it.edited }
        if (candidates.isEmpty()) return emptyList()
        val count = ((candidates.size + 1) / 2).coerceAtLeast(1)
        var used = 0
        val picked = ArrayList<Memory>()
        for (memory in candidates.take(count)) {
            used += estimator.text(memory.content)
            if (used > FOLD_MAX_TOKENS && picked.isNotEmpty()) break
            picked += memory
        }
        return picked
    }

    /**
     * 要自动总结的一批消息；不需要总结时为空。
     *
     * @param unsummarized 还没总结的消息，按时间正序
     * @param historyBudget 预算减去系统提示词和记忆之后，留给聊天记录的部分
     */
    fun planBatch(unsummarized: List<Message>, historyBudget: Int, estimator: TokenEstimator): List<Message> {
        // 等待回复、待投递的消息和之后的消息还不算「较早的聊天」
        val done = unsummarized.takeWhile { it.status != MessageStatus.SENDING && it.status != MessageStatus.PENDING }
        val total = done.sumOf { estimator.message(it) }
        if (total <= historyBudget.coerceAtLeast(MIN_HISTORY_BUDGET) * TRIGGER_RATIO) return emptyList()
        return takeOldest(done, (total / 2).coerceAtMost(BATCH_MAX_TOKENS), estimator)
    }

    /**
     * 「总结更早的聊天」的一批：紧挨着已总结部分的最新一段，最多 [BATCH_MAX_TOKENS]。
     * 这样分批往前总结，每批都和已有的记忆连在一起。
     *
     * @param older 早于记忆起点的消息，按时间正序
     */
    fun planBacklog(older: List<Message>, estimator: TokenEstimator): List<Message> {
        var used = 0
        var start = older.size
        while (start > 0) {
            val groupStart = groupStart(older, start)
            val cost = older.subList(groupStart, start).sumOf { estimator.message(it) }
            if (used + cost > BATCH_MAX_TOKENS && start != older.size) break
            used += cost
            start = groupStart
        }
        return older.subList(start, older.size).toList()
    }

    /**
     * 发给模型的聊天记录：只有发送成功的消息，图片写成文字，思考内容不带。
     * [textOf] 是文字在请求里的样子（0.9.0：带引用的用户消息前面多一行，见 ContextBuilder.textOf）；默认是原文
     */
    fun transcript(
        messages: List<Message>,
        myName: String,
        contactName: String,
        imagePlaceholder: String,
        textOf: (Message) -> String = { it.content },
    ): String =
        messages.filter { it.status == MessageStatus.SUCCESS }
            .joinToString("\n") { message ->
                val speaker = if (message.role == Role.USER) myName else contactName
                val text = if (message.type == MessageType.IMAGE) imagePlaceholder else textOf(message)
                "$speaker：$text"
            }

    /** 从最早的开始取，取够 [limit] 为止；同一次回复拆出的多条不拆开 */
    private fun takeOldest(messages: List<Message>, limit: Int, estimator: TokenEstimator): List<Message> {
        var used = 0
        var end = 0
        while (end < messages.size && used < limit) {
            val group = messages[end].replyGroup
            do {
                used += estimator.message(messages[end])
                end++
            } while (group != null && end < messages.size && messages[end].replyGroup == group)
        }
        return messages.subList(0, end).toList()
    }

    private fun groupStart(messages: List<Message>, end: Int): Int {
        val group = messages[end - 1].replyGroup ?: return end - 1
        var start = end - 1
        while (start > 0 && messages[start - 1].replyGroup == group) start--
        return start
    }
}
