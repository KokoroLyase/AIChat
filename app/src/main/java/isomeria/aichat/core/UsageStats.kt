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
    /** 其中主动聊天用掉的 token（0.6.0；含模型决定不发的那些请求） */
    val proactiveTokens: Long = 0,
    /** 语音合成的字数和合成出的秒数、语音识别的秒数（0.7.0）：按字数或时长计费，不算进 token */
    val ttsChars: Long = 0,
    val ttsSeconds: Long = 0,
    val asrSeconds: Long = 0,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
    val hasVoice: Boolean get() = ttsChars > 0 || ttsSeconds > 0 || asrSeconds > 0
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
            .sortedWith(compareByDescending<UsageTotal> { it.totalTokens }.thenByDescending { it.ttsChars + it.asrSeconds }.thenBy { it.key })

    /** 语音合成、识别（0.7.0）存的是字数和秒数，单独合计，不混进 token 和请求次数 */
    private val VOICE = setOf(UsagePurpose.TTS, UsagePurpose.ASR)

    private fun sum(key: Long, all: List<UsageDaily>): UsageTotal {
        val rows = all.filter { it.purpose !in VOICE }
        val tts = all.filter { it.purpose == UsagePurpose.TTS }
        return UsageTotal(
            key = key,
            promptTokens = rows.sumOf { it.promptTokens },
            completionTokens = rows.sumOf { it.completionTokens },
            memoryTokens = rows.filter { it.purpose == UsagePurpose.MEMORY }.sumOf { it.promptTokens + it.completionTokens },
            requests = rows.sumOf { it.requests },
            proactiveTokens = rows.filter { it.purpose == UsagePurpose.PROACTIVE }.sumOf { it.promptTokens + it.completionTokens },
            ttsChars = tts.sumOf { it.promptTokens },
            ttsSeconds = tts.sumOf { it.completionTokens },
            asrSeconds = all.filter { it.purpose == UsagePurpose.ASR }.sumOf { it.promptTokens },
        )
    }
}
