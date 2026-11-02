package isomeria.aichat.data.llm

import isomeria.aichat.data.db.DeepSeekDefaults
import isomeria.aichat.data.db.ThinkingLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepSeek。接口兼容 OpenAI 格式，只有思考字段是自己的。
 *
 * 字段对照官方文档（api-docs.deepseek.com，2026-11 核对）：
 * - thinking: {"type": "enabled" | "disabled"}，不传时默认开启，所以关闭必须显式传
 * - reasoning_effort: "low" | "high" | "max"，开启思考时生效
 * - temperature: 0–2，思考模式下不生效（不报错），所以只在关闭思考时发送
 */
@Singleton
class DeepSeekProvider @Inject constructor(client: OkHttpClient, json: Json) :
    OpenAiCompatibleProvider(client, json, DeepSeekDefaults.PROVIDER, DeepSeekRequestBuilder::build)

/** 拼请求体。单独拿出来，方便单元测试核对字段。 */
object DeepSeekRequestBuilder {
    fun build(request: ChatRequest): JsonObject = request.parameters?.let { ConfiguredRequestBuilder.build(request) } ?: buildJsonObject {
        putCommonFields(request)
        when (request.thinking) {
            ThinkingLevel.OFF -> {
                putJsonObject("thinking") { put("type", "disabled") }
                putTemperature(request)
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
}
