package isomeria.aichat.data.repo

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.DeepSeekDefaults
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatRequest
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.llm.RemoteModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 多服务商：添加、删除规则、Key 处理、模型列表与标记。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ProviderRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var repo: ProviderRepository

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:") else null
    }

    private class Recording(override val id: String) : LlmProvider {
        val endpoints = mutableListOf<LlmEndpoint>()
        var remote: List<RemoteModel> = emptyList()
        override suspend fun listModels(endpoint: LlmEndpoint): List<RemoteModel> {
            endpoints += endpoint
            return remote
        }

        override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = emptyFlow()
    }

    private val deepSeek = Recording(ProviderProtocol.DEEPSEEK)
    private val generic = Recording(ProviderProtocol.OPENAI)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.seedCallback)
            .allowMainThreadQueries()
            .build()
        repo = ProviderRepository(db, db.providerDao(), cipher, setOf(deepSeek, generic))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun contact(providerId: Long) = db.contactDao().insert(
        Contact(name = "小雨", avatarColor = 0, providerId = providerId, model = "m", createdAt = 1, updatedAt = 1),
    )

    @Test
    fun seed_deepSeekWithThinkingModels() = runBlocking {
        val config = repo.list().single()
        assertEquals("DeepSeek", config.name)
        assertEquals(ProviderProtocol.DEEPSEEK, config.provider)
        assertTrue(repo.models(config.id).all { it.supportsThinking })
        assertTrue(repo.model(config.id, "deepseek-flash")!!.supportsImage)
        assertFalse(repo.model(config.id, "deepseek-v4-pro")!!.supportsImage)
    }

    @Test
    fun create_encryptsKey_andDetectsDeepSeekByHost() = runBlocking {
        val id = repo.create(" OpenRouter ", "https://openrouter.ai/api/v1", " sk-abc ")
        val config = repo.config(id)!!
        assertEquals("OpenRouter", config.name)
        assertEquals(ProviderProtocol.OPENAI, config.provider)
        assertEquals("enc:sk-abc", config.apiKeyEncrypted)
        assertEquals("", config.defaultModel)
        assertEquals(LlmEndpoint("https://openrouter.ai/api/v1", "sk-abc"), repo.endpoint(config))

        val second = repo.create("我的 DeepSeek", "https://api.deepseek.com/v1", null)
        assertEquals(ProviderProtocol.DEEPSEEK, repo.config(second)!!.provider)
        assertNull(repo.config(second)!!.apiKeyEncrypted)
        assertNull(repo.endpoint(repo.config(second)!!))
    }

    @Test
    fun save_nullKeepsKey_blankClears_keyIsPerProvider() = runBlocking {
        val first = repo.create("甲", "https://a.example.com/v1", "sk-a")
        val second = repo.create("乙", "https://b.example.com/v1", "sk-b")

        repo.save(first, "甲二", "https://a2.example.com/v1", null, "m1")
        assertEquals("enc:sk-a", repo.config(first)!!.apiKeyEncrypted)
        assertEquals("甲二", repo.config(first)!!.name)
        assertEquals("https://a2.example.com/v1", repo.config(first)!!.baseUrl)
        assertEquals("m1", repo.config(first)!!.defaultModel)

        repo.save(first, "甲二", "https://a2.example.com/v1", "", "m1")
        assertNull(repo.config(first)!!.apiKeyEncrypted)
        assertEquals("enc:sk-b", repo.config(second)!!.apiKeyEncrypted)
    }

    @Test
    fun save_deepSeekOnProxyKeepsProtocol() = runBlocking {
        val id = repo.list().single().id
        repo.save(id, "DeepSeek", "https://proxy.example.com", null, DeepSeekDefaults.DEFAULT_MODEL)
        assertEquals(ProviderProtocol.DEEPSEEK, repo.config(id)!!.provider)
    }

    @Test
    fun defaultLlm_prefersFirstWithKey() = runBlocking {
        assertEquals(DeepSeekDefaults.PROVIDER_ID, repo.defaultLlm().id)
        val other = repo.create("甲", "https://a.example.com/v1", "sk-a")
        assertEquals("DeepSeek 没有 Key，用有 Key 的", other, repo.defaultLlm().id)
    }

    @Test
    fun hasApiKey_anyProvider() = runBlocking {
        assertFalse(repo.hasApiKey.first())
        repo.create("甲", "https://a.example.com/v1", "sk-a")
        assertTrue(repo.hasApiKey.first())
    }

    @Test
    fun delete_refusedWhileContactsUseIt_orWhenLast() = runBlocking {
        val other = repo.create("甲", "https://a.example.com/v1", "sk-a")
        val contactId = contact(other)
        assertEquals(DeleteProviderResult.InUse(1), repo.delete(other))
        assertTrue(repo.config(other) != null)

        db.contactDao().delete(contactId)
        repo.addManualModel(other, "m")
        assertEquals(DeleteProviderResult.Deleted, repo.delete(other))
        assertNull(repo.config(other))
        assertTrue(repo.models(other).isEmpty())

        val last = repo.list().single().id
        assertEquals(DeleteProviderResult.LastOne, repo.delete(last))
    }

    @Test
    fun deepSeekCanBeDeleted_whenAnotherExists() = runBlocking {
        repo.create("甲", "https://a.example.com/v1", "sk-a")
        assertEquals(DeleteProviderResult.Deleted, repo.delete(DeepSeekDefaults.PROVIDER_ID))
    }

    @Test
    fun fetchModels_usesTypedKeyFirst_thenSavedKey_routesByProtocol() = runBlocking {
        val id = repo.create("甲", "https://a.example.com/v1", "sk-saved")
        val config = repo.config(id)!!
        repo.fetchModels(config, "https://new.example.com/v1", "  sk-typed ")
        repo.fetchModels(config, "https://new.example.com/v1", null)
        assertEquals(listOf("sk-typed", "sk-saved"), generic.endpoints.map { it.apiKey })
        assertEquals("https://new.example.com/v1", generic.endpoints.first().baseUrl)

        repo.fetchModels(null, "https://api.deepseek.com", "sk-new")
        assertEquals("sk-new", deepSeek.endpoints.single().apiKey)
    }

    @Test
    fun fetchModels_noKey_isAuthError() = runBlocking {
        val id = repo.create("甲", "https://a.example.com/v1", null)
        try {
            repo.fetchModels(repo.config(id), "https://a.example.com/v1", "")
            fail("没有 Key 不应该发请求")
        } catch (e: LlmException) {
            assertEquals(LlmError.AUTH, e.error)
        }
        assertTrue(generic.endpoints.isEmpty())
    }

    @Test
    fun applyRemoteModels_keepsMarksAndManualModels_setsDefaultWhenMissing() = runBlocking {
        val id = repo.create("甲", "https://a.example.com/v1", "sk-a")
        repo.applyRemoteModels(
            id,
            listOf(
                RemoteModel("vision", "Vision", supportsImage = true, supportsThinking = true),
                RemoteModel("plain", null, supportsImage = null, supportsThinking = null),
                RemoteModel("gone", null, supportsImage = null),
            ),
        )
        assertEquals("vision", repo.config(id)!!.defaultModel)
        val vision = repo.model(id, "vision")!!
        assertEquals("Vision", vision.displayName)
        assertEquals(Capability.YES, vision.imageInput)
        assertTrue(vision.supportsImage && vision.supportsThinking)
        val plain = repo.model(id, "plain")!!
        assertEquals("plain", plain.displayName)
        assertEquals(Capability.UNKNOWN, plain.imageInput)
        assertFalse(plain.supportsImage || plain.supportsThinking)

        repo.setImageMark(id, "plain", true)
        repo.setThinkingMark(id, "plain", true)
        repo.setThinkingMark(id, "vision", false)
        repo.addManualModel(id, " my-model ")

        // 再刷新一次：标记和手动模型还在，接口不再返回的 gone 没了，默认模型不变
        repo.applyRemoteModels(
            id,
            listOf(
                RemoteModel("plain", null, supportsImage = null),
                RemoteModel("vision", "Vision", supportsImage = true, supportsThinking = true),
            ),
        )
        assertEquals("vision", repo.config(id)!!.defaultModel)
        assertNull(repo.model(id, "gone"))
        assertTrue(repo.model(id, "plain")!!.supportsImage)
        assertTrue(repo.model(id, "plain")!!.supportsThinking)
        assertFalse("手动标记优先于接口", repo.model(id, "vision")!!.supportsThinking)
        assertTrue(repo.model(id, "my-model")!!.manual)
    }

    @Test
    fun applyRemoteModels_emptyListChangesNothing() = runBlocking {
        val before = repo.models(DeepSeekDefaults.PROVIDER_ID)
        repo.applyRemoteModels(DeepSeekDefaults.PROVIDER_ID, emptyList())
        assertEquals(before, repo.models(DeepSeekDefaults.PROVIDER_ID))
    }

    @Test
    fun applyRemoteModels_deepSeekFallsBackToKnownCapabilities() = runBlocking {
        repo.applyRemoteModels(
            DeepSeekDefaults.PROVIDER_ID,
            listOf(RemoteModel("deepseek-flash", null, supportsImage = null), RemoteModel("deepseek-new", null, supportsImage = null)),
        )
        val known = repo.model(DeepSeekDefaults.PROVIDER_ID, "deepseek-flash")!!
        assertEquals("DeepSeek V4.1 Flash", known.displayName)
        assertTrue(known.supportsImage && known.supportsThinking)
        assertFalse(repo.model(DeepSeekDefaults.PROVIDER_ID, "deepseek-new")!!.supportsThinking)
    }

    @Test
    fun manualModel_becomesDefault_andRemovalPicksNext() = runBlocking {
        val id = repo.create("甲", "https://a.example.com/v1", "sk-a")
        repo.addManualModel(id, "m1")
        repo.addManualModel(id, "m2")
        repo.addManualModel(id, "m1") // 重复不加
        repo.addManualModel(id, "   ") // 空的不加
        assertEquals(listOf("m1", "m2"), repo.models(id).map(ProviderModel::modelId))
        assertEquals("m1", repo.config(id)!!.defaultModel)

        repo.removeModel(id, "m1")
        assertEquals("m2", repo.config(id)!!.defaultModel)
        repo.removeModel(id, "m2")
        assertEquals("", repo.config(id)!!.defaultModel)
    }

    @Test
    fun supportsThinking_unknownModel_dependsOnProtocol() = runBlocking {
        val deepSeekConfig = repo.config(DeepSeekDefaults.PROVIDER_ID)!!
        assertTrue(repo.supportsThinking(deepSeekConfig, "not-in-list"))
        val other = repo.config(repo.create("甲", "https://a.example.com/v1", "k"))!!
        assertFalse(repo.supportsThinking(other, "not-in-list"))
    }

    @Test
    fun marks_overrideReportedCapabilities() {
        val model = ProviderModel(1, "m", "m", Capability.NO, thinkingInput = Capability.YES)
        assertFalse(model.supportsImage)
        assertTrue(model.supportsThinking)
        assertTrue(model.copy(imageInputUserMark = true).supportsImage)
        assertFalse(model.copy(thinkingUserMark = false).supportsThinking)
    }

    // ---- Base URL ----

    @Test
    fun baseUrl_onlyHttps() {
        assertEquals("https://api.example.com/v1", BaseUrlRules.normalize("  https://api.example.com/v1/  "))
        for (bad in listOf("http://api.example.com", "ftp://x.com", "api.example.com", "", "https://", "http://localhost:8080")) {
            assertNull(bad, BaseUrlRules.normalize(bad))
        }
    }

    @Test
    fun baseUrl_localHttpOnlyWhenAllowed() {
        assertEquals("http://localhost:8080/v1", BaseUrlRules.normalize("http://localhost:8080/v1", allowLocalHttp = true))
        assertEquals("http://127.0.0.1:8080", BaseUrlRules.normalize("http://127.0.0.1:8080", allowLocalHttp = true))
        assertNull(BaseUrlRules.normalize("http://192.168.1.2:8080", allowLocalHttp = true))
        assertNull(BaseUrlRules.normalize("http://localhost.evil.com", allowLocalHttp = true))
    }

    @Test
    fun baseUrl_sameUrlIgnoresTrailingSlashAndCase() {
        assertTrue(BaseUrlRules.isSameUrl("https://A.example.com/v1/", "https://a.example.com/v1"))
        assertFalse(BaseUrlRules.isSameUrl("https://a.example.com/v1", "https://a.example.com/v2"))
    }
}
