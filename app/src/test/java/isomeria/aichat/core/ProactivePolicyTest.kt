package isomeria.aichat.core

import isomeria.aichat.data.db.ProactiveFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

/** 主动聊天的纯逻辑：免打扰时段、每日上限、暂停、触发概率、[SKIP]、约定标记。时钟和随机数都是传进去的。 */
class ProactivePolicyTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(hour: Int, minute: Int = 0, day: Int = 10) = LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val hour = 3_600_000L

    // ---------- 设置 ----------

    @Test
    fun defaults_areQuiet23To7_and20PerDay() {
        val settings = ProactiveSettings()
        assertTrue(settings.quietEnabled)
        assertEquals(23 * 60, settings.quietStartMinutes)
        assertEquals(7 * 60, settings.quietEndMinutes)
        assertEquals(20, settings.dailyLimit)
        assertEquals(settings, settings.normalized())
        assertEquals(2, ProactivePolicy.MAX_UNANSWERED)
    }

    @Test
    fun normalized_clampsLimitAndWrapsTimesToHalfHours() {
        val normalized = ProactiveSettings(quietStartMinutes = 24 * 60 + 45, quietEndMinutes = -10, dailyLimit = 0).normalized()
        assertEquals(30, normalized.quietStartMinutes)
        assertEquals(23 * 60 + 30, normalized.quietEndMinutes)
        assertEquals(1, normalized.dailyLimit)
        assertEquals(200, ProactiveSettings(dailyLimit = 9999).normalized().dailyLimit)
    }

    // ---------- 免打扰时段 ----------

    @Test
    fun quietHours_acrossMidnight_coversLateNightAndEarlyMorning_endIsExclusive() {
        val settings = ProactiveSettings()
        assertFalse(ProactivePolicy.inQuietHours(at(22, 59), settings, zone))
        assertTrue(ProactivePolicy.inQuietHours(at(23, 0), settings, zone))
        assertTrue(ProactivePolicy.inQuietHours(at(0, 0, day = 11), settings, zone))
        assertTrue(ProactivePolicy.inQuietHours(at(6, 59, day = 11), settings, zone))
        assertFalse(ProactivePolicy.inQuietHours(at(7, 0, day = 11), settings, zone))
        assertFalse(ProactivePolicy.inQuietHours(at(15, 0), settings, zone))
    }

    @Test
    fun quietHours_sameDayRange_equalTimes_andSwitchOff() {
        val noon = ProactiveSettings(quietStartMinutes = 12 * 60, quietEndMinutes = 14 * 60)
        assertFalse(ProactivePolicy.inQuietHours(11 * 60 + 59, noon))
        assertTrue(ProactivePolicy.inQuietHours(12 * 60, noon))
        assertTrue(ProactivePolicy.inQuietHours(13 * 60 + 59, noon))
        assertFalse(ProactivePolicy.inQuietHours(14 * 60, noon))
        assertFalse(ProactivePolicy.inQuietHours(23 * 60 + 30, noon))
        val equal = ProactiveSettings(quietStartMinutes = 8 * 60, quietEndMinutes = 8 * 60)
        assertTrue("开始等于结束：没有免打扰时段", (0 until 1440 step 30).none { ProactivePolicy.inQuietHours(it, equal) })
        val off = ProactiveSettings(quietEnabled = false)
        assertTrue((0 until 1440 step 30).none { ProactivePolicy.inQuietHours(it, off) })
    }

    @Test
    fun quietHours_useTheGivenZone() {
        // 同一时刻：上海 23:30 在时段里，伦敦 15:30 不在
        val moment = at(23, 30)
        assertTrue(ProactivePolicy.inQuietHours(moment, ProactiveSettings(), zone))
        assertFalse(ProactivePolicy.inQuietHours(moment, ProactiveSettings(), ZoneId.of("Europe/London")))
    }

    // ---------- 每日上限 ----------

    @Test
    fun quota_countsWithinTheDay_andResetsOnANewLocalDate() {
        var quota = ProactiveQuota()
        repeat(3) { quota = quota.spend(today = 100) }
        assertEquals(ProactiveQuota(100, 3), quota)
        assertTrue(quota.canSpend(today = 100, limit = 4))
        assertFalse(quota.canSpend(today = 100, limit = 3))
        // 第二天：昨天用掉的不算
        assertEquals(0, quota.usedOn(101))
        assertTrue(quota.canSpend(today = 101, limit = 3))
        assertEquals(ProactiveQuota(101, 1), quota.spend(today = 101))
        // 把手机日期调回前一天也从 0 算，不会变成负数或沿用旧值
        assertEquals(ProactiveQuota(99, 1), quota.spend(today = 99))
    }

    // ---------- 跳过条件 ----------

    private val clear = ProactiveSituation(quietHours = false, quotaLeft = true, unanswered = 0, busy = false, viewing = false)

    @Test
    fun blockFor_eachConditionSkips_inAFixedOrder() {
        assertNull(ProactivePolicy.blockFor(clear))
        assertEquals(ProactiveBlock.QUIET_HOURS, ProactivePolicy.blockFor(clear.copy(quietHours = true)))
        assertEquals(ProactiveBlock.QUOTA_EXHAUSTED, ProactivePolicy.blockFor(clear.copy(quotaLeft = false)))
        assertEquals(ProactiveBlock.PAUSED, ProactivePolicy.blockFor(clear.copy(unanswered = 2)))
        assertEquals(ProactiveBlock.BUSY, ProactivePolicy.blockFor(clear.copy(busy = true)))
        assertEquals(ProactiveBlock.VIEWING, ProactivePolicy.blockFor(clear.copy(viewing = true)))
        // 几种同时成立时先报全局的
        val everything = ProactiveSituation(quietHours = true, quotaLeft = false, unanswered = 5, busy = true, viewing = true)
        assertEquals(ProactiveBlock.QUIET_HOURS, ProactivePolicy.blockFor(everything))
        assertEquals(ProactiveBlock.QUOTA_EXHAUSTED, ProactivePolicy.blockFor(everything.copy(quietHours = false)))
    }

    @Test
    fun pause_startsAtTwoUnansweredSends_andEndsWhenCounterIsCleared() {
        assertFalse(ProactivePolicy.isPaused(0))
        assertFalse(ProactivePolicy.isPaused(1))
        assertTrue(ProactivePolicy.isPaused(2))
        assertTrue(ProactivePolicy.isPaused(3))
        assertNull(ProactivePolicy.blockFor(clear.copy(unanswered = 1)))
        assertNull("用户发言后计数清零，恢复", ProactivePolicy.blockFor(clear.copy(unanswered = 0)))
    }

    @Test
    fun reminder_ignoresQuietHoursPauseAndViewing_butNotQuotaOrBusy() {
        val blocked = ProactiveSituation(quietHours = true, quotaLeft = true, unanswered = 2, busy = false, viewing = true)
        assertNull(ProactivePolicy.blockFor(blocked, reminder = true))
        assertEquals(ProactiveBlock.QUOTA_EXHAUSTED, ProactivePolicy.blockFor(blocked.copy(quotaLeft = false), reminder = true))
        assertEquals(ProactiveBlock.BUSY, ProactivePolicy.blockFor(blocked.copy(busy = true), reminder = true))
    }

    /** 0.8.0：正在和这个联系人通话时跳过，约定提醒也等通话结束 */
    @Test
    fun inCall_skips_evenForAReminder() {
        assertEquals(ProactiveBlock.IN_CALL, ProactivePolicy.blockFor(clear.copy(inCall = true)))
        assertEquals(ProactiveBlock.IN_CALL, ProactivePolicy.blockFor(clear.copy(inCall = true, viewing = true), reminder = true))
        assertNull(ProactivePolicy.blockFor(clear.copy(inCall = false)))
    }

    // ---------- 触发概率 ----------

    private val interval = ProactivePolicy.CHECK_INTERVAL_MILLIS

    @Test
    fun probability_isZeroBeforeTheMinimumGap_forEachFrequency() {
        for ((frequency, gapHours) in listOf(ProactiveFrequency.LOW to 6, ProactiveFrequency.MEDIUM to 3, ProactiveFrequency.HIGH to 1)) {
            assertEquals(gapHours * hour, ProactivePolicy.minGapMillis(frequency))
            assertEquals(frequency.name, 0.0, ProactivePolicy.triggerProbability(frequency, gapHours * hour - 1, interval, 15), 0.0)
            assertTrue(frequency.name, ProactivePolicy.triggerProbability(frequency, gapHours * hour, interval, 15) > 0)
        }
    }

    @Test
    fun probability_ordersByFrequency_andStaysBelowOne() {
        val idle = 24 * hour
        val low = ProactivePolicy.triggerProbability(ProactiveFrequency.LOW, idle, interval, 15)
        val medium = ProactivePolicy.triggerProbability(ProactiveFrequency.MEDIUM, idle, interval, 15)
        val high = ProactivePolicy.triggerProbability(ProactiveFrequency.HIGH, idle, interval, 15)
        assertEquals(0.04, low, 1e-9)
        assertEquals(0.08, medium, 1e-9)
        assertEquals(0.16, high, 1e-9)
        for (frequency in ProactiveFrequency.entries) for (h in 0..23) for (since in listOf(0L, interval, 100 * interval)) {
            val p = ProactivePolicy.triggerProbability(frequency, idle, since, h)
            assertTrue("$frequency $h $since -> $p", p > 0 && p < 1)
        }
    }

    @Test
    fun probability_eveningHigher_lateNightLower() {
        val idle = 24 * hour
        fun p(h: Int) = ProactivePolicy.triggerProbability(ProactiveFrequency.MEDIUM, idle, interval, h)
        assertEquals(0.08, p(15), 1e-9)
        assertEquals(0.12, p(20), 1e-9)
        assertEquals(0.096, p(8), 1e-9)
        assertEquals(0.096, p(12), 1e-9)
        assertEquals(0.024, p(3), 1e-9)
    }

    @Test
    fun probability_justPastTheGapOrIdleForDays_isLower() {
        fun p(idle: Long) = ProactivePolicy.triggerProbability(ProactiveFrequency.MEDIUM, idle, interval, 15)
        assertEquals(0.048, p(3 * hour), 1e-9)
        assertEquals(0.048, p(6 * hour - 1), 1e-9)
        assertEquals(0.08, p(6 * hour), 1e-9)
        assertEquals(0.04, p(72 * hour), 1e-9)
    }

    @Test
    fun probability_scalesWithTimeSinceLastCheck_withACap() {
        fun p(since: Long) = ProactivePolicy.triggerProbability(ProactiveFrequency.MEDIUM, 24 * hour, since, 15)
        // 刚检查过又回到前台：几乎不会触发
        assertTrue(p(60_000) < 0.01)
        assertEquals(p(60_000), p(0), 1e-12)
        // 隔了两个周期：相当于两次独立检查
        assertEquals(1 - 0.92 * 0.92, p(2 * interval), 1e-9)
        // 被系统冻结了一整天：按 8 个周期封顶，不会一解冻就必发
        assertEquals(p(8 * interval), p(96 * interval), 1e-12)
        assertTrue(p(96 * interval) < 0.5)
    }

    @Test
    fun roll_usesTheInjectedRandom() {
        val low = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextDouble() = 0.05
        }
        val high = object : Random() {
            override fun nextBits(bitCount: Int) = 0
            override fun nextDouble() = 0.5
        }
        assertTrue(ProactivePolicy.roll(0.08, low))
        assertFalse(ProactivePolicy.roll(0.08, high))
        assertFalse("概率为 0 时永远不触发", ProactivePolicy.roll(0.0, low))
        assertFalse(ProactivePolicy.roll(0.05, low))
    }

    // ---------- [SKIP] ----------

    @Test
    fun isSkip_isLenient() {
        val skips = listOf(
            "[SKIP]", "SKIP", "skip", " [ skip ] ", "【SKIP】", "［ＳＫＩＰ］", "[S K I P]", "S.K.I.P", "[SKIP]。", "（[SKIP]）",
            "现在不合适\n[SKIP]", "[SKIP] 我先不打扰了", "[Skip]\n", "", "   ", "\n\n",
        )
        for (text in skips) assertTrue("应当视为不发：「$text」", ProactivePolicy.isSkip(text))
        val sends = listOf("在忙吗", "早上好！", "😊", "？", "今天 skate 去吗", "要不要去滑冰", "SKI 场开了", "s k 1 p")
        for (text in sends) assertFalse("应当照常发送：「$text」", ProactivePolicy.isSkip(text))
    }

    // ---------- 约定标记 ----------

    private val now = at(22, 0)
    private val marker = ReminderMarker("提醒")

    @Test
    fun extractReminders_stripsTheMarkerLine_andParsesLocalTime() {
        val (text, reminders) = marker.extract("好的，明早叫你\n[提醒 2026-11-11 07:00 叫他起床]", now, zone)
        assertEquals("好的，明早叫你", text)
        assertEquals(listOf(ParsedReminder(at(7, 0, day = 11), "叫他起床")), reminders)
    }

    @Test
    fun extractReminders_acceptsFullWidthBracketsColonAndSingleDigits_andNoNote() {
        val (text, reminders) = marker.extract("行\n【提醒 2026-11-11 7：05】\n到时候喊你", now, zone)
        assertEquals("行\n到时候喊你", text)
        assertEquals(listOf(ParsedReminder(at(7, 5, day = 11), "")), reminders)
        val (inline, inlineReminders) = marker.extract("好呀 [提醒 2026-11-10 23:30 提醒睡觉]", now, zone)
        assertEquals("好呀", inline)
        assertEquals(at(23, 30), inlineReminders.single().dueAt)
    }

    @Test
    fun extractReminders_keepsParagraphBreaks_andSeveralMarkers() {
        val reply = "第一段\n\n第二段\n[提醒 2026-11-11 07:00 起床]\n[提醒 2026-11-11 15:00 开会]\n[提醒 2026-11-11 07:00 重复的]"
        val (text, reminders) = marker.extract(reply, now, zone)
        assertEquals("第一段\n\n第二段", text)
        assertEquals(listOf("起床", "开会"), reminders.map { it.note })
    }

    @Test
    fun extractReminders_pastTooFarOrImpossibleTimes_areRemovedButNotRecorded() {
        for (written in listOf(
            "[提醒 2026-11-10 21:00 已经过去]",
            "[提醒 2026-11-10 22:00 就是现在]",
            "[提醒 2027-03-01 08:00 太远了]",
            "[提醒 2026-02-30 08:00 没有这一天]",
            "[提醒 2026-11-11 25:00 没有这个点]",
            "[提醒 明早七点 没按格式写]",
            "[提醒]",
        )) {
            val (text, reminders) = marker.extract("好的\n$written", now, zone)
            assertEquals(written, "好的", text)
            assertTrue(written, reminders.isEmpty())
        }
    }

    @Test
    fun extractReminders_usesTheKeywordItWasGiven() {
        val other = ReminderMarker("REMIND")
        assertEquals("ok" to listOf(ParsedReminder(at(7, 0, day = 11), "wake")), other.extract("ok\n[REMIND 2026-11-11 07:00 wake]", now, zone))
        assertEquals("好的\n[提醒 2026-11-11 07:00 起床]", other.extract("好的\n[提醒 2026-11-11 07:00 起床]", now, zone).first)
        // 关键词为空时什么也不做
        assertEquals("[ 2026-11-11 07:00 x]" to emptyList<ParsedReminder>(), ReminderMarker(" ").extract("[ 2026-11-11 07:00 x]", now, zone))
    }

    @Test
    fun extractReminders_leavesOrdinaryTextAlone() {
        for (reply in listOf("好的\n\n马上来", "记得提醒我带伞", "  前后空白保留  ", "[图片]\n看看", "")) {
            assertEquals(reply to emptyList<ParsedReminder>(), marker.extract(reply, now, zone))
        }
        // 只有标记的回复：正文为空，约定照记
        val (text, reminders) = marker.extract("[提醒 2026-11-11 07:00 起床]", now, zone)
        assertEquals("", text)
        assertEquals(1, reminders.size)
    }
}
