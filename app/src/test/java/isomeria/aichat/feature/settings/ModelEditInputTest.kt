package isomeria.aichat.feature.settings

import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.llm.NativeEffort
import isomeria.aichat.data.llm.ThinkingMode
import isomeria.aichat.data.llm.ThinkingRule
import isomeria.aichat.data.repo.ModelReference
import isomeria.aichat.data.repo.ReferencePurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型配置页和服务商页里不依赖界面的判断。 */
class ModelEditInputTest {
    @Test
    fun temperatureInputAcceptsOnlyCompleteNumbers() {
        assertEquals(0.7f, parseTemperatureInput("0.7"))
        assertEquals(1f, parseTemperatureInput("1"))
        assertEquals(0.5f, parseTemperatureInput(".5"))
        assertEquals(0.2f, parseTemperatureInput(" 0.2 "))
        // Float.toString 可能写成科学计数法，已保存的值不能被判成不合法
        assertEquals(0.0001f, parseTemperatureInput(0.0001f.toString()))
        for (bad in listOf("", " ", ".", "0.", "1.", "abc", "-1", "1f", "1d", "1e", "NaN", "Infinity", "1e99", "0,5")) {
            assertNull("「$bad」不应被接受", parseTemperatureInput(bad))
        }
    }

    private val rule = ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("low", "低")))

    @Test
    fun effortIssueExplainsEachRejection() {
        assertEquals(EffortIssue.VALUE_EMPTY, effortIssue(rule, " ", "高"))
        assertEquals(EffortIssue.VALUE_RESERVED, effortIssue(rule, "off", "关"))
        assertEquals(EffortIssue.VALUE_RESERVED, effortIssue(rule, "none", "无"))
        assertEquals(EffortIssue.VALUE_FORMAT, effortIssue(rule, "高", "高"))
        assertEquals(EffortIssue.VALUE_FORMAT, effortIssue(rule, "a".repeat(41), "长"))
        assertEquals(EffortIssue.VALUE_DUPLICATE, effortIssue(rule, "low", "又一个"))
        assertEquals(EffortIssue.LABEL_EMPTY, effortIssue(rule, "high", " "))
        assertNull(effortIssue(rule, " high ", " 高 "))
    }

    @Test
    fun effortIssueReportsBrokenExistingEfforts() {
        val broken = ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("low", "")))
        assertEquals(EffortIssue.EXISTING, effortIssue(broken, "high", "高"))
    }

    @Test
    fun referencesMergeByContactAndKeepOrder() {
        val refs = listOf(
            ModelReference(null, ReferencePurpose.DEFAULT),
            ModelReference("小雨", ReferencePurpose.CHAT),
            ModelReference("阿明", ReferencePurpose.MEMORY),
            ModelReference("小雨", ReferencePurpose.MEMORY),
            ModelReference("小雨", ReferencePurpose.CHAT),
        )
        assertEquals(
            listOf(
                null to listOf(ReferencePurpose.DEFAULT),
                "小雨" to listOf(ReferencePurpose.CHAT, ReferencePurpose.MEMORY),
                "阿明" to listOf(ReferencePurpose.MEMORY),
            ),
            groupReferences(refs),
        )
    }

    @Test
    fun modelDirtyComparesWithLastSavedContent() {
        val model = ProviderModel(1, "m", "m")
        assertFalse(ModelEditState(model, original = model).dirty)
        assertTrue(ModelEditState(model.copy(imageInputUserMark = true), original = model).dirty)
        // 还没读取完时不算修改
        assertFalse(ModelEditState(model).dirty)
    }

    @Test
    fun providerDirtyCoversEveryUnsavedInput() {
        val saved = ProviderSavedForm("名称", "https://a.example/v1", "GENERIC", "deepseek")
        val state = ProviderEditState(loaded = true, name = "名称", baseUrl = "https://a.example/v1", presetId = "deepseek", savedForm = saved)
        assertFalse(state.dirty)
        assertTrue(state.copy(name = "新名称").dirty)
        assertTrue(state.copy(baseUrl = "https://b.example/v1").dirty)
        assertTrue(state.copy(compatibility = "DEEPSEEK").dirty)
        assertTrue(state.copy(presetId = null).dirty)
        assertTrue(state.copy(keyInput = "123").dirty)
        assertTrue(state.copy(modelInput = "m").dirty)
        assertFalse(state.copy(loaded = false, name = "新名称").dirty)
    }

    @Test
    fun untouchedNewProviderIsNotDirty() {
        assertFalse(ProviderEditState(loaded = true).dirty)
    }
}
