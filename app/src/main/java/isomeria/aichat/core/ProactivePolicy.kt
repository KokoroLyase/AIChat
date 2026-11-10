package isomeria.aichat.core

import isomeria.aichat.data.db.ProactiveFrequency
import java.text.Normalizer
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.pow
import kotlin.random.Random

/** 今日已用的主动调用次数。[day] 是本地日期的 epochDay，换了一天就从 0 算。 */
data class ProactiveQuota(val day: Long = 0, val used: Int = 0) {
    fun usedOn(today: Long): Int = if (day == today) used else 0

    fun canSpend(today: Long, limit: Int): Boolean = usedOn(today) < limit

    /** 用掉一次：在发请求之前调用，[SKIP] 和失败也算 */
    fun spend(today: Long): ProactiveQuota = ProactiveQuota(today, usedOn(today) + 1)
}

/** 这次为什么不为某个联系人发起主动请求。前五种不调用模型就能判断，后两种在读配置时发现。 */
enum class ProactiveBlock {
    /** 现在在免打扰时段里 */
    QUIET_HOURS,

    /** 今天的调用次数用完了 */
    QUOTA_EXHAUSTED,

    /** 连续 [ProactivePolicy.MAX_UNANSWERED] 次主动发送都没有得到回复 */
    PAUSED,

    /** 正在等这个联系人回复，或上一轮回复还没送完 */
    BUSY,

    /** 用户此刻正在看这个聊天 */
    VIEWING,

    /** 服务商没有配置 API Key */
    NO_KEY,

    /** 模型配置校验不通过 */
    INVALID_CONFIG,
}

/** 判断要不要发起主动请求所看的状态，都是不调用模型就能知道的。 */
data class ProactiveSituation(
    val quietHours: Boolean,
    val quotaLeft: Boolean,
    val unanswered: Int,
    val busy: Boolean,
    val viewing: Boolean,
)

/** 回复里解析出的一个约定时间。 */
data class ParsedReminder(val dueAt: Long, val note: String)

/**
 * 主动聊天（0.6.0）里不依赖数据库和网络的判断，时钟和随机数都由调用方传入。
 *
 * - 免打扰时段、每日上限、连续未回复暂停、正在等回复、正在看聊天：直接跳过，不调用模型
 * - 触发概率：距上次互动不到最短间隔时为 0；之后每次检查按频率给一个概率，晚上略高、深夜略低，
 *   两次检查隔得久（系统推迟了任务）时按间隔折算，有上限
 * - [isSkip]：模型表示「现在不想找对方」。判断要宽：把 [SKIP] 显示给用户比少发一条严重得多
 * - 约定提醒：用户明确约好的时间，不受免打扰时段、暂停和概率限制，但仍占每日次数
 */
object ProactivePolicy {
    /** 连续这么多次主动发送没有回复就暂停（按发送次数算，一次发送可以拆成几个气泡；用户决定） */
    const val MAX_UNANSWERED = 2

    /** 周期检查的间隔，WorkManager 允许的最小值 */
    const val CHECK_INTERVAL_MILLIS = 15 * 60_000L

    /** 约定提醒晚了这么久还没轮到（系统冻结了后台）就不再发 */
    const val REMINDER_GRACE_MILLIS = 12 * 3_600_000L

    /** 只记录这么远以内的约定 */
    const val REMINDER_MAX_AHEAD_MILLIS = 60 * 86_400_000L

    /** 每个联系人最多同时记着的约定数 */
    const val MAX_REMINDERS_PER_CONTACT = 10

    private const val HOUR = 3_600_000L
    private const val MAX_CHECKS = 8.0
    private const val MIN_CHECKS = 1.0 / 15
    private const val MAX_CHANCE = 0.95
    private const val LONG_IDLE_MILLIS = 72 * HOUR

    // ---------- 免打扰时段 ----------

    /** [minuteOfDay] 是一天里的第几分钟。开始晚于结束表示跨午夜；开始等于结束表示没有免打扰时段 */
    fun inQuietHours(minuteOfDay: Int, settings: ProactiveSettings): Boolean {
        if (!settings.quietEnabled) return false
        val start = settings.quietStartMinutes
        val end = settings.quietEndMinutes
        return when {
            start == end -> false
            start < end -> minuteOfDay in start until end
            else -> minuteOfDay >= start || minuteOfDay < end
        }
    }

    fun inQuietHours(now: Long, settings: ProactiveSettings, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val time = Instant.ofEpochMilli(now).atZone(zone)
        return inQuietHours(time.hour * 60 + time.minute, settings)
    }

    // ---------- 跳过条件 ----------

    /** 不调用模型就跳过的原因；null 表示可以继续。[reminder] 是用户约好时间的提醒 */
    fun blockFor(situation: ProactiveSituation, reminder: Boolean = false): ProactiveBlock? = when {
        !reminder && situation.quietHours -> ProactiveBlock.QUIET_HOURS
        !situation.quotaLeft -> ProactiveBlock.QUOTA_EXHAUSTED
        !reminder && isPaused(situation.unanswered) -> ProactiveBlock.PAUSED
        situation.busy -> ProactiveBlock.BUSY
        !reminder && situation.viewing -> ProactiveBlock.VIEWING
        else -> null
    }

