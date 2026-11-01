package isomeria.aichat.core

/** 通讯录的一个分组：首字母 A–Z，其他（数字、符号等）归入 #。 */
data class ContactSection<T>(val letter: Char, val items: List<T>)

/**
 * 联系人按拼音首字母分组，组内按拼音（再按原名称）排序，# 组放在最后。
 */
object ContactGrouper {
    const val OTHER = '#'
    val INDEX_LETTERS: List<Char> = ('A'..'Z').toList() + OTHER

    fun <T> group(items: List<T>, name: (T) -> String, pinyin: (String) -> String): List<ContactSection<T>> {
        val keyed = items.map { item ->
            val key = pinyin(name(item)).uppercase()
            Triple(item, key, letterOf(key))
        }
        return keyed
            .groupBy { it.third }
            .toSortedMap(compareBy { if (it == OTHER) Char.MAX_VALUE else it })
            .map { (letter, entries) ->
                ContactSection(
                    letter,
                    entries.sortedWith(compareBy<Triple<T, String, Char>> { it.second }.thenBy { name(it.first) })
                        .map { it.first },
                )
            }
    }

    fun letterOf(pinyinKey: String): Char {
        val first = pinyinKey.firstOrNull()?.uppercaseChar() ?: return OTHER
        return if (first in 'A'..'Z') first else OTHER
    }
}
