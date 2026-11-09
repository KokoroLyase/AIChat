package isomeria.aichat.data.memory

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.ContextLabels
import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Memory
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
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.llm.LlmProvider
import isomeria.aichat.data.llm.*
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.MemoryRepository
import isomeria.aichat.data.repo.PromptGlobalsSource
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.core.GlobalMemoryEntry
import isomeria.aichat.core.PromptGlobals
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.repo.UsageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

/** 自动总结记忆：什么时候触发、用哪个模型、起点、失败、合并、更早的聊天。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MemoryEngineTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private val fake = FakeProvider()
    private lateinit var engine: MemoryEngine
    private lateinit var providers: ProviderRepository
    private lateinit var builder: ContextBuilder
    private var providerId = 0L

    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = encoded.removePrefix("enc:")
    }

    private class FakeProvider : LlmProvider {
        override val id = ProviderProtocol.OPENAI
        val requests = mutableListOf<ChatRequest>()
        var reply = "这是一段总结"
        var failure: LlmException? = null
        var usage: Pair<Int, Int>? = 100 to 20
        /** 模拟等待模型期间发生的事 */
        var whileWaiting: (suspend () -> Unit)? = null

        override suspend fun listModels(endpoint: LlmEndpoint) = emptyList<RemoteModel>()

        override fun streamChat(endpoint: LlmEndpoint, request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            whileWaiting?.invoke()
            failure?.let { throw it }
            emit(ChatEvent.Reasoning("思考文本不能写入记忆"))
            emit(ChatEvent.Content(reply))
            usage?.let { emit(ChatEvent.Usage(it.first, it.second)) }
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        providers = ProviderRepository(db, db.providerDao(), cipher, setOf(fake))
        builder = ContextBuilder(
            ContextLabels(
                namePlaceholder = "{名称}",
                personaPlaceholderPrefix = "{在这里写人设",
                timeLine = "现在是 %1\$d年%2\$d月%3\$d日 %4\$s %5\$02d:%6\$02d。",
                nicknameLine = "对方的昵称是「%1\$s」。",
                weekdays = listOf("一", "二", "三", "四", "五", "六", "日"),
                imagePlaceholder = "[图片]",
                memoryHeader = "记忆：",
                memoryItem = "- [%1\$d年%2\$d月%3\$d日] %4\$s",
            ),
        )
        engine = MemoryEngine(
            db, providers, UsageRepository(db.usageDao()), builder,
            MemoryLabels("总结 %1\$s 和 %2\$s", "合并 %1\$s", "对方", "[图片]"),
        ) { "阿哲" }
        runBlocking {
            providerId = db.providerDao().insert(
                ProviderConfig(
                    kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "测试", baseUrl = "https://example.com/v1",
                    apiKeyEncrypted = "enc:sk-test", defaultModel = "cheap-model",
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun contact(
        id: Long = 1,
        budget: Int = 1000,
        auto: Boolean = true,
        cursor: Long? = 0,
        start: Long = 0,
        provider: Long = providerId,
    ) {
        db.contactDao().insert(
            Contact(
                id = id, name = "小雨", avatarColor = 0, providerId = provider, model = "chat-model",
                thinking = ThinkingLevel.HIGH, contextTokens = budget, autoMemory = auto, createdAt = 1, updatedAt = 1,
            ),
        )
        db.conversationDao().upsert(Conversation(contactId = id, memoryCursor = cursor, memoryStart = start))
    }

    private var nextTime = 1000L

    /** 插入 [count] 条，每条 10 个汉字 = 14 token（通用格式） */
    private suspend fun messages(count: Int, contactId: Long = 1, status: MessageStatus = MessageStatus.SUCCESS): List<Long> =
        List(count) { index ->
            nextTime += 1000
            db.messageDao().insert(
                Message(
                    contactId = contactId, role = if (index % 2 == 0) Role.USER else Role.ASSISTANT, type = MessageType.TEXT,
                    content = "一二三四五六七八九十", status = status, createdAt = nextTime,
                ),
            )
        }

    @Test
    fun belowThreshold_noRequest() = runBlocking {
        contact()
        messages(20) // 280 token，预算 1000
        engine.autoRun(1)
        assertTrue(fake.requests.isEmpty())
        assertTrue(db.memoryDao().listAll().isEmpty())
    }

    @Test
    fun aboveThreshold_summarizesOldestHalf_withProviderDefaultModelAndThinkingOff() = runBlocking {
        contact()
        messages(70) // 980 token，超过 80%
        engine.autoRun(1)

        val request = fake.requests.single()
        assertEquals("cheap-model", request.model)
        assertEquals(ThinkingLevel.OFF, request.thinking)
        assertEquals("总结 小雨 和 阿哲", request.messages[0].plainText())
        assertTrue(request.messages[1].plainText().startsWith("阿哲：一二三"))

        val memory = db.memoryDao().listByContact(1).single()
        assertEquals("这是一段总结", memory.content)
        assertFalse(memory.edited)
        // 进度前进到这批最后一条；再检查一次不会重复总结
        val all = db.messageDao().listByContact(1)
        assertEquals(all.first().createdAt, memory.fromTime)
        assertEquals(memory.toTime, db.conversationDao().get(1)!!.memoryCursor)
        assertTrue(memory.toTime < all.last().createdAt)

        fake.requests.clear()
        engine.autoRun(1)
        assertTrue(fake.requests.isEmpty())
    }

    // ---------- 0.5.0 全局内容 ----------

    private fun engineWithGlobals(globals: PromptGlobals) = MemoryEngine(
        db, providers, UsageRepository(db.usageDao()), builder,
        MemoryLabels("总结 %1\$s 和 %2\$s", "合并 %1\$s", "对方", "[图片]"),
        PromptGlobalsSource { globals },
    ) { "阿哲" }

    private fun globals(count: Int) = PromptGlobals(List(count) { GlobalMemoryEntry("类", "题", "全局记忆的内容甲乙丙丁") }, ReplyPreferences())

    /** 判断何时总结时按聊天请求的系统提示词算（含全局内容）；总结请求本身不带全局内容 */
    @Test
    fun globalContent_countsTowardTrigger_butSummaryRequestExcludesIt() = runBlocking {
        contact()
        messages(20) // 280 token，没有全局内容时不到 80%
        engineWithGlobals(PromptGlobals.NONE).autoRun(1)
        assertTrue(fake.requests.isEmpty())

        engineWithGlobals(globals(60)).autoRun(1) // 全局内容约 900 token，留给聊天的只剩下限 200
        val request = fake.requests.single()
        assertEquals(2, request.messages.size)
        assertEquals("总结 小雨 和 阿哲", request.messages[0].plainText())
        assertFalse(request.messages.any { it.plainText().contains("全局记忆的内容") })
        assertEquals(1, db.memoryDao().listByContact(1).size)
    }

    /** 全局内容远超预算：留给聊天的预算是负数，仍按 200 下限判断，不出错也不反复总结 */
    @Test
    fun hugeGlobalContent_memoryStillWorks() = runBlocking {
        contact()
        messages(20)
        val engine = engineWithGlobals(globals(5_000))
        engine.autoRun(1)
        assertEquals(1, fake.requests.size)
        engine.autoRun(1) // 剩下的不到下限的 80%，不再总结
        assertEquals(1, fake.requests.size)
    }

    @Test
    fun noDefaultModel_fallsBackToContactModel() = runBlocking {
        val config = db.providerDao().get(providerId)!!
        db.providerDao().update(config.copy(defaultModel = ""))
        contact()
        messages(70)
        engine.autoRun(1)
        assertEquals("chat-model", fake.requests.single().model)
    }

    @Test
    fun usageIsRecordedAsMemory() = runBlocking {
        contact()
        messages(70)
        engine.autoRun(1)
        val usage = db.usageDao().listAll().single()
        assertEquals(UsagePurpose.MEMORY, usage.purpose)
        assertEquals(1L, usage.contactId)
        assertEquals(100L, usage.promptTokens)
        assertEquals(20L, usage.completionTokens)
    }

    @Test
    fun usageMissingFromProvider_isEstimated() = runBlocking {
        fake.usage = null
        contact()
        messages(70)
        engine.autoRun(1)
        val usage = db.usageDao().listAll().single()
        assertTrue(usage.promptTokens > 0)
        assertTrue(usage.completionTokens > 0)
    }

    @Test
    fun autoMemoryOff_doesNothing() = runBlocking {
        contact(auto = false)
        messages(70)
        engine.autoRun(1)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun noApiKey_doesNothing() = runBlocking {
        db.providerDao().update(db.providerDao().get(providerId)!!.copy(apiKeyEncrypted = null))
        contact()
        messages(70)
        engine.autoRun(1)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun failure_isSilent_progressUnchanged_retriedNextTime() = runBlocking {
        contact()
        messages(70)
        fake.failure = LlmException(LlmError.SERVER)
        engine.autoRun(1) // 不抛异常
        assertTrue(db.memoryDao().listAll().isEmpty())
        assertEquals(0L, db.conversationDao().get(1)!!.memoryCursor)
        assertTrue(db.usageDao().listAll().isEmpty())

        fake.failure = null
        engine.autoRun(1)
        assertEquals(1, db.memoryDao().listAll().size)
    }

    @Test
    fun emptyReply_isTreatedAsFailure() = runBlocking {
        contact()
        messages(70)
        fake.reply = "   "
        engine.autoRun(1)
        assertTrue(db.memoryDao().listAll().isEmpty())
        assertEquals(0L, db.conversationDao().get(1)!!.memoryCursor)
    }

    @Test
    fun longReply_isTruncated() = runBlocking {
        contact()
        messages(70)
        fake.reply = "长".repeat(MemoryPolicy.CONTENT_MAX_LENGTH + 500)
        engine.autoRun(1)
        assertEquals(MemoryPolicy.CONTENT_MAX_LENGTH, db.memoryDao().listAll().single().content.length)
    }

    @Test
    fun batchWithOnlyFailedMessages_advancesWithoutRequest() = runBlocking {
        contact()
        messages(70, status = MessageStatus.FAILED)
        engine.autoRun(1)
        assertTrue(fake.requests.isEmpty())
        assertTrue(db.memoryDao().listAll().isEmpty())
        assertTrue(db.conversationDao().get(1)!!.memoryCursor!! > 0)
    }

    @Test
    fun progressChangedWhileWaiting_resultIsDiscarded() = runBlocking {
        contact()
        messages(70)
        fake.whileWaiting = {
            // 比如等待期间恢复了数据
            db.conversationDao().setMemoryRange(1, 999_999, 0)
        }
        engine.autoRun(1)
        assertTrue(db.memoryDao().listAll().isEmpty())
        assertEquals(999_999L, db.conversationDao().get(1)!!.memoryCursor)
    }

    // ---- 老聊天 ----

    @Test
    fun upgradedChat_startsFromContextWindow_withoutBackfillingOldMessages() = runBlocking {
        contact(budget = 1000, cursor = null)
        messages(500) // 7000 token，上下文窗口只放得下最后约 70 条
        engine.autoRun(1)

        val conversation = db.conversationDao().get(1)!!
        val all = db.messageDao().listByContact(1)
        assertTrue("老消息留给手动总结", conversation.memoryStart > all.first().createdAt)
        assertEquals(1, fake.requests.size)
        val sent = fake.requests.single().messages[1].plainText().lines().size
        assertTrue("只总结窗口内的一半，实际 $sent 条", sent < 60)
        val memory = db.memoryDao().listAll().single()
        assertEquals(conversation.memoryStart, memory.fromTime)
    }

    @Test
    fun upgradedShortChat_hasNoBacklog() = runBlocking {
        contact(budget = 4000, cursor = null)
        messages(10)
        engine.autoRun(1)
        val conversation = db.conversationDao().get(1)!!
        assertEquals(0L, conversation.memoryStart)
        assertTrue(fake.requests.isEmpty())
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun memoryRepository() = MemoryRepository(db.memoryDao(), db.conversationDao(), db.messageDao(), engine, appScope)

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("等待超时")
            Thread.sleep(10)
        }
    }

    @Test
    fun manualSummary_doesNotDependOnThePage_andCannotBeStartedTwice() = runBlocking {
        contact(budget = 1000, cursor = null)
        messages(600)
        val gate = CompletableDeferred<Unit>()
        fake.whileWaiting = { gate.await() }
        val results = java.util.concurrent.CopyOnWriteArrayList<BacklogResult>()
        val repo = memoryRepository()

        // 模拟页面：它的协程作用域结束后，总结仍要继续
        val pageScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        pageScope.launch { repo.startBacklogSummary(1, { results += it }, { throw it }) }.join()
        pageScope.cancel()
        awaitUntil { fake.requests.size == 1 }
        assertTrue(engine.tasks.states.value[1]!!.manual)

        // 回到页面再点一次：不发第二个请求
        repo.startBacklogSummary(1, { results += it }, { throw it })
        Thread.sleep(100)
        assertEquals(1, fake.requests.size)

        gate.complete(Unit)
        awaitUntil { engine.tasks.states.value[1]?.manual == false }
        assertEquals(listOf(BacklogResult.DONE), results.toList())
        assertEquals(1, db.memoryDao().listByContact(1).size)
        assertTrue(engine.tasks.states.value[1]!!.lastSuccess != null)
    }

    @Test
    fun manualSummary_failureIsReportedAndCanBeRetried() = runBlocking {
        contact(budget = 1000, cursor = null)
        messages(600)
        fake.failure = LlmException(LlmError.SERVER, 500)
        val failures = java.util.concurrent.CopyOnWriteArrayList<LlmException>()
        val results = java.util.concurrent.CopyOnWriteArrayList<BacklogResult>()
        val repo = memoryRepository()

        repo.startBacklogSummary(1, { results += it }, { failures += it })
        awaitUntil { failures.isNotEmpty() && engine.tasks.states.value[1]?.manual == false }
        assertEquals(LlmError.SERVER, failures.single().error)
        assertEquals(FailReason.SERVER, engine.tasks.states.value[1]!!.failure)

        fake.failure = null
        repo.startBacklogSummary(1, { results += it }, { failures += it })
        awaitUntil { results.isNotEmpty() && engine.tasks.states.value[1]?.manual == false }
        assertEquals(listOf(BacklogResult.DONE), results.toList())
        assertEquals(1, failures.size)
    }

    @Test
    fun backlog_summarizesBackwardsInBatches_untilNothingLeft() = runBlocking {
        contact(budget = 1000, cursor = null)
        messages(600) // 8400 token，窗口外还有约 7400 token：一批 6000，要两批
        assertEquals(BacklogResult.DONE, engine.summarizeBacklog(1))
        val afterFirst = db.conversationDao().get(1)!!
        assertTrue(afterFirst.memoryStart > 0)
        assertEquals(1, db.memoryDao().listAll().size)

        assertEquals(BacklogResult.DONE, engine.summarizeBacklog(1))
        val afterSecond = db.conversationDao().get(1)!!
        assertEquals("没有更早的聊天了", 0L, afterSecond.memoryStart)
        val memories = db.memoryDao().listByContact(1)
        assertEquals(2, memories.size)
        assertEquals(db.messageDao().listByContact(1).first().createdAt, memories.first().fromTime)
        // 两批首尾相接，不重复也不漏
        assertTrue(memories[0].toTime < memories[1].fromTime)

        assertEquals(BacklogResult.NOTHING, engine.summarizeBacklog(1))
        assertEquals(2, fake.requests.size)
    }

    @Test
    fun backlog_failureThrows_andKeepsProgress() = runBlocking {
        contact(budget = 1000, cursor = null)
        messages(500)
        fake.failure = LlmException(LlmError.AUTH)
        try {
            engine.summarizeBacklog(1)
            fail("手动总结失败要告诉界面")
        } catch (e: LlmException) {
            assertEquals(LlmError.AUTH, e.error)
        }
        assertTrue(db.memoryDao().listAll().isEmpty())
        assertTrue(db.conversationDao().get(1)!!.memoryStart > 0)
    }

    @Test
    fun backlog_noKey() = runBlocking {
        db.providerDao().update(db.providerDao().get(providerId)!!.copy(apiKeyEncrypted = null))
        contact(start = 5000)
        assertEquals(BacklogResult.NO_API_KEY, engine.summarizeBacklog(1))
    }

    // ---- 合并 ----

    private suspend fun memoryRow(content: String, time: Long, edited: Boolean = false) = db.memoryDao().insert(
        Memory(contactId = 1, content = content, fromTime = time, toTime = time + 10, edited = edited, createdAt = time, updatedAt = time),
    )

    @Test
    fun overBudget_mergesOldestUneditedIntoOne_keepsEdited() = runBlocking {
        contact(budget = 1000) // 记忆预算 250 token
        memoryRow("改过的记忆", 100, edited = true)
        val text = "一".repeat(100)
        memoryRow(text, 200)
        memoryRow(text, 300)
        memoryRow(text, 400)
        fake.reply = "合并后的记忆"
        engine.autoRun(1)

        val request = fake.requests.single()
        assertEquals("合并 小雨", request.messages[0].plainText())
        val rows = db.memoryDao().listByContact(1)
        assertEquals(listOf("改过的记忆", "合并后的记忆", text), rows.map { it.content })
        val merged = rows[1]
        assertEquals("覆盖合并前的整段时间", 200L, merged.fromTime)
        assertEquals(310L, merged.toTime)
        assertFalse(merged.edited)
    }

    @Test
    fun withinBudget_noMerge() = runBlocking {
        contact(budget = 1000)
        memoryRow("一条记忆", 100)
        memoryRow("又一条记忆", 200)
        engine.autoRun(1)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun memoryEditedWhileMerging_mergeIsAbandoned() = runBlocking {
        contact(budget = 1000)
        val text = "一".repeat(150)
        val first = memoryRow(text, 200)
        memoryRow(text, 300)
        fake.whileWaiting = {
            db.memoryDao().update(db.memoryDao().get(first)!!.copy(content = "用户刚改的", edited = true))
        }
        engine.autoRun(1)
        val rows = db.memoryDao().listByContact(1)
        assertEquals(2, rows.size)
        assertEquals("用户刚改的", rows[0].content)
    }

    @Test fun memoryUsesIndependentNativeEffortAndOnlySavesBody() = runBlocking {
        contact()
        messages(70)
        val old = db.providerDao().get(providerId)!!
        db.providerDao().update(old.copy(compatibility = "DEEPSEEK"))
        db.providerDao().upsertModels(listOf(ProviderModel(providerId, "memory-model", "记忆", imageInputUserMark = false, legacy = false,
            userConfig = ModelRules.encode(ModelConfig(true, ThinkingRule(ThinkingMode.SWITCH, listOf(NativeEffort("low", "低"))), TemperatureRule(TemperatureSpec(TemperatureMode.FIXED, fixed = 1f)))))))
        db.contactDao().update(db.contactDao().get(1)!!.copy(memoryModel = "memory-model", memoryThinkingOption = "low"))
        engine.autoRun(1)
        assertEquals("memory-model", fake.requests.single().model)
        assertEquals("low", fake.requests.single().parameters!!.option)
        assertEquals(1f, fake.requests.single().parameters!!.temperature)
        assertEquals(fake.reply, db.memoryDao().listAll().single().content)
        assertFalse(db.memoryDao().listAll().single().content.contains("思考文本"))
    }

    @Test fun invalidMemoryConfigDoesNotRequestOrAdvanceAndReportsSafeFailure() = runBlocking {
        contact(); messages(70)
        db.providerDao().update(db.providerDao().get(providerId)!!.copy(compatibility = "DEEPSEEK"))
        engine.autoRun(1)
        assertTrue(fake.requests.isEmpty())
        assertEquals(0L, db.conversationDao().get(1)!!.memoryCursor)
        assertEquals(isomeria.aichat.data.repo.FailReason.CONFIGURATION, engine.tasks.states.value[1]!!.failure)
    }

    @Test fun restoreDuringNetworkInvalidatesResultEvenWhenCursorLooksIdentical() = runBlocking {
        contact(); messages(70)
        fake.whileWaiting = { db.restoreGeneration.incrementAndGet() }
        engine.autoRun(1)
        assertTrue(db.memoryDao().listAll().isEmpty())
        assertEquals(0L, db.conversationDao().get(1)!!.memoryCursor)
    }

    @Test fun activeRestoreRejectsLateResultsAndDoesNotInitializeOldProgress() = runBlocking {
        contact(); messages(70)
        fake.whileWaiting = { db.restoreInProgress.incrementAndGet() }
        engine.autoRun(1)
        assertTrue(db.memoryDao().listAll().isEmpty())
        assertEquals(0L, db.conversationDao().get(1)!!.memoryCursor)
        assertNull(engine.tasks.states.value[1]!!.lastSuccess)
        db.conversationDao().setMemoryRange(1, null, 0)
        val before = fake.requests.size
        engine.autoRun(1)
        assertEquals(before, fake.requests.size)
        assertNull(db.conversationDao().get(1)!!.memoryCursor)
    }

    @Test
    fun contactDeleted_doesNothing() = runBlocking {
        engine.autoRun(404)
        assertTrue(fake.requests.isEmpty())
        assertNull(db.conversationDao().get(404))
    }
}
