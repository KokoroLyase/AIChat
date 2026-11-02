package isomeria.aichat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatTimeFormatterTest {
    @Test
    fun forDate_omitsYearOnlyForThisYear() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val formatter = ChatTimeFormatter(
            TimeLabels("昨天", List(7) { "星期$it" }, "%1\$d月%2\$d日", "%1\$d年%2\$d月%3\$d日"),
            zone,
        )
        fun at(year: Int, month: Int, day: Int) =
            java.time.LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val now = at(2026, 11, 2)
        assertEquals("11月2日", formatter.forDate(now, now))
        assertEquals("10月20日", formatter.forDate(at(2026, 10, 20), now))
        assertEquals("2025年10月20日", formatter.forDate(at(2025, 10, 20), now))
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private val formatter = ChatTimeFormatter(
        TimeLabels(
            yesterday = "昨天",
            weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"),
            monthDay = "%1\$d月%2\$d日",
            yearMonthDay = "%1\$d年%2\$d月%3\$d日",
        ),
        zone,
    )

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    /** 2026-11-01 是星期日 */
    private val sundayNight = at(2026, 11, 1, 21, 30)

    @Test
    fun today_showsClockOnly() {
        assertEquals("09:05", formatter.forList(at(2026, 11, 1, 9, 5), sundayNight))
        assertEquals("09:05", formatter.forChat(at(2026, 11, 1, 9, 5), sundayNight))
        assertEquals("00:00", formatter.forList(at(2026, 11, 1, 0, 0), sundayNight))
    }

    @Test
    fun yesterday() {
        assertEquals("昨天", formatter.forList(at(2026, 10, 31, 21, 0), sundayNight))
        assertEquals("昨天 21:00", formatter.forChat(at(2026, 10, 31, 21, 0), sundayNight))
    }

    @Test
    fun earlierThisWeek_showsWeekday() {
        // 2026-10-28 是星期三，与 11-01 同一周（周一 10-26 开始）
        assertEquals("星期三", formatter.forList(at(2026, 10, 28, 21, 0), sundayNight))
        assertEquals("星期三 21:00", formatter.forChat(at(2026, 10, 28, 21, 0), sundayNight))
        assertEquals("星期一", formatter.forList(at(2026, 10, 26, 0, 1), sundayNight))
    }

    @Test
    fun earlierThisYear_showsMonthAndDay() {
        // 10-25 是上周日，不在本周
        assertEquals("10月25日", formatter.forList(at(2026, 10, 25, 23, 59), sundayNight))
        assertEquals("10月20日 21:00", formatter.forChat(at(2026, 10, 20, 21, 0), sundayNight))
        assertEquals("1月1日", formatter.forList(at(2026, 1, 1, 8, 0), sundayNight))
    }

    @Test
    fun previousYears_showFullDate() {
        assertEquals("2025年10月20日", formatter.forList(at(2025, 10, 20, 21, 0), sundayNight))
        assertEquals("2025年10月20日 21:00", formatter.forChat(at(2025, 10, 20, 21, 0), sundayNight))
    }

    @Test
    fun mondayMorning_yesterdayIsSundayAndSaturdayIsLastWeek() {
        val monday = at(2026, 11, 2, 8, 0)
        assertEquals("昨天", formatter.forList(at(2026, 11, 1, 23, 0), monday))
        assertEquals("10月31日", formatter.forList(at(2026, 10, 31, 12, 0), monday))
    }

    @Test
    fun crossingYear_yesterdayStillYesterday() {
        val newYear = at(2027, 1, 1, 10, 0)
        assertEquals("昨天 23:59", formatter.forChat(at(2026, 12, 31, 23, 59), newYear))
    }

    @Test
    fun slightlyFutureTime_isTreatedAsToday() {
        // 分条回复的后几条时间会比现在晚一两秒
        assertEquals("21:30", formatter.forList(sundayNight + 2_000, sundayNight))
    }

    @Test
    fun alwaysTwentyFourHour() {
        assertEquals("23:07", formatter.forList(at(2026, 11, 1, 23, 7), at(2026, 11, 1, 23, 59)))
        assertEquals("13:00", formatter.forList(at(2026, 11, 1, 13, 0), sundayNight))
    }

    @Test
    fun timeBar_onlyWhenGapExceedsFiveMinutes() {
        assertTrue(ChatTimeFormatter.needsTimeBar(null, sundayNight))
        assertFalse(ChatTimeFormatter.needsTimeBar(sundayNight, sundayNight + 5 * 60_000))
        assertTrue(ChatTimeFormatter.needsTimeBar(sundayNight, sundayNight + 5 * 60_000 + 1))
        assertFalse(ChatTimeFormatter.needsTimeBar(sundayNight, sundayNight + 1_000))
    }
}
