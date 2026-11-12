package isomeria.aichat.data.voice

import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 语音服务商的请求格式（provider_config.provider，kind 为 TTS 或 ASR）。
 *
 * 0.7.0 有两种通用格式（[custom]，自定义服务商可以选）；0.7.2 起预置的服务商各用自己的格式，只能由预置选出
 */
object VoiceProtocol {
    /**
     * 聊天接口带音频，按 Xiaomi MiMo 的文档实现：POST {Base URL}/chat/completions。
     * 各家的“聊天接口带音频”没有统一标准，只承诺按 MiMo 文档实现
     */
    const val CHAT_AUDIO = "chat-audio"

    /** OpenAI 标准音频接口：/audio/speech（合成）、/audio/transcriptions（识别） */
    const val OPENAI_AUDIO = "openai-audio"

    /** 火山引擎（豆包）语音合成：HTTP 单向流式 V3，新版控制台的 API Key（0.7.2） */
    const val VOLC_TTS = "volc-tts"

    /** 火山引擎（豆包）录音文件识别极速版（0.7.2） */
    const val VOLC_ASR = "volc-asr"

    /** 阿里云百炼 Qwen-Audio-TTS，流式返回（0.7.2） */
    const val QWEN_TTS = "qwen-tts"

    /** 阿里云百炼 qwen3-asr-flash，OpenAI 兼容的聊天接口（0.7.2） */
    const val QWEN_ASR = "qwen-asr"

    /** MiniMax 同步语音合成 t2a_v2（0.7.2） */
    const val MINIMAX_TTS = "minimax-tts"

    /** Fish Audio 语音合成（0.7.2） */
    const val FISH_TTS = "fish-tts"

    /** 自定义服务商可以选的通用格式 */
    val custom = listOf(CHAT_AUDIO, OPENAI_AUDIO)

    val all = custom + listOf(VOLC_TTS, VOLC_ASR, QWEN_TTS, QWEN_ASR, MINIMAX_TTS, FISH_TTS)

    /**
     * 这种格式发不发语气和风格要求：MiMo（单独一条消息）、火山 2.0 音色（context_texts）、百炼（instruction）、
     * 硅基流动的 CosyVoice2（文字前加指令）；其他的不发
     */
    fun supportsStyle(protocol: String, model: String): Boolean = when (protocol) {
        CHAT_AUDIO, VOLC_TTS, QWEN_TTS -> true
        OPENAI_AUDIO -> model.startsWith(COSYVOICE_PREFIX)
        else -> false
    }

    /** 硅基流动 CosyVoice2 的模型名前缀：风格要求写在文字前面，用 <|endofprompt|> 隔开 */
    const val COSYVOICE_PREFIX = "FunAudioLLM/CosyVoice"
}

/** 一次语音合成：[style] 是语气和风格要求（只对支持的格式生效，不会被读出来）。 */
data class TtsRequest(val model: String, val text: String, val voice: String, val style: String = "") {
    override fun toString() = "TtsRequest(model=$model, chars=${text.length}, voice=$voice)"
}

/**
 * 合成出的音频：[extension] 按文件头判断（mp3、wav、ogg、m4a），播放器靠它认格式。
 * [billable] 为 false 表示本机合成的（0.7.1 系统语音），不记用量
 */
class TtsAudio(val bytes: ByteArray, val extension: String, val billable: Boolean = true) {
    override fun toString() = "TtsAudio(${bytes.size} bytes, $extension)"
}

/** 一次语音识别：[wav] 是 16 kHz 单声道 16 位的 WAV。 */
class AsrRequest(val model: String, val wav: ByteArray, val language: String = LANGUAGE_AUTO) {
    override fun toString() = "AsrRequest(model=$model, ${wav.size} bytes)"

    companion object {
        const val LANGUAGE_AUTO = "auto"
    }
}

