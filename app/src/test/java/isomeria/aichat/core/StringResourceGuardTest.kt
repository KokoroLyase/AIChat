package isomeria.aichat.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Existing exceptions may be removed but new Chinese Kotlin literals must go in strings.xml. */
class StringResourceGuardTest {
    @Test fun noNewChineseStringLiterals() {
        val baseline = requireNotNull(javaClass.getResourceAsStream("/chinese-literals-baseline.txt"))
            .bufferedReader().use { it.readLines().toSet() }
        val root = File("src/main/java")
        val unexpected = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            // Preview-only tails are excluded. Comments cannot introduce a quoted literal.
            val source = file.readText().substringBefore("@Preview")
                .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            source.lineSequence().flatMap { line ->
                Regex("\"(?:\\\\.|[^\"\\\\])*\"").findAll(line.substringBefore("//"))
                    .map { it.value }.filter { it.any { char -> char in '\u4e00'..'\u9fff' } }
                    .map { "${file.relativeTo(root).invariantSeparatorsPath}:$it" }
            }
        }.filter { it !in baseline }.toList()
        assertTrue("New hardcoded strings: $unexpected", unexpected.isEmpty())
    }
}
