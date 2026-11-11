package isomeria.aichat.data.voice

import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 语音播放：点一下放、再点停；放完对方没听过的接着放下一条没听过的；连续朗读；听筒；后台播放的前台服务；
 * 离开聊天页停下（后台播放除外）；文件不在了。播放器是假的，不出声。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoicePlaybackTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakePlayer : VoicePlayer {
        val played = mutableListOf<Pair<File, AudioRoute>>()
        var stops = 0
        var onEnd: ((Boolean) -> Unit)? = null
        override fun play(file: File, route: AudioRoute, onEnd: (completed: Boolean) -> Unit) {
            played += file to route
            this.onEnd = onEnd
        }
        override fun stop() {
            stops++
            onEnd = null
        }
        fun finish(completed: Boolean = true) = onEnd!!.invoke(completed)
    }

    private class FakeHost : BackgroundPlaybackHost {
        var running = false
        var starts = 0
        override fun start() { running = true; starts++ }
        override fun stop() { running = false }
    }

    private val player = FakePlayer()
    private val host = FakeHost()
    private val messages = mutableListOf<Message>()

    private val source = object : VoiceQueueSource {
        override suspend fun message(id: Long) = messages.firstOrNull { it.id == id }
        override suspend fun voiceAfter(message: Message) = messages.filter { it.type == MessageType.VOICE && it.createdAt > message.createdAt }.sortedBy { it.createdAt }
        override suspend fun markVoicePlayed(id: Long) {
            val index = messages.indexOfFirst { it.id == id }
            messages[index] = messages[index].copy(voicePlayed = true)
        }
    }

    private fun voice(id: Long, role: Role, played: Boolean = role == Role.USER, file: Boolean = true): Message {
        val path = folder.root.resolve("v$id.m4a").apply { if (file) writeBytes(byteArrayOf(1)) }.absolutePath
        return Message(id = id, contactId = 1, role = role, type = MessageType.VOICE, content = "第 $id 条", voicePath = path, voiceMillis = 2_000, voicePlayed = played, status = MessageStatus.SUCCESS, createdAt = id * 1_000)
            .also { messages += it }
    }

    private fun TestScope.playback() = VoicePlayback(player, source, host, this)

    private fun fresh(id: Long) = messages.first { it.id == id }

    @Test
    fun tapPlays_tapAgainStops_marksPlayed() = runTest {
        val p = playback()
        val a = voice(1, Role.ASSISTANT)
        assertTrue(p.toggle(a))
        runCurrent()
        assertEquals(1L, p.state.value!!.messageId)
        assertEquals(AudioRoute.SPEAKER, player.played.single().second)
        assertTrue("开始放就去掉红点", fresh(1).voicePlayed)
        assertTrue(p.toggle(fresh(1)))
        assertNull(p.state.value)
        assertEquals(1, player.played.size)
    }

    @Test
    fun unplayedReceived_continuesToNextUnplayedReceived_skipsPlayedAndMine() = runTest {
        val p = playback()
        voice(1, Role.ASSISTANT)
        voice(2, Role.ASSISTANT, played = true)
        voice(3, Role.USER)
        voice(4, Role.ASSISTANT)
        p.toggle(fresh(1))
        runCurrent()
        player.finish()
        runCurrent()
        assertEquals("跳过听过的和自己的，接着放第 4 条", 4L, p.state.value!!.messageId)
        player.finish()
        runCurrent()
        assertNull("没有了就停下", p.state.value)
        assertEquals(listOf("v1.m4a", "v4.m4a"), player.played.map { it.first.name })
    }

    @Test
    fun alreadyPlayed_doesNotContinue() = runTest {
        val p = playback()
        voice(1, Role.ASSISTANT, played = true)
        voice(2, Role.ASSISTANT)
        p.toggle(fresh(1))
        player.finish()
        runCurrent()
        assertNull(p.state.value)
        assertEquals(1, player.played.size)
    }

    @Test
    fun continuous_playsEveryVoiceAfter_keepsMode_skipsMissingFiles() = runTest {
        val p = playback()
        voice(1, Role.USER)
        voice(2, Role.ASSISTANT, played = true)
        voice(3, Role.USER, file = false)
        voice(4, Role.USER)
        p.play(fresh(1), PlayMode.CONTINUOUS)
        repeat(3) { player.finish(); runCurrent() }
        assertEquals(listOf("v1.m4a", "v2.m4a", "v4.m4a"), player.played.map { it.first.name })
        assertNull(p.state.value)
    }

    @Test
    fun earpiece_routesToEarpiece_andContinuesThere() = runTest {
        val p = playback()
        voice(1, Role.ASSISTANT)
        voice(2, Role.ASSISTANT)
        p.play(fresh(1), PlayMode.EARPIECE)
        runCurrent()
        player.finish()
        runCurrent()
        assertEquals(listOf(AudioRoute.EARPIECE, AudioRoute.EARPIECE), player.played.map { it.second })
    }

    @Test
    fun interrupted_stopsWithoutContinuing() = runTest {
        val p = playback()
        voice(1, Role.ASSISTANT)
        voice(2, Role.ASSISTANT)
        p.toggle(fresh(1))
        runCurrent()
        player.finish(completed = false)
        runCurrent()
        assertNull(p.state.value)
        assertEquals(1, player.played.size)
    }

    @Test
    fun leavingChat_stopsNormalPlayback_butNotBackground() = runTest {
        val p = playback()
        voice(1, Role.ASSISTANT)
        voice(2, Role.ASSISTANT)
        p.toggle(fresh(1))
        p.leaveChat(1)
        assertNull(p.state.value)
        assertFalse(host.running)

        p.play(fresh(2), PlayMode.BACKGROUND)
        assertTrue("后台播放开前台服务", host.running)
        p.leaveChat(1)
        assertEquals(2L, p.state.value!!.messageId)
        player.finish()
        runCurrent()
        assertNull(p.state.value)
        assertFalse("放完关掉前台服务", host.running)
    }

    @Test
    fun switchingFromBackgroundToNormal_stopsTheService() = runTest {
        val p = playback()
        voice(1, Role.ASSISTANT)
        voice(2, Role.ASSISTANT)
        p.play(fresh(1), PlayMode.BACKGROUND)
        p.toggle(fresh(2))
        assertFalse(host.running)
        assertEquals(2L, p.state.value!!.messageId)
        assertEquals(1, host.starts)
    }

    @Test
    fun missingFile_returnsFalse_keepsCurrentPlayback() = runTest {
        val p = playback()
        voice(1, Role.ASSISTANT)
        val missing = voice(2, Role.ASSISTANT, file = false)
        p.toggle(fresh(1))
        assertFalse(p.toggle(missing))
        assertEquals(1L, p.state.value!!.messageId)
    }

    @Test
    fun staleCallback_afterSwitching_isIgnored() = runTest {
        val p = playback()
        voice(1, Role.ASSISTANT)
        voice(2, Role.ASSISTANT, played = true)
        voice(3, Role.ASSISTANT)
        p.toggle(fresh(1))
        val first = player.onEnd!!
        p.toggle(fresh(2))
        first(true)
        runCurrent()
        assertEquals("旧的一段的回调不影响新的", 2L, p.state.value!!.messageId)
    }

    @Test
    fun deletingThePlayingMessage_stops() = runTest {
        val p = playback()
        voice(1, Role.ASSISTANT)
        p.toggle(fresh(1))
        p.onDeleted(2)
        assertEquals(1L, p.state.value!!.messageId)
        p.onDeleted(1)
        assertNull(p.state.value)
    }
}
