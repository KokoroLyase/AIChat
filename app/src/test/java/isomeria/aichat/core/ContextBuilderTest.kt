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
            timeLine = "现在是 %1\$d年%2\$d月%3\$d日 %4\$s %5\$02d:%6\$02d。",
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
        val prompt = builder.systemPrompt(contact(), "阿哲", now)!!
        assertEquals(
            "你是小雨，正在用微信和对方聊天。\n不使用 Markdown。\n\n现在是 2026年11月1日 星期日 23:40。对方的昵称是「阿哲」。",
            prompt,
        )
    }

    @Test
    fun systemPrompt_keepsEditedPersona() {
        val edited = template.replace("{在这里写人设：性格、和对方的关系、说话习惯、是否使用 emoji}", "性格温柔，爱用 emoji")
        val prompt = builder.systemPrompt(contact(edited), "", now)!!
        assertTrue(prompt.contains("性格温柔，爱用 emoji"))
        assertFalse("没有昵称时不附带昵称", prompt.contains("昵称"))
    }

    @Test
    fun emptyPrompt_sendsNoSystemMessage() {
        assertNull(builder.systemPrompt(contact(""), "阿哲", now))
        val messages = builder.build(contact("  "), listOf(text(Role.USER, "hi")), true, "阿哲", now, loadImage = loader)
        assertEquals(1, messages.size)
        assertEquals(ChatRole.USER, messages[0].role)
    }

    private fun memory(content: String) = Memory(
        contactId = 1, content = content, fromTime = now - 1000, toTime = now - 500, createdAt = now, updatedAt = now,
    )

    @Test
    fun memories_comeAfterPersona_beforeTime() {
        val prompt = builder.systemPrompt(contact("人设"), "", now, listOf(memory("喜欢拿铁"), memory("养了猫")))!!
        assertEquals(
            "人设\n\n下面是记忆：\n- [2026年11月1日] 喜欢拿铁\n- [2026年11月1日] 养了猫\n\n现在是 2026年11月1日 星期日 23:40。",
            prompt,
        )
    }

    @Test
    fun emptyPersonaWithMemories_stillSendsSystemMessage() {
        val prompt = builder.systemPrompt(contact(""), "", now, listOf(memory("喜欢拿铁")))!!
        assertEquals("下面是记忆：\n- [2026年11月1日] 喜欢拿铁\n\n现在是 2026年11月1日 星期日 23:40。", prompt)
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
}
