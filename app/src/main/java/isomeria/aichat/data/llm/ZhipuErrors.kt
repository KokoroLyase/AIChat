package isomeria.aichat.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 智谱 BigModel（和国际版 Z.ai）的错误（0.7.2，2026-11-12 按官方错误码表）：出错时返回 `{"error":{"code":"1113","message":…}}`，
 * 余额不足（1113）、限流（1302）、套餐用量（1308、1309）的 HTTP 状态码都是 429，只看状态码会把余额不足说成请求太快。
 *
 * 只认错误码表里有的数字；读不出或不认识时按 HTTP 状态码分类（[LlmException.fromHttp]）。
 * 别家的错误内容一般不是这个形状，聊天接口对所有服务商都先过一遍也不会误判。不记录返回内容
 */
object ZhipuErrors {
    private val json = Json { ignoreUnknownKeys = true }

    fun of(httpCode: Int, body: String): LlmException {
        val code = codeOf(body) ?: return LlmException.fromHttp(httpCode)
        val error = when (code) {
            1000, 1001, 1002, 1003, 1004, 1005, 1220 -> LlmError.AUTH
            1113, 1308, 1309 -> LlmError.BALANCE
            1302 -> LlmError.RATE_LIMIT
            1210, 1212, 1213, 1214, 1215, 1261, 1301 -> LlmError.BAD_REQUEST
            1211, 1221, 1222 -> LlmError.NOT_FOUND
            1200, 1230, 1234, 1305 -> LlmError.SERVER
            else -> return LlmException.fromHttp(httpCode)
        }
        return LlmException(error, httpCode, "zhipu $code")
    }

    /** `error.code` 是 1000–1399 的数字（字符串或数字都认）时返回它 */
    private fun codeOf(body: String): Int? = try {
        val error = json.parseToJsonElement(body).jsonObject["error"] as? JsonObject
        error?.get("code")?.jsonPrimitive?.contentOrNull?.trim()?.toIntOrNull()?.takeIf { it in 1000..1399 }
    } catch (_: Exception) {
        null
    }
}
