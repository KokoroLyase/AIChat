package isomeria.aichat.data.voice

import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 语音服务商的请求格式（provider_config.provider，kind 为 TTS 或 ASR）。 */
object VoiceProtocol {
    /**
     * 聊天接口带音频，按 Xiaomi MiMo 的文档实现：POST {Base URL}/chat/completions。
     * 各家的“聊天接口带音频”没有统一标准，只承诺按 MiMo 文档实现
     */
    const val CHAT_AUDIO = "chat-audio"

    /** OpenAI 标准音频接口：/audio/speech（合成）、/audio/transcriptions（识别） */
    const val OPENAI_AUDIO = "openai-audio"

    val all = listOf(CHAT_AUDIO, OPENAI_AUDIO)
}

/** 一次语音合成：[style] 是语气和风格要求（只对聊天接口带音频的格式生效，不会被读出来）。 */
data class TtsRequest(val model: String, val text: String, val voice: String, val style: String = "") {
    override fun toString() = "TtsRequest(model=$model, chars=${text.length}, voice=$voice)"
}

/** 合成出的音频：[extension] 按文件头判断（mp3、wav、ogg、m4a），播放器靠它认格式。 */
class TtsAudio(val bytes: ByteArray, val extension: String) {
    override fun toString() = "TtsAudio(${bytes.size} bytes, $extension)"
}

/** 一次语音识别：[wav] 是 16 kHz 单声道 16 位的 WAV。 */
class AsrRequest(val model: String, val wav: ByteArray, val language: String = LANGUAGE_AUTO) {
    override fun toString() = "AsrRequest(model=$model, ${wav.size} bytes)"

    companion object {
        const val LANGUAGE_AUTO = "auto"
    }
}

/** 识别结果；[seconds] 是服务商报告的音频时长（MiMo 的 usage.seconds），没有时为 null。 */
data class AsrResult(val text: String, val seconds: Double? = null) {
    override fun toString() = "AsrResult(chars=${text.length}, seconds=$seconds)"
}

/** 语音合成和识别的接口；JVM 测试换成假实现。失败时抛 [LlmException]（[LlmError.UNKNOWN] 包括返回内容为空）。 */
interface VoiceService {
    suspend fun synthesize(protocol: String, endpoint: LlmEndpoint, request: TtsRequest): TtsAudio

    suspend fun transcribe(protocol: String, endpoint: LlmEndpoint, request: AsrRequest): AsrResult
}

/** 返回了 200 但没有内容：合成没有音频、识别没有文字。 */
class EmptyVoiceResultException : Exception("empty voice result")

/**
 * 两种请求格式的合成和识别（2026-11-11 按官方文档核对）：
 *
 * - **聊天接口带音频**（[VoiceProtocol.CHAT_AUDIO]，MiMo）：
 *   合成：要读的文字放在 assistant 消息里，语气和风格要求放在前面一条 user 消息里（不会被读出来），
 *   `audio.format` 要 mp3、`audio.voice` 填音色，非流式返回 base64 在 `choices[0].message.audio.data`。
 *   识别：一条 user 消息，content 里只放一段 `input_audio`（data URL 形式的 WAV），`asr_options.language`；
 *   文字在 `choices[0].message.content`，音频时长在 `usage.seconds`。
 * - **OpenAI 标准音频接口**（[VoiceProtocol.OPENAI_AUDIO]）：合成 POST /audio/speech（JSON，返回音频二进制，要 mp3）；
 *   识别 POST /audio/transcriptions（multipart：file、model，返回 JSON 的 text）。
 *
 * 认证都用 `Authorization: Bearer`（MiMo 文档写两种都支持）；只发到用户配置的 Base URL，不跟随重定向。
 * 一次请求最多等 [TIMEOUT_SECONDS] 秒（带思考的聊天模型 90 秒不够是 0.6.0 的教训；语音合成、识别的实际耗时以真机为准）。
 * 不记录请求和返回的内容。
 */
@Singleton
class VoiceApi(client: OkHttpClient, private val json: Json, timeoutMillis: Long) : VoiceService {
    @Inject constructor(client: OkHttpClient, json: Json) : this(client, json, TIMEOUT_SECONDS * 1000)

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun synthesize(protocol: String, endpoint: LlmEndpoint, request: TtsRequest): TtsAudio = withContext(Dispatchers.IO) {
        val bytes = when (protocol) {
            VoiceProtocol.CHAT_AUDIO -> {
                val body = chatAudioTtsBody(request).toString()
                val text = post(endpoint, "chat/completions", body.toRequestBody(JSON_TYPE)) { it.body.string() }
                val data = parse(text).choice()?.get("message")?.jsonObject?.get("audio")?.jsonObject?.get("data")?.jsonPrimitive?.contentOrNull
                if (data.isNullOrBlank()) throw LlmException(LlmError.UNKNOWN, cause = EmptyVoiceResultException())
                try {
                    Base64.getMimeDecoder().decode(data.substringAfter("base64,"))
                } catch (e: IllegalArgumentException) {
                    throw LlmException(LlmError.UNKNOWN, cause = e)
                }
            }
            VoiceProtocol.OPENAI_AUDIO -> {
                val body = openAiTtsBody(request).toString()
                post(endpoint, "audio/speech", body.toRequestBody(JSON_TYPE)) { response ->
                    // 有的服务商出错时返回 200 + JSON 说明
                    if (response.header("Content-Type").orEmpty().contains("json")) throw LlmException(LlmError.UNKNOWN)
                    response.body.bytes()
                }
            }
            else -> throw LlmException(LlmError.CONFIGURATION)
        }
        if (bytes.isEmpty()) throw LlmException(LlmError.UNKNOWN, cause = EmptyVoiceResultException())
        TtsAudio(bytes, AudioFormats.extension(bytes))
    }

