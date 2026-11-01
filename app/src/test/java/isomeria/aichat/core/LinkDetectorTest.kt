package isomeria.aichat.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDetectorTest {
    private fun urls(text: String) = LinkDetector.find(text).map { it.url }

    @Test
    fun httpsLink_stopsAtChinesePunctuation() {
        assertEquals(listOf("https://example.com/a?b=1"), urls("看看 https://example.com/a?b=1。"))
        assertEquals(listOf("https://a.com"), urls("链接：https://a.com，然后"))
    }

    @Test
    fun httpsLink_stopsAtChineseCharacters() {
        assertEquals(listOf("https://a.com/x"), urls("打开https://a.com/x就行"))
    }

    @Test
    fun wwwLink_getsHttpsPrefix() {
        assertEquals(listOf("https://www.deepseek.com"), urls("官网 www.deepseek.com 很好"))
    }

    @Test
    fun trailingEnglishPunctuation_isNotPartOfLink() {
        assertEquals(listOf("http://x.org/path"), urls("Go to http://x.org/path."))
        assertEquals(listOf("https://a.b/c"), urls("see https://a.b/c!"))
        assertEquals(listOf("https://a.b/c"), urls("\"https://a.b/c\""))
    }

    @Test
    fun balancedParentheses_areKept() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(bar)"),
            urls("(see https://en.wikipedia.org/wiki/Foo_(bar))"),
        )
        assertEquals(listOf("https://a.com/x"), urls("(https://a.com/x)"))
    }

    @Test
    fun rangesPointAtTheLink() {
        val text = "前缀 https://a.com 后缀"
        val link = LinkDetector.find(text).single()
        assertEquals("https://a.com", text.substring(link.start, link.end))
    }

    @Test
    fun multipleLinks() {
        assertEquals(
            listOf("https://a.com", "https://www.b.com/x"),
            urls("https://a.com 和 www.b.com/x"),
        )
    }

    @Test
    fun notLinks() {
        assertTrue(urls("没有链接").isEmpty())
        assertTrue(urls("http:// 只有前缀").isEmpty())
        assertTrue(urls("www. 不完整").isEmpty())
        assertTrue(urls("abcwww.x.com").isEmpty())
        assertTrue(urls("www.com").isEmpty())
    }

    @Test
    fun caseInsensitiveScheme() {
        assertEquals(listOf("HTTPS://A.COM"), urls("HTTPS://A.COM"))
    }
}
