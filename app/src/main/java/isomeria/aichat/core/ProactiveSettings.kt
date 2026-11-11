package isomeria.aichat.core

/**
 * 主动聊天的全局设置（0.6.0，「我 → 主动聊天」），所有开启主动聊天的联系人共用。
 *
 * - 免打扰时段：这段时间里联系人不会自己找上门（不调用模型）。开始晚于结束表示跨午夜（默认 23:00～7:00）；
 *   开始和结束相同表示没有免打扰时段。和单聊的「消息免打扰」（只是不发通知）是两回事。
 *   用户明确约好时间的提醒不受它限制
 * - [dailyLimit]：每天最多调用模型多少次，按本地日期重置；[SKIP]、失败和约定提醒都算
 */
data class ProactiveSettings(
    val quietEnabled: Boolean = true,
    /** 一天里的第几分钟，0～1439 */
    val quietStartMinutes: Int = DEFAULT_QUIET_START,
    val quietEndMinutes: Int = DEFAULT_QUIET_END,
    val dailyLimit: Int = DEFAULT_DAILY_LIMIT,
) {
    /** 把越界的值收回范围内；时间对齐到 [QUIET_STEP_MINUTES] */
    fun normalized(): ProactiveSettings = copy(
        quietStartMinutes = normalizeMinutes(quietStartMinutes),
        quietEndMinutes = normalizeMinutes(quietEndMinutes),
        dailyLimit = dailyLimit.coerceIn(LIMIT_RANGE),
    )

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_QUIET_START = 23 * 60
        const val DEFAULT_QUIET_END = 7 * 60
        const val DEFAULT_DAILY_LIMIT = 20

        /** 界面上调整时间的步长 */
        const val QUIET_STEP_MINUTES = 30
        val LIMIT_RANGE = 1..200

        private fun normalizeMinutes(value: Int): Int {
            val inDay = ((value % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
            return inDay - inDay % QUIET_STEP_MINUTES
        }
    }
}

/** 一次主动请求的结果，显示在「我 → 主动聊天」里，说明为什么没有消息。 */
enum class ProactiveResult { SENT, SKIPPED, FAILED, TIMED_OUT, DISCARDED }

/**
 * 每个联系人最近一次主动请求的结果。只属于这台手机，不随备份；按请求开始的时间和
 * 会话上的 lastProactiveAt 对上才显示，所以恢复数据或清空聊天后不会显示旧结果。
 */
interface ProactiveResultLog {
    fun record(contactId: Long, at: Long, result: ProactiveResult)

    /** 请求开始的时间和结果；没有记录时为 null */
    fun last(contactId: Long): Pair<Long, ProactiveResult>?

    companion object {
        val NONE = object : ProactiveResultLog {
            override fun record(contactId: Long, at: Long, result: ProactiveResult) = Unit
            override fun last(contactId: Long): Pair<Long, ProactiveResult>? = null
        }
    }
}

/** 读取当前的主动聊天设置。单元测试里换成固定值（MMKV 需要原生库）。 */
fun interface ProactiveSettingsSource {
    fun proactiveSettings(): ProactiveSettings
}
