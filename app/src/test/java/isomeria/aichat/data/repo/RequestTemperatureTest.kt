package isomeria.aichat.data.repo

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.llm.Compatibility
import isomeria.aichat.data.llm.DeepSeekRequestBuilder
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.llm.ModelConfig
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.llm.NativeEffort
import isomeria.aichat.data.llm.OpenAiRequestBuilder
import isomeria.aichat.data.llm.RequestTemperature
import isomeria.aichat.data.llm.TemperatureMode
import isomeria.aichat.data.llm.TemperatureRule
import isomeria.aichat.data.llm.TemperatureSpec
import isomeria.aichat.data.llm.ThinkingMode
import isomeria.aichat.data.llm.ThinkingRule
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 联系人详情的「温度参数」必须和聊天请求里实际发的一致：
 * 每种情况都走真实的 [ProviderRepository.snapshot] 和请求构造器拼出请求体，再和详情页的取值比对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RequestTemperatureTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var repo: ProviderRepository
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ProviderRepository(db, db.providerDao(), cipher, emptySet())
    }

    @After
    fun tearDown() = db.close()

    private var nextId = 1L

    /** 建一个服务商、可选的模型和联系人，返回详情页取值和请求体里的温度（没发送为 null） */
    private fun case(
        protocol: String,
        compatibility: String,
        model: ProviderModel?,
        thinking: ThinkingLevel = ThinkingLevel.OFF,
        option: String? = null,
        temperature: Float,
    ): Pair<RequestTemperature, Float?> = runBlocking {
        val id = nextId++
        val config = ProviderConfig(id, ProviderKind.LLM, protocol, "p$id", "https://example.com/v1", "enc:sk", "", compatibility = compatibility)
        db.providerDao().insert(config)
        model?.let { db.providerDao().upsertModels(listOf(it.copy(providerId = id))) }
        db.contactDao().insert(
            Contact(id = id, name = "c$id", avatarColor = 0, providerId = id, model = model?.modelId ?: "m",
                thinking = thinking, thinkingOption = option, temperature = temperature, createdAt = 1, updatedAt = 1),
        )
        val stored = db.providerDao().get(id)!!
        val storedModel = model?.let { db.providerDao().model(id, it.modelId) }
        val shown = ModelRules.requestTemperature(stored, storedModel, option ?: ModelRules.legacyOption(thinking), temperature)
        val sent = try {
            val request = repo.snapshot(id, memory = false).request(emptyList())
            val body = if (protocol == ProviderProtocol.DEEPSEEK) DeepSeekRequestBuilder.build(request) else OpenAiRequestBuilder.build(request)
            body["temperature"]?.jsonPrimitive?.float
        } catch (_: LlmException) {
            Float.NaN // 请求发不出去
        }
        shown to sent
    }

    private fun assertMatches(shown: RequestTemperature, sent: Float?) {
        when (shown) {
            is RequestTemperature.Sent -> assertEquals(shown.value, sent!!, 0.0001f)
            is RequestTemperature.Fixed -> assertEquals(shown.value, sent!!, 0.0001f)
            RequestTemperature.NotSent -> assertEquals(null, sent)
            is RequestTemperature.Blocked -> assertTrue("配置有问题时请求不应发出", sent!!.isNaN())
        }
    }

    private fun legacyModel(thinking: Boolean) =
        ProviderModel(0, "m", "m", thinkingInput = if (thinking) Capability.YES else Capability.NO)

    private fun configured(rule: TemperatureRule, thinking: ThinkingRule = ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("high", "高")))) =
        ProviderModel(0, "n", "n", imageInputUserMark = false, legacy = false, userConfig = ModelRules.encode(ModelConfig(true, thinking, rule)))

    @Test
    fun legacyDeepSeek_sendsRoundedOnlyWhenThinkingOff() {
        val off = case(ProviderProtocol.DEEPSEEK, "LEGACY", legacyModel(true), ThinkingLevel.OFF, temperature = 1.34f)
        assertEquals(RequestTemperature.Sent(1.3f), off.first)
        assertMatches(off.first, off.second)
        val high = case(ProviderProtocol.DEEPSEEK, "LEGACY", legacyModel(true), ThinkingLevel.HIGH, temperature = 1.3f)
        assertEquals(RequestTemperature.NotSent, high.first)
        assertMatches(high.first, high.second)
        val noModel = case(ProviderProtocol.DEEPSEEK, "LEGACY", null, ThinkingLevel.MAX, temperature = 0.7f)
        assertEquals(RequestTemperature.NotSent, noModel.first)
        assertMatches(noModel.first, noModel.second)
    }

    @Test
    fun legacyModelWithoutThinking_sendsTemperatureEvenIfHighSaved() {
        val generic = case(ProviderProtocol.OPENAI, "LEGACY", legacyModel(false), ThinkingLevel.HIGH, temperature = 2.6f)
        assertEquals("思考被降为关闭，温度照发（限制到 2）", RequestTemperature.Sent(2f), generic.first)
        assertMatches(generic.first, generic.second)
        val genericNoModel = case(ProviderProtocol.OPENAI, "LEGACY", null, ThinkingLevel.HIGH, temperature = 0.5f)
        assertEquals(RequestTemperature.Sent(0.5f), genericNoModel.first)
        assertMatches(genericNoModel.first, genericNoModel.second)
    }

    @Test
    fun configuredModel_adjustableFixedUnsupportedAndThinkingOverride() {
        val rule = TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0f, 2f), TemperatureSpec(TemperatureMode.FIXED, fixed = 1f))
        val adjustable = case(ProviderProtocol.OPENAI, Compatibility.GENERIC.name, configured(rule), option = "off", temperature = 0.73f)
        assertEquals(RequestTemperature.Sent(0.73f), adjustable.first)
        assertMatches(adjustable.first, adjustable.second)

        val overridden = case(ProviderProtocol.OPENAI, Compatibility.GENERIC.name, configured(rule), option = "high", temperature = 0.73f)
        assertEquals("思考档位的覆盖规则：固定 1", RequestTemperature.Fixed(1f), overridden.first)
        assertMatches(overridden.first, overridden.second)

        val unsupported = TemperatureRule(TemperatureSpec(TemperatureMode.UNSUPPORTED))
        val none = case(ProviderProtocol.DEEPSEEK, Compatibility.DEEPSEEK.name, configured(unsupported), option = "off", temperature = 1.3f)
        assertEquals(RequestTemperature.NotSent, none.first)
        assertMatches(none.first, none.second)

        val thinkingOmits = TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE), TemperatureSpec(TemperatureMode.UNSUPPORTED))
        val thinkingOn = case(ProviderProtocol.DEEPSEEK, Compatibility.DEEPSEEK.name, configured(thinkingOmits), option = "high", temperature = 1.3f)
        assertEquals(RequestTemperature.NotSent, thinkingOn.first)
        assertMatches(thinkingOn.first, thinkingOn.second)
    }

    @Test
    fun invalidConfiguration_showsSavedValueAndRequestIsBlocked() {
        val narrow = TemperatureRule(TemperatureSpec(TemperatureMode.ADJUSTABLE, 0f, 1f))
        val outOfRange = case(ProviderProtocol.OPENAI, Compatibility.GENERIC.name, configured(narrow), option = "off", temperature = 1.3f)
        assertEquals(RequestTemperature.Blocked(1.3f), outOfRange.first)
        assertMatches(outOfRange.first, outOfRange.second)
        val missingModel = case(ProviderProtocol.OPENAI, Compatibility.GENERIC.name, null, option = "off", temperature = 0.9f)
        assertEquals(RequestTemperature.Blocked(0.9f), missingModel.first)
        assertMatches(missingModel.first, missingModel.second)
    }
}
