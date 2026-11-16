package isomeria.aichat.data.voice

import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 选音色时的试听（0.7.2）：用这个来源把一句固定的话合成出来，用扬声器放一遍。
 *
 * - 服务商来源会用 Key 发一次合成请求（可能计费）；只有用户点了「试听」才发。试听不属于哪个联系人，不记进用量统计
 * - 系统语音在手机上合成，不联网
 * - 合成出的音频放在缓存目录，放完或停下就删掉；开始试听前停掉正在放的语音消息
 * - 1.0.0：WAV 先按实际长度改好文件头（[WavFormat.fixLengths]，DeepSeek 返回的文件头长度是占位值，系统播放器不认）；
 *   播放器一开始就放不了（不认格式、拿不到声音焦点）时抛 [VoicePreviewPlaybackException]，页面提示试听失败
 */
class VoicePreview(
    private val api: VoiceService,
    private val systemTts: SystemTts,
    private val player: VoicePlayer,
    private val stopOther: () -> Unit,
    private val tempDir: () -> File,
) {
    private var current: File? = null

    /**
     * 合成并开始放；放完或中途停下时调 [onEnd]。合成失败时抛 [LlmException] 或 [VoiceCatalogNeedsKeyException]，
     * 播放器一开始就放不了时抛 [VoicePreviewPlaybackException]（这时不调 [onEnd]）
     */
    suspend fun play(target: VoiceTarget, voice: String, text: String, onEnd: () -> Unit) {
        val audio = if (target.systemTts) {
            val wav = try {
                systemTts.synthesize(text, voice.ifBlank { null })
            } catch (e: IOException) {
                throw LlmException(LlmError.UNKNOWN, message = e.message, cause = e)
            }
            TtsAudio(wav, "wav", billable = false)
        } else {
            val key = target.apiKey?.takeIf { it.isNotBlank() } ?: throw VoiceCatalogNeedsKeyException()
            api.synthesize(target.protocol, LlmEndpoint(target.baseUrl, key), TtsRequest(target.model, text, voice))
        }
        val bytes = if (audio.extension.equals("wav", ignoreCase = true)) WavFormat.fixLengths(audio.bytes) else audio.bytes
        val file = withContext(Dispatchers.IO) {
            File(tempDir(), "preview-${UUID.randomUUID()}.${audio.extension}").apply { writeBytes(bytes) }
        }
        withContext(Dispatchers.Main.immediate) {
            stop()
            stopOther()
            current = file
            // 播放器在 play 里就结束（准备失败、拿不到声音焦点）时，结束回调比 play 返回还早
            var started = false
            var failedAtStart = false
            player.play(file, AudioRoute.SPEAKER) { completed ->
                file.delete()
                if (current == file) current = null
                if (!started && !completed) failedAtStart = true else onEnd()
            }
            started = true
            if (failedAtStart) throw VoicePreviewPlaybackException()
        }
    }

    fun stop() {
        player.stop()
        current?.delete()
        current = null
    }
}

/** 试听合成出来了，但手机一开始就放不了（系统播放器不认这个格式，或者拿不到声音焦点） */
class VoicePreviewPlaybackException : Exception("PreviewPlaybackFailed")
