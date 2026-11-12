package isomeria.aichat.core

/** 通话时长的写法（0.8.0）：不到一小时是「03:21」，超过一小时是「1:02:03」；不足一秒按 0 秒。 */
object CallDurations {
    fun format(millis: Long): String {
        val total = (millis.coerceAtLeast(0) / 1000)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds) else String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
