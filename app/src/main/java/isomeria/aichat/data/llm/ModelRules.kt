package isomeria.aichat.data.llm

import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ThinkingLevel
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** 配置值不包含型号目录；只有接口明确报告或用户确认的规则。 */
@Serializable enum class ThinkingMode { UNKNOWN, NONE, SWITCH, ALWAYS }
@Serializable data class NativeEffort(val value: String, val label: String)
@Serializable data class ThinkingRule(val mode: ThinkingMode, val efforts: List<NativeEffort> = emptyList()) {
    fun options(): List<NativeEffort> = buildList {
        if (mode == ThinkingMode.UNKNOWN) return@buildList
        if (mode != ThinkingMode.ALWAYS) add(NativeEffort(OFF, ""))
        if (mode != ThinkingMode.NONE) {
            if (efforts.isEmpty()) add(NativeEffort(ON, "")) else addAll(efforts)
        }
    }
    companion object { const val OFF = "off"; const val ON = "on" }
}
@Serializable enum class TemperatureMode { ADJUSTABLE, FIXED, UNSUPPORTED }
@Serializable data class TemperatureSpec(
    val mode: TemperatureMode,
    val min: Float = 0f,
    val max: Float = 2f,
    val fixed: Float = 1f,
) {
    fun valid(): Boolean = when (mode) {
        TemperatureMode.ADJUSTABLE -> min.isFinite() && max.isFinite() && min >= 0 && max > min
        TemperatureMode.FIXED -> fixed.isFinite() && fixed >= 0
        TemperatureMode.UNSUPPORTED -> true
    }
}
@Serializable data class TemperatureRule(val normal: TemperatureSpec, val thinking: TemperatureSpec? = null) {
    fun forOption(option: String) = if (option == ThinkingRule.OFF) normal else thinking ?: normal
}
@Serializable data class ModelConfig(
    val chat: Boolean? = null,
    val thinking: ThinkingRule? = null,
    val temperature: TemperatureRule? = null,
) {
    fun over(reported: ModelConfig) = ModelConfig(chat ?: reported.chat, thinking ?: reported.thinking, temperature ?: reported.temperature)
}

/** 开关与强度分别编码；NONE 表示不支持该字段，不能用低强度冒充关闭。 */
@Serializable enum class ThinkingField { NONE, THINKING_TYPE, ENABLE_THINKING, REASONING_ENABLED, REASONING_EFFORT }
@Serializable enum class EffortField { NONE, REASONING_EFFORT, REASONING_OBJECT }
@Serializable data class CompatibilityOverride(
    val thinkingField: ThinkingField? = null,
    val effortField: EffortField? = null,
    val streamOptions: Boolean? = null,
)
data class EncodingRules(val thinkingField: ThinkingField, val effortField: EffortField, val streamOptions: Boolean = true) {
    fun over(value: CompatibilityOverride) = EncodingRules(value.thinkingField ?: thinkingField, value.effortField ?: effortField, value.streamOptions ?: streamOptions)
}
enum class Compatibility(val encoding: EncodingRules) {
    LEGACY(EncodingRules(ThinkingField.NONE, EffortField.NONE)),
    GENERIC(EncodingRules(ThinkingField.REASONING_EFFORT, EffortField.REASONING_EFFORT)),
    DEEPSEEK(EncodingRules(ThinkingField.THINKING_TYPE, EffortField.REASONING_EFFORT)),
    QWEN(EncodingRules(ThinkingField.ENABLE_THINKING, EffortField.NONE)),
    ZHIPU(EncodingRules(ThinkingField.THINKING_TYPE, EffortField.NONE, false)),
    KIMI(EncodingRules(ThinkingField.THINKING_TYPE, EffortField.NONE, false)),
    MIMO(EncodingRules(ThinkingField.THINKING_TYPE, EffortField.NONE, false)),
    SILICONFLOW(EncodingRules(ThinkingField.ENABLE_THINKING, EffortField.NONE, false)),
    OPENROUTER(EncodingRules(ThinkingField.REASONING_ENABLED, EffortField.REASONING_OBJECT)),
}

object ModelRules {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(value: String?): ModelConfig = value?.let { json.decodeFromString<ModelConfig>(it) } ?: ModelConfig()
    fun encode(value: ModelConfig): String = json.encodeToString(value)
    fun override(value: String?): CompatibilityOverride = value?.let { json.decodeFromString<CompatibilityOverride>(it) } ?: CompatibilityOverride()
    fun encode(value: CompatibilityOverride): String = json.encodeToString(value)
    fun effective(model: ProviderModel): ModelConfig = decode(model.userConfig).over(decode(model.reportedConfig))
    fun compatibility(config: ProviderConfig): Compatibility = Compatibility.valueOf(config.compatibility)
    fun encoding(config: ProviderConfig, model: ProviderModel): EncodingRules {
        val inherited = compatibility(config).let {
            if (it == Compatibility.LEGACY) {
                if (config.provider == "deepseek") Compatibility.DEEPSEEK.encoding else Compatibility.GENERIC.encoding
            } else it.encoding
        }
        return inherited.over(override(model.compatibilityOverride))
    }
    fun complete(model: ProviderModel): Boolean = model.legacy || completeNew(model)
    fun completeNew(model: ProviderModel): Boolean = runCatching {
        val c = effective(model)
        val t = c.thinking
        val temp = c.temperature
        model.modelId.isNotBlank() && (model.imageInputUserMark != null || model.imageInput != isomeria.aichat.data.db.Capability.UNKNOWN) &&
            c.chat == true && t != null && validThinking(t) && temp != null && temp.normal.valid() && (temp.thinking?.valid() != false)
    }.getOrDefault(false)

