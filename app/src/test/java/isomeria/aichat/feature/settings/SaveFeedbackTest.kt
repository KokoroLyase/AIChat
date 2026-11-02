package isomeria.aichat.feature.settings

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import isomeria.aichat.R
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatRequest
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.llm.ModelConfig
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.llm.NativeEffort
import isomeria.aichat.data.llm.RemoteModel
import isomeria.aichat.data.llm.TemperatureMode
import isomeria.aichat.data.llm.TemperatureRule
import isomeria.aichat.data.llm.TemperatureSpec
import isomeria.aichat.data.llm.ThinkingMode
import isomeria.aichat.data.llm.ThinkingRule
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.ui.theme.AIChatTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

/** 保存有没有成功、获取为什么失败、为什么删不掉，都要让用户看到。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class SaveFeedbackTest {
    @get:Rule val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }
    private class Fake : LlmProvider {
        override val id = ProviderProtocol.OPENAI
        var failure: LlmException? = null
        var requests = 0
        override suspend fun listModels(endpoint: LlmEndpoint): List<RemoteModel> {
            requests++
            failure?.let { throw it }
            return emptyList()
        }
        override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = emptyFlow()
    }

    private val fake = Fake()
    private lateinit var db: AppDatabase
    private lateinit var repo: ProviderRepository
    private var id = 0L
    private val rules = ModelConfig(true, ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("low", "低"))), TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0f, 1f)))

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .addCallback(AppDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        repo = ProviderRepository(db, db.providerDao(), cipher, setOf(fake))
        id = repo.create("接口", "https://a.example/v1", null, null, "GENERIC")
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时")
    }

    private fun providerViewModel(): ProviderEditViewModel {
        val vm = ProviderEditViewModel(SavedStateHandle(mapOf("providerId" to id)), repo)
        await { vm.state.value.loaded }
        return vm
    }

    private fun modelViewModel(): ModelEditViewModel {
        val vm = ModelEditViewModel(SavedStateHandle(mapOf("providerId" to id, "modelId" to "m")), repo)
        await { vm.state.value.model != null && vm.state.value.provider != null }
        return vm
    }

    private fun seedModel(config: ModelConfig?) = runBlocking {
        repo.saveModel(ProviderModel(id, "m", "模型", imageInputUserMark = false, legacy = false, userConfig = config?.let(ModelRules::encode)))
    }

    @Test
    fun fetchWithoutKeySaysKeyIsMissingAndSendsNothing() {
        val vm = providerViewModel()
        vm.test()
        await { vm.state.value.messageRes != null }
        assertEquals(R.string.fetch_failed_no_key, vm.state.value.messageRes)
        assertEquals(0, fake.requests)
    }

    @Test
    fun rejectedKeyIsNotReportedAsMissing() {
        fake.failure = LlmException.fromHttp(401)
        val vm = providerViewModel()
        vm.setKey("typed-key")
        vm.test()
        await { vm.state.value.messageRes != null }
        assertEquals(R.string.fetch_failed_auth, vm.state.value.messageRes)
        assertEquals(false, vm.state.value.testing)
    }

    @Test
    fun unreadableListHasItsOwnReason() {
        fake.failure = LlmException(LlmError.UNKNOWN, message = "无法解析模型列表")
        val vm = providerViewModel()
        vm.setKey("typed-key")
        vm.test()
        await { vm.state.value.messageRes != null }
        assertEquals(R.string.fetch_failed_unreadable, vm.state.value.messageRes)
    }

    @Test
    fun providerSaveTellsTheUserAndStaysOnPage() {
        val vm = providerViewModel()
        var backs = 0
        compose.setContent { AIChatTheme(darkTheme = false) { ProviderEditScreen(onBack = { backs++ }, viewModel = vm) } }
        vm.setName("新名称")
        vm.save()
        await { vm.state.value.savedCount == 1 }
        compose.waitForIdle()
        assertEquals(app.getString(R.string.llm_saved), ShadowToast.getTextOfLatestToast())
        assertEquals(null, vm.state.value.messageRes)
        assertEquals(0, backs)
    }

    @Test
    fun deletingProviderInUseExplainsInDialog() {
        runBlocking {
            db.contactDao().insert(Contact(name = "小雨", avatarColor = 0, providerId = id, model = "m", createdAt = 1, updatedAt = 1))
        }
        val vm = providerViewModel()
        compose.setContent { AIChatTheme(darkTheme = false) { ProviderEditScreen(onBack = {}, viewModel = vm) } }
        vm.delete()
        await { vm.state.value.deleteBlockedRes != null }
        compose.waitForIdle()
        val message = app.getString(R.string.llm_delete_in_use, 1)
        compose.onNodeWithText(message).assertExists()
        vm.dismissDeleteBlocked()
        compose.waitForIdle()
        compose.onNodeWithText(message).assertDoesNotExist()
    }

    @Test
    fun completeModelSaveSaysSavedAndReturns() {
        seedModel(rules)
        val vm = modelViewModel()
        var backs = 0
        compose.setContent { AIChatTheme(darkTheme = false) { ModelEditScreen(onBack = { backs++ }, viewModel = vm) } }
        vm.save()
        await { vm.state.value.savedMessage != null }
        compose.waitForIdle()
        assertEquals(app.getString(R.string.llm_saved), ShadowToast.getTextOfLatestToast())
        assertEquals(1, backs)
    }

    @Test
    fun incompleteModelSaveSaysStillPending() {
        seedModel(rules.copy(thinking = null))
        val vm = modelViewModel()
        var backs = 0
        compose.setContent { AIChatTheme(darkTheme = false) { ModelEditScreen(onBack = { backs++ }, viewModel = vm) } }
        vm.save()
        await { vm.state.value.savedMessage != null }
        compose.waitForIdle()
        assertEquals(app.getString(R.string.model_saved_pending), ShadowToast.getTextOfLatestToast())
        assertEquals(1, backs)
    }

    @Test
    fun confirmedConflictSaveSaysContactsNeedFixing() {
        seedModel(rules)
        runBlocking {
            db.contactDao().insert(Contact(name = "小雨", avatarColor = 0, providerId = id, model = "m", thinkingOption = "retired", createdAt = 1, updatedAt = 1))
        }
        val vm = modelViewModel()
        var backs = 0
        compose.setContent { AIChatTheme(darkTheme = false) { ModelEditScreen(onBack = { backs++ }, viewModel = vm) } }
        vm.save()
        await { vm.state.value.conflicts.isNotEmpty() }
        compose.waitForIdle()
        assertEquals("确认前不算保存，也不返回", 0, backs)
        vm.save(confirmed = true)
        await { vm.state.value.savedMessage != null }
        compose.waitForIdle()
        assertEquals(app.getString(R.string.model_saved_conflict), ShadowToast.getTextOfLatestToast())
        assertEquals(1, backs)
    }

    @Test
    fun effortDraftBlocksSaveInsteadOfBeingLost() {
        seedModel(rules)
        val vm = modelViewModel()
        var backs = 0
        compose.setContent { AIChatTheme(darkTheme = false) { ModelEditScreen(onBack = { backs++ }, viewModel = vm) } }
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput("high")
        compose.onNodeWithText(app.getString(R.string.action_save)).performClick()
        compose.waitForIdle()
        await { ShadowToast.getTextOfLatestToast() != null }
        assertEquals(app.getString(R.string.model_effort_draft), ShadowToast.getTextOfLatestToast())
        assertEquals(null, vm.state.value.savedMessage)
        assertEquals(0, backs)
        assertFalse(vm.state.value.dirty)
    }
}
