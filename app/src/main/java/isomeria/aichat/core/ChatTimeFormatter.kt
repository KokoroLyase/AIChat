package isomeria.aichat.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 时间显示用到的文字，从 strings.xml 读取后传入，便于以后加英文界面。 */
data class TimeLabels(
    val yesterday: String,
    /** 星期一到星期日 */
    val weekdays: List<String>,
    /** 参数：月、日，如「%1$d月%2$d日」 */
    val monthDay: String,
    /** 参数：年、月、日，如「%1$d年%2$d月%3$d日」 */
    val yearMonthDay: String,
)

/**
 * 和微信一致的时间显示，统一 24 小时制。
 *
 * | 消息时间 | 聊天列表 | 聊天页时间条 |
 * | 今天 | 21:00 | 21:00 |
 * | 昨天 | 昨天 | 昨天 21:00 |
 * | 本周内 | 星期三 | 星期三 21:00 |
 * | 今年更早 | 10月20日 | 10月20日 21:00 |
 * | 往年 | 2025年10月20日 | 2025年10月20日 21:00 |
 *
 * 「本周」按周一到周日计算。
 */
class ChatTimeFormatter(
    private val labels: TimeLabels,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    fun forList(time: Long, now: Long): String = format(time, now, withTime = false)

    fun forChat(time: Long, now: Long): String = format(time, now, withTime = true)

    private fun format(time: Long, now: Long, withTime: Boolean): String {
        val moment = Instant.ofEpochMilli(time).atZone(zone)
        val date = moment.toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = "%02d:%02d".format(moment.hour, moment.minute)

        val day = when {
            !date.isBefore(today) -> return clock
            date == today.minusDays(1) -> labels.yesterday
            isSameWeek(date, today) -> labels.weekdays[date.dayOfWeek.value - 1]
            date.year == today.year -> labels.monthDay.format(date.monthValue, date.dayOfMonth)
            else -> labels.yearMonthDay.format(date.year, date.monthValue, date.dayOfMonth)
        }
        return if (withTime) "$day $clock" else day
    }

    private fun isSameWeek(date: LocalDate, today: LocalDate): Boolean {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return !date.isBefore(monday)
    }

    companion object {
        /** 聊天页中与上一条消息相隔超过 5 分钟才插入时间条 */
        const val TIME_BAR_GAP_MILLIS = 5 * 60 * 1000L

        fun needsTimeBar(previous: Long?, current: Long): Boolean =
            previous == null || current - previous > TIME_BAR_GAP_MILLIS
    }
}
