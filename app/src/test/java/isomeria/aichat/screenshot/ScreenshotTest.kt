package isomeria.aichat.screenshot

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import isomeria.aichat.feature.chat.ChatPageSample
import isomeria.aichat.feature.chatinfo.ChatInfoContent
import isomeria.aichat.feature.chatinfo.previewChatInfo
import isomeria.aichat.feature.chatlist.ChatListContent
import isomeria.aichat.feature.chatlist.previewItems
import isomeria.aichat.feature.contactedit.ContactDetailContent
import isomeria.aichat.feature.contactedit.previewState
import isomeria.aichat.feature.contacts.ContactsContent
import isomeria.aichat.feature.contacts.previewSections
import isomeria.aichat.feature.me.MeContent
import isomeria.aichat.feature.memory.MemoryContent
import isomeria.aichat.feature.memory.previewMemoryState
import isomeria.aichat.feature.settings.*
import isomeria.aichat.data.llm.*
import isomeria.aichat.data.db.*
import isomeria.aichat.data.memory.MemoryTaskState
import isomeria.aichat.data.repo.*
import isomeria.aichat.ui.components.ModelParameterControls
import isomeria.aichat.ui.components.ThinkingSelector
import isomeria.aichat.feature.settings.ProviderListContent
import isomeria.aichat.feature.usage.UsageContent
import isomeria.aichat.feature.usage.previewUsageState
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.ui.components.UpdateFailedDialog
import isomeria.aichat.ui.components.UpdateProgressDialog
import isomeria.aichat.update.UpdateFailure
import isomeria.aichat.update.UpdateInstallState
import isomeria.aichat.ui.components.EmojiCatalog
import isomeria.aichat.ui.components.EmojiPanel
import isomeria.aichat.ui.components.TabItem
import isomeria.aichat.ui.components.UpdateDialog
import isomeria.aichat.ui.components.WeBottomBar
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.WeTheme
import isomeria.aichat.update.ReleaseInfo
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 在 JVM 上渲染主要页面，浅色和深色各一张，输出到 app/screenshots/，用来和微信截图并排对比。
 *
 * 生成截图：./gradlew recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            AIChatTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) { content() }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
    }

    @Composable
    private fun WithTabs(selected: Int, unread: Int = 0, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { content() }
            WeBottomBar(
                items = listOf(
                    TabItem("智信", Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble, unread),
                    TabItem("通讯录", Icons.Outlined.Contacts, Icons.Filled.Contacts),
                    TabItem("我", Icons.Outlined.Person, Icons.Filled.Person),
                ),
                selectedIndex = selected,
                onSelect = {},
            )
        }
    }

    @Composable
    private fun ChatList() = WithTabs(0, unread = 2) {
        ChatListContent(previewItems, 2, {}, {}, {}, {}, {}, {}, {})
    }

    @Composable
    private fun Contacts() = WithTabs(1) { ContactsContent(previewSections, {}, {}, {}) }

    @Composable
    private fun Me() = WithTabs(2) { MeContent("Isomeria", null, {}, {}) }

    @Test fun chatListLight() = shot("1-chatlist", false) { ChatList() }

    @Test fun chatListDark() = shot("1-chatlist", true) { ChatList() }

    @Test fun chatLight() = shot("2-chat", false) { ChatPageSample() }

    @Test fun chatDark() = shot("2-chat", true) { ChatPageSample(input = "好的") }

    @Test fun chatTypingLight() = shot("2-chat-typing", false) { ChatPageSample(typing = true) }

    @Test fun contactsLight() = shot("3-contacts", false) { Contacts() }

    @Test fun contactsDark() = shot("3-contacts", true) { Contacts() }

    @Test fun meLight() = shot("4-me", false) { Me() }

    @Test fun meDark() = shot("4-me", true) { Me() }

    @Test fun contactDetailLight() = shot("5-contact-detail", false) { ContactDetailContent(previewState, {}, {}, {}, {}) }

    @Test fun contactDetailDark() = shot("5-contact-detail", true) { ContactDetailContent(previewState, {}, {}, {}, {}) }

    /** 对话框是单独的窗口，截整个屏幕 */
    @Test fun dialogLight() {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ChatList()
                WeDialog(
                    message = "删除后，将清空该聊天的消息记录，联系人会保留。",
                    onDismiss = {},
                    onConfirm = {},
                    confirmText = "删除",
                    destructive = true,
                )
            }
        }
        captureScreenRoboImage("screenshots/6-dialog-light.png")
    }

    @Test fun chatInfoLight() = shot("7-chat-info", false) { ChatInfoContent(previewChatInfo, {}, {}, {}, {}, {}) }

    @Test fun chatInfoDark() = shot("7-chat-info", true) { ChatInfoContent(previewChatInfo, {}, {}, {}, {}, {}) }

    private val emojiGroups = File("src/main/assets/emoji.txt").bufferedReader().useLines { EmojiCatalog.parse(it) { true } }

    @Composable
    private fun Emoji() = Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f))
        EmojiPanel(emojiGroups, listOf("😂", "👍", "🌹"), canSend = true, canDelete = true, {}, {}, {}, Modifier.height(320.dp))
    }

    @Test fun emojiLight() = shot("8-emoji", false) { Emoji() }

    @Test fun emojiDark() = shot("8-emoji", true) { Emoji() }

    private val release = ReleaseInfo(
        version = "0.2.0",
        tag = "v0.2.0",
        notes = "智信 0.2.0：数据与更新。\n\n• 导出数据和恢复数据\n• 检查更新\n• 聊天信息、搜索、表情面板、草稿",
        pageUrl = "https://github.com/KokoroLyase/AIChat/releases/tag/v0.2.0",
        apkUrl = null,
    )

    @Test fun updateDialogLight() = dialogShot("9-update", false)

    @Test fun updateDialogDark() = dialogShot("9-update", true)

    private val providers = listOf(
        ProviderConfig(1, ProviderKind.LLM, "deepseek", "DeepSeek", "https://api.deepseek.com", "enc", "deepseek-flash"),
        ProviderConfig(2, ProviderKind.LLM, "openai", "OpenRouter", "https://openrouter.ai/api/v1", null, ""),
    )

    @Test fun providerListLight() = shot("10-providers", false) { ProviderListContent(providers, {}, {}, {}, mapOf(providers.first().id to 2)) }

    @Test fun providerListDark() = shot("10-providers", true) { ProviderListContent(providers, {}, {}, {}, mapOf(providers.first().id to 2)) }

    @Test fun providerEditLight() = shot("11-provider-edit", false) { ProviderEditSample() }

    @Test fun providerEditDark() = shot("11-provider-edit", true) { ProviderEditSample() }

    @Test fun memoryLight() = shot("12-memory", false) { MemoryContent(previewMemoryState, {}, {}, {}, {}) }

    @Test fun memoryDark() = shot("12-memory", true) { MemoryContent(previewMemoryState, {}, {}, {}, {}) }

    @Test fun usageLight() = shot("13-usage", false) { UsageContent(previewUsageState, {}, {}, {}, {}) }

    @Test fun usageDark() = shot("13-usage", true) { UsageContent(previewUsageState, {}, {}, {}, {}) }

    private fun updateShot(name: String, dark: Boolean, dialog: @Composable () -> Unit) {
        compose.setContent {
            AIChatTheme(darkTheme = dark) {
                Me()
                dialog()
            }
        }
        captureScreenRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
    }

    private val downloading = UpdateInstallState.Downloading(release, 12_000_000, 31_000_000)

    @Test fun updateProgressLight() = updateShot("14-update-progress", false) { UpdateProgressDialog(downloading, {}) }

    @Test fun updateProgressDark() = updateShot("14-update-progress", true) { UpdateProgressDialog(downloading, {}) }

    @Test fun updateFailedLight() = updateShot("15-update-failed", false) { UpdateFailedDialog(UpdateFailure.CHECKSUM, {}, {}) }

    @Test fun updateFailedDark() = updateShot("15-update-failed", true) { UpdateFailedDialog(UpdateFailure.CHECKSUM, {}, {}) }

    private val configuredModel = ProviderModel(1, "example-chat", "示例模型", imageInputUserMark = false, legacy = false,
        userConfig = ModelRules.encode(ModelConfig(true, ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("low", "低"), NativeEffort("medium", "中"), NativeEffort("high", "高"), NativeEffort("max", "最高"))),
            TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0.2f, 1.5f), TemperatureSpec(TemperatureMode.FIXED, fixed = 1f)))))
    private val modelProvider = providers.first().copy(compatibility = "DEEPSEEK", presetId = "deepseek")
    @Test fun modelConfigureLight() = shot("16-model-configure", false) { ModelEditContent(ModelEditState(configuredModel, modelProvider), {}, {}, {}) }
    @Test fun modelConfigureDark() = shot("16-model-configure", true) { ModelEditContent(ModelEditState(configuredModel, modelProvider), {}, {}, {}) }
    @Test fun modelPendingLight() = shot("17-model-pending", false) { ModelEditContent(ModelEditState(configuredModel.copy(userConfig = null, imageInputUserMark = null), modelProvider), {}, {}, {}) }
    @Test fun modelPendingDark() = shot("17-model-pending", true) { ModelEditContent(ModelEditState(configuredModel.copy(userConfig = null, imageInputUserMark = null), modelProvider), {}, {}, {}) }
    @Composable private fun ParameterSample() {
        Column {
            ModelParameterControls(modelProvider, configuredModel, "low", 1.3f, {}, {})
            ModelParameterControls(modelProvider, configuredModel, "off", 1.8f, {}, {})
            ModelParameterControls(modelProvider, configuredModel.copy(userConfig = ModelRules.encode(ModelRules.effective(configuredModel).copy(temperature = TemperatureRule(TemperatureSpec(TemperatureMode.UNSUPPORTED))))), "off", 1.3f, {}, {})
            ModelParameterControls(modelProvider, configuredModel.copy(userConfig = ModelRules.encode(ModelRules.effective(configuredModel).copy(temperature = TemperatureRule(TemperatureSpec(TemperatureMode.FIXED, fixed = 1f))))), "off", 1.3f, {}, {})
        }
    }
    @Test fun parametersLight() = shot("18-model-parameters", false) { ParameterSample() }
    @Test fun parametersDark() = shot("18-model-parameters", true) { ParameterSample() }
    private val memorySettings = previewMemoryState.copy(contact = Contact(id = 1, name = "小雨", avatarColor = 0, providerId = 1, model = "example-chat", memoryModel = "example-chat", memoryThinkingOption = "low", createdAt = 1, updatedAt = 1),
        provider = modelProvider, models = listOf(configuredModel), task = MemoryTaskState(queued = 1, generating = true, lastSuccess = 1793592000000))
    @Test fun memorySettingsLight() = shot("19-memory-settings", false) { MemoryContent(memorySettings, {}, {}, {}, {}) }
    @Test fun memorySettingsDark() = shot("19-memory-settings", true) { MemoryContent(memorySettings, {}, {}, {}, {}) }
    @Test fun memoryConflictLight() = shot("20-memory-conflict", false) { MemoryContent(memorySettings.copy(contact = memorySettings.contact!!.copy(memoryThinkingOption = "retired"), task = MemoryTaskState(lastFailure = 1793592000000, failure = FailReason.CONFIGURATION)), {}, {}, {}, {}) }
    @Test fun memoryConflictDark() = shot("20-memory-conflict", true) { MemoryContent(memorySettings.copy(contact = memorySettings.contact!!.copy(memoryThinkingOption = "retired"), task = MemoryTaskState(lastFailure = 1793592000000, failure = FailReason.CONFIGURATION)), {}, {}, {}, {}) }
    @Test fun conflictDialogLight() = updateShot("21-config-conflict", false) { ConflictDialog(listOf(ModelReference("小雨", ReferencePurpose.CHAT), ModelReference("小雨", ReferencePurpose.MEMORY)), {}) {} }
    @Test fun conflictDialogDark() = updateShot("21-config-conflict", true) { ConflictDialog(listOf(ModelReference("小雨", ReferencePurpose.CHAT), ModelReference("小雨", ReferencePurpose.MEMORY)), {}) {} }

    private val existingConflicts = listOf(ModelReference("阿明", ReferencePurpose.MEMORY, 2))
    @Test fun conflictBothKindsLight() = updateShot("37-config-conflict-both", false) { ConflictDialog(listOf(ModelReference("小雨", ReferencePurpose.CHAT, 1)), {}, existingConflicts) {} }
    @Test fun conflictBothKindsDark() = updateShot("37-config-conflict-both", true) { ConflictDialog(listOf(ModelReference("小雨", ReferencePurpose.CHAT, 1)), {}, existingConflicts) {} }

    private val manyConflicts = (1..30).flatMap { listOf(ModelReference("联系人$it", ReferencePurpose.CHAT), ModelReference("联系人$it", ReferencePurpose.MEMORY)) }
    @Test fun conflictLongLight() = updateShot("23-config-conflict-long", false) { ConflictDialog(manyConflicts, {}) {} }
    @Test fun conflictLongDark() = updateShot("23-config-conflict-long", true) { ConflictDialog(manyConflicts, {}) {} }
    private val customUrlError = ProviderEditState(loaded = true, name = "我的接口", messageRes = isomeria.aichat.R.string.llm_base_url_invalid, messageIsError = true)
    private val presetProvider = previewProviderState.copy(presetId = "deepseek", name = "DeepSeek", baseUrl = "https://api.deepseek.com/v1")
    @Test fun providerUrlErrorLight() = shot("24-provider-url-error", false) { ProviderEditSample(customUrlError) }
    @Test fun providerUrlErrorDark() = shot("24-provider-url-error", true) { ProviderEditSample(customUrlError) }
    @Test fun providerPresetLight() = shot("25-provider-preset", false) { ProviderEditSample(presetProvider) }
    @Test fun providerPresetDark() = shot("25-provider-preset", true) { ProviderEditSample(presetProvider) }

    private val issueDetail = isomeria.aichat.feature.contactedit.previewState.copy(chatIssue = isomeria.aichat.data.llm.ConfigurationIssue.TEMPERATURE, memoryIssue = isomeria.aichat.data.llm.ConfigurationIssue.THINKING)
    @Test fun contactIssuesLight() = shot("26-contact-issues", false) { ContactDetailContent(issueDetail, {}, {}, {}, {}) }
    @Test fun contactIssuesDark() = shot("26-contact-issues", true) { ContactDetailContent(issueDetail, {}, {}, {}, {}) }
    @Test fun contactIssuesLargeFontLight() = shot("26-contact-issues-large", false) {
        AIChatTheme(darkTheme = false, fontScale = 2.5f) { ContactDetailContent(issueDetail, {}, {}, {}, {}) }
    }
    private val missingMemoryModel = memorySettings.copy(contact = memorySettings.contact!!.copy(memoryModel = "gone"), task = MemoryTaskState())
    @Test fun memoryMissingLight() = shot("27-memory-model-missing", false) { MemoryContent(missingMemoryModel, {}, {}, {}, {}) }
    @Test fun memoryMissingDark() = shot("27-memory-model-missing", true) { MemoryContent(missingMemoryModel, {}, {}, {}, {}) }
    @Test fun memoryRunningLight() = shot("28-memory-running", false) { MemoryContent(memorySettings.copy(rows = emptyList(), backlog = 128, task = memorySettings.task.copy(manual = true)), {}, {}, {}, {}) }
    @Test fun memoryRunningDark() = shot("28-memory-running", true) { MemoryContent(memorySettings.copy(rows = emptyList(), backlog = 128, task = memorySettings.task.copy(manual = true)), {}, {}, {}, {}) }
    private val fetchFailed = previewProviderState.copy(messageRes = isomeria.aichat.R.string.fetch_failed_not_found, messageIsError = true)
    @Test fun fetchFailedLight() = shot("29-provider-fetch-failed", false) { ProviderEditSample(fetchFailed) }
    @Test fun fetchFailedDark() = shot("29-provider-fetch-failed", true) { ProviderEditSample(fetchFailed) }

    private val longModelList = listOf(configuredModel, configuredModel.copy(modelId = "same-name", displayName = "same-name")) +
        (1..30).map { ProviderModel(2, "vendor/candidate-model-$it", "vendor/candidate-model-$it", legacy = false) } +
        ProviderModel(2, "gone-model", "下架的模型", legacy = false, missingFromList = true)
    private val modelListState = previewProviderState.copy(savedForm = ProviderSavedForm("OpenRouter", "https://openrouter.ai/api/v1", "GENERIC"))
    private fun scrolledTo(text: String) = compose.onNodeWithText(text).performScrollTo()
    private fun modelListShot(name: String, dark: Boolean, expandPending: Boolean = false) {
        compose.setContent {
            AIChatTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) { ProviderEditSample(modelListState, longModelList) }
            }
        }
        val pending = compose.onNodeWithText("待配置（31）").performScrollTo()
        if (expandPending) {
            pending.performClick()
            compose.onNodeWithText("vendor/candidate-model-4", substring = true).performScrollTo()
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
    }
    @Test fun modelListLongLight() = modelListShot("30-model-list-long", false)
    @Test fun modelListLongDark() = modelListShot("30-model-list-long", true)
    @Test fun modelListExpandedLight() = modelListShot("31-model-list-expanded", false, expandPending = true)
    @Test fun modelListExpandedDark() = modelListShot("31-model-list-expanded", true, expandPending = true)

    private fun advancedShot(dark: Boolean) {
        compose.setContent {
            AIChatTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) { ProviderEditSample(modelListState.copy(presetId = "openrouter", compatibility = "OPENROUTER")) }
            }
        }
        scrolledTo("高级连接设置").performClick()
        compose.onNodeWithText("兼容方案", substring = true).performClick()
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/32-compat-sheet-${if (dark) "dark" else "light"}.png")
    }
    @Test fun compatSheetLight() = advancedShot(false)
    @Test fun compatSheetDark() = advancedShot(true)

    private fun modelAdvancedShot(dark: Boolean) {
        compose.setContent {
            AIChatTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) { ModelEditContent(ModelEditState(configuredModel, modelProvider), {}, {}, {}) }
            }
        }
        scrolledTo("模型级高级设置").performClick()
        scrolledTo("恢复继承")
        compose.onRoot().captureRoboImage("screenshots/33-model-advanced-${if (dark) "dark" else "light"}.png")
    }
    @Test fun modelAdvancedLight() = modelAdvancedShot(false)
    @Test fun modelAdvancedDark() = modelAdvancedShot(true)

    private fun largeDialogShot(dark: Boolean) {
        compose.setContent {
            AIChatTheme(darkTheme = dark, fontScale = 1.6f) {
                Me()
                ConflictDialog(listOf(ModelReference("小雨", ReferencePurpose.CHAT), ModelReference("小雨", ReferencePurpose.MEMORY)), {}) {}
            }
        }
        captureScreenRoboImage("screenshots/34-large-dialog-${if (dark) "dark" else "light"}.png")
    }
    @Test fun largeDialogLight() = largeDialogShot(false)
    @Test fun largeDialogDark() = largeDialogShot(true)

    private fun largeSheetShot(dark: Boolean) {
        compose.setContent {
            AIChatTheme(darkTheme = dark, fontScale = 1.6f) {
                Me()
                isomeria.aichat.ui.components.WeActionSheet(
                    title = "选择模型",
                    options = listOf(
                        isomeria.aichat.ui.components.SheetOption("示例模型", selected = true, subtitle = "example-chat") {},
                        isomeria.aichat.ui.components.SheetOption("另一个模型", subtitle = "another-chat") {},
                    ),
                    onDismiss = {},
                )
            }
        }
        captureScreenRoboImage("screenshots/35-large-sheet-${if (dark) "dark" else "light"}.png")
    }
    @Test fun largeSheetLight() = largeSheetShot(false)
    @Test fun largeSheetDark() = largeSheetShot(true)

    @Composable private fun LargeFormSample() {
        Column {
            isomeria.aichat.ui.components.FormRow("最低温度", showDivider = true) { androidx.compose.material3.Text("0.2", color = WeTheme.colors.textPrimary, style = WeTheme.typography.listTitle) }
            isomeria.aichat.ui.components.FormRow("API Key") { androidx.compose.material3.Text("••••••", color = WeTheme.colors.textSecondary, style = WeTheme.typography.listTitle) }
            ConfigActionRow("默认模型", "示例模型", showDivider = true) {}
            ConfigActionRow("思考时使用不同规则", "单独设置思考温度规则", "用户设置") {}
        }
    }
    @Test fun largeFormLight() = shot("36-large-form", false) { AIChatTheme(darkTheme = false, fontScale = 2f) { LargeFormSample() } }
    @Test fun largeFormDark() = shot("36-large-form", true) { AIChatTheme(darkTheme = true, fontScale = 2f) { LargeFormSample() } }

    @Test fun longConfigurationLargeFontLight() = shot("22-large-config", false) {
        AIChatTheme(darkTheme = false, fontScale = 2.5f) {
            Column {
                ConfigActionRow("思考时使用不同规则", "单独设置思考温度规则") {}
                ConfigActionRow("模拟联系人使用的长名称模型与完整配置状态", "待配置：用途、思考与温度待确认") {}
                ThinkingSelector(configuredModel, "retired", "记忆思考强度") {}
            }
        }
    }
    @Test fun longConfigurationLargeFontDark() = shot("22-large-config", true) {
        AIChatTheme(darkTheme = true, fontScale = 2.5f) {
            Column {
                ConfigActionRow("思考时使用不同规则", "单独设置思考温度规则") {}
                ConfigActionRow("模拟联系人使用的长名称模型与完整配置状态", "待配置：用途、思考与温度待确认") {}
                ThinkingSelector(configuredModel, "retired", "记忆思考强度") {}
            }
        }
    }

    private fun dialogShot(name: String, dark: Boolean) {
        compose.setContent {
            AIChatTheme(darkTheme = dark) {
                Me()
                UpdateDialog(release, {}, {}, {})
            }
        }
        captureScreenRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
    }
}
