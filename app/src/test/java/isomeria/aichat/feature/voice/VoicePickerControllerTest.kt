package isomeria.aichat.feature.voice

import android.app.Application
import isomeria.aichat.R
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.voice.AsrRequest
import isomeria.aichat.data.voice.AsrResult
import isomeria.aichat.data.voice.AudioRoute
import isomeria.aichat.data.voice.BuiltInVoices
import isomeria.aichat.data.voice.FakeSystemTts
import isomeria.aichat.data.voice.TtsAudio
import isomeria.aichat.data.voice.TtsRequest
import isomeria.aichat.data.voice.VoiceCatalog
import isomeria.aichat.data.voice.VoicePlayer
import isomeria.aichat.data.voice.VoicePreview
import isomeria.aichat.data.voice.VoiceProtocol
import isomeria.aichat.data.voice.VoiceService
import isomeria.aichat.data.voice.VoiceTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 0.7.2 选音色：打开、过滤、缺 Key 和地址不对的提示、试听的合成和播放（只用假的服务商和播放器）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoicePickerControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0)

    private class FakeService(var failure: LlmException? = null) : VoiceService {
        val requests = mutableListOf<Pair<String, TtsRequest>>()
        var audio = TtsAudio(byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0), "mp3")
        override suspend fun synthesize(protocol: String, endpoint: LlmEndpoint, request: TtsRequest): TtsAudio {
            requests += protocol to request
            failure?.let { throw it }
            return audio
        }
        override suspend fun transcribe(protocol: String, endpoint: LlmEndpoint, request: AsrRequest) = AsrResult("")
    }

    private class FakePlayer : VoicePlayer {
        var playing: File? = null
        var playedBytes: ByteArray? = null
        var onEnd: ((Boolean) -> Unit)? = null
        var stops = 0
        /** 像 AndroidVoicePlayer 那样在 play 里就结束（prepare 失败、拿不到声音焦点时是 false） */
        var endsAtOnce: Boolean? = null
        override fun play(file: File, route: AudioRoute, onEnd: (completed: Boolean) -> Unit) {
            playing = file
            playedBytes = file.readBytes()
            this.onEnd = onEnd
            endsAtOnce?.let { onEnd(it) }
        }
        override fun stop() {
            stops++
            playing = null
        }
    }

    private val service = FakeService()
    private val player = FakePlayer()
    private var otherStopped = 0
    private val temp = File(context.cacheDir, "voice-temp").apply { mkdirs() }
    private val preview = VoicePreview(service, FakeSystemTts(), player, { otherStopped++ }) { temp }
    private val controller = VoicePickerController(scope, VoiceCatalog(OkHttpClient(), Json, FakeSystemTts()), preview, "试听的话")

    @After
    fun tearDown() {
        scope.cancel()
        temp.deleteRecursively()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            ShadowLooper.idleMainLooper()
            if (System.currentTimeMillis() > deadline) throw AssertionError("等待超时")
            Thread.sleep(10)
        }
    }

    private val volc = VoiceTarget("volcengine", VoiceProtocol.VOLC_TTS, "https://example.invalid/api/v3", "sk-test", "seed-tts-2.0")

    @Test
    fun open_loadsBuiltInList_queryFiltersByNameIdAndGroup() {
        controller.open(volc, "zh_female_vv_uranus_bigtts")
        await { !controller.state.value.loading }
        val state = controller.state.value
        assertTrue(state.open)
        assertFalse(state.remoteSearch)
        assertEquals(BuiltInVoices.VOLCENGINE.size, state.visible.size)
        controller.setQuery("vivi")
        assertEquals(listOf("zh_female_vv_uranus_bigtts"), controller.state.value.visible.map { it.id })
        controller.setQuery("外语")
        assertEquals(3, controller.state.value.visible.size)
        controller.close()
        assertFalse(controller.state.value.open)
    }

    @Test
    fun badUrlOrMissingKey_showMessages() {
        controller.open(null, "")
        assertEquals(PickerMessage(R.string.voice_picker_bad_url), controller.state.value.error)
        controller.open(null, "", R.string.voice_picker_no_source)
        assertEquals(PickerMessage(R.string.voice_picker_no_source), controller.state.value.error)
        controller.open(VoiceTarget("minimax", VoiceProtocol.MINIMAX_TTS, "https://example.invalid/v1", null, "speech-2.8-hd"), "")
        await { !controller.state.value.loading }
        assertEquals(PickerMessage(R.string.voice_picker_need_key), controller.state.value.error)
    }

    @Test
    fun preview_synthesizesWithThatVoice_playsOnSpeaker_deletesFileWhenDone() {
        controller.open(volc, "")
        await { !controller.state.value.loading }
        val option = BuiltInVoices.VOLCENGINE.first()
        controller.preview(option)
        await { controller.state.value.playing == option.id }
        assertEquals(VoiceProtocol.VOLC_TTS to TtsRequest("seed-tts-2.0", "试听的话", option.id), service.requests.single())
        assertEquals("试听前停掉正在放的语音消息", 1, otherStopped)
        val file = player.playing!!
        assertTrue(file.isFile)
        player.onEnd!!(true)
        assertFalse("放完删掉", file.exists())
        assertNull(controller.state.value.playing)
    }

    /** 1.0.0：DeepSeek 返回的 WAV 文件头长度是占位值，系统播放器不认；试听前改成实际长度 */
    @Test
    fun preview_wavWithStreamingPlaceholders_isFixedBeforePlaying() {
        val pcm = ByteArray(4_800) { (it % 7).toByte() }
        val streamed = (isomeria.aichat.data.voice.WavFormat.header(pcm.size.toLong(), 24_000, 1) + pcm).also {
            for (i in 4 until 8) it[i] = 0xFF.toByte()
            for (i in 40 until 44) it[i] = 0xFF.toByte()
        }
        service.audio = TtsAudio(streamed, "wav")
        controller.open(volc, "")
        await { !controller.state.value.loading }
        val option = BuiltInVoices.VOLCENGINE.first()
        controller.preview(option)
        await { controller.state.value.playing == option.id }
        val played = player.playedBytes!!
        assertEquals(isomeria.aichat.data.voice.WavFormat.header(pcm.size.toLong(), 24_000, 1).toList(), played.copyOf(44).toList())
        assertTrue(played.copyOfRange(44, played.size).contentEquals(pcm))
    }

    /** 1.0.0：播放器一开始就放不了时提示“试听失败：手机放不出这段音频”，不再一直停在“播放中”，临时文件删掉 */
    @Test
    fun preview_playerFailsAtOnce_showsCannotPlay_notStuckPlaying() {
        player.endsAtOnce = false
        controller.open(volc, "")
        await { !controller.state.value.loading }
        controller.preview(BuiltInVoices.VOLCENGINE.first())
        await { controller.state.value.previewError != null }
        val state = controller.state.value
        assertEquals(PickerMessage(R.string.voice_picker_preview_failed, R.string.voice_picker_preview_cannot_play), state.previewError)
        assertNull(state.playing)
        assertNull(state.previewing)
        assertFalse("临时文件删掉", player.playing!!.exists())
        assertEquals("试听失败：手机放不出这段音频", context.getString(R.string.voice_picker_preview_failed, context.getString(R.string.voice_picker_preview_cannot_play)))
    }

    /** 放得极快、play 返回前就放完时，也不能停在“播放中” */
    @Test
    fun preview_endsBeforePlayReturns_isNotStuckPlaying() {
        player.endsAtOnce = true
        controller.open(volc, "")
        await { !controller.state.value.loading }
        val option = BuiltInVoices.VOLCENGINE.first()
        controller.preview(option)
        await { controller.state.value.previewing == null }
        assertNull(controller.state.value.playing)
        assertNull(controller.state.value.previewError)
    }

    @Test
    fun previewFailure_isShown_andCloseStopsPlayback() {
        service.failure = LlmException(LlmError.AUTH)
        controller.open(volc, "")
        await { !controller.state.value.loading }
        controller.preview(BuiltInVoices.VOLCENGINE.first())
        await { controller.state.value.previewError != null }
        assertEquals(R.string.voice_picker_preview_failed, controller.state.value.previewError!!.res)
        assertNull(controller.state.value.previewing)
        val stops = player.stops
        controller.close()
        assertEquals(stops + 1, player.stops)
    }
}
