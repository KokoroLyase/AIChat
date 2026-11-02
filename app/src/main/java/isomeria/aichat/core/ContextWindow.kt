package isomeria.aichat.core

import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role

/**
 * 按 token 预算选出这次请求带上的聊天记录：
 *
 * - 从最新的消息往前取，放不下就停，更早的全部丢弃
 * - 同一次回复拆出的多条要么都带，要么都不带
 * - 正在等待回复的消息一定带上，即使超预算
 * - 开头是助手消息时去掉：有些服务商要求第一条是用户消息
 */
object ContextWindow {
    /**
     * 最多要从数据库读多少条才能填满 [budget]：每条消息至少占 [TokenEstimator.MESSAGE_OVERHEAD] 再加一个字。
     * 数据库里多读一些没有关系，[select] 会再按预算筛。
     */
    fun maxRows(budget: Int): Int = budget.coerceAtLeast(0) / (TokenEstimator.MESSAGE_OVERHEAD + 1) + MIN_ROWS

    /** @param history 按时间正序 */
    fun select(history: List<Message>, budget: Int, estimator: TokenEstimator, supportsImage: Boolean): List<Message> {
        val imagesWithData = if (supportsImage) imagesWithData(history) else emptySet()
        val kept = ArrayDeque<Message>()
        var used = 0
        var end = history.size
        while (end > 0) {
            val start = groupStart(history, end)
            val group = history.subList(start, end)
            val cost = group.sumOf { estimator.message(it, it.id in imagesWithData) }
            val waiting = group.any { it.status == MessageStatus.SENDING }
            if (!waiting && used + cost > budget) break
            used += cost
            group.asReversed().forEach(kept::addFirst)
            end = start
        }
        while (kept.firstOrNull()?.role == Role.ASSISTANT) kept.removeFirst()
        return kept.toList()
    }

    /** 带原图的图片：最新的 [ContextBuilder.MAX_IMAGES] 张 */
    fun imagesWithData(history: List<Message>): Set<Long> =
        history.filter { it.type == MessageType.IMAGE && it.imagePath != null }
            .takeLast(ContextBuilder.MAX_IMAGES)
            .mapTo(HashSet()) { it.id }

    /** 以 end - 1 结尾的这一组从哪里开始：同一次回复拆出的多条算一组，其他消息单独成组 */
    private fun groupStart(history: List<Message>, end: Int): Int {
        val group = history[end - 1].replyGroup ?: return end - 1
        var start = end - 1
        while (start > 0 && history[start - 1].replyGroup == group) start--
        return start
    }

    private const val MIN_ROWS = 50
}
