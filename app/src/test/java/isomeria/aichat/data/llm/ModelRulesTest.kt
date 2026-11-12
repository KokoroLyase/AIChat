package isomeria.aichat.data.llm

import isomeria.aichat.data.db.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelRulesTest {
    private fun provider(compat: Compatibility) = ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = "openai", name = "测试", baseUrl = "https://example.com/A/v1", defaultModel = "m", compatibility = compat.name)
    private val efforts = listOf("minimal", "low", "medium", "high", "xhigh", "max").map { NativeEffort(it, "显示 $it") }
    private fun model(thinking: ThinkingRule = ThinkingRule(ThinkingMode.SWITCH, efforts), temperature: TemperatureRule = TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE)), override: CompatibilityOverride? = null) =
        ProviderModel(1, "m", "m", imageInputUserMark = false, legacy = false,
            userConfig = ModelRules.encode(ModelConfig(true, thinking, temperature)), compatibilityOverride = override?.let(ModelRules::encode))
    private fun body(c: Compatibility, m: ProviderModel, option: String, temp: Float = 0.7f): JsonObject = ConfiguredRequestBuilder.build(
        ChatRequest("m", emptyList(), ThinkingLevel.OFF, temp, ModelRules.parameters(provider(c), m, option, temp)))

    @Test fun completeEffortsKeepNativeValuesAndLabels() {
        val m = model()
        assertEquals(listOf("off") + efforts.map { it.value }, ModelRules.options(m).map { it.value })
        efforts.forEach { effort ->
            val b = body(Compatibility.DEEPSEEK, m, effort.value)
            assertEquals(effort.value, b["reasoning_effort"]!!.jsonPrimitive.content)
            assertEquals("enabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        assertEquals(efforts, ModelRules.decode(m.userConfig).thinking!!.efforts)
    }
    @Test fun toggleOnlyDoesNotInventIntensity() {
        val m = model(ThinkingRule(ThinkingMode.SWITCH))
        assertEquals(listOf("off", "on"), ModelRules.options(m).map { it.value })
        for (c in listOf(Compatibility.QWEN, Compatibility.SILICONFLOW, Compatibility.ZHIPU, Compatibility.KIMI, Compatibility.MIMO)) {
            val b = body(c, m, "on")
            assertFalse(b.containsKey("reasoning_effort"))
            assertFalse(b.containsKey("reasoning"))
            if (c == Compatibility.QWEN || c == Compatibility.SILICONFLOW) assertEquals(JsonPrimitive(true), b["enable_thinking"])
            else assertEquals("enabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(c.encoding.streamOptions, b.containsKey("stream_options"))
        }
    }
    @Test fun offIsExplicitAndNeverMappedToLow() {
        val m = model()
        assertEquals("disabled", body(Compatibility.DEEPSEEK, m, "off")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("none", body(Compatibility.GENERIC, m, "off")["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), body(Compatibility.OPENROUTER, m, "off")["reasoning"]!!.jsonObject["enabled"])
        assertFalse(body(Compatibility.DEEPSEEK, m, "off").containsKey("reasoning_effort"))
    }
    @Test fun openRouterUsesNestedEffortOnly() {
        val b = body(Compatibility.OPENROUTER, model(), "xhigh")
        assertEquals("xhigh", b["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(b.containsKey("reasoning_effort"))
        assertFalse(b.containsKey("thinking"))
    }
    @Test fun alwaysOnRejectsOffAndInvalidStrength() {
        val m = model(ThinkingRule(ThinkingMode.ALWAYS, efforts))
        assertEquals(ConfigurationIssue.THINKING, ModelRules.validate(provider(Compatibility.DEEPSEEK), m, "off", 1f))
        assertEquals(ConfigurationIssue.THINKING, ModelRules.validate(provider(Compatibility.DEEPSEEK), m, "ultra", 1f))
        assertNull(ModelRules.validate(provider(Compatibility.DEEPSEEK), m, "low", 1f))
    }
    @Test fun allTemperatureModesAndThinkingOverride() {
        val adjustable = TemperatureSpec(TemperatureMode.ADJUSTABLE, min = 0.2f, max = 0.8f)
        val m = model(temperature = TemperatureRule(adjustable, TemperatureSpec(TemperatureMode.FIXED, fixed = 1f)))
        assertEquals("0.7", body(Compatibility.DEEPSEEK, m, "off")["temperature"]!!.jsonPrimitive.content)
        assertEquals("1.0", body(Compatibility.DEEPSEEK, m, "low", 1.3f)["temperature"]!!.jsonPrimitive.content)
        assertEquals(ConfigurationIssue.TEMPERATURE, ModelRules.validate(provider(Compatibility.DEEPSEEK), m, "off", 1.3f))
        assertFalse(body(Compatibility.DEEPSEEK, m.copy(userConfig = ModelRules.encode(ModelRules.effective(m).copy(temperature = TemperatureRule(TemperatureSpec(TemperatureMode.UNSUPPORTED))))), "off").containsKey("temperature"))
        assertEquals(1.3f, Contact.DEFAULT_TEMPERATURE)
    }
    @Test fun noThinkingSendsNoPrivateFields() {
        for (c in Compatibility.entries.filter { it != Compatibility.LEGACY }) {
            val b = body(c, model(ThinkingRule(ThinkingMode.NONE)), "off")
            assertFalse(b.containsKey("thinking") || b.containsKey("enable_thinking") || b.containsKey("reasoning") || b.containsKey("reasoning_effort"))
        }
    }
    @Test fun modelOverrideIsLocalAndRestoreReallyInherits() {
        val m = model(override = CompatibilityOverride(ThinkingField.ENABLE_THINKING, EffortField.REASONING_EFFORT, false))
        val custom = body(Compatibility.DEEPSEEK, m, "low")
        assertEquals(JsonPrimitive(true), custom["enable_thinking"])
        assertFalse(custom.containsKey("thinking") || custom.containsKey("stream_options"))
        val inherited = body(Compatibility.DEEPSEEK, m.copy(compatibilityOverride = null), "low")
        assertTrue(inherited.containsKey("thinking") && inherited.containsKey("stream_options"))
    }
    @Test fun missingCapabilitiesAndPurposeNeverBecomeUsable() {
        val m = ProviderModel(1, "m", "m", legacy = false)
        assertFalse(ModelRules.complete(m))
        assertFalse(ModelRules.complete(model().copy(imageInputUserMark = null)))
        assertFalse(ModelRules.complete(model().copy(userConfig = ModelRules.encode(ModelConfig(false, ThinkingRule(ThinkingMode.NONE), TemperatureRule(TemperatureSpec(TemperatureMode.UNSUPPORTED)))))))
        assertEquals(ConfigurationIssue.INCOMPLETE, ModelRules.validate(provider(Compatibility.GENERIC), m, "off", 0.3f))
    }
    @Test fun userOverridesReportedRulesAndInvalidRangesStayInvalid() {
        val reported = ModelConfig(true, ThinkingRule(ThinkingMode.SWITCH, efforts), TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE)))
        val user = ModelConfig(thinking = ThinkingRule(ThinkingMode.NONE))
        val m = model().copy(userConfig = ModelRules.encode(user), reportedConfig = ModelRules.encode(reported))
        assertEquals(ThinkingMode.NONE, ModelRules.effective(m).thinking!!.mode)
        assertEquals(reported.temperature, ModelRules.effective(m).temperature)
        assertFalse(TemperatureSpec(TemperatureMode.ADJUSTABLE, 2f, 1f).valid())
        assertFalse(TemperatureSpec(TemperatureMode.FIXED, fixed = Float.NaN).valid())
    }
    @Test fun unsupportedEncodingsFailLocallyAndLegacyKeepsBody() {
        assertEquals(ConfigurationIssue.ENCODING, ModelRules.validate(provider(Compatibility.QWEN), model(), "high", 1f))
        val request = ChatRequest("old", emptyList(), ThinkingLevel.MAX, 1.3f)
        assertEquals("high", OpenAiRequestBuilder.build(request)["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("max", DeepSeekRequestBuilder.build(request)["reasoning_effort"]!!.jsonPrimitive.content)
        assertNull(ModelRules.parameters(provider(Compatibility.LEGACY), model().copy(legacy = true), "max", 1.3f))
    }
    @Test fun legacyUnsupportedMarkNeverOffersInventedStrengths() {
        val m = ProviderModel(1, "old", "old", thinkingInput = Capability.YES, thinkingUserMark = false)
        assertEquals(listOf("off"), ModelRules.options(m).map { it.value })
        assertEquals(listOf("off", "high", "max"), ModelRules.options(m.copy(thinkingUserMark = true)).map { it.value })
    }

    @Test fun presetsAreOnlyConnectionsInFixedOrder() {
        // 用户 2026-11-12 定的顺序：0.7.2 加了火山引擎（豆包）和 MiniMax
        assertEquals(listOf("deepseek", "volcengine", "qwen", "zhipu", "kimi", "mimo", "minimax", "siliconflow", "openrouter"), ProviderPresets.all.map { it.id })
        assertEquals(9, ProviderPresets.all.size)
        val volc = ProviderPresets.all.first { it.id == "volcengine" }
        assertEquals("https://ark.cn-beijing.volces.com/api/v3", volc.baseUrl)
        assertEquals(Compatibility.VOLCENGINE, volc.compatibility)
        val minimax = ProviderPresets.all.first { it.id == "minimax" }
        assertEquals("https://api.minimax.cn/v1", minimax.baseUrl)
        assertEquals(Compatibility.MINIMAX, minimax.compatibility)
        assertTrue(ProviderPresets.all.all { it.docs.startsWith("https://") })
    }

    @Test fun volcengineUsesThinkingTypeAndReasoningEffort() {
        val on = body(Compatibility.VOLCENGINE, model(), "high")
        assertEquals("enabled", on["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("high", on["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue(on.containsKey("stream_options"))
        val off = body(Compatibility.VOLCENGINE, model(), "off")
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(off.containsKey("reasoning_effort"))
        // 只有开关、没有强度档位的模型不发 reasoning_effort
        val toggle = body(Compatibility.VOLCENGINE, model(ThinkingRule(ThinkingMode.SWITCH)), "on")
        assertEquals("enabled", toggle["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(toggle.containsKey("reasoning_effort"))
        assertFalse(on.containsKey("reasoning_split"))
    }

    @Test fun minimaxUsesAdaptiveOrDisabled_andAlwaysSplitsReasoning() {
        val on = body(Compatibility.MINIMAX, model(ThinkingRule(ThinkingMode.SWITCH)), "on")
        assertEquals("MiniMax 不认 enabled", "adaptive", on["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), on["reasoning_split"])
        assertTrue(on.containsKey("stream_options"))
        assertFalse(on.containsKey("reasoning_effort"))
        val off = body(Compatibility.MINIMAX, model(ThinkingRule(ThinkingMode.SWITCH)), "off")
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), off["reasoning_split"])
        // M3.1-Flash：始终思考，强度放 reasoning_effort
        val flash = model(ThinkingRule(ThinkingMode.ALWAYS, listOf("low", "medium", "high", "xhigh", "max").map { NativeEffort(it, it) }))
        assertEquals(ConfigurationIssue.THINKING, ModelRules.validate(provider(Compatibility.MINIMAX), flash, "off", 0.7f))
        val max = body(Compatibility.MINIMAX, flash, "max")
        assertEquals("adaptive", max["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("max", max["reasoning_effort"]!!.jsonPrimitive.content)
        // 没有思考的模型也带 reasoning_split，免得思考内容用 <think> 夹在正文里
        assertEquals(JsonPrimitive(true), body(Compatibility.MINIMAX, model(ThinkingRule(ThinkingMode.NONE)), "off")["reasoning_split"])
    }

    @Test fun reasoningSplitFollowsTheSchemeEvenWithModelOverride() {
        val m = model(ThinkingRule(ThinkingMode.SWITCH), override = CompatibilityOverride(ThinkingField.THINKING_TYPE, EffortField.NONE, false))
        val b = body(Compatibility.MINIMAX, m, "on")
        assertEquals("enabled", b["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), b["reasoning_split"])
        assertFalse(b.containsKey("stream_options"))
        assertEquals("adaptive", body(Compatibility.DEEPSEEK, model(ThinkingRule(ThinkingMode.SWITCH), override = CompatibilityOverride(ThinkingField.THINKING_TYPE_ADAPTIVE)), "on")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
}
