package isomeria.aichat.core

/**
 * 回复偏好（「我 → 回复偏好」）：开启「使用回复偏好」的联系人按它分条、带间隔地回复。
 *
 * - [waitSeconds]：用户发出消息后先等这么久，期间没有新消息才开始请求模型
 * - [targetLength]、[minCount]～[maxCount]：写进提示词的风格引导，不做硬截断
 * - [stripPunctuation]：去掉每条末尾的「。」「，」，保留「？」「！」「…」
 * - [minIntervalSeconds]～[maxIntervalSeconds]：相邻两条之间的间隔范围
 */
data class ReplyPreferences(
    val waitSeconds: Int = DEFAULT_WAIT_SECONDS,
    val targetLength: Int = DEFAULT_TARGET_LENGTH,
    val minCount: Int = DEFAULT_MIN_COUNT,
    val maxCount: Int = DEFAULT_MAX_COUNT,
    val stripPunctuation: Boolean = true,
    val minIntervalSeconds: Int = DEFAULT_MIN_INTERVAL,
    val maxIntervalSeconds: Int = DEFAULT_MAX_INTERVAL,
) {
    /** 把越界的值收回范围内，并保证最少不超过最多 */
    fun normalized(): ReplyPreferences {
        val minCount = minCount.coerceIn(COUNT_RANGE)
        val minInterval = minIntervalSeconds.coerceIn(INTERVAL_RANGE)
        return copy(
            waitSeconds = waitSeconds.coerceIn(WAIT_RANGE),
            targetLength = targetLength.coerceIn(LENGTH_RANGE),
            minCount = minCount,
            maxCount = maxCount.coerceIn(COUNT_RANGE).coerceAtLeast(minCount),
            minIntervalSeconds = minInterval,
            maxIntervalSeconds = maxIntervalSeconds.coerceIn(INTERVAL_RANGE).coerceAtLeast(minInterval),
        )
    }

    companion object {
        const val DEFAULT_WAIT_SECONDS = 3
        const val DEFAULT_TARGET_LENGTH = 14
        const val DEFAULT_MIN_COUNT = 1
        const val DEFAULT_MAX_COUNT = 5
        const val DEFAULT_MIN_INTERVAL = 3
        const val DEFAULT_MAX_INTERVAL = 10

        val WAIT_RANGE = 0..30
        val LENGTH_RANGE = 2..200
        val COUNT_RANGE = 1..10
        val INTERVAL_RANGE = 1..60
    }
}

/** 读取当前的回复偏好。单元测试里换成固定值（MMKV 需要原生库）。 */
fun interface ReplyPreferencesSource {
    fun replyPreferences(): ReplyPreferences
}
