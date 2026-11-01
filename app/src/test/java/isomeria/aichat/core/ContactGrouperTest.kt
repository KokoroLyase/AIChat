package isomeria.aichat.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactGrouperTest {
    private val pinyin = mapOf(
        "张三" to "zhangsan",
        "阿哲" to "azhe",
        "安然" to "anran",
        "Bob" to "Bob",
        "123" to "123",
        "😀笑笑" to "😀xiaoxiao",
        "小雨" to "xiaoyu",
        "夏天" to "xiatian",
    )

    @Test
    fun groupsByInitial_sortsWithinGroup_putsOthersLast() {
        val sections = ContactGrouper.group(pinyin.keys.toList(), { it }, { pinyin.getValue(it) })
        assertEquals(listOf('A', 'B', 'X', 'Z', '#'), sections.map { it.letter })
        assertEquals(listOf("安然", "阿哲"), sections[0].items)
        assertEquals(listOf("小雨", "夏天"), sections[2].items)
        assertEquals(listOf("123", "😀笑笑"), sections[4].items)
    }

    @Test
    fun letterOf() {
        assertEquals('Z', ContactGrouper.letterOf("zhang"))
        assertEquals('#', ContactGrouper.letterOf("1"))
        assertEquals('#', ContactGrouper.letterOf(""))
    }

    @Test
    fun emptyList() {
        assertEquals(emptyList<ContactSection<String>>(), ContactGrouper.group(emptyList<String>(), { it }, { it }))
    }
}
