package isomeria.aichat.core

import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Memory
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.llm.ChatPart
import isomeria.aichat.data.llm.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ContextBuilderTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val builder = ContextBuilder(
        ContextLabels(
            namePlaceholder = "{名称}",
            personaPlaceholderPrefix = "{在这里写人设",
            nicknameLine = "对方的昵称是「%1\$s」。",
            weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"),
            imagePlaceholder = "[图片]",
            memoryHeader = "下面是记忆：",
            memoryItem = "- [%1\$d年%2\$d月%3\$d日] %4\$s",
        ),
        zone,
    )
    private val now = LocalDateTime.of(2026, 11, 1, 23, 40).atZone(zone).toInstant().toEpochMilli()

    private val template = """
        你是{名称}，正在用微信和对方聊天。
        不使用 Markdown。
        {在这里写人设：性格、和对方的关系、说话习惯、是否使用 emoji}
    """.trimIndent()

    private fun contact(prompt: String = template) = Contact(
        id = 1,
        name = "小雨",
        avatarColor = 0,
        providerId = 1,
        model = "deepseek-flash",
        systemPrompt = prompt,
        createdAt = 0,
        updatedAt = 0,
    )

    private var nextId = 1L
    private fun text(role: Role, content: String, group: String? = null, reasoning: String? = null) = Message(
        id = nextId++,
        contactId = 1,
        role = role,
        type = MessageType.TEXT,
        content = content,
        reasoning = reasoning,
        status = MessageStatus.SUCCESS,
        replyGroup = group,
        createdAt = nextId * 1000,
    )

    private fun image(path: String) = Message(
        id = nextId++,
        contactId = 1,
        role = Role.USER,
        type = MessageType.IMAGE,
        imagePath = path,
        imageWidth = 10,
        imageHeight = 10,
        status = MessageStatus.SUCCESS,
        createdAt = nextId * 1000,
    )

    private val loader: (String) -> String? = { path -> "data:image/jpeg;base64,$path" }

    @Test
    fun systemPrompt_replacesName_dropsUntouchedPersonaLine_appendsTimeAndNickname() {
        val prompt = builder.systemPrompt(contact(), "阿哲")!!
        assertEquals(
            "你是小雨，正在用微信和对方聊天。\n不使用 Markdown。\n\n对方的昵称是「阿哲」。",
            prompt,
        )
    }

    @Test
    fun systemPrompt_keepsEditedPersona() {
        val edited = template.replace("{在这里写人设：性格、和对方的关系、说话习惯、是否使用 emoji}", "性格温柔，爱用 emoji")
        val prompt = builder.systemPrompt(contact(edited), "")!!
        assertTrue(prompt.contains("性格温柔，爱用 emoji"))
        assertFalse("没有昵称时不附带昵称", prompt.contains("昵称"))
    }

    @Test
    fun emptyPrompt_sendsNoSystemMessage() {
        assertNull(builder.systemPrompt(contact(""), "阿哲"))
        val messages = builder.build(contact("  "), listOf(text(Role.USER, "hi")), true, "阿哲", now, loadImage = loader)
        assertEquals(1, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
    }

    private fun memory(content: String) = Memory(
        contactId = 1, content = content, fromTime = now - 1000, toTime = now - 500, createdAt = now, updatedAt = now,
    )

    @Test
    fun memories_comeAfterPersona_beforeTime() {
        val prompt = builder.systemPrompt(contact("人设"), "", listOf(memory("喜欢拿铁"), memory("养了猫")))!!
        assertEquals(
            "人设\n\n下面是记忆：\n- [2026年11月1日] 喜欢拿铁\n- [2026年11月1日] 养了猫",
            prompt,
        )
    }

    /** 0.7.2：核心记忆在前、不带日期，有近期记忆时加小标题 */
    @Test
    fun coreMemory_comesFirst_withoutDate_thenRecent() {
        val labeled = ContextBuilder(
            ContextLabels(
                namePlaceholder = "{名称}", personaPlaceholderPrefix = "{在这里写人设",
                nicknameLine = "对方的昵称是「%1\$s」。",
                weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"), imagePlaceholder = "[图片]",
                memoryHeader = "下面是记忆：", memoryItem = "- [%1\$d年%2\$d月%3\$d日] %4\$s",
                memoryCore = "[一直记着的事]\n%1\$s", memoryRecentHeader = "[最近的事]",
            ),
            java.time.ZoneId.of("Asia/Shanghai"),
        )
        val core = memory("  学设计\n喜欢猫  ").copy(core = true)
        val prompt = labeled.systemPrompt(contact(""), "", listOf(core, memory("养了猫")))!!
        assertEquals(
            "下面是记忆：\n[一直记着的事]\n学设计\n喜欢猫\n[最近的事]\n- [2026年11月1日] 养了猫",
            prompt,
        )
        val onlyCore = labeled.systemPrompt(contact(""), "", listOf(core))!!
        assertEquals("下面是记忆：\n[一直记着的事]\n学设计\n喜欢猫", onlyCore)
    }

    @Test
    fun emptyPersonaWithMemories_stillSendsSystemMessage() {
        val prompt = builder.systemPrompt(contact(""), "", listOf(memory("喜欢拿铁")))!!
        assertEquals("下面是记忆：\n- [2026年11月1日] 喜欢拿铁", prompt)
    }

    @Test
    fun build_includesMemoriesInSystemMessage() {
        val messages = builder.build(contact(), listOf(text(Role.USER, "hi")), true, "", now, listOf(memory("喜欢拿铁")), loader)
        assertEquals(ChatRole.SYSTEM, messages[0].role)
        assertTrue(messages[0].plainText().contains("喜欢拿铁"))
    }

    @Test
    fun consecutiveMessages_areMerged_andSplitRepliesRejoinedWithBlankLine() {
        val history = listOf(
            text(Role.USER, "在吗"),
            text(Role.USER, "想你了"),
            text(Role.ASSISTANT, "在呀", group = "g1", reasoning = "思考内容不应回传"),
            text(Role.ASSISTANT, "怎么啦", group = "g1"),
            text(Role.USER, "没事"),
        )
        val messages = builder.build(contact(), history, true, "", now, loadImage = loader)
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), messages.map { it.role })
        assertEquals("在吗\n想你了", messages[1].plainText())
        assertEquals("在呀\n\n怎么啦", messages[2].plainText())
        assertFalse(messages.any { it.plainText().contains("思考内容") })
    }

    @Test
    fun onlyLastThreeImagesCarryData() {
        val history = listOf(image("p1"), image("p2"), image("p3"), image("p4"), text(Role.USER, "看看这些"))
        val user = builder.build(contact(""), history, true, "", now, loadImage = loader).single()
        assertEquals(ChatRole.USER, user.role)
        val parts = user.parts
        // 第一张替换为文字，后三张带原图，最后是文字
        assertEquals(ChatPart.Text("[图片]"), parts[0])
        assertEquals(
            listOf("data:image/jpeg;base64,p2", "data:image/jpeg;base64,p3", "data:image/jpeg;base64,p4"),
            parts.filterIsInstance<ChatPart.Image>().map { it.dataUrl },
        )
        assertEquals(ChatPart.Text("看看这些"), parts.last())
    }

    @Test
    fun modelWithoutImageSupport_getsPlaceholdersOnly() {
        val history = listOf(image("p1"), text(Role.USER, "这是什么"))
        val user = builder.build(contact(""), history, false, "", now, loadImage = loader).single()
        assertFalse(user.hasImage)
        assertEquals("[图片]\n这是什么", user.plainText())
    }

    @Test
    fun unreadableImage_becomesPlaceholder() {
        val history = listOf(image("missing"))
        val user = builder.build(contact(""), history, true, "", now, loadImage = { null }).single()
        assertEquals("[图片]", user.plainText())
    }

    @Test
    fun blankTextMessages_areSkipped() {
        val history = listOf(text(Role.USER, "  "), text(Role.USER, "你好"))
        val user = builder.build(contact(""), history, true, "", now, loadImage = loader).single()
        assertEquals("你好", user.plainText())
    }

    // ---------- 0.5.0 全局记忆、回复偏好要求 ----------

    private val full = ContextBuilder(
        ContextLabels(
            namePlaceholder = "{名称}",
            personaPlaceholderPrefix = "{在这里写人设",
            nicknameLine = "对方的昵称是「%1\$s」。",
            weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"),
            imagePlaceholder = "[图片]",
            memoryHeader = "下面是记忆：",
            memoryItem = "- [%1\$d年%2\$d月%3\$d日] %4\$s",
            globalMemoryHeader = "全局记忆：",
            globalMemoryTopic = "[%1\$s / %2\$s]",
            globalMemoryItem = "- %1\$s",
            replyStyle = "回复方式：每次 %1\$s 条，每条 %2\$d 字左右，用换行分隔。",
            countRange = "%1\$d～%2\$d",
        ),
        zone,
    )

    private val entries = listOf(
        GlobalMemoryEntry("个人信息", "基本情况", "住在杭州"),
        GlobalMemoryEntry("个人信息", "基本情况", "  养了一只猫  "),
        GlobalMemoryEntry("个人信息", "饮食", "不吃香菜"),
        GlobalMemoryEntry("工作", "职业", "后端开发"),
        GlobalMemoryEntry("工作", "职业", "   "),
    )

    @Test
    fun globals_orderIsGlobalMemory_persona_memories_replyStyle_time() {
        val prompt = full.systemPrompt(contact("人设"), "阿哲", listOf(memory("喜欢拿铁")), PromptGlobals(entries, ReplyPreferences()))!!
        assertEquals(
            "全局记忆：\n[个人信息 / 基本情况]\n- 住在杭州\n- 养了一只猫\n[个人信息 / 饮食]\n- 不吃香菜\n[工作 / 职业]\n- 后端开发" +
                "\n\n人设" +
                "\n\n下面是记忆：\n- [2026年11月1日] 喜欢拿铁" +
                "\n\n回复方式：每次 1～3 条，每条 16 字左右，用换行分隔。" +
                "\n\n对方的昵称是「阿哲」。",
            prompt,
        )
    }

    @Test
    fun globalsNone_isExactlyTheOldPrompt() {
        val old = builder.systemPrompt(contact(), "阿哲", listOf(memory("喜欢拿铁")))
        assertEquals(old, full.systemPrompt(contact(), "阿哲", listOf(memory("喜欢拿铁")), PromptGlobals.NONE))
        assertNull(full.systemPrompt(contact(""), "阿哲", emptyList(), PromptGlobals(emptyList(), null)))
        // 只有空白条目的全局记忆不算内容
        assertNull(full.systemPrompt(contact(""), "", emptyList(), PromptGlobals(listOf(GlobalMemoryEntry("a", "b", "  ")), null)))
    }

    @Test
    fun newContactWithoutPersona_stillGetsGlobalMemory() {
        val prompt = full.systemPrompt(contact(""), "", emptyList(), PromptGlobals(entries.take(1), null))!!
        assertEquals("全局记忆：\n[个人信息 / 基本情况]\n- 住在杭州", prompt)
        val messages = full.build(contact(""), listOf(text(Role.USER, "hi")), true, "", now, loadImage = loader, globals = PromptGlobals(entries.take(1), null))
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER), messages.map { it.role })
    }

    @Test
    fun turningOffOnePart_removesOnlyThatPart() {
        val both = full.systemPrompt(contact("人设"), "", emptyList(), PromptGlobals(entries, ReplyPreferences()))!!
        val noGlobal = full.systemPrompt(contact("人设"), "", emptyList(), PromptGlobals(emptyList(), ReplyPreferences()))!!
        val noStyle = full.systemPrompt(contact("人设"), "", emptyList(), PromptGlobals(entries, null))!!
        val neither = full.systemPrompt(contact("人设"), "", emptyList(), PromptGlobals.NONE)!!
        assertEquals(both.replace(full.globalMemoryBlock(entries) + "\n\n", ""), noGlobal)
        // 0.9.2 起回复要求是系统提示词的最后一段（时间移到了附注里）
        assertEquals(both.removeSuffix("\n\n" + full.replyStyleBlock(ReplyPreferences())), noStyle)
        assertEquals("人设", neither)
    }

    @Test
    fun replyStyle_usesSingleCountWhenMinEqualsMax_andNormalizes() {
        assertEquals("回复方式：每次 3 条，每条 20 字左右，用换行分隔。", full.replyStyleBlock(ReplyPreferences(minCount = 3, maxCount = 3, targetLength = 20)))
        // 最多小于最少时按最少算
        assertEquals("回复方式：每次 4 条，每条 16 字左右，用换行分隔。", full.replyStyleBlock(ReplyPreferences(minCount = 4, maxCount = 2)))
    }

    // ---------- 0.9.0：引用 ----------

    private val quoting = ContextBuilder(
        ContextLabels(
            namePlaceholder = "{名称}",
            personaPlaceholderPrefix = "{在这里写人设",
            nicknameLine = "对方的昵称是「%1\$s」。",
            weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"),
            imagePlaceholder = "[图片]",
            memoryHeader = "下面是记忆：",
            memoryItem = "- [%1\$d年%2\$d月%3\$d日] %4\$s",
            quoteOther = "（回复你之前说的“%1\$s”）",
            quoteSelf = "（接着我之前说的“%1\$s”）",
        ),
        zone,
    )

    private fun quoted(message: Message, role: Role, text: String) =
        message.copy(quoteId = 1, quoteRole = role, quoteType = MessageType.TEXT, quoteText = text)

    @Test
    fun quote_onlyUserMessagesGetTheLine_voiceWithoutTranscriptStillSkipped() {
        val history = listOf(
            quoted(text(Role.ASSISTANT, "联系人的消息不加"), Role.USER, "x"),
            quoted(text(Role.USER, "早"), Role.ASSISTANT, "在呀"),
            quoted(text(Role.USER, "").copy(type = MessageType.VOICE), Role.ASSISTANT, "没转写的语音"),
            quoted(text(Role.USER, "语音的转写").copy(type = MessageType.VOICE), Role.USER, "早"),
        )
        val messages = quoting.build(contact(""), history, true, "", now, loadImage = loader)
        assertEquals(listOf(ChatRole.ASSISTANT, ChatRole.USER), messages.map { it.role })
        assertEquals("联系人的消息不加", messages[0].plainText())
        assertEquals("（回复你之前说的“在呀”）\n早\n（接着我之前说的“早”）\n语音的转写", messages[1].plainText())
    }

    @Test
    fun quote_withoutLabels_orWithoutQuote_isTheOriginalText() {
        val message = quoted(text(Role.USER, "早"), Role.ASSISTANT, "在呀")
        assertEquals("早", builder.textOf(message))
        assertEquals("早", quoting.textOf(text(Role.USER, "早")))
    }

    @Test
    fun truncateQuote_flattensLines_cutsByCodePoint() {
        assertEquals("a b", ContextBuilder.truncateQuote("a\nb"))
        val exact = "字".repeat(ContextBuilder.QUOTE_MAX_CHARS)
        assertEquals(exact, ContextBuilder.truncateQuote(exact))
        assertEquals("😀".repeat(100) + "…", ContextBuilder.truncateQuote("😀".repeat(101)))
    }

    @Test
    fun transcript_usesTheSameQuoteLine() {
        val messages = listOf(text(Role.ASSISTANT, "在呀"), quoted(text(Role.USER, "早"), Role.ASSISTANT, "在呀"))
        assertEquals("小雨：在呀\n阿哲：（回复你之前说的“在呀”）\n早", MemoryPolicy.transcript(messages, "阿哲", "小雨", "[图片]", quoting::textOf))
        assertEquals("小雨：在呀\n阿哲：早", MemoryPolicy.transcript(messages, "阿哲", "小雨", "[图片]"))
    }

    @Test
    fun tokenEstimate_countsTheQuoteLineOnlyWhenSent() {
        val estimator = TokenEstimator.forProtocol("openai")
        val plain = text(Role.USER, "早")
        val withQuote = quoted(plain, Role.ASSISTANT, "在呀")
        assertEquals(
            estimator.message(plain) + estimator.text("在呀") + TokenEstimator.QUOTE_LINE_TOKENS,
            estimator.message(withQuote),
        )
        assertEquals(estimator.message(text(Role.ASSISTANT, "早")), estimator.message(quoted(text(Role.ASSISTANT, "早"), Role.USER, "在呀")))
    }

    // ---------- 0.9.2：发送时附带时间信息 ----------

    private val metaBuilder = ContextBuilder(
        ContextLabels(
            namePlaceholder = "{名称}",
            personaPlaceholderPrefix = "{在这里写人设",
            nicknameLine = "对方的昵称是「%1\$s」。",
            weekdays = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"),
            imagePlaceholder = "[图片]",
            memoryHeader = "下面是记忆：",
            memoryItem = "- [%1\$d年%2\$d月%3\$d日] %4\$s",
            metaNote = "（附注：%1\$s，%2\$s。）",
            metaTime = "现在是 %1\$d年%2\$d月%3\$d日 %4\$s %5\$02d:%6\$02d",
            metaSinceNow = "距离%1\$s上一条消息不到 1 分钟",
            metaSinceMinutes = "距离%1\$s上一条消息过了 %2\$d 分钟",
            metaSinceHours = "距离%1\$s上一条消息过了 %2\$d 小时",
            metaSinceDays = "距离%1\$s上一条消息过了 %2\$d 天",
            metaFirst = "这是你们的第一条消息",
            metaSelf = "你",
            metaOther = "对方",
        ),
        zone,
    )

    @Test
    fun metaNote_timeAndGapBuckets_likeProactive() {
        val minute = 60_000L
        fun gap(millis: Long, role: Role = Role.ASSISTANT) = metaBuilder.metaNote(now, now, now - millis, role)
        assertEquals("（附注：现在是 2026年11月1日 星期日 23:40，这是你们的第一条消息。）", metaBuilder.metaNote(now, now, null, null))
        assertEquals("（附注：现在是 2026年11月1日 星期日 23:40，距离你上一条消息不到 1 分钟。）", gap(59_999))
        assertEquals("（附注：现在是 2026年11月1日 星期日 23:40，距离对方上一条消息过了 1 分钟。）", gap(minute, Role.USER))
        assertTrue(gap(59 * minute).endsWith("过了 59 分钟。）"))
        assertTrue(gap(60 * minute).endsWith("过了 1 小时。）"))
        assertTrue(gap(48 * 60 * minute - 1).endsWith("过了 47 小时。）"))
        assertTrue(gap(48 * 60 * minute).endsWith("过了 2 天。）"))
        assertTrue("时间倒着（改过手机时间）按不到 1 分钟", gap(-5 * minute).contains("不到 1 分钟"))
        // 间隔按这一轮第一条消息发出的时间算，“现在”是请求的时间
        val later = now + 3 * 60 * minute
        assertEquals("（附注：现在是 2026年11月2日 星期一 02:40，距离你上一条消息过了 10 分钟。）", metaBuilder.metaNote(later, now, now - 10 * minute, Role.ASSISTANT))
        assertEquals("没配附注的文字时什么都不加", "", builder.metaNote(now, now, null, null))
    }

    @Test
    fun prependNote_goesBeforeTheLatestUserMessageOnly() {
        val history = listOf(text(Role.USER, "早"), text(Role.ASSISTANT, "早呀"), text(Role.USER, "吃了吗"), text(Role.USER, "我刚起"))
        val plain = metaBuilder.build(contact(""), history, false, "", now, loadImage = { null })
        val noted = metaBuilder.build(contact(""), history, false, "", now, loadImage = { null }, note = "【附注】")
        assertEquals(plain.dropLast(1), noted.dropLast(1))
        assertEquals("【附注】\n吃了吗\n我刚起", noted.last().plainText())
        assertEquals("空附注不改请求", plain, metaBuilder.build(contact(""), history, false, "", now, loadImage = { null }, note = ""))
        // 开头是图片时，附注单独放在前面
        val image = Message(id = nextId++, contactId = 1, role = Role.USER, type = MessageType.IMAGE, imagePath = "a.jpg", status = MessageStatus.SUCCESS, createdAt = 0)
        val withImage = metaBuilder.build(contact(""), listOf(image, text(Role.USER, "看")), true, "", now, loadImage = { "data:image/jpeg;base64,AA" }, note = "【附注】")
        val parts = withImage.single().parts
        assertEquals(ChatPart.Text("【附注】"), parts.first())
        assertTrue(parts[1] is ChatPart.Image)
        // 没有用户消息（只有联系人说的话）时不加
        val onlyAssistant = listOf(text(Role.ASSISTANT, "在吗"))
        assertEquals(metaBuilder.build(contact(""), onlyAssistant, false, "", now, loadImage = { null }), metaBuilder.build(contact(""), onlyAssistant, false, "", now, loadImage = { null }, note = "【附注】"))
    }

    @Test
    fun systemPrompt_hasNoTimeAnyMore_onlyNickname() {
        assertEquals("你是小雨，正在用微信和对方聊天。\n不使用 Markdown。\n\n对方的昵称是「阿哲」。", metaBuilder.systemPrompt(contact(), "阿哲"))
        assertEquals("你是小雨，正在用微信和对方聊天。\n不使用 Markdown。", metaBuilder.systemPrompt(contact(), ""))
    }
}