    override suspend fun transcribe(protocol: String, endpoint: LlmEndpoint, request: AsrRequest): AsrResult = withContext(Dispatchers.IO) {
        val result = when (protocol) {
            VoiceProtocol.CHAT_AUDIO -> {
                val text = post(endpoint, "chat/completions", chatAudioAsrBody(request).toString().toRequestBody(JSON_TYPE)) { it.body.string() }
                val root = parse(text)
                val content = root.choice()?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                val seconds = (root["usage"] as? JsonObject)?.get("seconds")?.jsonPrimitive?.doubleOrNull
                AsrResult(content.orEmpty().trim(), seconds)
            }
            VoiceProtocol.OPENAI_AUDIO -> {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", request.model)
                    .addFormDataPart("file", "audio.wav", request.wav.toRequestBody(WAV_TYPE))
                    .build()
                val text = post(endpoint, "audio/transcriptions", body) { it.body.string() }
                AsrResult(parse(text)["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim())
            }
            else -> throw LlmException(LlmError.CONFIGURATION)
        }
        if (result.text.isEmpty()) throw LlmException(LlmError.UNKNOWN, cause = EmptyVoiceResultException())
        result
    }

    private suspend fun <T> post(endpoint: LlmEndpoint, path: String, body: okhttp3.RequestBody, read: (Response) -> T): T {
        val request = Request.Builder()
            .url(endpoint.baseUrl.trim().trimEnd('/') + "/" + path)
            .header("Authorization", endpoint.authorization())
            .post(body)
            .build()
        return client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) throw LlmException.fromHttp(response.code)
            try {
                read(response)
            } catch (e: IOException) {
                throw LlmException(LlmError.NETWORK, cause = e)
            }
        }
    }

    private fun parse(text: String): JsonObject = try {
        json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw LlmException(LlmError.UNKNOWN, message = "unparseable voice response", cause = e)
    }

    private fun JsonObject.choice(): JsonObject? = (this["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject

    companion object {
        const val TIMEOUT_SECONDS = 120L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val WAV_TYPE = "audio/wav".toMediaType()

        /** 合成的请求体（聊天接口带音频）：风格要求在 user 消息里，要读的文字在 assistant 消息里 */
        fun chatAudioTtsBody(request: TtsRequest): JsonObject = buildJsonObject {
            put("model", request.model)
            putJsonArray("messages") {
                if (request.style.isNotBlank()) addJsonObject {
                    put("role", "user")
                    put("content", request.style.trim())
                }
                addJsonObject {
                    put("role", "assistant")
                    put("content", request.text)
                }
            }
            putJsonObject("audio") {
                put("format", "mp3")
                if (request.voice.isNotBlank()) put("voice", request.voice.trim())
            }
            put("stream", false)
        }

        fun openAiTtsBody(request: TtsRequest): JsonObject = buildJsonObject {
            put("model", request.model)
            put("input", request.text)
            if (request.voice.isNotBlank()) put("voice", request.voice.trim())
            put("response_format", "mp3")
        }

        fun chatAudioAsrBody(request: AsrRequest): JsonObject = buildJsonObject {
            put("model", request.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "input_audio")
                            putJsonObject("input_audio") {
                                put("data", "data:audio/wav;base64," + Base64.getEncoder().encodeToString(request.wav))
                                put("format", "wav")
                            }
                        }
                    }
                }
            }
            putJsonObject("asr_options") { put("language", JsonPrimitive(request.language)) }
            put("stream", false)
        }
    }
}

/** 可取消的异步请求：协程取消时取消 OkHttp 调用；网络错误（含超时）转成 [LlmError.NETWORK]。 */
private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(LlmException(LlmError.NETWORK, cause = e))
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}

/** 按文件头认音频格式；认不出时按 mp3（请求时要的就是 mp3）。 */
object AudioFormats {
    fun extension(bytes: ByteArray): String {
        fun at(i: Int) = bytes.getOrNull(i)?.toInt()?.and(0xFF) ?: -1
        fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
        return when {
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "wav"
            ascii(0, "OggS") -> "ogg"
            ascii(4, "ftyp") -> "m4a"
            ascii(0, "ID3") -> "mp3"
            at(0) == 0xFF && (at(1) and 0xE0) == 0xE0 -> "mp3"
            else -> "mp3"
        }
    }
}
