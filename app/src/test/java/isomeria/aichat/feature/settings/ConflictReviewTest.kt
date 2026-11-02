package isomeria.aichat.feature.settings

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import isomeria.aichat.data.repo.ModelReference
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.data.repo.ReferencePurpose
import isomeria.aichat.ui.theme.AIChatTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 只有这次保存才造成的冲突要确认；原本就有的只作提示，不打断保存。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class ConflictReviewTest {
    @get:Rule val compose = createComposeRule()

    private val app = RuntimeEnvironment.getApplication()
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }
    private class Fake : LlmProvider {
        override val id = ProviderProtocol.OPENAI
        var remote: List<RemoteModel> = emptyList()
        override suspend fun listModels(endpoint: LlmEndpoint): List<RemoteModel> = remote
        override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = emptyFlow()
    }

    private val fake = Fake()
    private lateinit var db: AppDatabase
    private lateinit var repo: ProviderRepository
    private var id = 0L

    private fun rules(max: Float = 1f) = ModelConfig(true, ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("low", "低"))),
        TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0f, max)))

    private fun model(modelId: String = "m", config: ModelConfig? = rules()) =
        ProviderModel(id, modelId, modelId, imageInputUserMark = false, legacy = false, userConfig = config?.let(ModelRules::encode))

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .addCallback(AppDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        repo = ProviderRepository(db, db.providerDao(), cipher, setOf(fake))
        id = repo.create("接口", "https://a.example/v1", "sk-old", null, "GENERIC")
        repo.saveModel(model())
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

    /** 温度 0.9：现在的规则有效，收窄到 0.5 后才失效 */
    private fun fine(name: String) = contact(name, "off", 0.9f)
    /** 档位已经不存在，一开始就待修正 */
    private fun broken(name: String) = contact(name, "retired", 0.5f)
    private fun contact(name: String, option: String, temperature: Float) = runBlocking {
        db.contactDao().insert(Contact(name = name, avatarColor = 0, providerId = id, model = "m", thinkingOption = option, temperature = temperature, createdAt = 1, updatedAt = 1))
    }

    private fun modelVm(): ModelEditViewModel {
        val vm = ModelEditViewModel(SavedStateHandle(mapOf("providerId" to id, "modelId" to "m")), repo)
        await { vm.state.value.model != null && vm.state.value.provider != null }
        return vm
    }

    private fun providerVm(): ProviderEditViewModel {
        val vm = ProviderEditViewModel(SavedStateHandle(mapOf("providerId" to id)), repo)
        await { vm.state.value.loaded }
        return vm
    }

    private fun ModelEditViewModel.tighten() = edit(state.value.model!!.copy(userConfig = ModelRules.encode(rules(0.5f))))
    private fun names(refs: List<ModelReference>) = refs.map { it.contactName }.toSet()
    private fun savedMax() = runBlocking { ModelRules.effective(repo.model(id, "m")!!).temperature!!.normal.max }

    // ---- 保存模型 ----

    @Test
    fun modelSaveWithoutConflictsJustSaves() {
        fine("小雨")
        val vm = modelVm()
        vm.save()
        await { vm.state.value.savedMessage != null }
        assertEquals(R.string.llm_saved, vm.state.value.savedMessage)
    }

    @Test
    fun existingConflictDoesNotInterruptModelSave() {
        broken("旧问题")
        val vm = modelVm()
        vm.tighten()
        vm.save()
        await { vm.state.value.savedMessage != null }
        assertTrue(vm.state.value.conflicts.isEmpty())
        assertEquals(R.string.model_saved_conflict, vm.state.value.savedMessage)
        assertEquals(0.5f, savedMax())
    }

    @Test
    fun newConflictAsksBeforeSavingModel() {
        fine("新问题")
        val vm = modelVm()
        vm.tighten()
        vm.save()
        await { vm.state.value.conflicts.isNotEmpty() }
        assertEquals(setOf("新问题"), names(vm.state.value.conflicts))
        assertTrue(vm.state.value.existingConflicts.isEmpty())
        assertEquals("确认前不写入", 1f, savedMax())
        vm.save(confirmed = true)
        await { vm.state.value.savedMessage != null }
        assertEquals(R.string.model_saved_conflict, vm.state.value.savedMessage)
        assertEquals(0.5f, savedMax())
    }

    @Test
    fun bothKindsAreSeparatedAndCancelClearsThem() {
        broken("旧问题")
        fine("新问题")
        val vm = modelVm()
        vm.tighten()
        vm.save()
        await { vm.state.value.conflicts.isNotEmpty() }
        assertEquals(setOf("新问题"), names(vm.state.value.conflicts))
        assertEquals(setOf("旧问题"), names(vm.state.value.existingConflicts))
        vm.dismiss()
        await { vm.state.value.conflicts.isEmpty() }
        assertTrue(vm.state.value.existingConflicts.isEmpty())
        assertEquals("取消后不写入", 1f, savedMax())
    }

    @Test
    fun aDifferentReasonOnAnAlreadyBrokenContactIsNotNew() {
        // 档位和温度原本都不对；这次只是让问题换个说法，联系人并没有因为这次保存才不能用
        contact("旧问题", "retired", 0.9f)
        val vm = modelVm()
        vm.tighten()
        vm.save()
        await { vm.state.value.savedMessage != null }
        assertTrue(vm.state.value.conflicts.isEmpty())
    }

    @Test
    fun sameNamedContactsAreTreatedSeparately() {
        broken("小雨")
        fine("小雨")
        val vm = modelVm()
        vm.tighten()
        vm.save()
        await { vm.state.value.conflicts.isNotEmpty() }
        assertEquals(1, vm.state.value.conflicts.size)
        assertEquals(1, vm.state.value.existingConflicts.size)
        assertEquals(ReferencePurpose.CHAT, vm.state.value.conflicts.single().purpose)
    }

    @Test
    fun fixingAConflictDoesNotAsk() {
        broken("旧问题")
        val vm = modelVm()
        vm.edit(vm.state.value.model!!.copy(userConfig = ModelRules.encode(rules().copy(thinking = ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("retired", "旧档位")))))))
        vm.save()
        await { vm.state.value.savedMessage != null }
        assertEquals(R.string.llm_saved, vm.state.value.savedMessage)
    }

    // ---- 保存服务商 ----

    @Test
    fun savingOnlyAKeyWithExistingConflictDoesNotAsk() {
        broken("旧问题")
        val vm = providerVm()
        vm.setKey("sk-new-key-123456")
        vm.save()
        await { vm.state.value.savedCount == 1 }
        assertTrue(vm.state.value.conflicts.isEmpty())
        assertTrue(vm.state.value.savedWithConflicts)
        assertEquals("sk-new-key-123456", runBlocking { repo.apiKey(repo.config(id)!!) })
    }

    @Test
    fun savingWithoutConflictsSaysPlainSaved() {
        fine("小雨")
        val vm = providerVm()
        vm.save()
        await { vm.state.value.savedCount == 1 }
        assertFalse(vm.state.value.savedWithConflicts)
    }

    @Test
    fun compatibilityChangeThatBreaksAContactAsks() {
        // 通义方案不能发强度字段，用 low 的联系人这次才失效
        contact("新问题", "low", 0.5f)
        broken("旧问题")
        val vm = providerVm()
        vm.setCompatibility(isomeria.aichat.data.llm.Compatibility.QWEN)
        vm.save()
        await { vm.state.value.conflicts.isNotEmpty() }
        assertEquals(setOf("新问题"), names(vm.state.value.conflicts))
        assertEquals(setOf("旧问题"), names(vm.state.value.existingConflicts))
        assertEquals("GENERIC", runBlocking { repo.config(id)!!.compatibility })
        vm.confirm()
        await { vm.state.value.savedCount == 1 }
        assertEquals("QWEN", runBlocking { repo.config(id)!!.compatibility })
        assertTrue(vm.state.value.savedWithConflicts)
    }

    // ---- 默认模型 ----

    @Test
    fun defaultModelThatBreaksFollowingMemoryAsks() {
        fine("小雨")
        runBlocking { repo.saveModel(model("pending", null)) }
        val vm = providerVm()
        vm.setDefaultModel("pending")
        await { vm.state.value.conflicts.isNotEmpty() }
        assertEquals(ReferencePurpose.MEMORY, vm.state.value.conflicts.single().purpose)
        assertEquals("", runBlocking { repo.config(id)!!.defaultModel })
    }

    @Test
    fun defaultModelWithOnlyExistingConflictDoesNotAsk() {
        broken("旧问题")
        runBlocking { repo.saveModel(model("other")) }
        val vm = providerVm()
        vm.setDefaultModel("other")
        await { runBlocking { repo.config(id)!!.defaultModel } == "other" }
        assertTrue(vm.state.value.conflicts.isEmpty())
    }

    // ---- 获取列表 ----

    @Test
    fun fetchWithOnlyExistingConflictSavesAndMentionsIt() {
        broken("旧问题")
        fake.remote = listOf(RemoteModel("new-model", null, null))
        val vm = providerVm()
        vm.setKey("sk-new-key-123456")
        vm.test()
        await { vm.state.value.messageRes != null }
        assertEquals(R.string.connection_fetch_ok_conflict, vm.state.value.messageRes)
        assertTrue(vm.state.value.conflicts.isEmpty())
        assertEquals("sk-new-key-123456", runBlocking { repo.apiKey(repo.config(id)!!) })
        assertTrue(runBlocking { repo.model(id, "new-model") != null })
    }

    @Test
    fun fetchWithoutConflictsSaysPlainOk() {
        fine("小雨")
        fake.remote = listOf(RemoteModel("new-model", null, null))
        val vm = providerVm()
        vm.test()
        await { vm.state.value.messageRes != null }
        assertEquals(R.string.connection_fetch_ok, vm.state.value.messageRes)
    }

    @Test
    fun fetchThatReportsNarrowerRulesAsksAndKeepsNothingOnCancel() {
        runBlocking { repo.saveModel(model("m", null).copy(reportedConfig = ModelRules.encode(rules()))) }
        fine("新问题")
        fake.remote = listOf(RemoteModel("m", null, null, config = ModelConfig(temperature = TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0f, 0.5f)))))
        val vm = providerVm()
        vm.setKey("sk-new-key-123456")
        vm.test()
        await { vm.state.value.conflicts.isNotEmpty() }
        assertEquals(setOf("新问题"), names(vm.state.value.conflicts))
        vm.dismiss()
        await { vm.state.value.conflicts.isEmpty() }
        assertEquals("sk-old", runBlocking { repo.apiKey(repo.config(id)!!) })
        assertNull(vm.state.value.messageRes)
    }

    // ---- 弹窗 ----

    @Test
    fun dialogShowsNewConflictsAndLabelsExistingOnes() {
        val added = listOf(ModelReference("新问题", ReferencePurpose.CHAT, 1))
        val existing = listOf(ModelReference("旧问题", ReferencePurpose.MEMORY, 2))
        compose.setContent { AIChatTheme(darkTheme = false) { ConflictDialog(added, {}, existing) {} } }
        compose.onNodeWithText(app.getString(R.string.conflict_existing)).assertExists()
        compose.onNodeWithText(app.getString(R.string.reference_contact, "新问题", app.getString(R.string.reference_chat))).assertExists()
        compose.onNodeWithText(app.getString(R.string.reference_contact, "旧问题", app.getString(R.string.reference_memory))).assertExists()
    }

    @Test
    fun dialogWithOnlyNewConflictsHasNoExistingLabel() {
        compose.setContent { AIChatTheme(darkTheme = false) { ConflictDialog(listOf(ModelReference("新问题", ReferencePurpose.CHAT, 1)), {}) {} } }
        compose.onNodeWithText(app.getString(R.string.conflict_existing)).assertDoesNotExist()
    }

    @Test
    fun sameNamedContactsGetTheirOwnLines() {
        val refs = listOf(ModelReference("小雨", ReferencePurpose.CHAT, 1), ModelReference("小雨", ReferencePurpose.MEMORY, 2))
        assertEquals(2, groupReferences(refs).size)
    }

    @Test
    fun errorsDoNotDependOnThisReview() {
        // 没填 Key 的提示不受冲突影响
        broken("旧问题")
        fake.remote = emptyList()
        val failing = object : LlmProvider {
            override val id = ProviderProtocol.OPENAI
            override suspend fun listModels(endpoint: LlmEndpoint): List<RemoteModel> = throw LlmException(LlmError.AUTH)
            override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = emptyFlow()
        }
        val r = ProviderRepository(db, db.providerDao(), cipher, setOf(failing))
        val vm = ProviderEditViewModel(SavedStateHandle(mapOf("providerId" to id)), r)
        await { vm.state.value.loaded }
        vm.test()
        await { vm.state.value.messageRes != null }
        assertTrue(vm.state.value.messageIsError)
        assertTrue(vm.state.value.conflicts.isEmpty())
    }
}
