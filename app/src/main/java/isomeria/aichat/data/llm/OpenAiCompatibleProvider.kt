package isomeria.aichat.data.llm

import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * 兼容 OpenAI Chat Completions 格式的服务商：拉取模型列表、流式对话。
 * 各家只有请求体里的思考字段不一样，见 [DeepSeekRequestBuilder] 和 [OpenAiRequestBuilder]。
 *
 * - 流式返回 delta.content；思考内容在 delta.reasoning_content 或 delta.reasoning 里（各家叫法不同）
 * - stream_options.include_usage 时最后一块带 usage
 * - GET /models：input_modalities（DeepSeek）或 architecture.input_modalities（OpenRouter）含 "image" 表示支持图片；
 *   有 effort（DeepSeek），或 supported_parameters 含 reasoning（OpenRouter）表示支持思考；没有这些字段时是未知，由用户标记
 */
open class OpenAiCompatibleProvider(
    client: OkHttpClient,
    private val json: Json,
    override val id: String,
    private val buildBody: (ChatRequest) -> JsonObject,
) : LlmProvider {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    override suspend fun listModels(endpoint: LlmEndpoint): List<RemoteModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint.url("models"))
            .header("Authorization", endpoint.authorization())
            .header("Accept", "application/json")
            .get()
            .build()
        val body = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw httpError(response)
            try {
                response.body.string()
            } catch (e: IOException) {
                throw LlmException(LlmError.NETWORK, cause = e)
            }
        }
        val list = try {
            json.decodeFromString<ModelListDto>(body)
        } catch (e: Exception) {
            throw LlmException(LlmError.UNKNOWN, message = "无法解析模型列表", cause = e)
        }
        if (list.data.any { it.id.isBlank() } || list.data.map { it.id }.distinct().size != list.data.size) throw LlmException(LlmError.UNKNOWN)
        list.data.map { dto ->
            RemoteModel(
                id = dto.id,
                name = dto.name,
                supportsImage = (dto.inputModalities ?: dto.architecture?.inputModalities)?.contains("image"),
                supportsThinking = when {
                    dto.effort != null -> true
                    dto.supportedParameters?.any { it == "reasoning" || it == "reasoning_effort" } == true -> true
                    else -> dto.supportsThinking
                },
                config = dto.rules(),
            )
        }
    }

    /** 出错时先看返回内容里的错误码（智谱的余额不足也是 429，见 [ZhipuErrors]）；只读开头一段，不记录 */
    private fun httpError(response: okhttp3.Response): LlmException =
        ZhipuErrors.of(response.code, runCatching { response.peekBody(ERROR_BODY_LIMIT).string() }.getOrDefault(""))

    override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val authorization = endpoint.authorization()
        val body = buildBody(request).toString()
        val call = client.newCall(
            Request.Builder()
                .url(endpoint.url("chat/completions"))
                .header("Authorization", authorization)
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw httpError(response)
                    val source = response.body.source()
                    val parser = SseParser()
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        val data = parser.feed(line) ?: continue
                        if (data == SseParser.DONE) break
                        parseChunk(data).forEach { send(it) }
                    }
                    parser.flush()?.takeIf { it != SseParser.DONE }?.let { data ->
                        parseChunk(data).forEach { send(it) }
                    }
                }
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                close(e)
            } catch (e: IOException) {
                close(LlmException(LlmError.NETWORK, cause = e))
            } catch (e: Exception) {
                close(LlmException(LlmError.UNKNOWN, cause = e))
            }
        }
        awaitClose {
            call.cancel()
            reader.cancel()
        }
    }

    private fun parseChunk(data: String): List<ChatEvent> {
        val chunk = try {
            json.decodeFromString<ChunkDto>(data)
        } catch (e: Exception) {
            throw LlmException(LlmError.UNKNOWN, message = "无法解析返回内容", cause = e)
        }
        if (chunk.error != null) throw LlmException(LlmError.SERVER, message = "服务端返回错误")
        val events = mutableListOf<ChatEvent>()
        chunk.choices.forEach { choice ->
            val reasoning = choice.delta?.reasoningContent ?: choice.delta?.reasoning
            reasoning?.takeIf { it.isNotEmpty() }?.let { events += ChatEvent.Reasoning(it) }
            choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { events += ChatEvent.Content(it) }
            choice.finishReason?.let { events += ChatEvent.Finished(it) }
        }
        chunk.usage?.let { events += ChatEvent.Usage(it.promptTokens, it.completionTokens) }
        return events
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val ERROR_BODY_LIMIT = 16_384L
    }
}

/** 其他兼容 OpenAI 格式的服务商：请求里只用通用字段，严格校验参数的服务商才不会报 400。 */
@Singleton
class GenericOpenAiProvider @Inject constructor(client: OkHttpClient, json: Json) :
    OpenAiCompatibleProvider(client, json, ProviderProtocol.OPENAI, OpenAiRequestBuilder::build)

