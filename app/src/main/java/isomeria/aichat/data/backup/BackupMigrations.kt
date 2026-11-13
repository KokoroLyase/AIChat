package isomeria.aichat.data.backup

import isomeria.aichat.core.UsageStats
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.DeepSeekDefaults
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.ZoneId

/** 备份来自更新版本的智信：数据格式版本比当前新，需要先升级 App。 */
class BackupTooNewException(val formatVersion: Int) : Exception("备份数据格式 v$formatVersion 比当前 v${BackupFormat.VERSION} 新")

/** 不是智信的备份文件，或文件已损坏。 */
class InvalidBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 旧格式的 data.json 逐版升级到当前格式：v1 → v2 → … → [BackupFormat.VERSION]。
 *
 * 以后改格式时，在 [steps] 里加一项「从 N 升到 N+1」的转换，直接改 JSON 树（加字段、改名、拆分），
 * 不依赖旧的数据类。
 */
class BackupMigrations(
    private val current: Int = BackupFormat.VERSION,
    private val steps: Map<Int, (JsonObject) -> JsonObject> = STEPS,
) {
    fun migrate(data: JsonObject, from: Int): JsonObject {
        if (from > current) throw BackupTooNewException(from)
        if (from < 1) throw InvalidBackupException("数据格式版本无效：$from")
        var result = data
        for (version in from until current) {
            val step = steps[version] ?: throw InvalidBackupException("缺少 v$version 到 v${version + 1} 的转换")
            result = step(result)
        }
        return result
    }

    companion object {
        /** 键是旧版本号，值把这个版本的 data.json 转成下一个版本 */
        val STEPS: Map<Int, (JsonObject) -> JsonObject> = mapOf(
            1 to { data -> v1ToV2(data) },
            2 to { data -> v2ToV3(data) },
            3 to { data -> v3ToV4(data) },
            4 to { data -> v4ToV5(data) },
            5 to { data -> v5ToV6(data) },
            6 to { data -> v6ToV7(data) },
            7 to { data -> v7ToV8(data) },
            8 to { data -> v8ToV9(data) },
            9 to { data -> v9ToV10(data) },
        )

        /**
         * v9 → v10（0.9.0）：消息多了引用和译文，旧消息都没有（默认值）；多了收藏，旧文件里没有。data.json 不用改
         */
        private fun v9ToV10(data: JsonObject): JsonObject = data

        /**
         * v8 → v9（0.8.0）：消息多了通话类型和它的每一条话（[MessageDto.call]），旧文件里没有通话。data.json 不用改
         */
        private fun v8ToV9(data: JsonObject): JsonObject = data

        /**
         * v7 → v8（0.7.2）：记忆多了「是否核心记忆」，旧记忆都是近期记忆（默认值）；manifest 多了导出了哪些内容，
         * 旧文件没有时按全部内容处理。data.json 不用改
         */
        private fun v7ToV8(data: JsonObject): JsonObject = data

        /**
         * v1 → v2（0.3.0）：
         * - 上下文条数换算成 token 预算：条数 × 200，最低 1000（和数据库迁移一样）
         * - 服务商加上名称；DeepSeek 的模型标记为支持思考
         * - 用消息里的 token 数生成用量统计，日期按手机当前时区算
         * 记忆是新功能，v1 里没有；会话的记忆起点留空，恢复后第一次检查时再定。
         */
        fun v1ToV2(data: JsonObject, zone: ZoneId = ZoneId.systemDefault()): JsonObject {
            val result = data.toMutableMap()
            data.arrayOf("contacts")?.let { contacts ->
                result["contacts"] = JsonArray(
                    contacts.map { element ->
                        val contact = element.jsonObject
                        val count = contact["contextCount"]?.jsonPrimitive?.intOrNull ?: LEGACY_DEFAULT_CONTEXT_COUNT
                        val tokens = (count * TOKENS_PER_LEGACY_MESSAGE).coerceAtLeast(Contact.MIN_CONTEXT_TOKENS)
                        JsonObject(contact - "contextCount" + ("contextTokens" to JsonPrimitive(tokens)))
                    },
                )
            }
            data.arrayOf("providers")?.let { providers ->
                result["providers"] = JsonArray(providers.map { migrateProvider(it.jsonObject) })
            }
            result["usage"] = JsonArray(usageFromMessages(data.arrayOf("messages").orEmpty(), zone))
            return JsonObject(result)
        }

        /** 与 Room v2→v3 的默认值一致，不推断品牌，不复制记忆默认型号。 */
        fun v2ToV3(data: JsonObject): JsonObject {
            val providers = data.arrayOf("providers").orEmpty().map { item ->
                val p = item.jsonObject
                JsonObject(p + ("compatibility" to JsonPrimitive("LEGACY")) + ("models" to JsonArray(p.arrayOf("models").orEmpty().map {
                    JsonObject(it.jsonObject + ("legacy" to JsonPrimitive(true)))
                })))
            }
            val contacts = data.arrayOf("contacts").orEmpty().map { item ->
                JsonObject(item.jsonObject + ("memoryThinkingOption" to JsonPrimitive("off")))
            }
            return JsonObject(data + ("providers" to JsonArray(providers)) + ("contacts" to JsonArray(contacts)))
        }

        /** 与 Room v3→v4 的默认值一致：旧会话不静音、无聊天背景。 */
        fun v3ToV4(data: JsonObject): JsonObject {
            val conversations = data.arrayOf("conversations") ?: return data
            return JsonObject(
                data + ("conversations" to JsonArray(conversations.map {
                    JsonObject(it.jsonObject + ("muted" to JsonPrimitive(false)) + ("background" to JsonNull))
                })),
            )
        }

        /**
         * 与 Room v4→v5 的默认值一致：旧联系人「使用全局记忆」「使用回复偏好」默认开启（用户决定），
         * 分条显示 splitReplies 原样保留。旧备份里没有全局记忆和回复偏好，恢复时不写这两项。
         */
        fun v4ToV5(data: JsonObject): JsonObject {
            val contacts = data.arrayOf("contacts") ?: return data
            return JsonObject(
                data + ("contacts" to JsonArray(contacts.map {
                    JsonObject(it.jsonObject + ("useGlobalMemory" to JsonPrimitive(true)) + ("useReplyPreferences" to JsonPrimitive(true)))
                })),
            )
        }

        /**
         * 与 Room v5→v6 的默认值一致：旧联系人的主动聊天关闭（用户决定，恢复旧备份后没有任何后台模型调用）、
         * 频率适中；旧消息都是普通消息。旧备份里没有主动聊天的全局设置，恢复时保留本机的。
         */
        fun v5ToV6(data: JsonObject): JsonObject {
            val result = data.toMutableMap()
            data.arrayOf("contacts")?.let { contacts ->
                result["contacts"] = JsonArray(contacts.map {
                    JsonObject(it.jsonObject + ("proactiveEnabled" to JsonPrimitive(false)) + ("proactiveFrequency" to JsonPrimitive("MEDIUM")))
                })
            }
            data.arrayOf("messages")?.let { messages ->
                result["messages"] = JsonArray(messages.map { JsonObject(it.jsonObject + ("origin" to JsonPrimitive("NORMAL"))) })
            }
            (data["settings"] as? JsonObject)?.let { settings -> result["settings"] = JsonObject(settings - "proactive") }
            return JsonObject(result)
        }

        /**
         * 与 Room v6→v7 的默认值一致：旧联系人的消息发送类型用全局设置（默认跟随）、音色用服务商默认、没有语气要求；
         * 旧消息都不是语音；旧服务商没有默认音色。旧备份里没有语音消息的全局设置，恢复时保留本机的。
         */
        fun v6ToV7(data: JsonObject): JsonObject {
            val result = data.toMutableMap()
            data.arrayOf("contacts")?.let { contacts ->
                result["contacts"] = JsonArray(contacts.map {
                    JsonObject(it.jsonObject + ("replyType" to JsonNull) + ("ttsVoice" to JsonNull) + ("ttsStyle" to JsonPrimitive("")))
                })
            }
            data.arrayOf("messages")?.let { messages ->
                result["messages"] = JsonArray(messages.map {
                    JsonObject(it.jsonObject + ("voice" to JsonNull) + ("voiceMillis" to JsonPrimitive(0)) +
                        ("voicePlayed" to JsonPrimitive(false)) + ("voiceShowText" to JsonPrimitive(false)))
                })
            }
            data.arrayOf("providers")?.let { providers ->
                result["providers"] = JsonArray(providers.map { JsonObject(it.jsonObject + ("voice" to JsonPrimitive(""))) })
            }
            (data["settings"] as? JsonObject)?.let { settings -> result["settings"] = JsonObject(settings - "voice") }
            return JsonObject(result)
        }

        private fun migrateProvider(provider: JsonObject): JsonObject {
            val protocol = provider["provider"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val deepSeek = protocol == DeepSeekDefaults.PROVIDER
            val models = provider.arrayOf("models").orEmpty().map { model ->
                if (deepSeek) JsonObject(model.jsonObject + ("thinkingInput" to JsonPrimitive("YES"))) else model
            }
            return JsonObject(
                provider +
                    ("name" to JsonPrimitive(if (deepSeek) DeepSeekDefaults.NAME else protocol)) +
                    ("models" to JsonArray(models)),
            )
        }

        /** 分条回复只有第一条带用量，所以带用量的回复条数就是请求数 */
        private fun usageFromMessages(messages: List<JsonElement>, zone: ZoneId): List<JsonObject> {
            class Total(var prompt: Long = 0, var completion: Long = 0, var requests: Int = 0)
            val totals = LinkedHashMap<Pair<Long, Long>, Total>()
            for (element in messages) {
                val message = element.jsonObject
                if (message["role"]?.jsonPrimitive?.contentOrNull != "ASSISTANT") continue
                val prompt = message["promptTokens"]?.jsonPrimitive?.longOrNull ?: 0
                val completion = message["completionTokens"]?.jsonPrimitive?.longOrNull ?: 0
                if (prompt <= 0 && completion <= 0) continue
                val time = message["createdAt"]?.jsonPrimitive?.longOrNull ?: continue
                val contactId = message["contactId"]?.jsonPrimitive?.longOrNull ?: continue
                val total = totals.getOrPut(UsageStats.epochDay(time, zone) to contactId) { Total() }
                total.prompt += prompt
                total.completion += completion
                total.requests++
            }
            return totals.map { (key, total) ->
                buildJsonObject {
                    put("day", key.first)
                    put("contactId", key.second)
                    put("purpose", "CHAT")
                    put("promptTokens", total.prompt)
                    put("completionTokens", total.completion)
                    put("requests", total.requests)
                }
            }
        }

        private fun JsonObject.arrayOf(key: String): List<JsonElement>? = (this[key] as? JsonArray)?.jsonArray

        private const val LEGACY_DEFAULT_CONTEXT_COUNT = 20
        private const val TOKENS_PER_LEGACY_MESSAGE = 200
    }
}
