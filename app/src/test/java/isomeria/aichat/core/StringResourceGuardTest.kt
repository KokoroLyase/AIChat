package isomeria.aichat.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Existing exceptions may be removed but new Chinese Kotlin literals must go in strings.xml. */
class StringResourceGuardTest {
    @Test fun noNewChineseStringLiterals() {
        val baseline = requireNotNull(javaClass.getResourceAsStream("/chinese-literals-baseline.txt"))
            .bufferedReader().use { it.readLines().toSet() }
        val root = File("src/main/java")
        val unexpected = root.walkTopDown().filter { it.extension == "kt" && !it.name.endsWith("Preview.kt") && !it.name.endsWith("Previews.kt") }.flatMap { file ->
            chineseLiterals(file.readText().substringBefore("@Preview"))
                .map { "${file.relativeTo(root).invariantSeparatorsPath}:$it" }
        }.filter { it !in baseline }.toList()
        assertTrue("New hardcoded strings: $unexpected", unexpected.isEmpty())
    }

    @Test fun commentsAndPureUrlsAreExcludedButUiTextContainingUrlsIsChecked() {
        val text = "// \"comment text\"\nval link = \"https://example.com/path\"\nval ui = \"请打开 https://example.com\""
        assertEquals(listOf("\"请打开 https://example.com\""), chineseLiterals(text).toList())
    }

    private fun chineseLiterals(source: String): Sequence<String> =
        Regex("/\\*[\\s\\S]*?\\*/|//[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"").findAll(source).map { it.value }
            .filter { it.startsWith('"') && !it.startsWith("\"https://") && !it.startsWith("\"http://") && it.any { char -> char in '\u4e00'..'\u9fff' } }
}
