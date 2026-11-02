package isomeria.aichat.feature.settings

import isomeria.aichat.R
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.ConfigurationIssue
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.llm.ModelConfig
import isomeria.aichat.data.llm.ModelGap
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.llm.NativeEffort
import isomeria.aichat.data.llm.TemperatureMode
import isomeria.aichat.data.llm.TemperatureRule
import isomeria.aichat.data.llm.TemperatureSpec
import isomeria.aichat.data.llm.ThinkingMode
import isomeria.aichat.data.llm.ThinkingRule
import isomeria.aichat.ui.components.configurationIssueText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型缺什么、配置问题的原因、获取列表失败的原因，都要说具体。 */
class ModelStatusTest {
    private val config = ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = "openai", name = "接口", baseUrl = "https://a.example/v1", compatibility = "GENERIC", defaultModel = "")
    private val rules = ModelConfig(true, ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("low", "低"))), TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0f, 1f)))
    private fun model(value: ModelConfig?, image: Boolean? = false) =
        ProviderModel(1, "m", "m", imageInputUserMark = image, legacy = false, userConfig = value?.let(ModelRules::encode))

    @Test
    fun usableModelHasNoGaps() {
        assertEquals(emptyList<ModelGap>(), ModelRules.gaps(config, model(rules)))
    }

    @Test
    fun freshModelListsEverythingMissing() {
        assertEquals(listOf(ModelGap.CHAT, ModelGap.IMAGE, ModelGap.THINKING, ModelGap.TEMPERATURE), ModelRules.gaps(config, model(null, image = null)))
    }

    @Test
    fun onlyMissingItemsAreListed() {
        assertEquals(listOf(ModelGap.THINKING), ModelRules.gaps(config, model(rules.copy(thinking = null))))
        assertEquals(listOf(ModelGap.TEMPERATURE), ModelRules.gaps(config, model(rules.copy(temperature = null))))
        assertEquals(listOf(ModelGap.IMAGE), ModelRules.gaps(config, model(rules, image = null)))
    }

    @Test
    fun invalidTemperatureRangeCountsAsMissing() {
        val invalid = TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 1f, 0.5f))
        assertEquals(listOf(ModelGap.TEMPERATURE), ModelRules.gaps(config, model(rules.copy(temperature = invalid))))
    }

    @Test
    fun imageReportedByServiceIsNotMissing() {
        val reported = model(rules, image = null).copy(imageInput = Capability.YES)
        assertEquals(emptyList<ModelGap>(), ModelRules.gaps(config, reported))
    }

    @Test
    fun nonChatModelSaysSoInsteadOfListingFields() {
        assertEquals(listOf(ModelGap.NOT_CHAT), ModelRules.gaps(config, model(ModelConfig(chat = false))))
    }

    @Test
    fun completeRulesThatCannotBeEncodedAreReportedAsEncoding() {
        // 档位需要强度字段，智谱的兼容方案没有
        val zhipu = config.copy(compatibility = "ZHIPU")
        assertEquals(listOf(ModelGap.ENCODING), ModelRules.gaps(zhipu, model(rules)))
    }

    @Test
    fun gapsAreEmptyExactlyWhenModelIsUsable() {
        val zhipu = config.copy(compatibility = "ZHIPU")
        listOf(model(rules), model(null, image = null), model(ModelConfig(chat = false)), model(rules.copy(thinking = null))).forEach { m ->
            listOf(config, zhipu).forEach { c -> assertEquals(ModelRules.usable(c, m), ModelRules.gaps(c, m).isEmpty()) }
        }
    }

    @Test
    fun damagedRulesDoNotCrash() {
        val damaged = ProviderModel(1, "m", "m", legacy = false, userConfig = "{不是 JSON")
        assertTrue(ModelGap.CHAT in ModelRules.gaps(config, damaged))
    }

    @Test
    fun onlyRuleProblemsGoToModelPage() {
        assertTrue(ModelRules.fixedInModel(ConfigurationIssue.INCOMPLETE))
        assertTrue(ModelRules.fixedInModel(ConfigurationIssue.ENCODING))
        assertFalse(ModelRules.fixedInModel(ConfigurationIssue.MODEL))
        assertFalse(ModelRules.fixedInModel(ConfigurationIssue.THINKING))
        assertFalse(ModelRules.fixedInModel(ConfigurationIssue.TEMPERATURE))
    }

    @Test
    fun everyIssueHasItsOwnText() {
        val chat = ConfigurationIssue.entries.map { configurationIssueText(it) }
        val memory = ConfigurationIssue.entries.map { configurationIssueText(it, memory = true) }
        assertEquals(chat.size, chat.distinct().size)
        assertEquals(memory.size, memory.distinct().size)
    }

    @Test
    fun fetchFailureSaysTheActualReason() {
        assertEquals(R.string.fetch_failed_no_key, fetchFailureText(LlmException(LlmError.AUTH, message = "未填写 API Key")))
        assertEquals(R.string.fetch_failed_auth, fetchFailureText(LlmException.fromHttp(401)))
        assertEquals(R.string.fetch_failed_auth, fetchFailureText(LlmException.fromHttp(403)))
        assertEquals(R.string.fetch_failed_balance, fetchFailureText(LlmException.fromHttp(402)))
        assertEquals(R.string.fetch_failed_rate_limit, fetchFailureText(LlmException.fromHttp(429)))
        assertEquals(R.string.fetch_failed_bad_request, fetchFailureText(LlmException.fromHttp(400)))
        assertEquals(R.string.fetch_failed_not_found, fetchFailureText(LlmException.fromHttp(404)))
        assertEquals(R.string.fetch_failed_server, fetchFailureText(LlmException.fromHttp(500)))
        assertEquals(R.string.fetch_failed_network, fetchFailureText(LlmException(LlmError.NETWORK)))
        assertEquals(R.string.fetch_failed_unreadable, fetchFailureText(LlmException(LlmError.UNKNOWN, message = "无法解析模型列表")))
        assertEquals(R.string.llm_base_url_invalid, fetchFailureText(LlmException(LlmError.CONFIGURATION)))
    }
}
