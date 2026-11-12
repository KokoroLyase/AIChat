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
 * - 每次请求先带核心记忆，再从最新的近期记忆往前放，放不下就不带（不会删除）
 */
object MemoryPolicy {
    const val TRIGGER_RATIO = 0.8
    const val MEMORY_SHARE = 0.25

    /** 每次请求带的记忆最多多少 token（核心加近期） */
    const val MEMORY_MAX_TOKENS = 1500

    /** 近期记忆最多多少 token；超出时最早的整理进核心记忆 */
    const val RECENT_MAX_TOKENS = 800

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
     * 这次请求带哪些记忆：核心记忆放得下就先放，再从最新的近期记忆往前放，放不下就不带（不会删除）。
     * 结果是核心记忆在前，近期记忆按时间正序
     */
    fun forRequest(memories: List<Memory>, budget: Int, estimator: TokenEstimator): List<Memory> {
        val core = memories.firstOrNull { it.core }?.takeIf { estimator.text(it.content) <= budget }
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

    /** 发给模型的聊天记录：只有发送成功的消息，图片写成文字，思考内容不带 */
    fun transcript(messages: List<Message>, myName: String, contactName: String, imagePlaceholder: String): String =
        messages.filter { it.status == MessageStatus.SUCCESS }
            .joinToString("\n") { message ->
                val speaker = if (message.role == Role.USER) myName else contactName
                val text = if (message.type == MessageType.IMAGE) imagePlaceholder else message.content
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
