package isomeria.aichat.data.repo

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatRequest
import isomeria.aichat.data.llm.ChatRole
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.llm.RemoteModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 0.9.0 翻译在数据层：用联系人的聊天模型和温度、关掉思考，只发提示和这条文字；译文存在消息上，再点直接显示不再请求；
 * 隐藏保留译文；失败带原因、什么都不存；用量记为“翻译”。只用假的服务商，不会发出真实请求。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TranslationDataTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private val fake = FakeProvider()
    private lateinit var repo: TranslationRepository

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }

    private class FakeProvider : LlmProvider {
        override val id = ProviderProtocol.OPENAI
        val requests = mutableListOf<ChatRequest>()
        var reply = " Shall we go hiking this weekend? "
        var failure: LlmException? = null
        var usage: Pair<Int, Int>? = 30 to 8

        override suspend fun listModels(endpoint: LlmEndpoint) = emptyList<RemoteModel>()

        override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            failure?.let { throw it }
            emit(ChatEvent.Reasoning("思考不进译文"))
            emit(ChatEvent.Content(reply))
            usage?.let { emit(ChatEvent.Usage(it.first, it.second)) }
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val providers = ProviderRepository(db, db.providerDao(), cipher, setOf(fake))
        repo = TranslationRepository(db, providers, UsageRepository(db.usageDao()), TranslateLabels("翻成英语", "翻成中文"))
        runBlocking {
            val providerId = db.providerDao().insert(
                ProviderConfig(
                    kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "测试", baseUrl = "https://example.com/v1",
                    apiKeyEncrypted = "enc:sk-test", defaultModel = "cheap-model",
                ),
            )
            db.contactDao().insert(
                Contact(
                    id = 1, name = "小雨", avatarColor = 0, providerId = providerId, model = "chat-model",
                    thinking = ThinkingLevel.HIGH, temperature = 0.9f, createdAt = 1, updatedAt = 1,
                ),
            )
            db.conversationDao().upsert(Conversation(contactId = 1))
        }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insert(content: String, type: MessageType = MessageType.TEXT, role: Role = Role.ASSISTANT, status: MessageStatus = MessageStatus.SUCCESS) =
        db.messageDao().insert(Message(contactId = 1, role = role, type = type, content = content, status = status, createdAt = 100))

    @Test
    fun translate_chatModelContactTemperatureThinkingOff_onlyPromptAndText() = runBlocking {
        val id = insert("周末去爬山吗")
        assertNull(repo.translate(id))
        val request = fake.requests.single()
        assertEquals("聊天模型，不是记忆用的默认模型", "chat-model", request.model)
        assertEquals(ThinkingLevel.OFF, request.thinking)
        assertEquals(0.9f, request.temperature)
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), request.messages.map { it.role })
        assertEquals(listOf("翻成英语", "周末去爬山吗"), request.messages.map { it.plainText() })
        val saved = db.messageDao().get(id)!!
        assertEquals("Shall we go hiking this weekend?", saved.translation)
        assertTrue(saved.translationShown)
        assertEquals("原文不变", "周末去爬山吗", saved.content)
    }

    @Test
    fun englishAndVoiceTranscript_toChinese() = runBlocking {
        val id = insert("Are you free?", MessageType.VOICE, Role.USER)
        repo.translate(id)
        assertEquals("翻成中文", fake.requests.single().messages.first().plainText())
    }

    @Test
    fun translatedOnce_showingAgainDoesNotRequest_hideKeepsText() = runBlocking {
        val id = insert("周末去爬山吗")
        repo.translate(id)
        repo.hide(id)
        val hidden = db.messageDao().get(id)!!
        assertFalse(hidden.translationShown)
        assertEquals("Shall we go hiking this weekend?", hidden.translation)
        repo.translate(id)
        assertEquals(1, fake.requests.size)
        assertTrue(db.messageDao().get(id)!!.translationShown)
    }

    @Test
    fun usage_recordedAsTranslate_estimatedWhenProviderSendsNone() = runBlocking {
        repo.translate(insert("周末去爬山吗"))
        fake.usage = null
        repo.translate(insert("还去吗"))
        val rows = db.usageDao().listAll()
        assertEquals(setOf(UsagePurpose.TRANSLATE), rows.map { it.purpose }.toSet())
        assertEquals(2, rows.sumOf { it.requests })
        assertTrue(rows.sumOf { it.promptTokens } > 30)
    }

    @Test
    fun failure_returnsReason_storesNothing() = runBlocking {
        val id = insert("周末去爬山吗")
        fake.failure = LlmException(LlmError.NETWORK)
        assertEquals(FailReason.NETWORK, repo.translate(id))
        assertNull(db.messageDao().get(id)!!.translation)
        fake.failure = null
        fake.reply = "  "
        assertEquals(FailReason.EMPTY_REPLY, repo.translate(id))
        assertNull(db.messageDao().get(id)!!.translation)
    }

    @Test
    fun noApiKey_reportedAsNoKey_keyRejectedAsAuth() = runBlocking {
        val id = insert("周末去爬山吗")
        val config = db.providerDao().get(db.contactDao().get(1)!!.providerId)!!
        db.providerDao().update(config.copy(apiKeyEncrypted = null))
        assertEquals(FailReason.NO_API_KEY, repo.translate(id))
        assertTrue(fake.requests.isEmpty())
        db.providerDao().update(config)
        fake.failure = LlmException(LlmError.AUTH)
        assertEquals(FailReason.AUTH, repo.translate(id))
    }

    @Test
    fun nothingToTranslate_noRequest() = runBlocking {
        assertNull(repo.translate(insert("😀 123！")))
        assertNull(repo.translate(insert("", MessageType.VOICE, Role.USER, MessageStatus.SENDING)))
        assertNull(repo.translate(9_999))
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun canTranslate_textAndTranscribedVoiceWithLetters() {
        fun m(type: MessageType, content: String, status: MessageStatus = MessageStatus.SUCCESS) =
            Message(contactId = 1, role = Role.USER, type = type, content = content, status = status, createdAt = 1)
        assertTrue(TranslationRepository.canTranslate(m(MessageType.TEXT, "hi")))
        assertTrue(TranslationRepository.canTranslate(m(MessageType.VOICE, "语音转写")))
        assertTrue("等待回复中的也能翻译", TranslationRepository.canTranslate(m(MessageType.TEXT, "hi", MessageStatus.SENDING)))
        assertFalse(TranslationRepository.canTranslate(m(MessageType.TEXT, "👍👍")))
        assertFalse(TranslationRepository.canTranslate(m(MessageType.VOICE, "")))
        assertFalse(TranslationRepository.canTranslate(m(MessageType.IMAGE, "")))
        assertFalse(TranslationRepository.canTranslate(m(MessageType.CALL, "")))
        assertFalse(TranslationRepository.canTranslate(m(MessageType.TEXT, "hi", MessageStatus.PENDING)))
    }
}