    fun isPaused(unanswered: Int): Boolean = unanswered >= MAX_UNANSWERED

    // ---------- 触发概率 ----------

    /** 距上次互动至少这么久才可能主动 */
    fun minGapMillis(frequency: ProactiveFrequency): Long = when (frequency) {
        ProactiveFrequency.LOW -> 6 * HOUR
        ProactiveFrequency.MEDIUM -> 3 * HOUR
        ProactiveFrequency.HIGH -> 1 * HOUR
    }

    private fun chancePerCheck(frequency: ProactiveFrequency): Double = when (frequency) {
        ProactiveFrequency.LOW -> 0.04
        ProactiveFrequency.MEDIUM -> 0.08
        ProactiveFrequency.HIGH -> 0.16
    }

    private fun hourFactor(hour: Int): Double = when (hour) {
        in 0..5 -> 0.3
        in 7..8, in 11..13 -> 1.2
        in 18..22 -> 1.5
        else -> 1.0
    }

    private fun idleFactor(idleMillis: Long, minGap: Long): Double = when {
        idleMillis >= LONG_IDLE_MILLIS -> 0.5
        idleMillis < 2 * minGap -> 0.6
        else -> 1.0
    }

    /**
     * 这次检查为某个联系人发起主动请求的概率。
     *
     * @param idleMillis 距上次互动（最后一条消息、上一次主动请求、最近一次改联系人设置里最晚的）多久
     * @param sinceLastCheckMillis 距上一次检查多久；从没检查过时传 [CHECK_INTERVAL_MILLIS]
     * @param hourOfDay 现在是几点（0～23）
     */
    fun triggerProbability(frequency: ProactiveFrequency, idleMillis: Long, sinceLastCheckMillis: Long, hourOfDay: Int): Double {
        val minGap = minGapMillis(frequency)
        if (idleMillis < minGap) return 0.0
        val once = (chancePerCheck(frequency) * hourFactor(hourOfDay) * idleFactor(idleMillis, minGap)).coerceIn(0.0, MAX_CHANCE)
        val checks = (sinceLastCheckMillis.toDouble() / CHECK_INTERVAL_MILLIS).coerceIn(MIN_CHECKS, MAX_CHECKS)
        return 1 - (1 - once).pow(checks)
    }

    fun roll(probability: Double, random: Random): Boolean = probability > 0 && random.nextDouble() < probability

    // ---------- [SKIP] ----------

    /**
     * 模型的回复是不是「不发」。去掉空白和标点后含有 SKIP（不分大小写、全角半角）就算；空回复也算。
     * 宁可少发一条，也不能把 [SKIP] 显示给用户。
     */
    fun isSkip(text: String): Boolean {
        if (text.isBlank()) return true
        val compact = Normalizer.normalize(text, Normalizer.Form.NFKC).filter { it.isLetterOrDigit() }.uppercase()
        return "SKIP" in compact
    }

    // ---------- 约定提醒的隐藏标记 ----------

    private val reminderMarker = Regex("""[\[【]\s*提醒\s+(\d{4})\s*-\s*(\d{1,2})\s*-\s*(\d{1,2})\s+(\d{1,2})\s*[:：]\s*(\d{1,2})\s*([^\]】\n]*)[\]】]""")

    /** 没按格式写的标记（比如没有具体日期）也要去掉，不能显示给用户 */
    private val looseMarker = Regex("""[\[【]\s*提醒[^\]】\n]*[\]】]""")

    /**
     * 从回复里取出约定标记 `[提醒 2026-11-11 07:00 叫他起床]`，返回去掉标记后的正文和解析出的约定。
     * 标记里的时间按手机当前时区理解；已经过去的、太远的、日期不存在的只去掉不记录。
     */
    fun extractReminders(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<String, List<ParsedReminder>> {
        if ("提醒" !in text) return text to emptyList()
        val reminders = reminderMarker.findAll(text).mapNotNull { match ->
            val (year, month, day, hour, minute, note) = match.destructured
            val dueAt = try {
                LocalDateTime.of(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt()).atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeException) {
                return@mapNotNull null
            }
            if (dueAt <= now || dueAt - now > REMINDER_MAX_AHEAD_MILLIS) null else ParsedReminder(dueAt, note.trim())
        }.distinctBy { it.dueAt }.toList()
        val cleaned = text.replace(looseMarker, "").lines().map { it.trimEnd() }
        // 标记原来单独占一行：去掉它留下的空行，但保留正文里原有的空行分段
        val original = text.lines()
        val kept = cleaned.filterIndexed { index, line -> line.isNotBlank() || original[index].isBlank() }
        return kept.joinToString("\n").trim() to reminders
    }
}
