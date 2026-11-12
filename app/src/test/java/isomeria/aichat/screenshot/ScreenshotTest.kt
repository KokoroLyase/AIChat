package isomeria.aichat.screenshot

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import isomeria.aichat.feature.contactedit.ContactPromptContent
import isomeria.aichat.feature.contactedit.PromptRow
import isomeria.aichat.feature.contactedit.previewState
import isomeria.aichat.feature.contacts.ContactsContent
import isomeria.aichat.feature.contacts.previewSections
import isomeria.aichat.feature.me.MeContent
import isomeria.aichat.feature.memory.MemoryContent
import isomeria.aichat.feature.memory.previewMemoryState
import isomeria.aichat.feature.settings.*
import isomeria.aichat.feature.backup.*
import isomeria.aichat.data.backup.*
import isomeria.aichat.data.llm.*
import isomeria.aichat.data.db.*
import isomeria.aichat.data.memory.MemoryTaskState
import isomeria.aichat.data.repo.*
import isomeria.aichat.ui.components.GroupSpacer
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
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.WeTheme
import isomeria.aichat.update.ReleaseInfo
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
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

    /** App 内特大（1.25）叠加 JVM 大字体（2.0）：手机系统字体不改，极端情况用这里看 */
    private fun large(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            AIChatTheme(darkTheme = dark, fontScale = 2.5f) {
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

    private val crashNames = listOf(
        "1793664000000-00000000-0000-0000-0000-000000000001.txt",
        "1793577600000-00000000-0000-0000-0000-000000000002.txt",
        "1793491200000-00000000-0000-0000-0000-000000000003.txt",
    )
    private val crashDetail = "time=1793664000000\napp=0.4.0-perf (400)\ndevice=Fixture Phone\nandroid=16 (36)\nkind=JAVA\njava.lang.IllegalStateException\n" +
        (1..24).joinToString("\n") { "  at isomeria.aichat.feature.chat.VeryLongFixtureClassNameForWrapping\$\$ExternalSyntheticLambda$it.invoke(ChatViewModel.kt:${100 + it})" }
    @Test fun crashLogsLight() = shot("40-crash-logs", false) { CrashLogsContent(CrashLogsState(loading = false, names = crashNames), {}, {}, {}, {}, {}) }
    @Test fun crashLogsDark() = shot("40-crash-logs", true) { CrashLogsContent(CrashLogsState(loading = false, names = crashNames), {}, {}, {}, {}, {}) }
    @Test fun crashLogsEmptyLight() = shot("40-crash-empty", false) { CrashLogsContent(CrashLogsState(loading = false), {}, {}, {}, {}, {}) }
    @Test fun crashLogsEmptyDark() = shot("40-crash-empty", true) { CrashLogsContent(CrashLogsState(loading = false), {}, {}, {}, {}, {}) }
    @Test fun crashLogsDetailLongDark() = shot("40-crash-detail-long", true) { CrashLogsContent(CrashLogsState(loading = false, selected = "fixture", detail = crashDetail), {}, {}, {}, {}, {}) }
    @Test fun crashLogsDetailLongLight() = shot("40-crash-detail-long", false) { CrashLogsContent(CrashLogsState(loading = false, selected = "fixture", detail = crashDetail), {}, {}, {}, {}, {}) }
    @Test fun crashLogsFailureLight() = shot("40-crash-failure", false) { CrashLogsContent(CrashLogsState(loading = false, failed = true), {}, {}, {}, {}, {}) }
    @Test fun crashLogsLoadingDark() = shot("40-crash-loading", true) { CrashLogsContent(CrashLogsState(), {}, {}, {}, {}, {}) }
    @Test fun crashLogsCompletedLight() = shot("40-crash-completed", false) { CrashLogsContent(CrashLogsState(loading = false, completed = true), {}, {}, {}, {}, {}) }
    @Test fun crashLogsLargeLight() = large("40-crash-large", false) { CrashLogsContent(CrashLogsState(loading = false, names = crashNames), {}, {}, {}, {}, {}) }
    @Test fun crashLogsLargeDark() = large("40-crash-detail-large", true) { CrashLogsContent(CrashLogsState(loading = false, selected = "fixture", detail = crashDetail), {}, {}, {}, {}, {}) }
    @Test fun crashLogsDetailLargeLight() = large("40-crash-detail-large", false) { CrashLogsContent(CrashLogsState(loading = false, selected = "fixture", detail = crashDetail), {}, {}, {}, {}, {}) }
    @Test fun crashClearConfirm() {
        compose.setContent { AIChatTheme(darkTheme = false) { CrashLogsContent(CrashLogsState(loading = false, names = crashNames), {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("清空崩溃日志").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/40-crash-clear-confirm-light.png")
    }
    @Test fun crashLogsLoading() = shot("40-crash-loading", false) { CrashLogsContent(CrashLogsState(), {}, {}, {}, {}, {}) }
    @Test fun crashLogsFailure() = shot("40-crash-failure", true) { CrashLogsContent(CrashLogsState(loading = false, failed = true), {}, {}, {}, {}, {}) }
    @Test fun crashLogsDetail() = shot("40-crash-detail", false) { CrashLogsContent(CrashLogsState(loading = false, selected = "fixture", detail = "java.lang.IllegalStateException\n at fixture.App.run(App.kt:42)"), {}, {}, {}, {}, {}) }
    @Test fun crashLogsCompleted() = shot("40-crash-completed", true) { CrashLogsContent(CrashLogsState(loading = false, completed = true), {}, {}, {}, {}, {}) }
    @Composable private fun ExportSample(state: ExportUiState) = ExportContent(state, {}, {}, {}, {}, {}, {})
    @Composable private fun RestoreSample(state: RestoreUiState) = RestoreContent(state, {}, {}, {}, {}, {}, {}, {})
    private fun backupDialogShot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { AIChatTheme(darkTheme = dark, content = content) }
        captureScreenRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
    }
    @Test fun exportPlainLight() = shot("43-export-plain", false) { ExportSample(ExportUiState()) }
    @Test fun exportProtectedDark() = shot("43-export-protected", true) { ExportSample(ExportUiState(password = "123456", confirm = "123456")) }
    @Test fun exportIncludesKeysLight() = shot("43-export-keys", false) { ExportSample(ExportUiState(hasApiKey = true, includeKey = true, password = "123456", confirm = "123456")) }
    @Test fun exportMismatchDark() = shot("43-export-mismatch", true) { ExportSample(ExportUiState(password = "123456", confirm = "654321", errorRes = isomeria.aichat.R.string.export_password_mismatch)) }
    @Test fun exportRunning() = backupDialogShot("43-export-running", false) { ExportSample(ExportUiState(running = true)) }
    @Test fun exportCompleted() = backupDialogShot("43-export-completed", true) { ExportSample(ExportUiState(result = ExportSummary(2, 1000, 2, false, encrypted = true))) }
    @Test fun exportFailed() = backupDialogShot("43-export-failed", false) { ExportSample(ExportUiState(failed = true)) }
    @Test fun restoreLockedLight() = shot("44-restore-locked", false) { RestoreSample(RestoreUiState(locked = true)) }
    @Test fun restoreLockedDark() = shot("44-restore-locked", true) { RestoreSample(RestoreUiState(locked = true, password = "123456")) }
    @Test fun restoreWrongPassword() = shot("44-restore-password-failed", false) { RestoreSample(RestoreUiState(locked = true, password = "123456", wrongPassword = true)) }
    @Test fun restoreReading() = backupDialogShot("44-restore-reading", true) { RestoreSample(RestoreUiState(reading = true, locked = true)) }
    @Test fun restoreRunning() = backupDialogShot("44-restore-running", false) { RestoreSample(RestoreUiState(restoring = true)) }
    @Test fun restoreCompleted() = backupDialogShot("44-restore-completed", true) { RestoreSample(RestoreUiState(done = RestoreSummary(2, 1000, true))) }
    @Test fun restoreCleanupPending() = backupDialogShot("44-restore-cleanup-pending", false) { RestoreSample(RestoreUiState(done = RestoreSummary(2, 1000, true, cleanupPending = true))) }
    @Test fun restoreInvalidFile() = backupDialogShot("44-restore-invalid", true) { RestoreSample(RestoreUiState(error = RestoreError.Invalid)) }
    private fun sampleBackupPreview(decrypted: Boolean, keyEncrypted: Boolean) = BackupPreview(
        java.io.File("fixture.zip"),
        BackupManifest(appVersion = "0.4.0", appVersionCode = 400, formatVersion = 3, exportedAt = 1793664000000, includesApiKey = keyEncrypted, contactCount = 2, messageCount = 1000, imageCount = 2),
        BackupData(providers = if (keyEncrypted) listOf(ProviderDto(1, ProviderKind.LLM, "deepseek", baseUrl = "https://example.invalid", defaultModel = "fixture", apiKey = SecretDto(SecretDto.SCHEME_PASSWORD, data = "fixture"))) else emptyList()),
        decryptedTemporary = decrypted,
    )
    // 解锁后的加密文件不再问第二次密码
    @Test fun restoreUnlockedPreviewLight() = shot("44-restore-unlocked", false) { RestoreSample(RestoreUiState(preview = sampleBackupPreview(decrypted = true, keyEncrypted = true), password = "123456")) }
    @Test fun restoreUnlockedPreviewDark() = shot("44-restore-unlocked", true) { RestoreSample(RestoreUiState(preview = sampleBackupPreview(decrypted = true, keyEncrypted = true), password = "123456")) }
    // 只加密 Key 的旧备份仍然在预览页单独输密码
    @Test fun restoreLegacyPreviewLight() = shot("44-restore-legacy-key", false) { RestoreSample(RestoreUiState(preview = sampleBackupPreview(decrypted = false, keyEncrypted = true), wrongPassword = true)) }
    @Test fun restoreLegacyPreviewDark() = shot("44-restore-legacy-key", true) { RestoreSample(RestoreUiState(preview = sampleBackupPreview(decrypted = false, keyEncrypted = true))) }
    @Test fun restoreWrongPasswordDark() = shot("44-restore-password-failed", true) { RestoreSample(RestoreUiState(locked = true, password = "123456", wrongPassword = true)) }
    @Test fun restorePickLight() = shot("44-restore-pick", false) { RestoreSample(RestoreUiState()) }
    @Test fun restorePickDark() = shot("44-restore-pick", true) { RestoreSample(RestoreUiState()) }
    @Test fun restoreLockedLargeLight() = large("44-restore-locked-large", false) { RestoreSample(RestoreUiState(locked = true, password = "123456", wrongPassword = true)) }
    @Test fun restoreLockedLargeDark() = large("44-restore-locked-large", true) { RestoreSample(RestoreUiState(locked = true)) }
    @Test fun restoreCleanupPendingDark() = backupDialogShot("44-restore-cleanup-pending", true) { RestoreSample(RestoreUiState(done = RestoreSummary(2, 1000, true, cleanupPending = true))) }
    @Test fun exportPlainDark() = shot("43-export-plain", true) { ExportSample(ExportUiState()) }
    @Test fun exportProtectedLight() = shot("43-export-protected", false) { ExportSample(ExportUiState(password = "123456", confirm = "123456")) }
    @Test fun exportIncludesKeysDark() = shot("43-export-keys", true) { ExportSample(ExportUiState(hasApiKey = true, includeKey = true, password = "123456", confirm = "123456")) }
    @Test fun exportMismatchLight() = shot("43-export-mismatch", false) { ExportSample(ExportUiState(password = "123456", confirm = "654321", errorRes = isomeria.aichat.R.string.export_password_mismatch)) }
    @Test fun exportPlainCompletedLight() = backupDialogShot("43-export-completed-plain", false) { ExportSample(ExportUiState(result = ExportSummary(2, 1000, 2, false, encrypted = false))) }
    @Test fun exportLargeLight() = large("43-export-large", false) { ExportSample(ExportUiState(hasApiKey = true, includeKey = true, password = "123", confirm = "", errorRes = isomeria.aichat.R.string.export_password_mismatch)) }
    @Test fun exportLargeDark() = large("43-export-large", true) { ExportSample(ExportUiState()) }
    private val storageSample = StorageState(loading = false, usage = StorageUsage(12000000, 200000, 12345, 1200000, 3456789, backgrounds = 860000, voice = 2400000, asrModels = 239549735))
    @Test fun storageLight() = shot("42-storage", false) { StorageContent(storageSample, {}, {}, {}, {}, {}, {}) }
    @Test fun storageDark() = shot("42-storage", true) { StorageContent(storageSample, {}, {}, {}, {}, {}, {}) }
    @Test fun storageLoading() = shot("42-storage-loading", false) { StorageContent(StorageState(), {}, {}, {}, {}, {}, {}) }
    @Test fun storageFailed() = shot("42-storage-failed", true) { StorageContent(storageSample.copy(error = StorageError.FAILED), {}, {}, {}, {}, {}, {}) }
    @Test fun storageBusy() = shot("42-storage-busy", false) { StorageContent(storageSample.copy(error = StorageError.BUSY), {}, {}, {}, {}, {}, {}) }
    @Test fun storageEmpty() = shot("42-storage-empty", true) { StorageContent(storageSample.copy(orphans = OrphanPreview(emptyList())), {}, {}, {}, {}, {}, {}) }
    @Test fun storageCompleted() = shot("42-storage-completed", false) { StorageContent(storageSample.copy(result = CleanupResult(3, 65536)), {}, {}, {}, {}, {}, {}) }
    @Test fun storageWorking() {
        compose.setContent { AIChatTheme(darkTheme = false) { StorageContent(storageSample.copy(working = true), {}, {}, {}, {}, {}, {}) } }
        captureScreenRoboImage("screenshots/42-storage-working-light.png")
    }
    @Test fun storageConfirmOrphans() {
        compose.setContent { AIChatTheme(darkTheme = true) { StorageContent(storageSample.copy(orphans = OrphanPreview(listOf(OrphanFile("fixture", 1024, 1)))), {}, {}, {}, {}, {}, {}) } }
        captureScreenRoboImage("screenshots/42-storage-confirm-dark.png")
    }
    @Test fun crashLogsWorking() {
        compose.setContent { AIChatTheme(darkTheme = false) { CrashLogsContent(CrashLogsState(loading = false, busy = true), {}, {}, {}, {}, {}) } }
        captureScreenRoboImage("screenshots/40-crash-working-light.png")
    }

    @Test fun chatListDark() = shot("1-chatlist", true) { ChatList() }

    @Test fun chatLight() = shot("2-chat", false) { ChatPageSample() }

    @Test fun chatDark() = shot("2-chat", true) { ChatPageSample(input = "好的") }
    @Composable private fun NewMessagesOverChat(count: Int) = Box(Modifier.fillMaxSize()) {
        ChatPageSample()
        isomeria.aichat.feature.chat.NewMessagesButton(count, {}, Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(bottom = 72.dp))
    }
    @Test fun newMessagesLight() = shot("41-new-messages", false) { NewMessagesOverChat(12) }
    @Test fun newMessagesDark() = shot("41-new-messages", true) { NewMessagesOverChat(12345) }
    @Test fun newMessagesLargeLight() = large("41-new-messages-large", false) { NewMessagesOverChat(12345) }
    @Test fun storageLargeLight() = large("42-storage-large", false) { StorageContent(storageSample.copy(result = CleanupResult(3, 65536), error = StorageError.BUSY), {}, {}, {}, {}, {}, {}) }
    @Test fun storageLargeDark() = large("42-storage-large", true) { StorageContent(storageSample, {}, {}, {}, {}, {}, {}) }
    @Test fun storageDarkStates() = shot("42-storage-loading", true) { StorageContent(StorageState(), {}, {}, {}, {}, {}, {}) }
    @Test fun storageFailedLight() = shot("42-storage-failed", false) { StorageContent(storageSample.copy(error = StorageError.FAILED), {}, {}, {}, {}, {}, {}) }
    @Test fun storageBusyDark() = shot("42-storage-busy", true) { StorageContent(storageSample.copy(error = StorageError.BUSY), {}, {}, {}, {}, {}, {}) }
    @Test fun storageEmptyLight() = shot("42-storage-empty", false) { StorageContent(storageSample.copy(orphans = OrphanPreview(emptyList())), {}, {}, {}, {}, {}, {}) }
    @Test fun storageCompletedDark() = shot("42-storage-completed", true) { StorageContent(storageSample.copy(result = CleanupResult(3, 65536)), {}, {}, {}, {}, {}, {}) }
    @Test fun storageLoadFailedNoUsage() = shot("42-storage-load-failed", false) { StorageContent(StorageState(loading = false, error = StorageError.FAILED), {}, {}, {}, {}, {}, {}) }
    @Test fun storageConfirmOrphansLight() {
        compose.setContent { AIChatTheme(darkTheme = false) { StorageContent(storageSample.copy(orphans = OrphanPreview(listOf(OrphanFile("fixture", 1024, 1)))), {}, {}, {}, {}, {}, {}) } }
        captureScreenRoboImage("screenshots/42-storage-confirm-light.png")
    }
    @Test fun storageConfirmCache() {
        compose.setContent { AIChatTheme(darkTheme = false) { StorageContent(storageSample, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("清理缓存").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/42-storage-confirm-cache-light.png")
    }

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

    @Test fun chatInfoLight() = shot("7-chat-info", false) { ChatInfoContent(previewChatInfo, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun chatInfoDark() = shot("7-chat-info", true) { ChatInfoContent(previewChatInfo, {}, {}, {}, {}, {}, {}, {}) }

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

    private fun largeKeySummary(dark: Boolean) {
        compose.setContent { AIChatTheme(darkTheme = dark, fontScale = 2.5f) { ProviderEditSample() } }
        compose.onNodeWithText("已保存：sk-o••••••3f9c", useUnmergedTree = true).performScrollTo()
        compose.onRoot().captureRoboImage("screenshots/41-key-summary-large-${if (dark) "dark" else "light"}.png")
    }
    @Test fun keySummaryLargeLight() = largeKeySummary(false)
    @Test fun keySummaryLargeDark() = largeKeySummary(true)

    @Composable private fun SwitchStates() {
        Column {
            WeListRow("开启", trailing = { WeSwitch(true, {}, label = "开启") })
            WeListRow("关闭", trailing = { WeSwitch(false, {}, label = "关闭") })
            WeListRow("不可用", enabled = false, trailing = { WeSwitch(false, {}, enabled = false, label = "不可用") })
        }
    }
    @Test fun switchStatesLight() = shot("41-switch-states", false) { SwitchStates() }
    @Test fun switchStatesDark() = shot("41-switch-states", true) { SwitchStates() }

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
    private val movedKeyUrl = "https://very-long-subdomain.example-provider.example/compatible-mode/openai/v1"
    @Test fun keyMoveLight() = updateShot("50-provider-key-move", false) { KeyMoveDialog(movedKeyUrl, {}) {} }
    @Test fun keyMoveDark() = updateShot("50-provider-key-move", true) { KeyMoveDialog(movedKeyUrl, {}) {} }
    private fun keyMoveLarge(dark: Boolean) {
        compose.setContent { AIChatTheme(darkTheme = dark, fontScale = 2.5f) { Me(); KeyMoveDialog(movedKeyUrl, {}) {} } }
        captureScreenRoboImage("screenshots/50-provider-key-move-large-${if (dark) "dark" else "light"}.png")
    }
    @Test fun keyMoveLargeLight() = keyMoveLarge(false)
    @Test fun keyMoveLargeDark() = keyMoveLarge(true)
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
    @Test fun memoryRunningLight() = shot("28-memory-running", false) { MemoryContent(memorySettings.copy(core = null, rows = emptyList(), backlog = 128, task = memorySettings.task.copy(manual = true)), {}, {}, {}, {}) }
    @Test fun memoryRunningDark() = shot("28-memory-running", true) { MemoryContent(memorySettings.copy(core = null, rows = emptyList(), backlog = 128, task = memorySettings.task.copy(manual = true)), {}, {}, {}, {}) }
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

    /** 0.7.2：大语言模型的预置列表（9 家，用户定的顺序） */
    private fun presetSheetShot(dark: Boolean) {
        compose.setContent {
            AIChatTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) { ProviderEditSample(modelListState.copy(providerId = null, presetId = "deepseek", compatibility = "DEEPSEEK")) }
            }
        }
        compose.onNodeWithText("服务商预置", substring = true).performClick()
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/32-preset-sheet-${if (dark) "dark" else "light"}.png")
    }
    @Test fun presetSheetLight() = presetSheetShot(false)
    @Test fun presetSheetDark() = presetSheetShot(true)

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

    @Test fun contextInputLight() = shot("48-context-input", false) { isomeria.aichat.feature.contactedit.ContextBudgetRow(200000, {}) }
    @Test fun contextInputDark() = shot("48-context-input", true) { isomeria.aichat.feature.contactedit.ContextBudgetRow(200000, {}) }
    @Test fun contextInputLarge() = large("48-context-input-large", false) { isomeria.aichat.feature.contactedit.ContextBudgetRow(200000, {}) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun contextInputNarrow() = large("48-context-input-narrow", true) { isomeria.aichat.feature.contactedit.ContextBudgetRow(200000, {}) }
    @Test fun contextInvalid() = shot("48-context-invalid", false) { isomeria.aichat.feature.contactedit.ContextBudgetRow(4000, {}, "") }
    private val emptyMemory = memorySettings.copy(core = null, rows = emptyList(), backlog = 0, task = MemoryTaskState())
    @Test fun emptyMemoryLight() = shot("49-memory-empty", false) { MemoryContent(emptyMemory, {}, {}, {}, {}) }
    @Test fun emptyMemoryDark() = shot("49-memory-empty", true) { MemoryContent(emptyMemory, {}, {}, {}, {}) }
    @Test fun emptyMemoryLarge() {
        large("49-memory-empty-large", false) { MemoryContent(emptyMemory, {}, {}, {}, {}) }
        compose.onNodeWithText(isomeria.aichat.R.string.memory_empty_on.let { org.robolectric.RuntimeEnvironment.getApplication().getString(it) }).performScrollTo()
        compose.onRoot().captureRoboImage("screenshots/49-memory-empty-large-light.png")
    }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun emptyMemoryNarrow() {
        large("49-memory-empty-narrow", true) { MemoryContent(emptyMemory, {}, {}, {}, {}) }
        compose.onNodeWithText(isomeria.aichat.R.string.memory_empty_on.let { org.robolectric.RuntimeEnvironment.getApplication().getString(it) }).performScrollTo()
        compose.onRoot().captureRoboImage("screenshots/49-memory-empty-narrow-dark.png")
    }
    @Test fun emptyMemoryOff() = shot("49-memory-empty-off", false) { MemoryContent(emptyMemory.copy(autoMemory = false), {}, {}, {}, {}) }

    // ---------- 0.4.2 聊天信息：免打扰、聊天背景 ----------

    private val chatInfoSet = previewChatInfo.copy(muted = true, pinned = false, hasBackground = true)
    @Test fun chatInfoMutedLight() = shot("52-chat-info-muted", false) { ChatInfoContent(chatInfoSet, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun chatInfoMutedDark() = shot("52-chat-info-muted", true) { ChatInfoContent(chatInfoSet, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun chatInfoLargeLight() = large("52-chat-info-large", false) { ChatInfoContent(chatInfoSet, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun chatInfoLargeDark() = large("52-chat-info-large", true) { ChatInfoContent(chatInfoSet, {}, {}, {}, {}, {}, {}, {}) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun chatInfoNarrow() = large("52-chat-info-narrow", false) { ChatInfoContent(chatInfoSet, {}, {}, {}, {}, {}, {}, {}) }
    @Test fun chatInfoBackgroundSheet() {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                ChatInfoContent(chatInfoSet, {}, {}, {}, {}, {}, {}, {})
                isomeria.aichat.ui.components.WeActionSheet(
                    options = listOf(
                        isomeria.aichat.ui.components.SheetOption("从相册选择") {},
                        isomeria.aichat.ui.components.SheetOption("恢复默认背景") {},
                    ),
                    onDismiss = {},
                )
            }
        }
        captureScreenRoboImage("screenshots/52-chat-info-background-sheet-light.png")
    }

    /** 最考验可读性的背景：深浅交替的斜条纹，时间文字和气泡都会压在深色和浅色上 */
    private val stripes: androidx.compose.ui.graphics.ImageBitmap by lazy {
        val bitmap = android.graphics.Bitmap.createBitmap(360, 640, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint().apply { strokeWidth = 40f }
        canvas.drawColor(0xFF2E4A62.toInt())
        for (i in -20..40) {
            paint.color = if (i % 2 == 0) 0xFFF2D7A0.toInt() else 0xFF1B2A38.toInt()
            canvas.drawLine(i * 40f, 0f, i * 40f - 640f, 640f, paint)
        }
        bitmap.asImageBitmap()
    }
    @Test fun chatBackgroundLight() = shot("53-chat-background", false) { ChatPageSample(background = stripes) }
    @Test fun chatBackgroundDark() = shot("53-chat-background", true) { ChatPageSample(background = stripes) }
    @Test fun chatBackgroundLargeLight() = large("53-chat-background-large", false) { ChatPageSample(background = stripes) }
    @Test fun chatBackgroundLargeDark() = large("53-chat-background-large", true) { ChatPageSample(background = stripes) }
    // ---------- 0.4.2 联系人详情：温度参数、思考强度、朋友资料 ----------

    private val detailFixed = previewState.copy(temperature = RequestTemperature.Fixed(1f), thinkingName = "高")
    private val detailNotSent = previewState.copy(temperature = RequestTemperature.NotSent, chatIssue = null)
    @Test fun contactDetailFixedLight() = shot("54-contact-detail-fixed", false) { ContactDetailContent(detailFixed, {}, {}, {}, {}) }
    @Test fun contactDetailNotSentDark() = shot("54-contact-detail-not-sent", true) { ContactDetailContent(detailNotSent, {}, {}, {}, {}) }
    @Test fun contactDetailLargeLight() = large("54-contact-detail-large", false) { ContactDetailContent(detailFixed, {}, {}, {}, {}) }
    @Test fun contactDetailLargeDark() = large("54-contact-detail-large", true) { ContactDetailContent(detailNotSent.copy(temperature = RequestTemperature.Blocked(2.5f), chatIssue = ConfigurationIssue.TEMPERATURE), {}, {}, {}, {}) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun contactDetailNarrow() = large("54-contact-detail-narrow", false) { ContactDetailContent(detailFixed, {}, {}, {}, {}) }

    // ---------- 0.4.2 编辑联系人：系统提示词改为一行入口，单独一页编辑 ----------
    private val longPrompt = "你是小雨，一个住在海边小城的插画师。说话简短、口语化，偶尔用一点颜文字。\n不要主动提起自己是 AI。\n" +
        "聊到画画时会变得很健谈，会分享最近在画的东西和遇到的困难。"
    @Composable private fun PromptRows() = Column { GroupSpacer(); PromptRow(longPrompt) {}; GroupSpacer(); PromptRow("") {} }
    @Test fun promptRowLight() = shot("57-contact-prompt-row", false) { PromptRows() }
    @Test fun promptRowDark() = shot("57-contact-prompt-row", true) { PromptRows() }
    @Test fun promptRowLarge() = large("57-contact-prompt-row-large", false) { PromptRows() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun promptRowNarrow() = large("57-contact-prompt-row-narrow", true) { PromptRows() }
    @Test fun promptEditorLight() = shot("58-contact-prompt-editor", false) { ContactPromptContent(longPrompt, {}, {}) }
    @Test fun promptEditorDark() = shot("58-contact-prompt-editor", true) { ContactPromptContent(longPrompt, {}, {}) }
    @Test fun promptEditorLarge() = large("58-contact-prompt-editor-large", true) { ContactPromptContent(longPrompt, {}, {}) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun promptEditorNarrow() = large("58-contact-prompt-editor-narrow", false) { ContactPromptContent(longPrompt, {}, {}) }

    // ---------- 0.5.0 全局记忆、回复偏好、编辑联系人的开关和提示词占用 ----------

    private val globalTree = isomeria.aichat.data.repo.GlobalMemoryTree(
        categories = listOf(
            isomeria.aichat.data.db.GlobalMemoryCategory(1, "个人信息", 0, 1, 1),
            isomeria.aichat.data.db.GlobalMemoryCategory(2, "工作和学习", 1, 2, 2),
        ),
        topics = listOf(
            isomeria.aichat.data.db.GlobalMemoryTopic(10, 1, "基本情况", 0, 1, 1),
            isomeria.aichat.data.db.GlobalMemoryTopic(11, 1, "饮食习惯", 1, 1, 1),
            isomeria.aichat.data.db.GlobalMemoryTopic(12, 2, "职业", 0, 1, 1),
        ),
        items = listOf(
            isomeria.aichat.data.db.GlobalMemoryItem(100, 10, "住在杭州，平时骑车上班", true, 0, 1, 1),
            isomeria.aichat.data.db.GlobalMemoryItem(101, 10, "生日是 3 月 5 日，不喜欢过生日被大张旗鼓地庆祝，简单吃顿饭就好", false, 1, 1, 1),
            isomeria.aichat.data.db.GlobalMemoryItem(102, 10, "养了一只叫豆包的橘猫", true, 2, 1, 1),
            isomeria.aichat.data.db.GlobalMemoryItem(103, 11, "不吃香菜", true, 0, 1, 1),
            isomeria.aichat.data.db.GlobalMemoryItem(104, 12, "做后端开发", true, 0, 1, 1),
        ),
    )
    private val globalState = isomeria.aichat.feature.globalmemory.GlobalMemoryState(true, globalTree, 4, 33, 96, 3, 4, listOf("DeepSeek", "OpenRouter"))
    private val noActions = isomeria.aichat.feature.globalmemory.GlobalMemoryActions()
    @Composable private fun GlobalPage() = isomeria.aichat.feature.globalmemory.GlobalMemoryContent(globalState, noActions, {}, {})
    @Composable private fun GlobalCategory() = isomeria.aichat.feature.globalmemory.GlobalMemoryCategoryContent(1, globalState, noActions, {}, {})
    @Composable private fun GlobalTopic() = isomeria.aichat.feature.globalmemory.GlobalMemoryTopicContent(10, globalState, noActions, {}, { _, _ -> })
    @Composable private fun GlobalItem() = isomeria.aichat.feature.globalmemory.GlobalMemoryItemContent(
        isomeria.aichat.feature.globalmemory.GlobalItemDraft(true, true, "生日是 3 月 5 日，不喜欢过生日被大张旗鼓地庆祝，简单吃顿饭就好", false), {}, {}, {}, {}, {},
    )
    @Composable private fun GlobalEmpty() = isomeria.aichat.feature.globalmemory.GlobalMemoryContent(
        isomeria.aichat.feature.globalmemory.GlobalMemoryState(loaded = true, contactsTotal = 4), noActions, {}, {},
    )
    @Test fun globalMemoryLight() = shot("59-global-memory", false) { GlobalPage() }
    @Test fun globalMemoryDark() = shot("59-global-memory", true) { GlobalPage() }
    @Test fun globalMemoryLarge() = large("59-global-memory-large", false) { GlobalPage() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun globalMemoryNarrow() = large("59-global-memory-narrow", true) { GlobalPage() }
    @Test fun globalMemoryEmptyLight() = shot("59-global-memory-empty", false) { GlobalEmpty() }
    @Test fun globalCategoryLight() = shot("60-global-memory-category", false) { GlobalCategory() }
    @Test fun globalCategoryDark() = shot("60-global-memory-category", true) { GlobalCategory() }
    @Test fun globalCategoryLarge() = large("60-global-memory-category-large", true) { GlobalCategory() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun globalCategoryNarrow() = large("60-global-memory-category-narrow", false) { GlobalCategory() }
    @Test fun globalTopicLight() = shot("61-global-memory-topic", false) { GlobalTopic() }
    @Test fun globalTopicDark() = shot("61-global-memory-topic", true) { GlobalTopic() }
    @Test fun globalTopicLarge() = large("61-global-memory-topic-large", false) { GlobalTopic() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun globalTopicNarrow() = large("61-global-memory-topic-narrow", true) { GlobalTopic() }
    @Test fun globalItemLight() = shot("62-global-memory-item", false) { GlobalItem() }
    @Test fun globalItemDark() = shot("62-global-memory-item", true) { GlobalItem() }
    @Test fun globalItemLarge() = large("62-global-memory-item-large", true) { GlobalItem() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun globalItemNarrow() = large("62-global-memory-item-narrow", false) { GlobalItem() }

    private val replyState = isomeria.aichat.feature.replyprefs.ReplyPreferencesState(true, isomeria.aichat.core.ReplyPreferences(), 4, 4)
    @Composable private fun ReplyPage() = isomeria.aichat.feature.replyprefs.ReplyPreferencesContent(replyState, {}, {}, {})
    @Test fun replyPreferencesLight() = shot("63-reply-preferences", false) { ReplyPage() }
    @Test fun replyPreferencesDark() = shot("63-reply-preferences", true) { ReplyPage() }
    @Test fun replyPreferencesLarge() = large("63-reply-preferences-large", false) { ReplyPage() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun replyPreferencesNarrow() = large("63-reply-preferences-narrow", true) { ReplyPage() }

    @Composable private fun ContactSwitches() = Column {
        GroupSpacer()
        PromptRow(longPrompt, isomeria.aichat.feature.contactedit.PromptUsage(2_300, 4_000)) {}
        GroupSpacer()
        PromptRow(longPrompt, isomeria.aichat.feature.contactedit.PromptUsage(640, 4_000)) {}
        isomeria.aichat.feature.contactedit.GlobalSwitchRows(isomeria.aichat.feature.contactedit.ContactForm(), {})
    }
    @Test fun contactSwitchesLight() = shot("64-contact-global-switches", false) { ContactSwitches() }
    @Test fun contactSwitchesDark() = shot("64-contact-global-switches", true) { ContactSwitches() }
    @Test fun contactSwitchesLarge() = large("64-contact-global-switches-large", false) { ContactSwitches() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun contactSwitchesNarrow() = large("64-contact-global-switches-narrow", true) { ContactSwitches() }
    @Test fun promptEditorUsageLight() = shot("64-contact-prompt-editor-usage", false) {
        ContactPromptContent(longPrompt, {}, {}, isomeria.aichat.feature.contactedit.PromptUsage(2_300, 4_000))
    }

    // ---------- 0.7.2 导出和恢复时选择内容 ----------

    private fun partialPreview() = BackupPreview(
        java.io.File("fixture.zip"),
        BackupManifest(
            appVersion = "0.7.2", appVersionCode = 702, formatVersion = 8, exportedAt = 1793664000000, contactCount = 2, messageCount = 0,
            modules = listOf("CONTACTS", "GLOBAL_MEMORY", "REPLY_PREFERENCES"),
        ),
        BackupData(),
    )
    private fun overwriteConfirm(modules: Set<isomeria.aichat.data.backup.BackupModule>?) = RestoreUiState(
        preview = sampleBackupPreview(decrypted = false, keyEncrypted = false), mode = RestoreMode.OVERWRITE, modules = modules, confirmOverwrite = true,
    )
    private fun restoreDialogShot(name: String, dark: Boolean, fontScale: Float = 1f, state: RestoreUiState) {
        compose.setContent { AIChatTheme(darkTheme = dark, fontScale = fontScale) { RestoreSample(state) } }
        captureScreenRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
    }
    private val exportPartial = ExportUiState(hasApiKey = true, modules = setOf(isomeria.aichat.data.backup.BackupModule.GLOBAL_MEMORY, isomeria.aichat.data.backup.BackupModule.REPLY_PREFERENCES))
    @Test fun exportPartialLight() = shot("43-export-partial", false) { ExportSample(exportPartial) }
    @Test fun exportPartialDark() = shot("43-export-partial", true) { ExportSample(exportPartial) }
    @Test fun restorePartialLight() = shot("65-restore-partial", false) { RestoreSample(RestoreUiState(preview = partialPreview())) }
    @Test fun restorePartialDark() = shot("65-restore-partial", true) { RestoreSample(RestoreUiState(preview = partialPreview(), modules = setOf(isomeria.aichat.data.backup.BackupModule.GLOBAL_MEMORY))) }
    @Test fun restoreOverwriteAllLight() = restoreDialogShot("65-restore-overwrite-all", false, state = overwriteConfirm(null))
    @Test fun restoreOverwriteContactsDark() = restoreDialogShot("65-restore-overwrite-contacts", true, state = overwriteConfirm(setOf(isomeria.aichat.data.backup.BackupModule.CONTACTS, isomeria.aichat.data.backup.BackupModule.PROFILE)))
    @Test fun restoreOverwriteLarge() = restoreDialogShot("65-restore-overwrite-large", true, 1.6f, overwriteConfirm(null))
    @Test fun restoreNeedModelLight() = backupDialogShot("65-restore-need-model", false) { RestoreSample(RestoreUiState(done = RestoreSummary(2, 0, false, contactsNeedModel = 1))) }

    // ---------- 0.6.0 主动聊天 ----------

    private val proactiveContacts = listOf(
        isomeria.aichat.feature.proactive.ProactiveContactStatus(1, "小雨", ProactiveFrequency.MEDIUM, null, "今天 12:05", "明天 07:00", isomeria.aichat.core.ProactiveResult.SENT),
        isomeria.aichat.feature.proactive.ProactiveContactStatus(2, "阿哲", ProactiveFrequency.HIGH, isomeria.aichat.core.ProactiveBlock.PAUSED, "昨天 21:40", lastResult = isomeria.aichat.core.ProactiveResult.TIMED_OUT),
        isomeria.aichat.feature.proactive.ProactiveContactStatus(3, "名字特别特别长的一位联系人朋友", ProactiveFrequency.LOW, isomeria.aichat.core.ProactiveBlock.NO_KEY),
    )
    private val proactiveState = isomeria.aichat.feature.proactive.ProactiveScreenState(
        loaded = true, usedToday = 3, lastRun = "15:30", scheduled = true, canNotify = true, contacts = proactiveContacts,
    )
    private val proactiveEmpty = isomeria.aichat.feature.proactive.ProactiveScreenState(
        loaded = true, settings = isomeria.aichat.core.ProactiveSettings(quietEnabled = false, dailyLimit = 5), canNotify = false,
    )
    @Composable private fun ProactivePage(state: isomeria.aichat.feature.proactive.ProactiveScreenState = proactiveState) =
        isomeria.aichat.feature.proactive.ProactiveContent(state, {}, {}, {}, {})
    @Test fun proactiveLight() = shot("66-proactive", false) { ProactivePage() }
    @Test fun proactiveDark() = shot("66-proactive", true) { ProactivePage() }
    @Test fun proactiveLarge() = large("66-proactive-large", false) { ProactivePage() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun proactiveNarrow() = large("66-proactive-narrow", true) { ProactivePage() }
    @Test fun proactiveEmptyLight() = shot("66-proactive-empty", false) { ProactivePage(proactiveEmpty) }
    @Test fun proactiveEmptyDark() = shot("66-proactive-empty", true) { ProactivePage(proactiveEmpty) }
    /** 页面较长：滚到底看联系人状态和恢复默认 */
    private fun proactiveBottom(name: String, dark: Boolean, big: Boolean) {
        if (big) large(name, dark) { ProactivePage() } else shot(name, dark) { ProactivePage() }
        compose.onNodeWithText(org.robolectric.RuntimeEnvironment.getApplication().getString(isomeria.aichat.R.string.reply_pref_reset)).performScrollTo()
        compose.onRoot().captureRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
    }
    @Test fun proactiveBottomLight() = proactiveBottom("66-proactive-bottom", false, big = false)
    @Test fun proactiveBottomLarge() = proactiveBottom("66-proactive-bottom-large", true, big = true)
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun proactiveBottomNarrow() = proactiveBottom("66-proactive-bottom-narrow", false, big = true)

    @Composable private fun ContactProactive() = Column {
        GroupSpacer()
        isomeria.aichat.feature.contactedit.ProactiveRows(false, ProactiveFrequency.MEDIUM, {}, {})
        GroupSpacer()
        isomeria.aichat.feature.contactedit.ProactiveRows(true, ProactiveFrequency.MEDIUM, {}, {})
        GroupSpacer()
        isomeria.aichat.feature.contactedit.ProactiveRows(true, ProactiveFrequency.HIGH, {}, {})
    }
    @Test fun contactProactiveLight() = shot("67-contact-proactive", false) { ContactProactive() }
    @Test fun contactProactiveDark() = shot("67-contact-proactive", true) { ContactProactive() }
    @Test fun contactProactiveLarge() = large("67-contact-proactive-large", false) { ContactProactive() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun contactProactiveNarrow() = large("67-contact-proactive-narrow", true) { ContactProactive() }
    @Composable private fun ProactiveIntro() = WeDialog(
        title = androidx.compose.ui.res.stringResource(isomeria.aichat.R.string.contact_proactive_intro_title),
        message = androidx.compose.ui.res.stringResource(isomeria.aichat.R.string.contact_proactive_intro),
        confirmText = androidx.compose.ui.res.stringResource(isomeria.aichat.R.string.contact_proactive_intro_confirm),
        onDismiss = {}, onConfirm = {},
    )
    @Test fun proactiveIntroLight() = updateShot("67-contact-proactive-intro", false) { ProactiveIntro() }
    @Test fun proactiveIntroDark() = updateShot("67-contact-proactive-intro", true) { ProactiveIntro() }
    @Test fun proactiveIntroLarge() {
        compose.setContent { AIChatTheme(darkTheme = false, fontScale = 2.5f) { Me(); ProactiveIntro() } }
        captureScreenRoboImage("screenshots/67-contact-proactive-intro-large-light.png")
    }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun proactiveIntroNarrow() {
        compose.setContent { AIChatTheme(darkTheme = true, fontScale = 2.5f) { Me(); ProactiveIntro() } }
        captureScreenRoboImage("screenshots/67-contact-proactive-intro-narrow-dark.png")
    }

    private val usageProactive = isomeria.aichat.feature.usage.UsageState(
        loaded = true,
        total = isomeria.aichat.core.UsageTotal(0, promptTokens = 58_320, completionTokens = 9_140, memoryTokens = 1_850, requests = 86, proactiveTokens = 6_420),
        rows = listOf(
            isomeria.aichat.feature.usage.UsageRowItem(3, "今天", isomeria.aichat.core.UsageTotal(3, promptTokens = 12_400, completionTokens = 2_100, requests = 18, proactiveTokens = 4_300)),
            isomeria.aichat.feature.usage.UsageRowItem(2, "昨天", isomeria.aichat.core.UsageTotal(2, promptTokens = 21_500, completionTokens = 3_300, memoryTokens = 1_850, requests = 31, proactiveTokens = 2_120)),
            isomeria.aichat.feature.usage.UsageRowItem(1, "10月30日", isomeria.aichat.core.UsageTotal(1, promptTokens = 24_420, completionTokens = 3_740, requests = 37)),
        ),
    )
    @Test fun usageProactiveLight() = shot("68-usage-proactive", false) { UsageContent(usageProactive, {}, {}, {}, {}) }
    @Test fun usageProactiveDark() = shot("68-usage-proactive", true) { UsageContent(usageProactive, {}, {}, {}, {}) }
    @Test fun usageProactiveLarge() = large("68-usage-proactive-large", false) { UsageContent(usageProactive, {}, {}, {}, {}) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun usageProactiveNarrow() = large("68-usage-proactive-narrow", true) { UsageContent(usageProactive, {}, {}, {}, {}) }

    // ---------- 0.4.2「我」页：四个入口，各进一栏设置页 ----------

    private val settingsSample = SettingsUiState(hasKey = true, canNotify = false, newerVersion = "0.4.3")
    @Composable private fun SettingsPage(section: SettingsSection) = SettingsSectionContent(section, settingsSample, {}, SettingsNavigation(), SettingsActions())
    @Test fun meLargeLight() = large("55-me-large", false) { Me() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun meNarrow() = large("55-me-narrow", true) { Me() }
    @Test fun settingsModelsLight() = shot("56-settings-models", false) { SettingsPage(SettingsSection.MODELS) }
    @Test fun settingsModelsDark() = shot("56-settings-models", true) { SettingsPage(SettingsSection.MODELS) }
    @Test fun settingsGeneralLight() = shot("56-settings-general", false) { SettingsPage(SettingsSection.GENERAL) }
    @Test fun settingsGeneralDark() = shot("56-settings-general", true) { SettingsPage(SettingsSection.GENERAL) }
    @Test fun settingsDataLight() = shot("56-settings-data", false) { SettingsPage(SettingsSection.DATA) }
    @Test fun settingsDataDark() = shot("56-settings-data", true) { SettingsPage(SettingsSection.DATA) }
    @Test fun settingsAboutLight() = shot("56-settings-about", false) { SettingsPage(SettingsSection.ABOUT) }
    @Test fun settingsAboutDark() = shot("56-settings-about", true) { SettingsPage(SettingsSection.ABOUT) }
    @Test fun settingsGeneralLarge() = large("56-settings-general-large", false) { SettingsPage(SettingsSection.GENERAL) }
    @Test fun settingsAboutLarge() = large("56-settings-about-large", true) { SettingsPage(SettingsSection.ABOUT) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun settingsDataNarrow() = large("56-settings-data-narrow", false) { SettingsPage(SettingsSection.DATA) }

    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun chatBackgroundNarrow() = large("53-chat-background-narrow", true) { ChatPageSample(background = stripes) }

    // ---------- 0.7.0 语音消息 ----------

    private val voiceSettingsSample = isomeria.aichat.feature.voice.VoiceSettingsUiState(
        isomeria.aichat.core.VoiceSettings(isomeria.aichat.data.db.ReplyType.FOLLOW), ttsName = "Xiaomi MiMo", asrName = null,
    )
    @Composable private fun VoiceSettingsPage() = isomeria.aichat.feature.voice.VoiceSettingsContent(voiceSettingsSample, {}, {}, {})
    @Test fun voiceSettingsLight() = shot("69-voice-settings", false) { VoiceSettingsPage() }
    @Test fun voiceSettingsDark() = shot("69-voice-settings", true) { VoiceSettingsPage() }
    @Test fun voiceSettingsLarge() = large("69-voice-settings-large", false) { VoiceSettingsPage() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun voiceSettingsNarrow() = large("69-voice-settings-narrow", true) { VoiceSettingsPage() }
    @Test fun settingsModelsVoiceLight() = shot("69-settings-models-voice", false) {
        SettingsSectionContent(SettingsSection.MODELS, settingsSample.copy(ttsName = "Xiaomi MiMo", asrName = "硅基流动"), {}, SettingsNavigation(), SettingsActions())
    }

    @Composable private fun VoiceProvidersPage() = isomeria.aichat.feature.voice.VoiceProviderListContent(
        isomeria.aichat.data.db.ProviderKind.TTS,
        isomeria.aichat.data.repo.VoiceProviders(isomeria.aichat.feature.voice.previewVoiceProviders, 2), {}, {}, {},
    )
    @Test fun voiceProvidersLight() = shot("70-voice-providers", false) { VoiceProvidersPage() }
    @Test fun voiceProvidersDark() = shot("70-voice-providers", true) { VoiceProvidersPage() }
    @Test fun voiceProvidersLarge() = large("70-voice-providers-large", false) { VoiceProvidersPage() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun voiceProvidersNarrow() = large("70-voice-providers-narrow", true) { VoiceProvidersPage() }
    @Test fun voiceProvidersEmptyLight() = shot("70-voice-providers-empty", false) {
        isomeria.aichat.feature.voice.VoiceProviderListContent(isomeria.aichat.data.db.ProviderKind.ASR, isomeria.aichat.data.repo.VoiceProviders(emptyList(), null), {}, {}, {})
    }

    private val voiceEditSample = isomeria.aichat.feature.voice.VoiceProviderEditState(
        loaded = true, kind = isomeria.aichat.data.db.ProviderKind.TTS, providerId = 2, presetId = "mimo",
        protocol = isomeria.aichat.data.voice.VoiceProtocol.CHAT_AUDIO, name = "Xiaomi MiMo", baseUrl = "https://api.xiaomimimo.com/v1",
        savedKeyMasked = "sk-o••••••3f9c", model = "mimo-v2.5-tts", voice = "mimo_default", use = true,
    )
    private val voiceEditCustom = isomeria.aichat.feature.voice.VoiceProviderEditState(
        loaded = true, kind = isomeria.aichat.data.db.ProviderKind.ASR, protocol = isomeria.aichat.data.voice.VoiceProtocol.OPENAI_AUDIO,
        name = "自定义", baseUrl = "", model = "", errorRes = isomeria.aichat.R.string.voice_model_required,
    )
    @Composable private fun VoiceEditPage(state: isomeria.aichat.feature.voice.VoiceProviderEditState = voiceEditSample) =
        isomeria.aichat.feature.voice.VoiceProviderEditContent(state, {}, isomeria.aichat.feature.voice.VoiceProviderEditActions())
    @Test fun voiceEditLight() = shot("71-voice-provider-edit", false) { VoiceEditPage() }
    @Test fun voiceEditDark() = shot("71-voice-provider-edit", true) { VoiceEditPage() }
    @Test fun voiceEditLarge() = large("71-voice-provider-edit-large", false) { VoiceEditPage() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun voiceEditNarrow() = large("71-voice-provider-edit-narrow", true) { VoiceEditPage() }
    @Test fun voiceEditCustomLight() = shot("71-voice-provider-edit-custom", false) { VoiceEditPage(voiceEditCustom) }
    @Test fun voiceEditCustomDark() = shot("71-voice-provider-edit-custom", true) { VoiceEditPage(voiceEditCustom) }

    // ---------- 0.7.2 预置服务商和选音色 ----------

    private val voiceEditVolc = isomeria.aichat.feature.voice.VoiceProviderEditState(
        loaded = true, kind = isomeria.aichat.data.db.ProviderKind.TTS, presetId = "volcengine",
        protocol = isomeria.aichat.data.voice.VoiceProtocol.VOLC_TTS, name = "火山引擎（豆包）", baseUrl = "https://openspeech.bytedance.com/api/v3",
        model = "seed-tts-2.0", voice = "zh_female_vv_uranus_bigtts", use = true,
    )
    private val voiceEditQwen = isomeria.aichat.feature.voice.VoiceProviderEditState(
        loaded = true, kind = isomeria.aichat.data.db.ProviderKind.ASR, presetId = "qwen",
        protocol = isomeria.aichat.data.voice.VoiceProtocol.QWEN_ASR, name = "阿里云百炼（千问）",
        baseUrl = "https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", model = "qwen3-asr-flash",
        errorRes = isomeria.aichat.R.string.base_url_placeholder_required,
    )
    @Test fun voiceEditVolcLight() = shot("71-voice-provider-edit-volcengine", false) { VoiceEditPage(voiceEditVolc) }
    @Test fun voiceEditVolcDark() = shot("71-voice-provider-edit-volcengine", true) { VoiceEditPage(voiceEditVolc) }
    @Test fun voiceEditQwenLight() = shot("71-voice-provider-edit-qwen", false) { VoiceEditPage(voiceEditQwen) }
    private val voiceEditZhipuAsr = isomeria.aichat.feature.voice.VoiceProviderEditState(
        loaded = true, kind = isomeria.aichat.data.db.ProviderKind.ASR, presetId = "zhipu",
        protocol = isomeria.aichat.data.voice.VoiceProtocol.ZHIPU_ASR, name = "BigModel（智谱）",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4", model = "glm-asr-2512", use = true,
    )
    private val voiceEditOpenRouter = isomeria.aichat.feature.voice.VoiceProviderEditState(
        loaded = true, kind = isomeria.aichat.data.db.ProviderKind.TTS, presetId = "openrouter",
        protocol = isomeria.aichat.data.voice.VoiceProtocol.OPENROUTER_TTS, name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1", model = "google/gemini-3.8-flash-tts", voice = "Kore",
    )
    @Test fun voiceEditZhipuAsrLight() = shot("71-voice-provider-edit-zhipu-asr", false) { VoiceEditPage(voiceEditZhipuAsr) }
    @Test fun voiceEditOpenRouterDark() = shot("71-voice-provider-edit-openrouter", true) { VoiceEditPage(voiceEditOpenRouter) }

    private val pickerSample = isomeria.aichat.feature.voice.VoicePickerState(
        open = true, options = isomeria.aichat.data.voice.BuiltInVoices.QWEN.take(12), current = "longanhuan_v3.1",
        previewing = "longanfengyue_v3.1", playing = "yuxiaoyun_v3.1",
    )
    @Composable private fun PickerPage(state: isomeria.aichat.feature.voice.VoicePickerState = pickerSample) =
        isomeria.aichat.feature.voice.VoicePickerContent(state, {}, {}, {}, {}, {})
    @Test fun voicePickerLight() = shot("74-voice-picker", false) { PickerPage() }
    @Test fun voicePickerDark() = shot("74-voice-picker", true) { PickerPage() }
    @Test fun voicePickerLarge() = large("74-voice-picker-large", false) { PickerPage() }
    @Test fun voicePickerFilteredLight() = shot("74-voice-picker-filtered", false) { PickerPage(pickerSample.copy(query = "龙安")) }
    @Test fun voicePickerLoadingDark() = shot("74-voice-picker-loading", true) { PickerPage(isomeria.aichat.feature.voice.VoicePickerState(open = true, loading = true, remoteSearch = true)) }
    @Test fun voicePickerNeedKeyLight() = shot("74-voice-picker-need-key", false) {
        PickerPage(isomeria.aichat.feature.voice.VoicePickerState(open = true, error = isomeria.aichat.feature.voice.PickerMessage(isomeria.aichat.R.string.voice_picker_need_key)))
    }
    @Test fun voicePickerZhipuLight() = shot("74-voice-picker-zhipu", false) {
        PickerPage(
            isomeria.aichat.feature.voice.VoicePickerState(
                open = true, current = "tongtong",
                options = listOf(isomeria.aichat.data.voice.VoiceOption("voice_clone_1", "我的声音", "2026-11-01 10:00:00", isomeria.aichat.data.voice.VoiceCatalog.GROUP_MINE)) +
                    isomeria.aichat.data.voice.BuiltInVoices.ZHIPU,
            ),
        )
    }
    @Test fun voicePickerNoListDark() = shot("74-voice-picker-no-list", true) { PickerPage(isomeria.aichat.feature.voice.VoicePickerState(open = true)) }
    @Test fun voicePickerFailedDark() = shot("74-voice-picker-failed", true) {
        PickerPage(pickerSample.copy(previewError = isomeria.aichat.feature.voice.PickerMessage(isomeria.aichat.R.string.voice_picker_preview_failed, isomeria.aichat.R.string.error_auth)))
    }

    private fun rec(zone: isomeria.aichat.ui.components.RecordZone = isomeria.aichat.ui.components.RecordZone.SEND, toggled: Boolean = false,
        mode: isomeria.aichat.ui.components.RecordMode = isomeria.aichat.ui.components.RecordMode.VOICE_MESSAGE, elapsed: Long = 4_000) =
        isomeria.aichat.ui.components.RecordingUi(mode, toggled, elapsed, 0.6f, zone)
    @Test fun holdToTalkLight() = shot("72-chat-hold-to-talk", false) { ChatPageSample(voiceMode = true) }
    @Test fun holdToTalkDark() = shot("72-chat-hold-to-talk", true) { ChatPageSample(voiceMode = true) }
    @Test fun holdToTalkLarge() = large("72-chat-hold-to-talk-large", false) { ChatPageSample(voiceMode = true) }
    @Test fun transcribingLight() = shot("72-chat-transcribing", false) { ChatPageSample(transcribing = true) }
    @Test fun recordingSendLight() = shot("73-recording-send", false) { ChatPageSample(voiceMode = true, recording = rec()) }
    @Test fun recordingSendDark() = shot("73-recording-send", true) { ChatPageSample(voiceMode = true, recording = rec()) }
    @Test fun recordingCancelLight() = shot("73-recording-cancel", false) { ChatPageSample(voiceMode = true, recording = rec(isomeria.aichat.ui.components.RecordZone.CANCEL)) }
    @Test fun recordingTextDark() = shot("73-recording-to-text", true) { ChatPageSample(voiceMode = true, recording = rec(isomeria.aichat.ui.components.RecordZone.TO_TEXT, elapsed = 52_300)) }
    @Test fun recordingToggledLight() = shot("73-recording-toggled", false) { ChatPageSample(voiceMode = true, recording = rec(toggled = true)) }
    @Test fun recordingMicDark() = shot("73-recording-mic", true) { ChatPageSample(recording = rec(toggled = true, mode = isomeria.aichat.ui.components.RecordMode.TO_TEXT)) }
    @Test fun recordingLarge() = large("73-recording-large", false) { ChatPageSample(voiceMode = true, recording = rec(toggled = true)) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun recordingNarrow() = large("73-recording-narrow", true) { ChatPageSample(voiceMode = true, recording = rec(toggled = true)) }

    // ---------- 0.7.1 系统语音（本机） ----------

    private val systemTtsReady = isomeria.aichat.feature.voice.SystemTtsUiState(
        info = isomeria.aichat.data.voice.SystemTtsInfo(
            isomeria.aichat.data.voice.SystemTtsStatus.READY, "小米语音引擎",
            listOf(isomeria.aichat.data.voice.SystemTtsVoice("zh-CN-xiaoai-local", "zh-CN"), isomeria.aichat.data.voice.SystemTtsVoice("yue-HK-local", "yue-HK")),
        ),
        inUse = true,
        voice = "zh-CN-xiaoai-local",
    )
    private val systemTtsNoChinese = isomeria.aichat.feature.voice.SystemTtsUiState(
        info = isomeria.aichat.data.voice.SystemTtsInfo(isomeria.aichat.data.voice.SystemTtsStatus.NO_CHINESE, "Speech Services by Google"),
    )
    @Composable private fun SystemTtsPage(state: isomeria.aichat.feature.voice.SystemTtsUiState = systemTtsReady) =
        isomeria.aichat.feature.voice.SystemTtsContent(state, {}, {}, {}, {})
    @Test fun systemTtsLight() = shot("78-system-tts", false) { SystemTtsPage() }
    @Test fun systemTtsDark() = shot("78-system-tts", true) { SystemTtsPage() }
    @Test fun systemTtsLarge() = large("78-system-tts-large", false) { SystemTtsPage() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun systemTtsNarrow() = large("78-system-tts-narrow", true) { SystemTtsPage() }
    @Test fun systemTtsNoChineseLight() = shot("78-system-tts-no-chinese", false) { SystemTtsPage(systemTtsNoChinese) }
    @Test fun voiceProvidersLocalInUseLight() = shot("70-voice-providers-local", false) {
        isomeria.aichat.feature.voice.VoiceProviderListContent(
            isomeria.aichat.data.db.ProviderKind.TTS,
            isomeria.aichat.data.repo.VoiceProviders(isomeria.aichat.feature.voice.previewVoiceProviders, null, isomeria.aichat.core.VoiceSource.SYSTEM_TTS), {}, {}, {},
        )
    }
    @Test fun voiceSettingsLocalDark() = shot("69-voice-settings-local", true) {
        isomeria.aichat.feature.voice.VoiceSettingsContent(voiceSettingsSample.copy(ttsName = null, ttsLocal = isomeria.aichat.core.VoiceSource.SYSTEM_TTS), {}, {}, {})
    }

    // ---------- 0.7.1 离线识别（本机） ----------

    private fun asrState(model: isomeria.aichat.data.voice.ModelState, work: isomeria.aichat.data.voice.ModelDownloadWork = isomeria.aichat.data.voice.ModelDownloadWork.NONE, inUse: Boolean = false, supported: Boolean = true) =
        isomeria.aichat.feature.voice.OfflineAsrUiState(supported, model, work, inUse)
    @Composable private fun OfflineAsrPage(state: isomeria.aichat.feature.voice.OfflineAsrUiState = asrState(isomeria.aichat.data.voice.ModelState.Missing)) =
        isomeria.aichat.feature.voice.OfflineAsrContent(state, {}, isomeria.aichat.feature.voice.OfflineAsrActions())
    @Test fun offlineAsrLight() = shot("79-offline-asr", false) { OfflineAsrPage() }
    @Test fun offlineAsrDark() = shot("79-offline-asr", true) { OfflineAsrPage() }
    @Test fun offlineAsrLarge() = large("79-offline-asr-large", false) { OfflineAsrPage() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun offlineAsrNarrow() = large("79-offline-asr-narrow", true) { OfflineAsrPage() }
    @Test fun offlineAsrDownloadingLight() = shot("79-offline-asr-downloading", false) {
        OfflineAsrPage(asrState(isomeria.aichat.data.voice.ModelState.Downloading(96_000_000), isomeria.aichat.data.voice.ModelDownloadWork.RUNNING))
    }
    @Test fun offlineAsrFailedDark() = shot("79-offline-asr-failed", true) {
        OfflineAsrPage(asrState(isomeria.aichat.data.voice.ModelState.Failed(isomeria.aichat.data.voice.ModelFailure.NETWORK, 120_000_000)))
    }
    @Test fun offlineAsrInstalledLight() = shot("79-offline-asr-installed", false) { OfflineAsrPage(asrState(isomeria.aichat.data.voice.ModelState.Installed, inUse = true)) }
    @Test fun offlineAsrInstalledLarge() = large("79-offline-asr-installed-large", true) { OfflineAsrPage(asrState(isomeria.aichat.data.voice.ModelState.Installed, inUse = true)) }
    @Test fun offlineAsrUnsupportedLight() = shot("79-offline-asr-unsupported", false) { OfflineAsrPage(asrState(isomeria.aichat.data.voice.ModelState.Missing, supported = false)) }
    @Test fun offlineAsrDownloadDialog() {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                isomeria.aichat.feature.voice.OfflineAsrContent(asrState(isomeria.aichat.data.voice.ModelState.Missing), {}, isomeria.aichat.feature.voice.OfflineAsrActions(metered = { true }))
            }
        }
        compose.onNodeWithText("下载模型").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("screenshots/79-offline-asr-download-dialog-light.png")
    }
    @Test fun licensesLight() = shot("80-licenses", false) { isomeria.aichat.feature.settings.LicensesScreen {} }
    @Test fun licensesLarge() = large("80-licenses-large", true) { isomeria.aichat.feature.settings.LicensesScreen {} }
    @Test fun voiceProvidersAsrLocalDark() = shot("70-voice-providers-asr-local", true) {
        isomeria.aichat.feature.voice.VoiceProviderListContent(
            isomeria.aichat.data.db.ProviderKind.ASR,
            isomeria.aichat.data.repo.VoiceProviders(emptyList(), null, isomeria.aichat.core.VoiceSource.OFFLINE_ASR), {}, {}, {},
        )
    }

    // ---------- 0.7.0 语音条和长按菜单 ----------

    @Test fun voiceBubblesLight() = shot("74-chat-voice", false) { ChatPageSample(rows = isomeria.aichat.feature.chat.voiceRows, playingId = 13) }
    @Test fun voiceBubblesDark() = shot("74-chat-voice", true) { ChatPageSample(rows = isomeria.aichat.feature.chat.voiceRows, playingId = 13) }
    @Test fun voiceBubblesLarge() = large("74-chat-voice-large", false) { ChatPageSample(rows = isomeria.aichat.feature.chat.voiceRows, playingId = 13) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun voiceBubblesNarrow() = shot("74-chat-voice-narrow", true) { ChatPageSample(rows = isomeria.aichat.feature.chat.voiceRows, playingId = 13) }

    private fun voiceMenuShot(name: String, dark: Boolean, scale: Float = 1f, id: Long = 13) {
        compose.setContent {
            AIChatTheme(darkTheme = dark, fontScale = scale) {
                Box(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) {
                    ChatPageSample(rows = isomeria.aichat.feature.chat.voiceRows, menuOpenId = id)
                }
            }
        }
        captureScreenRoboImage("screenshots/$name-${if (dark) "dark" else "light"}.png")
    }
    /** 0.7.2：语音合成、识别来源可以单独选；第一组跟随全局，第二组单独选了系统语音和一个识别服务商 */
    private val voiceChoices = isomeria.aichat.feature.contactedit.ContactVoiceChoices(
        tts = listOf(isomeria.aichat.data.db.ProviderConfig(id = 3, kind = isomeria.aichat.data.db.ProviderKind.TTS, provider = "chat-audio", name = "Xiaomi MiMo", baseUrl = "https://a.test", defaultModel = "m")),
        asr = listOf(isomeria.aichat.data.db.ProviderConfig(id = 4, kind = isomeria.aichat.data.db.ProviderKind.ASR, provider = "volc-asr", name = "火山引擎（豆包）", baseUrl = "https://a.test", defaultModel = "m")),
        globalTts = "provider:3",
        globalAsr = "local:offline-asr",
    )
    @Composable private fun ContactVoice() = Column {
        GroupSpacer()
        isomeria.aichat.feature.contactedit.ContactVoiceRows(
            isomeria.aichat.feature.contactedit.ContactForm(), isomeria.aichat.data.db.ReplyType.FOLLOW, "mimo_default", {}, {}, canPickVoice = true,
            choices = voiceChoices,
        )
        GroupSpacer()
        isomeria.aichat.feature.contactedit.ContactVoiceRows(
            isomeria.aichat.feature.contactedit.ContactForm(
                replyType = isomeria.aichat.data.db.ReplyType.VOICE, ttsVoice = "default_zh", ttsStyle = "温柔一点，语速慢一些，像睡前聊天",
                ttsSource = "local:system-tts", asrSource = "provider:4",
            ),
            isomeria.aichat.data.db.ReplyType.FOLLOW, "", {}, {}, choices = voiceChoices,
        )
    }
    @Test fun usageVoiceLight() = shot("77-usage-voice", false) { UsageContent(isomeria.aichat.feature.usage.previewVoiceUsageState, {}, {}, {}, {}) }
    @Test fun usageVoiceDark() = shot("77-usage-voice", true) { UsageContent(isomeria.aichat.feature.usage.previewVoiceUsageState, {}, {}, {}, {}) }
    @Test fun usageVoiceLarge() = large("77-usage-voice-large", false) { UsageContent(isomeria.aichat.feature.usage.previewVoiceUsageState, {}, {}, {}, {}) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun usageVoiceNarrow() = large("77-usage-voice-narrow", true) { UsageContent(isomeria.aichat.feature.usage.previewVoiceUsageState, {}, {}, {}, {}) }

    /** 0.7.2 联系人单独设置回复偏好：单独设置（各项可调）和跟随全局（写出全局现在的值） */
    @Composable private fun ContactReplyCustom() = isomeria.aichat.feature.contactedit.ContactReplyPreferencesContent(
        isomeria.aichat.feature.contactedit.ContactForm(name = "小雨", replyPreferences = isomeria.aichat.core.ReplyPreferences(waitSeconds = 3, targetLength = 30, maxCount = 2)),
        isomeria.aichat.core.ReplyPreferences(), {}, {}, {},
    )
    @Composable private fun ContactReplyFollow() = isomeria.aichat.feature.contactedit.ContactReplyPreferencesContent(
        isomeria.aichat.feature.contactedit.ContactForm(name = "小雨"), isomeria.aichat.core.ReplyPreferences(), {}, {}, {},
    )
    @Test fun contactReplyCustomLight() = shot("81-contact-reply-preferences-custom", false) { ContactReplyCustom() }
    @Test fun contactReplyCustomDark() = shot("81-contact-reply-preferences-custom", true) { ContactReplyCustom() }
    @Test fun contactReplyCustomLarge() = large("81-contact-reply-preferences-custom-large", false) { ContactReplyCustom() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun contactReplyCustomNarrow() = large("81-contact-reply-preferences-custom-narrow", true) { ContactReplyCustom() }
    @Test fun contactReplyFollowLight() = shot("81-contact-reply-preferences-follow", false) { ContactReplyFollow() }
    @Test fun contactReplyFollowDark() = shot("81-contact-reply-preferences-follow", true) { ContactReplyFollow() }

    @Test fun contactVoiceLight() = shot("76-contact-voice", false) { ContactVoice() }
    @Test fun contactVoiceDark() = shot("76-contact-voice", true) { ContactVoice() }
    @Test fun contactVoiceLarge() = large("76-contact-voice-large", false) { ContactVoice() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun contactVoiceNarrow() = large("76-contact-voice-narrow", true) { ContactVoice() }

    @Test fun voiceMenuLight() = voiceMenuShot("75-voice-menu", false)
    @Test fun voiceMenuDark() = voiceMenuShot("75-voice-menu", true, id = 14)
    @Test fun voiceMenuLarge() = voiceMenuShot("75-voice-menu-large", false, scale = 1.6f)
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun voiceMenuNarrow() = voiceMenuShot("75-voice-menu-narrow", true)
    // ---------- 0.8.0 语音通话 ----------

    private val callBase = isomeria.aichat.feature.call.CallUi(name = "小雨", avatarPath = null, avatarColor = isomeria.aichat.ui.theme.AvatarPalette[0], status = "03:21")
    private val callListening = callBase.copy(phase = "在听，请说话", subtitle = "小雨" to "好呀，那就去西湖边的山，早上八点出发？")
    private val callThinking = callBase.copy(
        phase = "对方正在想… 6 秒",
        subtitle = "我" to "周末天气好的话，我们去爬山吧",
        notice = "有几句没有合成出来，只显示了文字",
        hints = listOf("这台手机用音量判断你是否在说话，环境吵时，说完可以点「说完了」", "这个联系人开着思考，每轮可能要等较久，可以在联系人资料里关掉"),
        muted = true,
    )
    private val callSpeaking = callBase.copy(phase = "对方正在说", subtitle = "小雨" to "好呀！", speakerphone = true, action = isomeria.aichat.feature.call.CallAction.INTERRUPT)
    private val callHearing = callBase.copy(phase = "正在听你说…", subtitles = false, action = isomeria.aichat.feature.call.CallAction.FINISH)
    private val callConnecting = callBase.copy(status = "正在接通…", active = false)
    private val callEnded = callBase.copy(status = "通话已结束", phase = "很久没有人说话，通话已自动结束", active = false)

    @Composable private fun Call(ui: isomeria.aichat.feature.call.CallUi) = isomeria.aichat.feature.call.CallContent(ui, isomeria.aichat.feature.call.CallActions())

    @Test fun callListeningLight() = shot("82-call-listening", false) { Call(callListening) }
    @Test fun callListeningDark() = shot("82-call-listening", true) { Call(callListening) }
    @Test fun callThinkingLight() = shot("82-call-thinking", false) { Call(callThinking) }
    @Test fun callThinkingDark() = shot("82-call-thinking", true) { Call(callThinking) }
    @Test fun callThinkingLarge() = large("82-call-thinking-large", false) { Call(callThinking) }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun callThinkingNarrow() = large("82-call-thinking-narrow", true) { Call(callThinking) }
    @Test fun callSpeakingLight() = shot("82-call-speaking", false) { Call(callSpeaking) }
    @Test fun callSpeakingLarge() = large("82-call-speaking-large", true) { Call(callSpeaking) }
    @Test fun callHearingDark() = shot("82-call-hearing", true) { Call(callHearing) }
    @Test fun callConnectingLight() = shot("82-call-connecting", false) { Call(callConnecting) }
    @Test fun callEndedDark() = shot("82-call-ended", true) { Call(callEnded) }

    /** 聊天页「+」面板：相册、拍照、语音通话 */
    @Composable private fun MorePanelShot() = Column(Modifier.fillMaxSize(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom) {
        isomeria.aichat.ui.components.MorePanel(imageEnabled = true, onAlbum = {}, onCamera = {}, modifier = Modifier.height(260.dp), onCall = {})
    }
    @Test fun morePanelLight() = shot("83-more-panel", false) { MorePanelShot() }
    @Test fun morePanelDark() = shot("83-more-panel", true) { MorePanelShot() }
    @Test fun morePanelLarge() = large("83-more-panel-large", false) { MorePanelShot() }
    @Test @Config(qualifiers = "w320dp-h860dp-xxhdpi") fun morePanelNarrow() = large("83-more-panel-narrow", true) { MorePanelShot() }
}
