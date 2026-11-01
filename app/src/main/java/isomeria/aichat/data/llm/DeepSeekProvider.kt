package isomeria.aichat.data.llm

import isomeria.aichat.data.db.DeepSeekDefaults
import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * DeepSeek 实现。DeepSeek 兼容 OpenAI 的 Chat Completions 格式。
 *
 * 字段对照官方文档（api-docs.deepseek.com，2026-11 核对）：
 * - thinking: {"type": "enabled" | "disabled"}，不传时默认开启，所以关闭必须显式传
 * - reasoning_effort: "low" | "high" | "max"，开启思考时生效
 * - temperature: 0–2，思考模式下不生效（不报错），所以只在关闭思考时发送
 * - 流式返回 delta.content 与 delta.reasoning_content，stream_options.include_usage 时最后一块带 usage
 * - GET /models 返回 input_modalities，含 "image" 表示支持图片输入
 */
@Singleton
class DeepSeekProvider @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) : LlmProvider {

    override val id: String = DeepSeekDefaults.PROVIDER

    override suspend fun listModels(endpoint: LlmEndpoint): List<RemoteModel> {
        val request = Request.Builder()
            .url(endpoint.url("models"))
            .header("Authorization", "Bearer ${endpoint.apiKey}")
            .header("Accept", "application/json")
            .get()
            .build()
        val body = client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw LlmException.fromHttp(response.code)
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
        return list.data.map { dto ->
            RemoteModel(
                id = dto.id,
                name = dto.name,
                supportsImage = dto.inputModalities?.contains("image"),
            )
        }
    }

    override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = callbackFlow {
        val body = DeepSeekRequestBuilder.build(request).toString()
        val call = client.newCall(
            Request.Builder()
                .url(endpoint.url("chat/completions"))
                .header("Authorization", "Bearer ${endpoint.apiKey}")
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        )
        val reader = launch(Dispatchers.IO) {
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw LlmException.fromHttp(response.code)
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
            choice.delta?.reasoningContent?.takeIf { it.isNotEmpty() }?.let { events += ChatEvent.Reasoning(it) }
            choice.delta?.content?.takeIf { it.isNotEmpty() }?.let { events += ChatEvent.Content(it) }
            choice.finishReason?.let { events += ChatEvent.Finished(it) }
        }
        chunk.usage?.let { events += ChatEvent.Usage(it.promptTokens, it.completionTokens) }
        return events
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** 拼请求体。单独拿出来，方便单元测试核对字段。 */
object DeepSeekRequestBuilder {
    fun build(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        putJsonArray("messages") {
            request.messages.forEach { add(messageJson(it)) }
        }
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        when (request.thinking) {
            ThinkingLevel.OFF -> {
                putJsonObject("thinking") { put("type", "disabled") }
                put("temperature", (request.temperature.coerceIn(0f, 2f) * 10).roundToInt() / 10.0)
            }
            ThinkingLevel.HIGH -> {
                putJsonObject("thinking") { put("type", "enabled") }
                put("reasoning_effort", "high")
            }
            ThinkingLevel.MAX -> {
                putJsonObject("thinking") { put("type", "enabled") }
                put("reasoning_effort", "max")
            }
        }
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
private data class ModelListDto(val data: List<ModelDto> = emptyList())

@Serializable
private data class ModelDto(
    val id: String,
    val name: String? = null,
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
)

@Serializable
private data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
)
