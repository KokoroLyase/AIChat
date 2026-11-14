package isomeria.aichat.core

import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.llm.ChatPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * 文件和图片的解析结果进请求（0.9.2，[ContextBuilder.withAttachments]）：
 * 最近 3 个文件带全文、合计不超过预算的一半；模型不能看图时识别过的图片发文字；能看图时最近 3 张照旧带原图。
 */
class ContextAttachmentsTest {
    private val builder = ContextBuilder(
        ContextLabels(
            namePlaceholder = "{名称}",
            personaPlaceholderPrefix = "{在这里写人设",
            nicknameLine = "",
            weekdays = listOf("一", "二", "三", "四", "五", "六", "日"),
            imagePlaceholder = "[图片]",
            memoryHeader = "",
            memoryItem = "",
            fileLabel = "[文件：%1\$s]",
            fileTruncated = "（已截断）",
            imageText = "[图片：%1\$s]",
            imageNoText = "[图片（无文字）]",
        ),
        ZoneId.of("Asia/Shanghai"),
    )
    private val estimator = TokenEstimator(1.0)
    private var nextId = 1L
    private val parsed = HashMap<String, String>()

    private fun file(name: String, text: String? = null, status: MessageStatus = MessageStatus.SUCCESS) = Message(
        id = nextId++, contactId = 1, role = Role.USER, type = MessageType.FILE, content = name, filePath = "/f/$name",
        parsedPath = text?.let { "/p/$name".also { path -> parsed[path] = it } }, status = status, createdAt = nextId,
    )

    private fun image(text: String? = null) = Message(
        id = nextId++, contactId = 1, role = Role.USER, type = MessageType.IMAGE, imagePath = "/i/$nextId.jpg",
        parsedPath = text?.let { "/p/img$nextId".also { path -> parsed[path] = it } }, status = MessageStatus.SUCCESS, createdAt = nextId,
    )

    private fun text(content: String) = Message(id = nextId++, contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = content, status = MessageStatus.SUCCESS, createdAt = nextId)

    private fun render(rows: List<Message>, supportsImage: Boolean = false, budget: Int = 10_000) =
        builder.withAttachments(rows, supportsImage, budget, estimator) { parsed[it] }

    @Test
    fun noFilesOrParsedImages_rowsUnchanged() {
        val rows = listOf(text("在吗"), image())
        assertSame(rows, render(rows))
    }

    @Test
    fun latestThreeFilesCarryText_olderOnlyName_unparsedOnlyName() {
        val rows = listOf(file("1.pdf", "一"), file("2.pdf", "二"), file("3.pdf", "三"), file("4.pdf", "四"), file("5.docx"))
        val out = render(rows)
        assertTrue(out.all { it.type == MessageType.TEXT })
        assertEquals(listOf("[文件：1.pdf]", "[文件：2.pdf]", "[文件：3.pdf]\n三", "[文件：4.pdf]\n四", "[文件：5.docx]"), out.map { it.content })
        // id、状态、时间都不变
        assertEquals(rows.map { it.id to it.createdAt }, out.map { it.id to it.createdAt })
    }

    @Test
    fun fileTextLimitedToHalfTheBudget_newestFirst_truncatedNoted() {
        val rows = listOf(file("old.pdf", "甲".repeat(400)), file("new.pdf", "乙".repeat(400)))
        // 预算 1000，文件最多 500：新的 400 全带，旧的只剩 100
        val out = render(rows, budget = 1_000)
        assertEquals("[文件：new.pdf]\n" + "乙".repeat(400), out[1].content)
        val old = out[0].content
        assertTrue(old, old.startsWith("[文件：old.pdf]\n甲") && old.endsWith("\n（已截断）"))
        val kept = old.removePrefix("[文件：old.pdf]\n").removeSuffix("\n（已截断）")
        assertTrue("截到 100 以内：${kept.length}", kept.length in 1..100)
        // 预算用完时只剩标题和截断说明
        val none = render(listOf(file("x.pdf", "丙".repeat(50))), budget = 0)
        assertEquals("[文件：x.pdf]\n（已截断）", none.single().content)
    }

    @Test
    fun truncation_doesNotSplitSurrogatePairs() {
        val emoji = "😀".repeat(300)
        val out = render(listOf(file("e.pdf", emoji)), budget = 200)
        val kept = out.single().content.removePrefix("[文件：e.pdf]\n").removeSuffix("\n（已截断）")
        assertTrue(kept.isNotEmpty())
        assertTrue(kept.all { it == "😀"[0] || it == "😀"[1] } && kept.length % 2 == 0)
    }

    @Test
    fun imagesWithoutVision_parsedTextOrNoTextOrPlaceholder() {
        val rows = listOf(image("图里有字"), image(""), image(), image("x".repeat(2_100)))
        val out = render(rows)
        assertEquals("[图片：图里有字]", out[0].content)
        assertEquals(MessageType.TEXT, out[0].type)
        assertEquals("[图片（无文字）]", out[1].content)
        assertEquals("没识别过的照旧是图片", MessageType.IMAGE, out[2].type)
        assertEquals("[图片：" + "x".repeat(2_000) + "…]", out[3].content)
    }

    @Test
    fun imagesWithVision_latestThreeKeepImage_olderUseParsedText() {
        val rows = listOf(image("最早"), image("第二"), image("第三"), image("第四"))
        val out = render(rows, supportsImage = true)
        assertEquals("[图片：最早]", out[0].content)
        assertTrue(out.drop(1).all { it.type == MessageType.IMAGE })
    }

    @Test
    fun build_withAttachments_mergesIntoUserMessage_andUnrenderedFileHasName() {
        val rows = listOf(file("报告.pdf", "第一章"), Message(id = nextId++, contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "总结", status = MessageStatus.SENDING, createdAt = nextId))
        val contact = Contact(id = 1, name = "小雨", avatarColor = 0, providerId = 1, model = "m", systemPrompt = "", createdAt = 0, updatedAt = 0)
        val messages = builder.build(contact, render(rows), false, "", 0, loadImage = { null })
        assertEquals("[文件：报告.pdf]\n第一章\n总结", (messages.single().parts.single() as ChatPart.Text).text)
        // 没经过 withAttachments 的文件消息（比如记忆总结）只有名字
        val raw = builder.build(contact, rows, false, "", 0, loadImage = { null })
        assertEquals("[文件：报告.pdf]\n总结", raw.single().plainText())
    }

    @Test
    fun tokenEstimator_fileWithoutRenderingCountsName() {
        val f = file("报告.pdf")
        assertTrue(estimator.message(f) > TokenEstimator.MESSAGE_OVERHEAD)
    }
}
