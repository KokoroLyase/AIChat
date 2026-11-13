package isomeria.aichat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 0.9.0 翻译方向：汉字超过一半翻成英语，否则翻成中文；标点、数字、表情、空白不算；一个字都没有时不翻译 */
class TranslateDirectionTest {
    private fun of(text: String) = TranslateDirection.of(text)

    @Test
    fun chinese_toEnglish_otherScripts_toChinese() {
        assertEquals(TranslateTarget.ENGLISH, of("周末去爬山吗？"))
        assertEquals(TranslateTarget.CHINESE, of("Are you free this weekend?"))
        assertEquals("假名", TranslateTarget.CHINESE, of("こんにちは"))
        assertEquals("谚文", TranslateTarget.CHINESE, of("안녕하세요"))
        assertEquals("西里尔字母", TranslateTarget.CHINESE, of("Привет"))
        assertEquals("全角字母", TranslateTarget.CHINESE, of("ＯＫ"))
    }

    @Test
    fun overHalf_meansMoreThanHalf() {
        assertEquals("正好一半", TranslateTarget.CHINESE, of("好的OK"))
        assertEquals(TranslateTarget.ENGLISH, of("好的呀OK"))
        assertEquals(TranslateTarget.CHINESE, of("我爱Python"))
    }

    @Test
    fun punctuationDigitsEmojiAndSpaces_doNotCount() {
        assertEquals(TranslateTarget.ENGLISH, of("好 😀😀😀😀 1234 !!! ..."))
        assertEquals(TranslateTarget.CHINESE, of("ok，，，。。。１２３"))
        // 扩展区的汉字是两个 char
        assertEquals(TranslateTarget.ENGLISH, of("𠀀𠀀a"))
    }

    @Test
    fun noLetters_noTranslation() {
        assertNull(of(""))
        assertNull(of("   \n"))
        assertNull(of("😀👍🎉"))
        assertNull(of("123 456"))
        assertNull(of("？！。，…"))
        assertNull(of("😀 2026！"))
    }
}
