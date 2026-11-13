package isomeria.aichat.core

/** 翻译（0.9.0）翻成哪种语言 */
enum class TranslateTarget { ENGLISH, CHINESE }

/**
 * 翻译方向（0.9.0）：数汉字和其他文字（拉丁字母、假名、谚文、西里尔字母等）的个数，标点、数字、表情和空白不算；
 * 汉字超过一半翻成英语，否则翻成中文。一个字都没有（只有表情、数字、标点）时为 null，不显示“翻译”。
 */
object TranslateDirection {
    fun of(text: String): TranslateTarget? {
        var han = 0
        var other = 0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) han++ else other++
        }
        if (han + other == 0) return null
        return if (han * 2 > han + other) TranslateTarget.ENGLISH else TranslateTarget.CHINESE
    }
}