/**
 * 通用请求体：
 * - 思考关：不发思考字段，发温度
 * - 思考高 / 最高：都发 reasoning_effort = high（max 不是通用取值），不发温度
 */
object OpenAiRequestBuilder {
    fun build(request: ChatRequest): JsonObject = request.parameters?.let { ConfiguredRequestBuilder.build(request) } ?: buildJsonObject {
        putCommonFields(request)
        if (request.thinking == ThinkingLevel.OFF) {
            putTemperature(request)
        } else {
            put("reasoning_effort", "high")
        }
    }
}

internal fun JsonObjectBuilder.putCommonFields(request: ChatRequest, streamOptions: Boolean = true) {
    put("model", request.model)
    putJsonArray("messages") {
        request.messages.forEach { add(messageJson(it)) }
    }
    put("stream", true)
    if (streamOptions) putJsonObject("stream_options") { put("include_usage", true) }
}

internal fun JsonObjectBuilder.putTemperature(request: ChatRequest) {
    put("temperature", (request.temperature.coerceIn(0f, 2f) * 10).roundToInt() / 10.0) // 与 ModelRules.legacyTemperature 一致
}

private fun messageJson(message: ChatMessage): JsonObject = buildJsonObject {
    put(
        "role",
        when (message.role) {
            ChatRole.SYSTEM -> "system"
            ChatRole.USER -> "user"
            ChatRole.ASSISTANT -> "assistant"
        },
    )
    if (!message.hasImage) {
        put("content", message.plainText())
    } else {
        // 文字和图片作为同一条用户消息的不同部分
        put(
            "content",
            buildJsonArray {
                message.parts.forEach { part ->
                    when (part) {
                        is ChatPart.Text -> addJsonObject {
                            put("type", "text")
                            put("text", part.text)
                        }
                        is ChatPart.Image -> addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", part.dataUrl) }
                        }
                    }
                }
            },
        )
    }
}

internal fun LlmEndpoint.url(path: String): String = baseUrl.trim().trimEnd('/') + "/" + path

/** 可取消的异步请求：协程取消时取消 OkHttp 调用。网络错误转成 [LlmException]。 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
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

@Serializable
private data class ModelListDto(val data: List<ModelDto>)

@Serializable
private data class ModelDto(
    val id: String,
    val name: String? = null,
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
    val architecture: ArchitectureDto? = null,
    val effort: EffortDto? = null,
    val type: String? = null,
    val task: String? = null,
    @SerialName("supports_thinking") val supportsThinking: Boolean? = null,
    @SerialName("thinking_mode") val thinkingMode: ThinkingMode? = null,
    val temperature: ReportedTemperatureDto? = null,
    @SerialName("supported_parameters") val supportedParameters: List<String>? = null,
)

@Serializable
private data class ArchitectureDto(
    @SerialName("input_modalities") val inputModalities: List<String>? = null,
)

@Serializable
private data class ChunkDto(
    val choices: List<ChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
    val error: JsonElement? = null,
)

@Serializable
private data class ChoiceDto(
    val delta: DeltaDto? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class DeltaDto(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: String? = null,
)

@Serializable
private data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)

@Serializable
private data class EffortDto(@SerialName("supported_levels") val supportedLevels: List<String>)
@Serializable
private data class ReportedTemperatureDto(val supported: Boolean? = null, val min: Float? = null, val max: Float? = null, val fixed: Float? = null)
private fun ModelDto.rules(): ModelConfig {
    val purpose = task ?: type
    val chat = when (purpose) {
        "chat", "chat.completions", "text-generation", "text_generation" -> true
        "embedding", "embeddings", "rerank", "reranking", "tts", "asr", "speech", "image-generation", "audio" -> false
        else -> null
    }
    val thinking = if (supportsThinking == false) ThinkingRule(ThinkingMode.NONE) else {
        val efforts = effort?.supportedLevels?.map { NativeEffort(it, it) }.orEmpty()
        if (effort != null || supportsThinking == true || thinkingMode != null) ThinkingRule(thinkingMode ?: ThinkingMode.UNKNOWN, efforts) else null
    }
    val temp = temperature?.let {
        when {
            it.supported == false -> TemperatureSpec(TemperatureMode.UNSUPPORTED)
            it.fixed != null -> TemperatureSpec(TemperatureMode.FIXED, fixed = it.fixed)
            it.min != null && it.max != null -> TemperatureSpec(TemperatureMode.ADJUSTABLE, min = it.min, max = it.max)
            else -> null
        }
    }?.takeIf { it.valid() }?.let { TemperatureRule(it) }
    return ModelConfig(chat, thinking, temp)
}
