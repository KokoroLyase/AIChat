package isomeria.aichat.feature.settings

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.repo.ProviderRepository
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
