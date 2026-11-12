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
 */
class VoicePreview(
    private val api: VoiceService,
    private val systemTts: SystemTts,
    private val player: VoicePlayer,
    private val stopOther: () -> Unit,
    private val tempDir: () -> File,
) {
    private var current: File? = null

    /** 合成并开始放；放完时调 [onEnd]。失败时抛 [LlmException] 或 [VoiceCatalogNeedsKeyException] */
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
        val file = withContext(Dispatchers.IO) {
            File(tempDir(), "preview-${UUID.randomUUID()}.${audio.extension}").apply { writeBytes(audio.bytes) }
        }
        withContext(Dispatchers.Main.immediate) {
            stop()
            stopOther()
            current = file
            player.play(file, AudioRoute.SPEAKER) {
                file.delete()
                if (current == file) current = null
                onEnd()
            }
        }
    }

    fun stop() {
        player.stop()
        current?.delete()
        current = null
    }
}
