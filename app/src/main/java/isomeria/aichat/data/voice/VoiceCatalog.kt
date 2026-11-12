package isomeria.aichat.data.voice

import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 一个可选的音色：[id] 是填进请求的值，[name] 是显示名，[detail] 是性别、风格之类的说明，[group] 是分组标题。 */
data class VoiceOption(val id: String, val name: String, val detail: String = "", val group: String = "")

/**
 * 要列音色或试听的那个合成来源（0.7.2）：服务商的预置、格式、地址、Key（编辑页里可能是还没保存的）和模型；
 * [systemTts] 为 true 时是本机的系统语音
 */
data class VoiceTarget(
    val presetId: String?,
    val protocol: String,
    val baseUrl: String,
    val apiKey: String?,
    val model: String,
    val systemTts: Boolean = false,
) {
    override fun toString() = "VoiceTarget(preset=$presetId, protocol=$protocol, model=$model, systemTts=$systemTts)"
}

/** 列音色时缺 Key：MiniMax、Fish Audio 的音色要用 Key 从服务商取 */
class VoiceCatalogNeedsKeyException : Exception("api key required")

/**
 * 可选的音色（0.7.2）：
 *
 * - MiMo、火山引擎、百炼、硅基流动：内置官方音色列表（2026-11-12 按官方文档整理，可能不全，新音色可以手填 ID）；
 *   硅基流动另外用 Key 取自己上传的音色
 * - MiniMax：用 Key 从服务商取（系统音色和自己复刻、设计的音色）
 * - Fish Audio：公开音色非常多，按名称到服务商搜索；另外列出自己的音色
 * - 智谱：内置 7 个系统音色；有 Key 时另外取自己复刻的音色（GET /voice/list?voiceType=PRIVATE）
 * - OpenRouter：按模型名内置 Gemini TTS、OpenAI 的音色，别的模型没有列表
 * - 系统语音：手机引擎里不联网的中文音色
 *
 * 只请求用户配置的地址，不跟随重定向；不记录返回的内容。
 */
@Singleton
class VoiceCatalog @Inject constructor(client: OkHttpClient, private val json: Json, private val systemTts: SystemTts) {

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Fish Audio：输入关键词后到服务商搜索，不在本机过滤 */
    fun searchesRemotely(target: VoiceTarget): Boolean = !target.systemTts && target.protocol == VoiceProtocol.FISH_TTS

    /**
     * 打开列表时的音色：内置的加上从服务商取的。Fish Audio 是自己的音色加上热门的公开音色。
     * 内置列表在缺 Key 时照常返回；只能从服务商取的缺 Key 时抛 [VoiceCatalogNeedsKeyException]。
     * 有内置列表时，另外取自己的音色失败（Key 不对、网络）也照常列出内置的，不让整页打不开。
     * 同一组里重复的 ID 只留一个（列表按组和 ID 作键）
     */
    suspend fun list(target: VoiceTarget): List<VoiceOption> = withContext(Dispatchers.IO) { listAll(target).distinctBy { it.group to it.id } }

    private suspend fun listAll(target: VoiceTarget): List<VoiceOption> {
        if (target.systemTts) {
            return systemTts.info().voices.map { VoiceOption(it.name, it.name, it.locale) }
        }
        val builtIn = BuiltInVoices.forTarget(target).orEmpty()
        return when {
            target.protocol == VoiceProtocol.MINIMAX_TTS -> miniMaxVoices(endpoint(target))
            target.protocol == VoiceProtocol.FISH_TTS -> fishMine(endpoint(target)) + fishSearch(endpoint(target), "")
            target.protocol == VoiceProtocol.ZHIPU_TTS -> {
                val key = target.apiKey?.takeIf { it.isNotBlank() } ?: return builtIn
                mineOrNothing { zhipuPrivate(LlmEndpoint(target.baseUrl, key)) } + builtIn
            }
            target.presetId == VoicePresets.SILICONFLOW.id && target.protocol == VoiceProtocol.OPENAI_AUDIO -> {
                // 没有 Key 时只列预置音色
                val key = target.apiKey?.takeIf { it.isNotBlank() } ?: return builtIn
                mineOrNothing { siliconFlowCustom(LlmEndpoint(target.baseUrl, key)) } + builtIn
            }
            else -> builtIn
        }
    }

    /** 取自己的音色；失败时当作没有（内置列表照常显示），取消照常传出去 */
    private suspend fun mineOrNothing(block: suspend () -> List<VoiceOption>): List<VoiceOption> = try {
        block()
    } catch (e: LlmException) {
        emptyList()
    }

    /** Fish Audio 按名称搜索公开音色（[query] 为空时是热门的） */
    suspend fun search(target: VoiceTarget, query: String): List<VoiceOption> = withContext(Dispatchers.IO) {
        fishSearch(endpoint(target), query.trim()).distinctBy { it.group to it.id }
    }

    private fun endpoint(target: VoiceTarget): LlmEndpoint {
        val key = target.apiKey?.takeIf { it.isNotBlank() } ?: throw VoiceCatalogNeedsKeyException()
        return LlmEndpoint(target.baseUrl, key)
    }

    private fun url(endpoint: LlmEndpoint, path: String) =
        (endpoint.baseUrl.trim().trimEnd('/') + "/" + path).toHttpUrlOrNull() ?: throw LlmException(LlmError.CONFIGURATION)

