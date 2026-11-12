package isomeria.aichat.feature.backup

import isomeria.aichat.data.backup.BackupData
import isomeria.aichat.data.backup.BackupManifest
import isomeria.aichat.data.backup.BackupModule
import isomeria.aichat.data.backup.BackupModule.CHATS
import isomeria.aichat.data.backup.BackupModule.CONTACTS
import isomeria.aichat.data.backup.BackupModule.GLOBAL_MEMORY
import isomeria.aichat.data.backup.BackupModule.MEMORIES
import isomeria.aichat.data.backup.BackupModule.PROVIDERS
import isomeria.aichat.data.backup.BackupModule.USAGE
import isomeria.aichat.data.backup.BackupPreview
import isomeria.aichat.data.backup.ProviderDto
import isomeria.aichat.data.backup.SecretDto
import isomeria.aichat.data.db.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 0.7.2：导出、恢复时选择内容的规则——依赖联系人的几项、API Key 跟着模型配置、恢复只能选文件里有的 */
class BackupModuleSelectionTest {
    @Test
    fun turningContactsOff_turnsDependentsOff_andDependentOn_turnsContactsOn() {
        val off = BackupModule.ALL.toggled(CONTACTS, false)
        assertFalse(off.any { it == CONTACTS || it.requiresContacts })
        assertTrue(GLOBAL_MEMORY in off)
        assertEquals(setOf(CONTACTS, CHATS), emptySet<BackupModule>().toggled(CHATS, true))
        assertEquals(setOf(CONTACTS, MEMORIES), setOf(CONTACTS, CHATS, MEMORIES).toggled(CHATS, false))
    }

    @Test
    fun apiKeyOnlyAvailableWithProviders() {
        assertTrue(ExportUiState(hasApiKey = true).keyAvailable)
        assertFalse(ExportUiState(hasApiKey = true, modules = BackupModule.ALL - PROVIDERS).keyAvailable)
        assertFalse(ExportUiState(hasApiKey = false).keyAvailable)
    }

    @Test
    fun keyPassword_onlyAskedWhenProvidersAreRestored() {
        val key = SecretDto(scheme = SecretDto.SCHEME_PASSWORD, data = "x")
        val data = BackupData(providers = listOf(ProviderDto(id = 1, kind = ProviderKind.LLM, provider = "openai", baseUrl = "https://a.test", defaultModel = "m", apiKey = key)))
        val state = RestoreUiState(preview = preview(null, data))
        assertTrue(state.asksKeyPassword)
        assertFalse("没选模型配置就不恢复 Key", state.toggledModule(PROVIDERS, false).asksKeyPassword)
        assertFalse("整个文件解锁过时用同一个密码", RestoreUiState(preview = preview(null, data, decrypted = true)).asksKeyPassword)
        assertFalse(RestoreUiState(preview = preview(null)).asksKeyPassword)
    }

    private fun preview(modules: List<String>?, data: BackupData = BackupData(), decrypted: Boolean = false) = BackupPreview(
        File("fixture.zip"),
        BackupManifest(appVersion = "0.7.2", appVersionCode = 702, formatVersion = 8, exportedAt = 1, modules = modules),
        data,
        decryptedTemporary = decrypted,
    )

    @Test
    fun restoreSelection_isLimitedToWhatTheFileHas() {
        val state = RestoreUiState(preview = preview(listOf("CONTACTS", "USAGE", "GLOBAL_MEMORY")))
        assertEquals(setOf(CONTACTS, USAGE, GLOBAL_MEMORY), state.selectedModules)
        val noContacts = state.toggledModule(CONTACTS, false)
        assertEquals(setOf(GLOBAL_MEMORY), noContacts.selectedModules)
        // 文件里没有聊天记录：选上也不算
        assertEquals(setOf(CONTACTS, GLOBAL_MEMORY), noContacts.toggledModule(CHATS, true).selectedModules)
        assertTrue(RestoreUiState().selectedModules.isEmpty())
        assertEquals(BackupModule.ALL, RestoreUiState(preview = preview(null)).selectedModules)
    }
}
