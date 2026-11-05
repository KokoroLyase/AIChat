package isomeria.aichat.feature.settings

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatRequest
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.llm.RemoteModel
import isomeria.aichat.data.repo.ProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 选默认模型、清除 Key 是立刻生效的操作，不能把正在填写但还没保存的内容换回旧值。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ProviderEditViewModelTest {
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }
    private lateinit var db: AppDatabase
    private lateinit var repo: ProviderRepository
    private var id = 0L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .addCallback(AppDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        repo = ProviderRepository(db, db.providerDao(), cipher, emptySet())
        id = repo.create("原名称", "https://saved.example/v1", "saved-key-123456", null, "GENERIC")
        repo.setDefaultModel(id, "")
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 等主线程和 Room 的后台线程都做完。 */
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时")
    }

    private class RecordingProvider(private val requests: MutableList<LlmEndpoint>) : LlmProvider {
        override val id = ProviderProtocol.OPENAI
        override suspend fun listModels(endpoint: LlmEndpoint): List<RemoteModel> {
            requests += endpoint
            return emptyList()
        }
        override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = emptyFlow()
    }

    private fun viewModel(): ProviderEditViewModel {
        val vm = ProviderEditViewModel(SavedStateHandle(mapOf("providerId" to id)), repo)
        await { vm.state.value.loaded }
        return vm
    }

    private fun ProviderEditViewModel.typeUnsavedForm() {
        setName("新名称")
        setBaseUrl("https://typed.example/v1")
        setKey("typed-key")
    }

    private fun assertUnsavedFormKept(vm: ProviderEditViewModel) {
        val s = vm.state.value
        assertEquals("新名称", s.name)
        assertEquals("https://typed.example/v1", s.baseUrl)
        assertEquals("typed-key", s.keyInput)
        assertTrue("仍然算有未保存的修改", s.dirty)
    }

    @Test
    fun choosingDefaultModelKeepsUnsavedInput() = runBlocking {
        repo.save(id, "原名称", "https://saved.example/v1", null, "m")
        val vm = viewModel()
        assertEquals("m", vm.state.value.defaultModel)
        vm.typeUnsavedForm()

        vm.setDefaultModel("")
        await { vm.state.value.defaultModel == "" }

        assertUnsavedFormKept(vm)
        val saved = repo.config(id)!!
        assertEquals("", saved.defaultModel)
        assertEquals("默认模型之外不写入", "原名称", saved.name)
        assertEquals("https://saved.example/v1", saved.baseUrl)
    }

    @Test
    fun clearingKeyKeepsUnsavedInput() = runBlocking {
        val vm = viewModel()
        assertTrue(vm.state.value.savedKeyMasked != null)
        vm.typeUnsavedForm()

        vm.clearKey()
        await { vm.state.value.savedKeyMasked == null }

        assertUnsavedFormKept(vm)
        val saved = repo.config(id)!!
        assertNull(saved.apiKeyEncrypted)
        assertEquals("清除 Key 之外不写入", "原名称", saved.name)
        assertEquals("https://saved.example/v1", saved.baseUrl)
    }

    @Test
    fun changedUrlWithoutNewKeyAsksBeforeSaving() = runBlocking {
        val vm = viewModel()
        vm.setBaseUrl("https://other.example/v1")

        vm.save()
        await { vm.state.value.keyMoveUrl != null }

        assertEquals("https://other.example/v1", vm.state.value.keyMoveUrl)
        assertEquals("确认前不写入新地址", "https://saved.example/v1", repo.config(id)!!.baseUrl)

        vm.confirmKeyMove()
        await { runBlocking { repo.config(id)!!.baseUrl } == "https://other.example/v1" }

        assertNull(vm.state.value.keyMoveUrl)
        assertEquals("确认后 Key 保留", "enc:saved-key-123456", repo.config(id)!!.apiKeyEncrypted)
    }

    @Test
    fun cancellingKeyMoveKeepsSavedAddressAndTypedForm() = runBlocking {
        val vm = viewModel()
        vm.setBaseUrl("https://other.example/v1")
        vm.save()
        await { vm.state.value.keyMoveUrl != null }

        vm.dismissKeyMove()

        assertNull(vm.state.value.keyMoveUrl)
        assertEquals("页面上的地址还在，可以接着改", "https://other.example/v1", vm.state.value.baseUrl)
        assertEquals("https://saved.example/v1", repo.config(id)!!.baseUrl)
        vm.confirmKeyMove()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("取消后不会再执行", "https://saved.example/v1", repo.config(id)!!.baseUrl)
    }

    @Test
    fun changedUrlAsksBeforeFetchingModels() = runBlocking {
        val requests = mutableListOf<LlmEndpoint>()
        repo = ProviderRepository(db, db.providerDao(), cipher, setOf(RecordingProvider(requests)))
        val vm = viewModel()
        vm.setBaseUrl("https://other.example/v1")

        vm.test()
        await { vm.state.value.keyMoveUrl != null }
        assertTrue("确认前不能把 Key 发出去", requests.isEmpty())
        assertTrue(!vm.state.value.testing)

        vm.confirmKeyMove()
        await { requests.isNotEmpty() }
        assertEquals("https://other.example/v1", requests.single().baseUrl)
    }

    @Test
    fun changedUrlAsksBeforeAddingManualModel() = runBlocking {
        val vm = viewModel()
        vm.setBaseUrl("https://other.example/v1")
        vm.setModelInput("manual-model")

        vm.addModel()
        await { vm.state.value.keyMoveUrl != null }
        assertEquals("https://saved.example/v1", repo.config(id)!!.baseUrl)

        vm.confirmKeyMove()
        await { runBlocking { repo.models(id) }.any { it.modelId == "manual-model" } }
        assertEquals("https://other.example/v1", repo.config(id)!!.baseUrl)
    }

    @Test
    fun keyMoveRuleOnlyMatchesARealChangeOfAddress() = runBlocking {
        val saved = repo.config(id)!!
        assertTrue(saved.keyMovesTo("https://other.example/v1", ""))
        assertTrue("路径区分大小写", saved.keyMovesTo("https://saved.example/V1", ""))
        assertTrue(!saved.keyMovesTo("https://saved.example/v1", ""))
        assertTrue("末尾斜杠不算改地址", !saved.keyMovesTo("https://saved.example/v1/", ""))
        assertTrue("域名大小写不算改地址", !saved.keyMovesTo("https://SAVED.example/v1", ""))
        assertTrue("填了新 Key 就不是旧 Key 跟着走", !saved.keyMovesTo("https://other.example/v1", "typed-key"))
        assertTrue("没有已保存的 Key", !saved.copy(apiKeyEncrypted = null).keyMovesTo("https://other.example/v1", ""))
    }

    @Test
    fun savingAfterClearingKeyStoresTypedForm() = runBlocking {
        val vm = viewModel()
        vm.typeUnsavedForm()
        vm.clearKey()
        await { vm.state.value.savedKeyMasked == null }

        vm.save()
        await { !vm.state.value.dirty }

        val saved = repo.config(id)!!
        assertEquals("新名称", saved.name)
        assertEquals("https://typed.example/v1", saved.baseUrl)
        assertEquals("enc:typed-key", saved.apiKeyEncrypted)
    }
}