    fun validThinking(t: ThinkingRule): Boolean = t.mode != ThinkingMode.UNKNOWN && (t.mode != ThinkingMode.NONE || t.efforts.isEmpty()) &&
        t.efforts.all { it.value.matches(Regex("[A-Za-z0-9_-]{1,40}")) && it.value !in listOf("off", "on", "none") && it.label.isNotBlank() } &&
        t.efforts.map { it.value }.distinct().size == t.efforts.size

    fun legacyOption(level: ThinkingLevel): String = level.name.lowercase()
    fun options(model: ProviderModel?): List<NativeEffort> = if (model == null || model.legacy) {
        if (model != null && !model.supportsThinking) listOf(NativeEffort("off", ""))
        else listOf(NativeEffort("off", ""), NativeEffort("high", ""), NativeEffort("max", ""))
    } else runCatching { effective(model).thinking?.options().orEmpty() }.getOrDefault(emptyList())

    /** 返回安全的分类原因；不携带 Key、正文或原始服务端响应。 */
    fun validate(config: ProviderConfig, model: ProviderModel?, option: String, temperature: Float): ConfigurationIssue? = runCatching {
        if (model == null) return if (config.compatibility == "LEGACY") null else ConfigurationIssue.MODEL
        if (model.legacy && config.compatibility == "LEGACY") return null
        if (!completeNew(model)) return ConfigurationIssue.INCOMPLETE
        val rules = effective(model)
        if (option !in rules.thinking!!.options().map { it.value }) return ConfigurationIssue.THINKING
        val encoding = encoding(config, model)
        if (rules.thinking.mode == ThinkingMode.SWITCH && encoding.thinkingField == ThinkingField.NONE) return ConfigurationIssue.ENCODING
        if (option != "off" && rules.thinking.efforts.isNotEmpty() && encoding.effortField == EffortField.NONE) return ConfigurationIssue.ENCODING
        if (option == "on" && rules.thinking.mode == ThinkingMode.SWITCH && encoding.thinkingField == ThinkingField.REASONING_EFFORT) return ConfigurationIssue.ENCODING
        val temp = rules.temperature!!.forOption(option)
        if (temp.mode == TemperatureMode.ADJUSTABLE && (!temperature.isFinite() || temperature < temp.min || temperature > temp.max)) return ConfigurationIssue.TEMPERATURE
        null
    }.getOrDefault(ConfigurationIssue.INCOMPLETE)

    fun usable(config: ProviderConfig, model: ProviderModel): Boolean {
        if (model.legacy && config.compatibility == "LEGACY") return true
        if (!completeNew(model)) return false
        return effective(model).thinking!!.options().all { option ->
            val spec = effective(model).temperature!!.forOption(option.value)
            validate(config, model, option.value, if (spec.mode == TemperatureMode.ADJUSTABLE) spec.min else 0.3f) == null
        }
    }

    /** 模型为什么还不能用；能用时为空。 */
    fun gaps(config: ProviderConfig, model: ProviderModel): List<ModelGap> {
        if (usable(config, model)) return emptyList()
        val rules = runCatching { effective(model) }.getOrDefault(ModelConfig())
        if (rules.chat == false) return listOf(ModelGap.NOT_CHAT)
        return buildList {
            if (rules.chat != true) add(ModelGap.CHAT)
            if (model.imageInputUserMark == null && model.imageInput == isomeria.aichat.data.db.Capability.UNKNOWN) add(ModelGap.IMAGE)
            if (rules.thinking == null || !validThinking(rules.thinking)) add(ModelGap.THINKING)
            if (rules.temperature == null || !rules.temperature.normal.valid() || rules.temperature.thinking?.valid() == false) add(ModelGap.TEMPERATURE)
            if (isEmpty()) add(ModelGap.ENCODING)
        }
    }

    /** 原因在模型规则本身，要到模型配置页修正；其余原因在联系人或记忆的选择上。 */
    fun fixedInModel(issue: ConfigurationIssue): Boolean = issue == ConfigurationIssue.INCOMPLETE || issue == ConfigurationIssue.ENCODING

    /** 旧请求语义下模型是否支持思考：没有模型记录时只认 DeepSeek 格式。 */
    fun legacySupportsThinking(config: ProviderConfig, model: ProviderModel?): Boolean =
        model?.supportsThinking ?: (config.provider == isomeria.aichat.data.db.ProviderProtocol.DEEPSEEK)

