package isomeria.aichat.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmojiCatalogTest {
    @Test
    fun parse_skipsCommentsAndUnsupported() {
        val lines = sequenceOf("# 注释", "", "smileys\t😀 😁 🫩", "flags\t🫩", "broken line")
        val groups = EmojiCatalog.parse(lines) { it != "🫩" }
        assertEquals(listOf(EmojiGroup("smileys", listOf("😀", "😁"))), groups)
    }

    /** 打包进 App 的列表：9 个分类、没有肤色变体，常用表情都在 */
    @Test
    fun bundledAsset_isComplete() {
        val file = File("src/main/assets/emoji.txt")
        val groups = file.bufferedReader().useLines { EmojiCatalog.parse(it) { true } }
        assertEquals(
            listOf("smileys", "people", "animals", "food", "travel", "activities", "objects", "symbols", "flags"),
            groups.map { it.key },
        )
        val all = groups.flatMap { it.emojis }
        assertTrue(all.size > 1500)
        assertEquals("不能有重复", all.size, all.toSet().size)
        assertFalse("不放肤色变体", all.any { e -> e.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF } })
        for (common in listOf("😀", "😂", "❤️", "👍", "🙏", "🎉", "🇨🇳")) assertTrue(common, common in all)
        assertEquals("😀", groups.first().emojis.first())
    }
}