/** 识别结果；[seconds] 是服务商报告的音频时长（MiMo、百炼的 usage.seconds，火山的 audio_info.duration），没有时为 null。 */
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
 * 各种请求格式的合成和识别（通用格式 2026-11-11、各家格式 2026-11-12 按官方文档核对）：
 *
 * - **聊天接口带音频**（[VoiceProtocol.CHAT_AUDIO]，MiMo）：
 *   合成：要读的文字放在 assistant 消息里，语气和风格要求放在前面一条 user 消息里（不会被读出来），
 *   `audio.format` 要 mp3、`audio.voice` 填音色，非流式返回 base64 在 `choices[0].message.audio.data`。
 *   识别：一条 user 消息，content 里只放一段 `input_audio`（data URL 形式的 WAV），`asr_options.language`；
 *   文字在 `choices[0].message.content`，音频时长在 `usage.seconds`。
 * - **OpenAI 标准音频接口**（[VoiceProtocol.OPENAI_AUDIO]）：合成 POST /audio/speech（JSON，返回音频二进制，要 mp3）；
 *   识别 POST /audio/transcriptions（multipart：file、model，返回 JSON 的 text）。硅基流动 CosyVoice2 的风格要求写在文字前，
 *   用 `<|endofprompt|>` 隔开。
 * - **火山引擎合成**（[VoiceProtocol.VOLC_TTS]）：POST {Base URL}/tts/unidirectional，`X-Api-Key`、`X-Api-Resource-Id`（填在“模型”里，
 *   如 seed-tts-2.0）；`req_params.speaker` 是音色，风格要求放在 `additions`（JSON 字符串）的 `context_texts`；
 *   返回一串 JSON，每段 `data` 是一块 base64 音频，`code` 为 20000000 表示结束，其他非 0 的是错误。
 * - **火山引擎识别**（[VoiceProtocol.VOLC_ASR]）：POST {Base URL}/auc/bigmodel/recognize/flash，`X-Api-Key`、
 *   `X-Api-Resource-Id`（如 volc.bigasr.auc_turbo）、`X-Api-Sequence: -1`；`audio.data` 是 base64 的 WAV；
 *   结果看返回头 `X-Api-Status-Code`（20000000 成功、20000003 静音），文字在 `result.text`，时长（毫秒）在 `audio_info.duration`。
 * - **百炼合成**（[VoiceProtocol.QWEN_TTS]）：POST {Base URL}/services/audio/tts/SpeechSynthesizer，`X-DashScope-SSE: enable`；
 *   `input.text`、`input.voice`、`input.format`=mp3、风格要求在 `input.instruction`；每个事件的 `output.audio.data` 是一块 base64 音频，
 *   拼起来就是完整的 mp3。不用非流式：它只返回另一个域名上的下载链接
 * - **百炼识别**（[VoiceProtocol.QWEN_ASR]）：POST {Base URL}/chat/completions，一条 user 消息里一段 `input_audio`，
 *   顶层 `asr_options.enable_itn`；语言自动识别时不发 `language`。文字在 `choices[0].message.content`，时长在 `usage.seconds`。
 * - **MiniMax 合成**（[VoiceProtocol.MINIMAX_TTS]）：POST {Base URL}/t2a_v2，`voice_setting.voice_id`、`audio_setting.format`=mp3、
 *   `output_format`=hex；`base_resp.status_code` 不为 0 是错误，音频是 `data.audio` 的十六进制。不要下载链接，理由同上
 * - **Fish Audio 合成**（[VoiceProtocol.FISH_TTS]）：POST {Base URL}/v1/tts，请求头 `model` 填模型（如 s2.1-pro），
 *   `reference_id` 是音色，`format`=mp3，返回音频二进制。
 *
 * 除火山（X-Api-Key）外认证都用 `Authorization: Bearer`；只发到用户配置的 Base URL，不跟随重定向。
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
                decodeBase64(data.substringAfter("base64,"))
            }
            VoiceProtocol.OPENAI_AUDIO -> {
                val body = openAiTtsBody(request).toString()
                post(endpoint, "audio/speech", body.toRequestBody(JSON_TYPE)) { response -> audioBytes(response) }
            }
            VoiceProtocol.VOLC_TTS -> {
                val text = post(endpoint, "tts/unidirectional", volcTtsBody(request).toString().toRequestBody(JSON_TYPE), volcHeaders(endpoint, request.model)) {
                    it.body.string()
                }
                volcAudio(text)
            }
            VoiceProtocol.QWEN_TTS -> {
                val text = post(endpoint, "services/audio/tts/SpeechSynthesizer", qwenTtsBody(request).toString().toRequestBody(JSON_TYPE), mapOf("X-DashScope-SSE" to "enable")) {
                    it.body.string()
                }
                qwenAudio(text)
            }
            VoiceProtocol.MINIMAX_TTS -> {
                val text = post(endpoint, "t2a_v2", miniMaxTtsBody(request).toString().toRequestBody(JSON_TYPE)) { it.body.string() }
                miniMaxAudio(parse(text))
            }
            VoiceProtocol.FISH_TTS -> {
                val headers = request.model.takeIf { it.isNotBlank() }?.let { mapOf("model" to it) }.orEmpty()
                post(endpoint, "v1/tts", fishTtsBody(request).toString().toRequestBody(JSON_TYPE), headers) { response -> audioBytes(response) }
            }
            else -> throw LlmException(LlmError.CONFIGURATION)
        }
        if (bytes.isEmpty()) throw LlmException(LlmError.UNKNOWN, cause = EmptyVoiceResultException())
        TtsAudio(bytes, AudioFormats.extension(bytes))
    }

    override suspend fun transcribe(protocol: String, endpoint: LlmEndpoint, request: AsrRequest): AsrResult = withContext(Dispatchers.IO) {
        val result = when (protocol) {
            VoiceProtocol.CHAT_AUDIO, VoiceProtocol.QWEN_ASR -> {
                val body = if (protocol == VoiceProtocol.CHAT_AUDIO) chatAudioAsrBody(request) else qwenAsrBody(request)
                val text = post(endpoint, "chat/completions", body.toString().toRequestBody(JSON_TYPE)) { it.body.string() }
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
            VoiceProtocol.VOLC_ASR -> {
                val headers = volcHeaders(endpoint, request.model) + ("X-Api-Sequence" to "-1")
                post(endpoint, "auc/bigmodel/recognize/flash", volcAsrBody(request).toString().toRequestBody(JSON_TYPE), headers) { response ->
                    volcAsrResult(response.header("X-Api-Status-Code"), response.body.string())
                }
            }
            else -> throw LlmException(LlmError.CONFIGURATION)
        }
        if (result.text.isEmpty()) throw LlmException(LlmError.UNKNOWN, cause = EmptyVoiceResultException())
        result
    }

    /**
     * 发一个请求。[headers] 是这家服务商要的请求头；火山用 `X-Api-Key`（在 [volcHeaders] 里），不带 `Authorization`
     */
    private suspend fun <T> post(
        endpoint: LlmEndpoint,
        path: String,
        body: okhttp3.RequestBody,
        headers: Map<String, String> = emptyMap(),
        read: (Response) -> T,
    ): T {
        val builder = Request.Builder()
            .url(endpoint.baseUrl.trim().trimEnd('/') + "/" + path)
            .post(body)
        if (VOLC_KEY_HEADER !in headers) builder.header("Authorization", endpoint.authorization())
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).awaitResponse().use { response ->
            if (!response.isSuccessful) throw LlmException.fromHttp(response.code)
            try {
                read(response)
            } catch (e: IOException) {
                throw LlmException(LlmError.NETWORK, cause = e)
            }
        }
    }

    /** 有的服务商出错时返回 200 + JSON 说明 */
    private fun audioBytes(response: Response): ByteArray {
        if (response.header("Content-Type").orEmpty().contains("json")) throw LlmException(LlmError.UNKNOWN)
        return response.body.bytes()
    }

    private fun parse(text: String): JsonObject = try {
        json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw LlmException(LlmError.UNKNOWN, message = "unparseable voice response", cause = e)
    }

    private fun JsonObject.choice(): JsonObject? = (this["choices"] as? JsonArray)?.firstOrNull()?.jsonObject

    /** 火山返回的一串 JSON：拼起每段的音频；中途的错误码转成对应的错误 */
    private fun volcAudio(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (chunk in JsonChunks.split(text)) {
            val obj = parse(chunk)
            val code = obj["code"]?.jsonPrimitive?.longOrNull ?: 0
            if (code != 0L && code != VOLC_OK) throw volcError(code)
            obj["data"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { out.write(decodeBase64(it)) }
        }
        return out.toByteArray()
    }

    private fun volcAsrResult(status: String?, text: String): AsrResult {
        val code = status?.trim()?.toLongOrNull()
        // 静音：和服务商返回空内容一样，这一轮不发
        if (code == VOLC_SILENT) return AsrResult("")
        if (code != null && code != VOLC_OK) throw volcError(code)
        val root = parse(text)
        val content = (root["result"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val millis = (root["audio_info"] as? JsonObject)?.get("duration")?.jsonPrimitive?.doubleOrNull
        return AsrResult(content, millis?.let { it / 1000.0 })
    }

    /** 火山的错误码：45 开头是请求的问题（音色或资源没开通、参数不对），55 开头是服务端的 */
    private fun volcError(code: Long): LlmException = when {
        code.toString().startsWith("55") -> LlmException(LlmError.SERVER, message = "volc $code")
        code.toString().startsWith("45") -> LlmException(LlmError.CONFIGURATION, message = "volc $code")
        else -> LlmException(LlmError.UNKNOWN, message = "volc $code")
    }

    /** 百炼的流式返回：每个 data: 事件里的音频块拼起来；事件里带错误码时转成错误 */
    private fun qwenAudio(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (line in text.lineSequence()) {
            val data = line.trim().removePrefix("data:").trim().takeIf { line.trim().startsWith("data:") && it.startsWith("{") } ?: continue
            val obj = parse(data)
            val output = obj["output"] as? JsonObject
            val code = obj["code"]?.jsonPrimitive?.contentOrNull
            if (output == null && !code.isNullOrEmpty()) throw qwenError(code)
            val audio = (output?.get("audio") as? JsonObject)?.get("data")?.jsonPrimitive?.contentOrNull
            if (!audio.isNullOrEmpty()) out.write(decodeBase64(audio))
        }
        return out.toByteArray()
    }

    private fun qwenError(code: String): LlmException = when {
        code.contains("Arrearage", ignoreCase = true) || code.contains("Quota", ignoreCase = true) -> LlmException(LlmError.BALANCE, message = "qwen $code")
        code.contains("Throttling", ignoreCase = true) -> LlmException(LlmError.RATE_LIMIT, message = "qwen $code")
        code.contains("InvalidApiKey", ignoreCase = true) || code.contains("AccessDenied", ignoreCase = true) -> LlmException(LlmError.AUTH, message = "qwen $code")
        code.contains("InvalidParameter", ignoreCase = true) -> LlmException(LlmError.CONFIGURATION, message = "qwen $code")
        else -> LlmException(LlmError.UNKNOWN, message = "qwen $code")
    }

    private fun miniMaxAudio(root: JsonObject): ByteArray {
        val status = ((root["base_resp"] as? JsonObject)?.get("status_code")?.jsonPrimitive?.intOrNull) ?: 0
        if (status != 0) {
            throw when (status) {
                1004, 2049 -> LlmException(LlmError.AUTH, message = "minimax $status")
                1002, 1039 -> LlmException(LlmError.RATE_LIMIT, message = "minimax $status")
                1008 -> LlmException(LlmError.BALANCE, message = "minimax $status")
                2013 -> LlmException(LlmError.CONFIGURATION, message = "minimax $status")
                else -> LlmException(LlmError.UNKNOWN, message = "minimax $status")
            }
        }
        val hex = (root["data"] as? JsonObject)?.get("audio")?.jsonPrimitive?.contentOrNull.orEmpty()
        return try {
            Hex.decode(hex)
        } catch (e: IllegalArgumentException) {
            throw LlmException(LlmError.UNKNOWN, cause = e)
        }
    }

    private fun decodeBase64(data: String): ByteArray = try {
        Base64.getMimeDecoder().decode(data)
    } catch (e: IllegalArgumentException) {
        throw LlmException(LlmError.UNKNOWN, cause = e)
    }

    companion object {
        const val TIMEOUT_SECONDS = 120L
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val WAV_TYPE = "audio/wav".toMediaType()
        private const val VOLC_KEY_HEADER = "X-Api-Key"
        private const val VOLC_OK = 20_000_000L
        private const val VOLC_SILENT = 20_000_003L

        /** 发给火山的 user.uid：只是区分调用方的标识，不含用户信息 */
        private const val VOLC_UID = "aichat"

        /** 合成和识别的采样率：火山、百炼按 24 kHz 要 mp3 */
        private const val SAMPLE_RATE = 24_000

        /** 火山的请求头：新版控制台的 API Key、资源 ID（填在“模型”里）、每次随机的请求 ID */
        fun volcHeaders(endpoint: LlmEndpoint, resourceId: String): Map<String, String> = mapOf(
            // authorization() 先检查 Key 里有没有请求头放不下的字符，有就按 Key 无效处理
            VOLC_KEY_HEADER to endpoint.authorization().removePrefix("Bearer ").trim(),
            "X-Api-Resource-Id" to resourceId,
            "X-Api-Request-Id" to UUID.randomUUID().toString(),
        )

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

        /** 标准接口：硅基流动 CosyVoice2 的风格要求写在文字前面 */
        fun openAiTtsBody(request: TtsRequest): JsonObject = buildJsonObject {
            put("model", request.model)
            val styled = request.style.isNotBlank() && VoiceProtocol.supportsStyle(VoiceProtocol.OPENAI_AUDIO, request.model)
            put("input", if (styled) request.style.trim() + "<|endofprompt|>" + request.text else request.text)
            if (request.voice.isNotBlank()) put("voice", request.voice.trim())
            put("response_format", "mp3")
        }

        fun chatAudioAsrBody(request: AsrRequest): JsonObject = buildJsonObject {
            put("model", request.model)
            putJsonArray("messages") { addAudioMessage(request) }
            putJsonObject("asr_options") { put("language", JsonPrimitive(request.language)) }
            put("stream", false)
        }

        /** 百炼识别：语言自动识别时不发 language（文档：不确定语种时不要指定）；打开数字等的规范化 */
        fun qwenAsrBody(request: AsrRequest): JsonObject = buildJsonObject {
            put("model", request.model)
            putJsonArray("messages") { addAudioMessage(request) }
            put("stream", false)
            putJsonObject("asr_options") {
                if (request.language != AsrRequest.LANGUAGE_AUTO) put("language", request.language)
                put("enable_itn", true)
            }
        }

        private fun kotlinx.serialization.json.JsonArrayBuilder.addAudioMessage(request: AsrRequest) {
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

        fun volcTtsBody(request: TtsRequest): JsonObject = buildJsonObject {
            putJsonObject("user") { put("uid", VOLC_UID) }
            putJsonObject("req_params") {
                put("text", request.text)
                put("speaker", request.voice.trim())
                putJsonObject("audio_params") {
                    put("format", "mp3")
                    put("sample_rate", SAMPLE_RATE)
                }
                if (request.style.isNotBlank()) {
                    // additions 是 JSON 字符串；context_texts 只用第一项，只对 2.0 音色生效
                    put("additions", buildJsonObject { putJsonArray("context_texts") { add(JsonPrimitive(request.style.trim())) } }.toString())
                }
            }
        }

        fun volcAsrBody(request: AsrRequest): JsonObject = buildJsonObject {
            putJsonObject("user") { put("uid", VOLC_UID) }
            putJsonObject("audio") { put("data", Base64.getEncoder().encodeToString(request.wav)) }
            putJsonObject("request") {
                put("model_name", "bigmodel")
                put("enable_itn", true)
                put("enable_punc", true)
            }
        }

        fun qwenTtsBody(request: TtsRequest): JsonObject = buildJsonObject {
            put("model", request.model)
            putJsonObject("input") {
                put("text", request.text)
                put("voice", request.voice.trim())
                put("format", "mp3")
                put("sample_rate", SAMPLE_RATE)
                if (request.style.isNotBlank()) put("instruction", request.style.trim())
            }
        }

        fun miniMaxTtsBody(request: TtsRequest): JsonObject = buildJsonObject {
            put("model", request.model)
            put("text", request.text)
            put("stream", false)
            putJsonObject("voice_setting") {
                put("voice_id", request.voice.trim())
                put("speed", 1)
                put("vol", 1)
                put("pitch", 0)
            }
            putJsonObject("audio_setting") {
                put("format", "mp3")
                put("sample_rate", 32_000)
                put("bitrate", 128_000)
                put("channel", 1)
            }
            put("output_format", "hex")
            put("language_boost", "auto")
        }

        fun fishTtsBody(request: TtsRequest): JsonObject = buildJsonObject {
            put("text", request.text)
            if (request.voice.isNotBlank()) put("reference_id", request.voice.trim())
            put("format", "mp3")
            put("normalize", true)
        }
    }
}

/** 把一串挨着的 JSON 对象（火山的分块返回，可能有换行也可能没有）拆成一个个对象的文字。 */
internal object JsonChunks {
    fun split(text: String): List<String> {
        val result = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result += text.substring(start, i + 1)
                        start = -1
                    }
                }
            }
        }
        return result
    }
}

/** 十六进制文字转字节（MiniMax 的音频）。 */
internal object Hex {
    fun decode(text: String): ByteArray {
        val clean = text.trim()
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { i ->
            val high = Character.digit(clean[i * 2], 16)
            val low = Character.digit(clean[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "not hex" }
            ((high shl 4) or low).toByte()
        }
    }
}

/** 可取消的异步请求：协程取消时取消 OkHttp 调用；网络错误（含超时）转成 [LlmError.NETWORK]。 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { cont ->
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
