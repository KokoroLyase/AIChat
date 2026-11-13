package isomeria.aichat.feature.chat

import isomeria.aichat.data.voice.VoiceRecorder
import isomeria.aichat.ui.components.RecordMode
import isomeria.aichat.ui.components.RecordZone
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
 * 录音的结束方式：原地松开发送、上滑左边取消、上滑右边转文字、不到 1 秒太短、60 秒自动结束、麦克风打不开、
 * 麦克风按钮录音转文字、离开页面丢弃。录音器是假的，不碰麦克风。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecordingTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakeRecorder : VoiceRecorder {
        var elapsed = 0L
        var failStart = false
        var started: File? = null
        var stopped = 0
        var cancelled = 0
        override fun start(file: File) {
            if (failStart) throw IllegalStateException("busy")
            started = file
            file.writeBytes(ByteArray(44))
        }
        override fun level() = 0.5f
        override fun elapsedMillis() = elapsed
        override fun stop(): Long { stopped++; return elapsed }
        override fun cancel() { cancelled++; started?.delete() }
    }

    private val recorder = FakeRecorder()
    private val sent = mutableListOf<Pair<File, Long>>()
    private val events = mutableListOf<ChatEvent>()
    private val discarded = mutableListOf<File>()
    private var transcript: () -> String = { "识别出的字" }

    private fun TestScope.controller() = VoiceRecording(
        recorder = recorder,
        newVoiceFile = { folder.newFile("voice-${System.nanoTime()}.wav") },
        newTempFile = { folder.newFile("temp-${System.nanoTime()}.wav") },
        discard = { discarded += it; it.delete() },
        send = { file, millis -> sent += file to millis },
        transcribe = { transcript() },
        emit = { events += it },
        uiScope = backgroundScope,
        workScope = this,
    )

    @Test
    fun releaseInPlace_sendsVoiceMessage() = runTest {
        val c = controller()
        assertTrue(c.start(RecordMode.VOICE_MESSAGE, toggled = false))
        recorder.elapsed = 3_200
        advanceTimeBy(150)
        assertEquals(3_200L, c.state.value!!.elapsedMillis)
        c.finish(RecordZone.SEND)
        runCurrent()
        assertNull(c.state.value)
        assertEquals(listOf(recorder.started!! to 3_200L), sent)
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun slideLeft_cancels_noSendFileDeleted() = runTest {
        val c = controller()
        c.start(RecordMode.VOICE_MESSAGE, toggled = false)
        recorder.elapsed = 5_000
        c.setZone(RecordZone.CANCEL)
        assertEquals(RecordZone.CANCEL, c.state.value!!.zone)
        c.finish(c.state.value!!.zone)
        runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(1, recorder.cancelled)
        assertEquals(listOf(recorder.started), discarded)
    }

    @Test
    fun slideRight_transcribesIntoInput_noVoiceMessage_fileDiscarded() = runTest {
        val c = controller()
        c.start(RecordMode.VOICE_MESSAGE, toggled = false)
        recorder.elapsed = 2_000
        c.finish(RecordZone.TO_TEXT)
        runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(listOf<ChatEvent>(ChatEvent.VoiceText("识别出的字")), events)
        assertEquals(listOf(recorder.started), discarded)
        assertFalse(c.transcribing.value)
    }

    @Test
    fun underOneSecond_isTooShort_notSent() = runTest {
        val c = controller()
        c.start(RecordMode.VOICE_MESSAGE, toggled = false)
        recorder.elapsed = 999
        c.finish(RecordZone.SEND)
        runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(listOf<ChatEvent>(ChatEvent.VoiceTooShort), events)
        assertEquals(listOf(recorder.started), discarded)
    }

    @Test
    fun sixtySeconds_stopsAndSendsAutomatically() = runTest {
        val c = controller()
        c.start(RecordMode.VOICE_MESSAGE, toggled = false)
        recorder.elapsed = 59_900
        advanceTimeBy(150)
        assertTrue("还没到 60 秒", sent.isEmpty())
        recorder.elapsed = MAX_RECORD_MILLIS
        advanceTimeBy(150)
        runCurrent()
        assertNull(c.state.value)
        assertEquals(listOf(recorder.started!! to MAX_RECORD_MILLIS), sent)
        // 自动结束后手指才松开：什么也不做
        c.finish(RecordZone.SEND)
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun micUnavailable_reportsAndDoesNotRecord() = runTest {
        recorder.failStart = true
        val c = controller()
        assertFalse(c.start(RecordMode.VOICE_MESSAGE, toggled = false))
        runCurrent()
        assertNull(c.state.value)
        assertEquals(listOf<ChatEvent>(ChatEvent.MicUnavailable), events)
        assertEquals(1, discarded.size)
    }

    @Test
    fun micButton_toggled_finishTranscribes_failureReported() = runTest {
        val c = controller()
        c.start(RecordMode.TO_TEXT, toggled = true)
        assertTrue(c.state.value!!.toggled)
        assertFalse("只能同时录一段", c.start(RecordMode.VOICE_MESSAGE, toggled = false))
        recorder.elapsed = 4_000
        transcript = { throw IllegalStateException("network") }
        c.finish(RecordZone.SEND)
        runCurrent()
        assertTrue("转文字模式下“完成”不会发语音", sent.isEmpty())
        assertEquals(listOf<ChatEvent>(ChatEvent.VoiceTextFailed), events)
        assertEquals(1, discarded.size)
    }

    @Test
    fun leavingThePage_discardsRecording() = runTest {
        val c = controller()
        c.start(RecordMode.VOICE_MESSAGE, toggled = true)
        recorder.elapsed = 10_000
        c.abandon()
        runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(1, recorder.cancelled)
        c.abandon()
        assertEquals(1, recorder.cancelled)
    }
}