    private suspend fun get(request: Request): JsonObject = client.newCall(request).awaitResponse().use { response ->
        if (!response.isSuccessful) throw LlmException.fromHttp(response.code)
        try {
            json.parseToJsonElement(response.body.string()).jsonObject
        } catch (e: java.io.IOException) {
            throw LlmException(LlmError.NETWORK, cause = e)
        } catch (e: Exception) {
            throw LlmException(LlmError.UNKNOWN, message = "unparseable voice list", cause = e)
        }
    }

    /** MiniMax：POST /get_voice，voice_type=all；系统音色在前，自己复刻、设计的放在「我的音色」 */
    private suspend fun miniMaxVoices(endpoint: LlmEndpoint): List<VoiceOption> {
        val body = """{"voice_type":"all"}""".toRequestBody(JSON_TYPE)
        val root = get(Request.Builder().url(url(endpoint, "get_voice")).header("Authorization", endpoint.authorization()).post(body).build())
        val status = (root["base_resp"] as? JsonObject)?.get("status_code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        if (status == 1004 || status == 2049) throw LlmException(LlmError.AUTH, message = "minimax $status")
        if (status != 0) throw LlmException(LlmError.UNKNOWN, message = "minimax $status")
        fun JsonObject.text(name: String) = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun JsonObject.description() = (this["description"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString("，").orEmpty()
        val mine = listOf("voice_cloning", "voice_generation").flatMap { key ->
            (root[key] as? JsonArray).orEmpty().mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = item.text("voice_id").ifBlank { return@mapNotNull null }
                VoiceOption(id, id, item.description(), GROUP_MINE)
            }
        }
        val system = (root["system_voice"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.text("voice_id").ifBlank { return@mapNotNull null }
            VoiceOption(id, item.text("voice_name").ifBlank { id }, item.description(), GROUP_SYSTEM)
        }
        return mine + system
    }

    /** 硅基流动：GET /audio/voice/list，自己上传的音色，填进请求的是 uri */
    private suspend fun siliconFlowCustom(endpoint: LlmEndpoint): List<VoiceOption> {
        val root = get(Request.Builder().url(url(endpoint, "audio/voice/list")).header("Authorization", endpoint.authorization()).get().build())
        return (root["results"] as? JsonArray ?: root["result"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val uri = item["uri"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            VoiceOption(uri, item["customName"]?.jsonPrimitive?.contentOrNull ?: uri, item["model"]?.jsonPrimitive?.contentOrNull.orEmpty(), GROUP_MINE)
        }
    }

    /** 智谱：自己复刻的音色（系统音色内置）；名称、创建时间做说明 */
    private suspend fun zhipuPrivate(endpoint: LlmEndpoint): List<VoiceOption> {
        val url = url(endpoint, "voice/list").newBuilder().addQueryParameter("voiceType", "PRIVATE").build()
        val root = get(Request.Builder().url(url).header("Authorization", endpoint.authorization()).get().build())
        return (root["voice_list"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item["voice"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (item["voice_type"]?.jsonPrimitive?.contentOrNull?.equals("OFFICIAL", ignoreCase = true) == true) return@mapNotNull null
            VoiceOption(id, item["voice_name"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: id, item["create_time"]?.jsonPrimitive?.contentOrNull.orEmpty(), GROUP_MINE)
        }
    }

    private suspend fun fishModels(endpoint: LlmEndpoint, query: String, mine: Boolean): List<VoiceOption> {
        val url = url(endpoint, "model").newBuilder()
            .addQueryParameter("page_size", if (mine) "100" else FISH_PAGE.toString())
            .addQueryParameter("page_number", "1")
            .addQueryParameter("sort_by", "score")
            .apply {
                if (mine) addQueryParameter("self", "true")
                if (query.isNotEmpty()) addQueryParameter("title", query)
            }
            .build()
        val root = get(Request.Builder().url(url).header("Authorization", endpoint.authorization()).get().build())
        return (root["items"] as? JsonArray).orEmpty().mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item["_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val languages = (item["languages"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString("、").orEmpty()
            val author = (item["author"] as? JsonObject)?.get("nickname")?.jsonPrimitive?.contentOrNull.orEmpty()
            val detail = listOf(languages, author).filter { it.isNotBlank() }.joinToString(" · ")
            VoiceOption(id, item["title"]?.jsonPrimitive?.contentOrNull?.ifBlank { null } ?: id, detail, if (mine) GROUP_MINE else GROUP_PUBLIC)
        }
    }

    private suspend fun fishMine(endpoint: LlmEndpoint) = fishModels(endpoint, "", mine = true)

    private suspend fun fishSearch(endpoint: LlmEndpoint, query: String) = fishModels(endpoint, query, mine = false)

    companion object {
        /** 这个来源能不能打开音色列表：系统语音、有内置列表的、能从服务商取或搜索的 */
        fun supports(presetId: String?, protocol: String, systemTts: Boolean = false): Boolean = systemTts ||
            protocol == VoiceProtocol.VOLC_TTS || protocol == VoiceProtocol.QWEN_TTS ||
            protocol == VoiceProtocol.ZHIPU_TTS || protocol == VoiceProtocol.OPENROUTER_TTS ||
            protocol == VoiceProtocol.MINIMAX_TTS || protocol == VoiceProtocol.FISH_TTS ||
            (presetId == VoicePresets.MIMO.id && protocol == VoiceProtocol.CHAT_AUDIO) ||
            (presetId == VoicePresets.SILICONFLOW.id && protocol == VoiceProtocol.OPENAI_AUDIO)

        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val FISH_PAGE = 40
        /** 从服务商取的音色的分组：页面上换成对应的文字 */
        const val GROUP_MINE = "@mine"
        const val GROUP_SYSTEM = "@system"
        const val GROUP_PUBLIC = "@public"
    }
}