    /** 旧请求语义的思考档位：模型不支持思考时按关闭发送；认不出的档位返回 null（请求会被拒绝）。 */
    fun legacyThinking(option: String, supportsThinking: Boolean): ThinkingLevel? {
        val level = ThinkingLevel.entries.firstOrNull { it.name.equals(option, true) } ?: return null
        return if (supportsThinking) level else ThinkingLevel.OFF
    }

    /**
     * 聊天请求里实际发送的温度，和 [parameters] 及各请求构造器的取舍一致：
     * 新规则按模型的温度规则（可调、固定、不发送，思考档位可以有单独规则）；
     * 旧请求语义只在思考关闭时发送，按 0.1 取整、限制在 0～2。
     * 配置有问题时请求发不出去，返回 [RequestTemperature.Blocked]，界面显示保存的值并由问题行说明。
     */
    fun requestTemperature(config: ProviderConfig, model: ProviderModel?, option: String, temperature: Float): RequestTemperature {
        if (validate(config, model, option, temperature) != null) return RequestTemperature.Blocked(temperature)
        if (model == null || (model.legacy && config.compatibility == "LEGACY")) {
            val level = legacyThinking(option, legacySupportsThinking(config, model)) ?: return RequestTemperature.Blocked(temperature)
            return if (level == ThinkingLevel.OFF) RequestTemperature.Sent(legacyTemperature(temperature)) else RequestTemperature.NotSent
        }
        val spec = effective(model).temperature!!.forOption(option)
        return when (spec.mode) {
            TemperatureMode.ADJUSTABLE -> RequestTemperature.Sent(temperature)
            TemperatureMode.FIXED -> RequestTemperature.Fixed(spec.fixed)
            TemperatureMode.UNSUPPORTED -> RequestTemperature.NotSent
        }
    }

    /** 旧请求构造器发送的温度：限制在 0～2，按 0.1 取整 */
    fun legacyTemperature(value: Float): Float = (value.coerceIn(0f, 2f) * 10).roundToInt() / 10f

    fun parameters(config: ProviderConfig, model: ProviderModel?, option: String, temperature: Float): RequestParameters? {
        validate(config, model, option, temperature)?.let { throw LlmException(LlmError.CONFIGURATION) }
        if (model == null || (model.legacy && config.compatibility == "LEGACY")) return null
        val spec = effective(model).temperature!!.forOption(option)
        val value = when (spec.mode) {
            TemperatureMode.ADJUSTABLE -> temperature
            TemperatureMode.FIXED -> spec.fixed
            TemperatureMode.UNSUPPORTED -> null
        }
        val thinking = effective(model).thinking!!
        return RequestParameters(encoding(config, model), thinking.mode != ThinkingMode.NONE, option, thinking.efforts.isNotEmpty(), value)
    }
}
enum class ConfigurationIssue { MODEL, INCOMPLETE, THINKING, TEMPERATURE, ENCODING }
enum class ModelGap { NOT_CHAT, CHAT, IMAGE, THINKING, TEMPERATURE, ENCODING }
/** 联系人详情显示的温度：请求里实际怎么发。 */
sealed interface RequestTemperature {
    data class Sent(val value: Float) : RequestTemperature
    /** 模型要求固定温度，发送模型规定的值 */
    data class Fixed(val value: Float) : RequestTemperature
    /** 模型或当前思考档位不发送温度 */
    data object NotSent : RequestTemperature
    /** 配置有问题，请求发不出去；显示保存的值 */
    data class Blocked(val saved: Float) : RequestTemperature
}

data class RequestParameters(val encoding: EncodingRules, val hasThinking: Boolean, val option: String, val hasEfforts: Boolean, val temperature: Float?)

object ConfiguredRequestBuilder {
    fun build(request: ChatRequest): JsonObject = buildJsonObject {
        val p = requireNotNull(request.parameters)
        putCommonFields(request, p.encoding.streamOptions)
        if (p.hasThinking) {
            val enabled = p.option != "off"
            when (p.encoding.thinkingField) {
                ThinkingField.NONE -> Unit
                ThinkingField.THINKING_TYPE -> putJsonObject("thinking") { put("type", if (enabled) "enabled" else "disabled") }
                ThinkingField.ENABLE_THINKING -> put("enable_thinking", enabled)
                ThinkingField.REASONING_ENABLED -> putJsonObject("reasoning") {
                    put("enabled", enabled)
                    if (enabled && p.hasEfforts && p.encoding.effortField == EffortField.REASONING_OBJECT) put("effort", p.option)
                }
                ThinkingField.REASONING_EFFORT -> if (!enabled) put("reasoning_effort", "none")
            }
            if (enabled && p.hasEfforts) when (p.encoding.effortField) {
                EffortField.NONE -> Unit
                EffortField.REASONING_EFFORT -> put("reasoning_effort", p.option)
                EffortField.REASONING_OBJECT -> if (p.encoding.thinkingField != ThinkingField.REASONING_ENABLED) {
                    putJsonObject("reasoning") { put("effort", p.option) }
                }
            }
        }
        p.temperature?.let { put("temperature", it) }
    }
}
