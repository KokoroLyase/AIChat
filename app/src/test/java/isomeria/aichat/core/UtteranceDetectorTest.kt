package isomeria.aichat.core

import isomeria.aichat.core.UtteranceDetector.Event
import isomeria.aichat.data.voice.EnergySpeechActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * 0.8.0 通话里判断“在说话”和“说完了”：用预置的“声音”代替麦克风，用假的说话概率代替 Silero VAD
 * （窗口里有声音就算 0.9，安静就算 0.05），检查切分的规则。
 */
class UtteranceDetectorTest {
    private val rate = 16_000

    /** [millis] 毫秒的“说话”（幅度 8000 的方波）或静音 */
    private fun audio(millis: Long, loud: Boolean): ShortArray =
        ShortArray((rate * millis / 1000).toInt()) { i -> if (!loud) 0 else if ((i / 20) % 2 == 0) 8000 else -8000 }

    private fun loud(millis: Long) = audio(millis, true)
    private fun quiet(millis: Long) = audio(millis, false)

    /** 窗口里最大幅度过 1000 就算在说话 */
    private val fakeVad: (FloatArray) -> Float = { window -> if (window.any { kotlin.math.abs(it) > 1000 / 32768f }) 0.9f else 0.05f }

    private fun feed(detector: UtteranceDetector, vararg parts: ShortArray, chunk: Int = 512): List<Event> {
        val all = parts.fold(ShortArray(0)) { acc, part -> acc + part }
        val events = ArrayList<Event>()
        var offset = 0
        while (offset < all.size) {
            val end = minOf(all.size, offset + chunk)
            events += detector.accept(all.copyOfRange(offset, end))
            offset = end
        }
        return events
    }

    private fun millisOf(pcm: ShortArray) = pcm.size * 1000L / rate

    @Test
    fun speechThenSilence_isOneUtterance_withShortPaddingOnBothEnds() {
        val detector = UtteranceDetector(fakeVad)
        val events = feed(detector, quiet(2_000), loud(1_500), quiet(2_000))
        assertEquals(listOf(Event.Started::class, Event.Utterance::class), events.map { it::class })
        val utterance = events[1] as Event.Utterance
        assertTrue(!utterance.forced)
        // 说话约 1.5 秒；前后各留约 0.3 秒，中间长时间的静音不保存
        assertTrue(utterance.speechMillis in 1_400L..1_600L)
        assertTrue("长度 ${millisOf(utterance.pcm)}", millisOf(utterance.pcm) in 2_000L..2_300L)
        assertTrue(!detector.speaking)
    }

    @Test
    fun pauseShorterThanEndSilence_staysInTheSameUtterance() {
        val detector = UtteranceDetector(fakeVad)
        val events = feed(detector, loud(1_000), quiet(600), loud(1_000), quiet(1_000))
        assertEquals(1, events.count { it is Event.Utterance })
        assertEquals(1, events.count { it is Event.Started })
        assertTrue((events.single { it is Event.Utterance } as Event.Utterance).speechMillis in 1_900L..2_100L)
    }

    @Test
    fun pauseLongerThanEndSilence_splitsIntoTwo() {
        val detector = UtteranceDetector(fakeVad)
        val events = feed(detector, loud(1_000), quiet(900), loud(1_000), quiet(1_000))
        assertEquals(2, events.count { it is Event.Utterance })
    }

    @Test
    fun tooShort_isDiscarded_notSentToRecognition() {
        val detector = UtteranceDetector(fakeVad)
        val events = feed(detector, quiet(500), loud(200), quiet(1_000))
        assertEquals(listOf(Event.Started::class, Event.Discarded::class), events.map { it::class })
    }

    @Test
    fun longSpeech_isCutAtTheMaximum_andContinues() {
        val detector = UtteranceDetector(fakeVad)
        val events = feed(detector, loud(30_000), quiet(1_000))
        val utterances = events.filterIsInstance<Event.Utterance>()
        assertEquals(2, utterances.size)
        assertTrue(utterances[0].forced)
        assertTrue(millisOf(utterances[0].pcm) in 24_900L..25_100L)
        assertTrue(utterances[1].speechMillis in 4_800L..5_200L)
    }

    @Test
    fun finishNow_cutsWhatWasSaid_orNothingWhenSilent() {
        val detector = UtteranceDetector(fakeVad)
        assertTrue(detector.finishNow().isEmpty())
        feed(detector, loud(800))
        assertTrue(detector.speaking)
        val events = detector.finishNow()
        assertTrue((events.single() as Event.Utterance).forced)
        assertTrue(!detector.speaking)
        feed(detector, loud(100))
        assertTrue("太短的「说完了」也丢掉", detector.finishNow().single() is Event.Discarded)
    }

    @Test
    fun hysteresis_lowerThresholdOnlyAfterStarting() {
        var p = 0.4f
        val detector = UtteranceDetector({ p })
        assertTrue("0.4 不到开口的门限", feed(detector, quiet(1_000)).isEmpty())
        p = 0.6f
        assertEquals(listOf(Event.Started::class), feed(detector, quiet(100)).map { it::class })
        p = 0.4f
        assertTrue("开口之后 0.4 仍算在说话", feed(detector, quiet(2_000)).isEmpty())
        p = 0.2f
        assertTrue(feed(detector, quiet(1_000)).single() is Event.Utterance)
    }

    @Test
    fun reset_dropsWhatWasNotFinished() {
        val detector = UtteranceDetector(fakeVad)
        feed(detector, loud(1_000))
        detector.reset()
        assertTrue(!detector.speaking)
        assertTrue(feed(detector, quiet(2_000)).isEmpty())
    }

    @Test
    fun chunkSizeDoesNotMatter() {
        fun run(chunk: Int) = feed(UtteranceDetector(fakeVad), quiet(700), loud(1_200), quiet(1_000), chunk = chunk)
            .filterIsInstance<Event.Utterance>().single().pcm.toList()
        assertEquals(run(512), run(37))
        assertEquals(run(512), run(4_000))
    }

    @Test
    fun energyFallback_quietIsZero_speechAboveTheNoiseFloorIsHigh() {
        val energy = EnergySpeechActivity()
        fun window(amplitude: Double) = FloatArray(512) { i -> (amplitude * sin(2 * PI * 200 * i / rate)).toFloat() }
        // 一开始就有持续的环境声（约 -37 分贝）：底噪从它开始，不算说话
        repeat(50) { assertTrue(energy.probability(window(0.02)) < 0.1f) }
        // 明显比底噪大的声音算说话
        assertTrue(energy.probability(window(0.2)) > 0.9f)
        // 环境变吵了（约 -27 分贝）：先当成说话，几秒内底噪跟上来，不再算说话
        assertTrue(energy.probability(window(0.06)) > 0.5f)
        repeat(250) { energy.probability(window(0.06)) }
        assertTrue(energy.probability(window(0.06)) < 0.1f)
        // 太小的声音一律不算
        assertEquals(0f, EnergySpeechActivity().probability(FloatArray(512)))
        // 用它切分：安静—说话—安静切出一段
        val fallback = EnergySpeechActivity()
        val events = feed(UtteranceDetector(fallback::probability), quiet(1_000), loud(1_000), quiet(1_500))
        assertEquals(1, events.count { it is Event.Utterance })
    }
}
