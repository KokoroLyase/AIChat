package isomeria.aichat.feature.contactedit

import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.ModelConfig
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.llm.TemperatureMode
import isomeria.aichat.data.llm.TemperatureRule
import isomeria.aichat.data.llm.TemperatureSpec
import isomeria.aichat.data.llm.ThinkingMode
import isomeria.aichat.data.llm.ThinkingRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 冲突状态下：没换模型可以保存其他字段；新建或换模型时，选的模型必须配置好。 */
class ContactSaveRuleTest {
    private val pending = ProviderModel(1, "pending", "待配置", legacy = false)
    private val ready = ProviderModel(
        1, "ready", "已配置", imageInputUserMark = false, legacy = false,
        userConfig = ModelRules.encode(ModelConfig(true, ThinkingRule(ThinkingMode.NONE), TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE)))),
    )
    private val saved = ContactForm(name = "小雨", providerId = 1, model = "pending")

    @Test fun renamingWithPendingModelIsAllowed() =
        assertFalse(modelBlocksSave(saved.copy(name = "新名字", systemPrompt = "新提示词"), saved, pending))

    @Test fun modelThatNoLongerExistsDoesNotBlockOtherFields() =
        assertFalse(modelBlocksSave(saved.copy(name = "新名字"), saved, null))

    @Test fun existingContactWithoutModelCanStillSaveOtherFields() {
        val blank = saved.copy(model = "")
        assertFalse(modelBlocksSave(blank.copy(name = "新名字"), blank, null))
    }

    @Test fun switchingToPendingModelIsBlocked() =
        assertTrue(modelBlocksSave(saved.copy(model = "other"), saved.copy(model = "ready"), pending))

    @Test fun switchingProviderToOneWithoutModelIsBlocked() =
        assertTrue(modelBlocksSave(saved.copy(providerId = 2, model = ""), saved, null))

    @Test fun switchingProviderKeepingTheSameModelIdStillChecksIt() =
        assertTrue(modelBlocksSave(saved.copy(providerId = 2), saved, pending))

    @Test fun switchingToReadyModelIsAllowed() =
        assertFalse(modelBlocksSave(saved.copy(model = "ready"), saved, ready))

    @Test fun newContactMustPickAConfiguredModel() {
        val form = ContactForm(name = "新人", providerId = 1, model = "pending")
        assertTrue(modelBlocksSave(form, null, pending))
        assertTrue(modelBlocksSave(form.copy(model = ""), null, null))
        assertFalse(modelBlocksSave(form.copy(model = "ready"), null, ready))
    }
}
