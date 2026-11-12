package isomeria.aichat.data.llm

import isomeria.aichat.R

/** 品牌预置只填新表单，不参与已保存地址的运行时解析。 */
data class ProviderPreset(val id: String, val nameRes: Int, val baseUrl: String, val compatibility: Compatibility, val docs: String)
object ProviderPresets {
    val all = listOf(
        ProviderPreset("deepseek", R.string.preset_deepseek, "https://api.deepseek.com", Compatibility.DEEPSEEK, "https://api-docs.deepseek.com/"),
        ProviderPreset("qwen", R.string.preset_qwen, "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", Compatibility.QWEN, "https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions"),
        ProviderPreset("zhipu", R.string.preset_zhipu, "https://open.bigmodel.cn/api/paas/v4", Compatibility.ZHIPU, "https://docs.bigmodel.cn/api-reference/模型-api/对话补全"),
        ProviderPreset("kimi", R.string.preset_kimi, "https://api.moonshot.cn/v1", Compatibility.KIMI, "https://platform.kimi.com/docs/api/chat"),
        ProviderPreset("mimo", R.string.preset_mimo, "https://api.xiaomimimo.com/v1", Compatibility.MIMO, "https://mimo.mi.com/docs/zh-CN/api/chat/openai-api"),
        ProviderPreset("siliconflow", R.string.preset_siliconflow, "https://api.siliconflow.cn/v1", Compatibility.SILICONFLOW, "https://docs.siliconflow.cn/docs/api/chat-completions-post"),
        ProviderPreset("openrouter", R.string.preset_openrouter, "https://openrouter.ai/api/v1", Compatibility.OPENROUTER, "https://openrouter.ai/docs/guides/best-practices/reasoning-tokens"),
    )
}
