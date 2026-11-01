package isomeria.aichat.core

import android.icu.text.Transliterator
import javax.inject.Inject
import javax.inject.Singleton

/** 把名称转成拼音（不带声调），用于通讯录分组和排序。 */
fun interface PinyinConverter {
    fun toPinyin(text: String): String
}

/**
 * 用系统自带的 ICU 转写（Android 10 起提供）。没有采用 TinyPinyin：
 * 它自 2017 年起不再维护，也只发布在已关闭的 JCenter 上。
 * 优先用「姓名」读音规则（如「曾」读 zeng），不可用时退回普通规则。
 */
@Singleton
class IcuPinyinConverter @Inject constructor() : PinyinConverter {
    private val transliterator: Transliterator? by lazy {
        runCatching { Transliterator.getInstance("Han-Latin/Names; Latin-ASCII") }.getOrNull()
            ?: runCatching { Transliterator.getInstance("Han-Latin; Latin-ASCII") }.getOrNull()
    }
    private val cache = HashMap<String, String>()

    @Synchronized
    override fun toPinyin(text: String): String = cache.getOrPut(text) {
        val converted = transliterator?.transliterate(text) ?: text
        converted.replace(" ", "")
    }
}
