package isomeria.aichat.feature.call

import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.voice.AudioRoute
import isomeria.aichat.data.voice.VoicePlayer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 0.8.0 通话详情页的播放：完整通话按顺序接着放各条，没有音频的跳过；单独放一条；进度条拖到某一条。 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallReplayTest {
    @get:Rule val temporary = TemporaryFolder()

    private val scope = TestScope(StandardTestDispatcher())
    private var now = 0L

    private val player = object : VoicePlayer {
        val played = mutableListOf<String>()
        /** 每次从第几毫秒开始放 */
        val starts = mutableListOf<Long>()
        var onEnd: ((Boolean) -> Unit)? = null
        var stops = 0
        override fun play(file: File, route: AudioRoute, onEnd: (completed: Boolean) -> Unit) = play(file, route, 0, onEnd)
        override fun play(file: File, route: AudioRoute, startMillis: Long, onEnd: (completed: Boolean) -> Unit) {
            played += file.name
            starts += startMillis
            this.onEnd = onEnd
        }
        override fun stop() { stops++; onEnd = null }

        fun finish(completed: Boolean = true) {
            val callback = onEnd
            onEnd = null
            callback?.invoke(completed)
            scope.advanceUntilIdle()
        }
    }

    private lateinit var replay: CallReplay
    private lateinit var turns: List<CallTurn>

    @Before
    fun setUp() {
        fun file(name: String) = temporary.newFile(name).absolutePath
        turns = listOf(
            CallTurn(id = 1, messageId = 9, seq = 0, role = Role.USER, text = "在吗", audioPath = file("a.m4a"), audioMillis = 1_000, createdAt = 0),
            CallTurn(id = 2, messageId = 9, seq = 1, role = Role.USER, text = "", audioPath = null, status = CallTurnStatus.ASR_FAILED, createdAt = 0),
            CallTurn(id = 3, messageId = 9, seq = 2, role = Role.ASSISTANT, text = "在呢", audioPath = file("b.mp3"), audioMillis = 2_000, createdAt = 0),
            CallTurn(id = 4, messageId = 9, seq = 3, role = Role.ASSISTANT, text = "丢了", audioPath = "/nowhere/c.mp3", audioMillis = 500, createdAt = 0),
            CallTurn(id = 5, messageId = 9, seq = 4, role = Role.USER, text = "好", audioPath = file("d.m4a"), audioMillis = 700, createdAt = 0),
        )
        replay = CallReplay(player, scope) { now }
        replay.setTurns(turns)
    }

    @Test
    fun playAll_goesThroughTurnsWithAudio_skippingTheRest() {
        assertEquals(listOf(1L, 3L, 5L), replay.playable().map { it.id })
        assertEquals(1_000L + 2_000 + 700 + 2 * CallReplay.GAP_MILLIS, replay.totalMillis())
        replay.toggleAll()
        assertEquals(listOf("a.m4a"), player.played)
        assertTrue(replay.state.value!!.all)
        player.finish()
        player.finish()
        assertEquals(listOf("a.m4a", "b.mp3", "d.m4a"), player.played)
        player.finish()
        assertNull("放完了", replay.state.value)
    }

    @Test
    fun position_andSeekingToATurn() {
        assertEquals(1_000L + CallReplay.GAP_MILLIS, replay.offsetOf(3))
        assertEquals(1L, replay.turnAt(500)!!.id)
        assertEquals(3L, replay.turnAt(1_400)!!.id)
        assertEquals(5L, replay.turnAt(99_999)!!.id)
        replay.seekTo(1_000L + CallReplay.GAP_MILLIS)
        replay.toggleAll()
        now += 800
        assertEquals(1_000L + CallReplay.GAP_MILLIS + 800, replay.position())
        now += 10_000
        assertEquals("不超过这一条的长度", 1_000L + CallReplay.GAP_MILLIS + 2_000, replay.position())
    }

    @Test
    fun singleTurn_playsOnlyIt_andTogglesOff() {
        replay.toggleTurn(3)
        player.finish()
        assertEquals(listOf("b.mp3"), player.played)
        assertNull(replay.state.value)
        replay.toggleTurn(5)
        replay.toggleTurn(5)
        assertNull("再点一下停止", replay.state.value)
        assertTrue(player.stops > 0)
    }

    @Test
    fun interruptedOrFailedPlayback_stopsTheChain_butKeepsThePlace() {
        replay.toggleAll()
        player.finish(completed = false)
        scope.advanceUntilIdle()
        assertEquals(listOf("a.m4a"), player.played)
        assertTrue("停在原处，可以接着放", replay.state.value!!.paused)
    }

    @Test
    fun pause_thenResume_continuesWhereItStopped() {
        replay.toggleAll()
        player.finish()
        assertEquals(listOf("a.m4a", "b.mp3"), player.played)
        now += 1_200
        replay.toggleAll()
        val paused = replay.state.value!!
        assertTrue("暂停", paused.paused)
        assertEquals(3L to 1_200L, paused.turnId to paused.from)
        assertEquals(1_000L + CallReplay.GAP_MILLIS + 1_200, replay.position())
        now += 60_000
        assertEquals("暂停时进度不走", 1_000L + CallReplay.GAP_MILLIS + 1_200, replay.position())
        player.finish()
        assertEquals("暂停后不会接着放下一条", listOf("a.m4a", "b.mp3"), player.played)
        replay.toggleAll()
        assertEquals("从停下的地方接着放", listOf("a.m4a", "b.mp3", "b.mp3"), player.played)
        assertEquals(1_200L, player.starts.last())
        assertFalse(replay.state.value!!.paused)
        player.finish()
        assertEquals("d.m4a", player.played.last())
    }

    @Test
    fun pauseInTheGapBetweenTurns_resumesAtTheNextOne() {
        replay.toggleAll()
        val callback = player.onEnd!!
        player.onEnd = null
        callback(true)
        scope.runCurrent()
        replay.pause()
        assertEquals(3L to 0L, replay.state.value!!.let { it.turnId to it.from })
        assertTrue(replay.state.value!!.paused)
        scope.advanceUntilIdle()
        assertEquals("空档里暂停，不会自己开始下一条", listOf("a.m4a"), player.played)
    }

    @Test
    fun seek_whilePlaying_jumpsToTheExactSpot_andWhilePausedOnlyMoves() {
        replay.toggleAll()
        replay.seekTo(1_000L + CallReplay.GAP_MILLIS + 1_500)
        assertEquals(listOf("a.m4a", "b.mp3"), player.played)
        assertEquals("跳到这一条的第 1.5 秒", 1_500L, player.starts.last())
        replay.pause()
        replay.seekTo(200)
        assertEquals("暂停中拖动不出声", 2, player.played.size)
        assertEquals(Triple(1L, true, 200L), replay.state.value!!.let { Triple(it.turnId, it.paused, it.from) })
        assertEquals(200L, replay.position())
        replay.toggleAll()
        assertEquals(200L, player.starts.last())
    }

    @Test
    fun seek_beforePlaying_onlyMoves_andTheGapCountsAsTheNextTurn() {
        replay.seekTo(1_000L + 100)
        assertTrue(player.played.isEmpty())
        assertEquals(Triple(3L, true, 0L), replay.state.value!!.let { Triple(it.turnId, it.paused, it.from) })
        replay.seekTo(99_999)
        assertEquals("拖到最后：最后一条的末尾", 5L to 700L, replay.state.value!!.let { it.turnId to it.from })
    }

    @Test
    fun interruptedSingleTurn_justStops() {
        replay.toggleTurn(3)
        player.finish(completed = false)
        assertNull(replay.state.value)
    }

    @Test
    fun whilePlayingASingleTurn_thePlayButtonStartsTheWholeCallFromTheTop() {
        replay.toggleTurn(3)
        replay.toggleAll()
        assertEquals(listOf("b.mp3", "a.m4a"), player.played)
        assertTrue(replay.state.value!!.all)
    }

    @Test
    fun pausingASingleTurn_stopsIt() {
        replay.toggleTurn(3)
        replay.pause()
        assertNull(replay.state.value)
    }

    @Test
    fun lateEndOfAnEarlierTurn_doesNotStopTheCurrentOne() {
        replay.toggleTurn(3)
        val stale = player.onEnd!!
        replay.toggleTurn(5)
        stale(true)
        scope.advanceUntilIdle()
        assertEquals("还在放第 5 条", 5L, replay.state.value?.turnId)
    }

    @Test
    fun stop_cancelsTheNextTurn() {
        replay.toggleAll()
        val callback = player.onEnd!!
        player.onEnd = null
        callback(true)
        replay.stop()
        scope.advanceUntilIdle()
        assertEquals("停下后不会接着放", listOf("a.m4a"), player.played)
    }
}
