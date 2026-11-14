package isomeria.aichat.data.reply

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Capability
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.llm.ChatPart
import isomeria.aichat.data.llm.ChatRole
import isomeria.aichat.data.parse.ParseError
import isomeria.aichat.data.parse.ParseException
import isomeria.aichat.data.repo.FailReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 发文件（0.9.2）：回复前先解析这一轮的文件（模型不能看图时还有图片），解析出的文字进请求；
 * 没配解析服务、解析失败时这一轮标为失败、不请求模型；模型能看图时图片照旧发原图、不解析。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FileReplyTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = LocalDateTime.of(2026, 11, 14, 15, 30).atZone(zone).toInstant().toEpochMilli()
    private lateinit var db: AppDatabase
    private lateinit var harness: ReplyHarness

    @Before
    fun setUp() {
        listOf("chat_files", "parsed", "images").forEach { File(context.filesDir, it).deleteRecursively() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        harness = ReplyHarness(context, db, zone, now)
        runBlocking {
            val providers = db.providerDao()
            // 1：不能看图的模型；2：能看图的模型
            providers.insert(ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = ProviderProtocol.DEEPSEEK, name = "DeepSeek", baseUrl = "https://api.deepseek.com", apiKeyEncrypted = "enc:sk", defaultModel = "chat"))
            providers.upsertModels(listOf(ProviderModel(1, "chat", "Chat")))
            providers.insert(ProviderConfig(id = 2, kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "其他", baseUrl = "https://example.com/v1", apiKeyEncrypted = "enc:sk2", defaultModel = "vision"))
            providers.upsertModels(listOf(ProviderModel(2, "vision", "Vision", imageInput = Capability.YES)))
            db.contactDao().insert(Contact(id = 1, name = "小雨", avatarColor = 0, providerId = 1, model = "chat", systemPrompt = "", createdAt = 1, updatedAt = 1))
            db.contactDao().insert(Contact(id = 2, name = "阿哲", avatarColor = 0, providerId = 2, model = "vision", systemPrompt = "", createdAt = 2, updatedAt = 2))
            listOf(1L, 2L).forEach { db.conversationDao().upsert(Conversation(contactId = it)) }
        }
        harness.allOff(1)
        harness.allOff(2)
    }

    @After
    fun tearDown() = db.close()

    private fun configureParser() = runBlocking { harness.parsers.save(apiKey = "mineru-token", enabled = true) }

    private fun sendFile(contactId: Long, name: String, at: Long = now - 1_000): Long = runBlocking {
        val file = File(context.filesDir, "chat_files/${System.nanoTime()}.${name.substringAfterLast('.')}").apply { parentFile?.mkdirs(); writeText("原文件") }
        db.messageDao().insert(
            Message(contactId = contactId, role = Role.USER, type = MessageType.FILE, content = name, filePath = file.path, fileSize = 9, fileMime = "application/pdf", status = MessageStatus.SENDING, createdAt = at),
        )
    }

    private fun sendImage(contactId: Long, at: Long = now - 1_000): Long = runBlocking {
        val file = File(context.filesDir, "images/${System.nanoTime()}.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        db.messageDao().insert(
            Message(contactId = contactId, role = Role.USER, type = MessageType.IMAGE, imagePath = file.path, imageWidth = 10, imageHeight = 10, status = MessageStatus.SENDING, createdAt = at),
        )
    }

    private fun run(contactId: Long) = runBlocking { harness.engine.run(contactId) }

    private fun message(id: Long) = runBlocking { db.messageDao().get(id)!! }

    private fun lastUserText(): String = harness.provider.requests.last().messages.last { it.role == ChatRole.USER }.parts
        .filterIsInstance<ChatPart.Text>().joinToString("|") { it.text }

    @Test
    fun fileWithoutParser_failsWithParseUnavailable_noRequest() {
        val id = sendFile(1, "报告.pdf")
        run(1)
        assertTrue(harness.provider.requests.isEmpty())
        assertEquals(MessageStatus.FAILED, message(id).status)
        assertEquals(FailReason.PARSE_UNAVAILABLE.code, message(id).errorCode)
        // 关掉的解析服务也一样
        runBlocking { harness.parsers.save(apiKey = "t", enabled = false) }
        runBlocking { db.messageDao().setStatus(listOf(id), MessageStatus.SENDING, 0) }
        run(1)
        assertEquals(FailReason.PARSE_UNAVAILABLE.code, message(id).errorCode)
        assertTrue(harness.parser.parsed.isEmpty())
    }

    @Test
    fun fileParsed_textInRequest_storedAndNotParsedAgain() {
        configureParser()
        val id = sendFile(1, "报告.pdf")
        run(1)
        assertEquals(listOf("报告.pdf"), harness.parser.parsed)
        assertEquals("[文件：报告.pdf]\n解析：报告.pdf", lastUserText())
        val stored = message(id)
        assertEquals(MessageStatus.SUCCESS, stored.status)
        assertEquals("解析：报告.pdf", File(stored.parsedPath!!).readText())
        // 下一轮还带着全文，不再解析
        runBlocking { db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "总结一下", status = MessageStatus.SENDING, createdAt = now)) }
        run(1)
        assertEquals(1, harness.parser.parsed.size)
        val history = harness.provider.requests.last().messages.filter { it.role == ChatRole.USER }.joinToString("|") { it.plainText() }
        assertTrue(history, history.contains("[文件：报告.pdf]\n解析：报告.pdf"))
    }

    /** 系统没给扩展名的文件名：上传时用本机文件的扩展名（复制时按类型猜出来的），MinerU 按它认格式 */
    @Test
    fun fileNameWithoutExtension_uploadsWithStoredExtension() {
        configureParser()
        val file = File(context.filesDir, "chat_files/x.docx").apply { parentFile?.mkdirs(); writeText("x") }
        runBlocking {
            db.messageDao().insert(
                Message(contactId = 1, role = Role.USER, type = MessageType.FILE, content = "合同", filePath = file.path, fileSize = 1, status = MessageStatus.SENDING, createdAt = now - 1_000),
            )
        }
        run(1)
        assertEquals(listOf("合同.docx"), harness.parser.parsed)
        assertEquals("[文件：合同]\n解析：合同.docx", lastUserText())
    }

    @Test
    fun imageForModelWithoutVision_parsedWhenConfigured_elsePlaceholder() {
        // 没配解析：照旧按“[图片]”发
        val first = sendImage(1, now - 5_000)
        run(1)
        assertEquals("[图片]", lastUserText())
        assertNull(message(first).parsedPath)
        // 配了解析：这一轮的图片交给它识别，以前的不补
        configureParser()
        harness.parser.result = { "图里写着 你好" }
        val second = sendImage(1)
        run(1)
        assertEquals(listOf("image.jpg"), harness.parser.parsed)
        assertNull(message(first).parsedPath)
        assertNotNull(message(second).parsedPath)
        assertTrue(lastUserText(), lastUserText().endsWith("[图片：图里写着 你好]"))
        // 没识别出文字的
        harness.parser.result = { "" }
        sendImage(1, now)
        run(1)
        assertTrue(lastUserText(), lastUserText().endsWith("[图片（无文字）]"))
    }

    @Test
    fun imageForModelWithVision_sentAsImage_notParsed() {
        configureParser()
        sendImage(2)
        run(2)
        assertTrue(harness.parser.parsed.isEmpty())
        assertTrue(harness.provider.requests.last().messages.last().parts.single() is ChatPart.Image)
    }

    @Test
    fun parseFailure_marksRoundFailed_retryParsesAgain() {
        configureParser()
        harness.parser.failure = ParseException(ParseError.AUTH, "A0202")
        val file = sendFile(1, "报告.pdf", now - 2_000)
        val text = runBlocking { db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "看看", status = MessageStatus.SENDING, createdAt = now - 1_000)) }
        run(1)
        assertTrue(harness.provider.requests.isEmpty())
        assertEquals(FailReason.PARSE_AUTH.code, message(file).errorCode)
        assertEquals(MessageStatus.FAILED, message(text).status)
        // 别的失败原因
        mapOf(
            ParseError.TOO_LARGE to FailReason.PARSE_TOO_LARGE,
            ParseError.TIMEOUT to FailReason.PARSE_TIMEOUT,
            ParseError.QUOTA to FailReason.PARSE_QUOTA,
            ParseError.NETWORK to FailReason.NETWORK,
            ParseError.FAILED to FailReason.PARSE_FAILED,
        ).forEach { (error, reason) ->
            harness.parser.failure = ParseException(error)
            runBlocking { db.messageDao().setStatus(listOf(file, text), MessageStatus.SENDING, 0) }
            run(1)
            assertEquals(reason.code, message(file).errorCode)
        }
        // 重试：解析成功后照常请求
        harness.parser.failure = null
        runBlocking { db.messageDao().setStatus(listOf(file, text), MessageStatus.SENDING, 0) }
        run(1)
        assertEquals(1, harness.provider.requests.size)
        assertEquals("[文件：报告.pdf]\n解析：报告.pdf\n看看", lastUserText())
        assertEquals(MessageStatus.SUCCESS, message(file).status)
    }

    @Test
    fun missingOriginalFile_failsParse() {
        configureParser()
        val id = sendFile(1, "报告.pdf")
        File(message(id).filePath!!).delete()
        run(1)
        assertEquals(FailReason.PARSE_FAILED.code, message(id).errorCode)
        assertTrue(harness.parser.parsed.isEmpty())
    }
}
