package isomeria.aichat.core

import isomeria.aichat.data.db.UsageDaily
import isomeria.aichat.data.db.UsagePurpose
import java.time.Instant
import java.time.ZoneId

/** 用量统计的时间范围。[days] 为 null 时是全部。 */
enum class UsageRange(val days: Int?) {
    WEEK(7),
    MONTH(30),
    ALL(null),
}

/** 一行合计。[key] 是日期（epochDay）或联系人 id。 */
data class UsageTotal(
    val key: Long,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    /** 其中记忆总结用掉的 token */
    val memoryTokens: Long = 0,
    val requests: Int = 0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
}

/** 把按天、按联系人存的用量汇总成页面要显示的几种列表。 */
object UsageStats {
    fun epochDay(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toEpochDay()

    /** 只留下范围内的行：包含今天在内的最近 N 天 */
    fun inRange(rows: List<UsageDaily>, range: UsageRange, today: Long): List<UsageDaily> {
        val days = range.days ?: return rows
        val first = today - days + 1
        return rows.filter { it.day in first..today }
    }

    fun total(rows: List<UsageDaily>): UsageTotal = sum(0, rows)

    /** 按天合计，最近的在前 */
    fun byDay(rows: List<UsageDaily>): List<UsageTotal> =
        rows.groupBy { it.day }.map { (day, list) -> sum(day, list) }.sortedByDescending { it.key }

    /** 按联系人合计，用得多的在前；用量相同时按 id 排，顺序稳定 */
    fun byContact(rows: List<UsageDaily>): List<UsageTotal> =
        rows.groupBy { it.contactId }.map { (id, list) -> sum(id, list) }
            .sortedWith(compareByDescending<UsageTotal> { it.totalTokens }.thenBy { it.key })

    private fun sum(key: Long, rows: List<UsageDaily>) = UsageTotal(
        key = key,
        promptTokens = rows.sumOf { it.promptTokens },
        completionTokens = rows.sumOf { it.completionTokens },
        memoryTokens = rows.filter { it.purpose == UsagePurpose.MEMORY }.sumOf { it.promptTokens + it.completionTokens },
        requests = rows.sumOf { it.requests },
    )
}
