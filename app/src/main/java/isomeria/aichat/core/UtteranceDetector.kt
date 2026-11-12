package isomeria.aichat.core

/**
 * 通话里判断“在说话”和“一段话说完了”（0.8.0）。纯逻辑，不碰麦克风和原生库：
 * 每 [Config.windowSamples] 个采样（16 kHz 下 32 毫秒）交给 [probability]（Silero VAD，或按音量的简单判断）
 * 算出是人在说话的概率，再按下面的规则切出一段一段的话：
 *
 * - 概率到 [Config.startThreshold] 算开口；开口之后概率低于 [Config.endThreshold] 才算静音
 *   （两个门限，免得在门限附近来回跳）
 * - 静音满 [Config.endSilenceMillis]（0.8 秒）算这段说完；没满又开口，接在同一段里
 * - 说话累计不到 [Config.minSpeechMillis]（0.3 秒）的不算一段（咳嗽、碰麦），丢掉，不发去识别
 * - 一段最长 [Config.maxUtteranceMillis]（25 秒），到了就切开（智谱识别单次上限 30 秒）
 * - 一段开头补上开口前的 [Config.padMillis]（0.3 秒），结尾只留说完后的 [Config.padMillis] 静音，
 *   中间长时间的静音和背景声不保存
 * - [finishNow]：用户点「说完了」，有足够的说话就马上切出这一段
 *
 * 同一时间只在一个线程上调用。
 */
class UtteranceDetector(
    private val probability: (FloatArray) -> Float,
    private val config: Config = Config(),
) {
    data class Config(
        val sampleRate: Int = 16_000,
        val windowSamples: Int = 512,
        val startThreshold: Float = 0.5f,
        val endThreshold: Float = 0.35f,
        val minSpeechMillis: Long = 300,
        val endSilenceMillis: Long = 800,
        val maxUtteranceMillis: Long = 25_000,
        val padMillis: Long = 300,
    )

    sealed interface Event {
        /** 开口了（界面显示“正在说”） */
        data object Started : Event

        /** 一段说完：16 kHz 单声道 16 位 PCM；[speechMillis] 是其中算作说话的时长；[forced] 是到了最长时间或点了「说完了」 */
        class Utterance(val pcm: ShortArray, val speechMillis: Long, val forced: Boolean) : Event

        /** 太短，不算一段，丢掉了 */
        data object Discarded : Event
    }

    private val windowMillis = config.windowSamples * 1000.0 / config.sampleRate
    private fun windows(millis: Long): Int = kotlin.math.ceil(millis / windowMillis).toInt()

    private val padWindows = windows(config.padMillis)
    private val endSilenceWindows = windows(config.endSilenceMillis)
    private val minSpeechWindows = windows(config.minSpeechMillis)
    private val maxWindows = windows(config.maxUtteranceMillis)

    private val pending = ShortArray(config.windowSamples)
    private var pendingCount = 0
    private val floats = FloatArray(config.windowSamples)

    /** 开口前的最近几个窗口，开口时补在前面 */
    private val preroll = ArrayDeque<ShortArray>()
    private val segment = ArrayList<ShortArray>()
    private var collecting = false
    private var speechWindows = 0
    private var trailingSilence = 0

    /** 现在是不是在一段话里（开口之后、说完之前） */
    val speaking: Boolean get() = collecting

    /** 喂进一段 PCM（长度随意），返回这期间发生的事 */
    fun accept(samples: ShortArray, count: Int = samples.size): List<Event> {
        val events = ArrayList<Event>(1)
        var offset = 0
        while (offset < count) {
            val take = minOf(count - offset, config.windowSamples - pendingCount)
            samples.copyInto(pending, pendingCount, offset, offset + take)
            pendingCount += take
            offset += take
            if (pendingCount == config.windowSamples) {
                window(pending.copyOf(), events)
                pendingCount = 0
            }
        }
        return events
    }

    /** 点了「说完了」：有足够的说话就切出这一段，否则丢掉 */
    fun finishNow(): List<Event> {
        if (!collecting) return emptyList()
        val events = ArrayList<Event>(1)
        finish(forced = true, events)
        return events
    }

    /** 从头开始（静音、联系人说话时不听、换一轮）：丢掉还没说完的部分 */
    fun reset() {
        pendingCount = 0
        preroll.clear()
        segment.clear()
        collecting = false
        speechWindows = 0
        trailingSilence = 0
    }

    private fun window(window: ShortArray, events: MutableList<Event>) {
        for (i in window.indices) floats[i] = window[i] / 32768f
        val p = probability(floats)
        if (!collecting) {
            if (p >= config.startThreshold) {
                collecting = true
                segment.addAll(preroll)
                preroll.clear()
                segment += window
                speechWindows = 1
                trailingSilence = 0
                events += Event.Started
            } else {
                preroll.addLast(window)
                while (preroll.size > padWindows) preroll.removeFirst()
            }
            return
        }
        segment += window
        if (p >= config.endThreshold) {
            speechWindows++
            trailingSilence = 0
        } else {
            trailingSilence++
        }
        when {
            trailingSilence >= endSilenceWindows -> finish(forced = false, events)
            segment.size >= maxWindows -> finish(forced = true, events)
        }
    }

    private fun finish(forced: Boolean, events: MutableList<Event>) {
        if (speechWindows < minSpeechWindows) {
            events += Event.Discarded
        } else {
            val keep = segment.size - (trailingSilence - padWindows).coerceAtLeast(0)
            val pcm = ShortArray(keep * config.windowSamples)
            for (i in 0 until keep) segment[i].copyInto(pcm, i * config.windowSamples)
            events += Event.Utterance(pcm, (speechWindows * windowMillis).toLong(), forced)
        }
        segment.clear()
        collecting = false
        speechWindows = 0
        trailingSilence = 0
    }
}
