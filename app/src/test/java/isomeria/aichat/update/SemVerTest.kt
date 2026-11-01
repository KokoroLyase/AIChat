package isomeria.aichat.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemVerTest {
    private fun v(text: String) = SemVer.parse(text)!!

    @Test
    fun parse_stripsVPrefix() {
        assertEquals(SemVer(0, 1, 0), v("v0.1.0"))
        assertEquals(SemVer(1, 2, 3), v("1.2.3"))
        assertEquals(SemVer(1, 0, 0, "beta.1"), v("V1.0.0-beta.1"))
        assertEquals(SemVer(1, 0, 0), v("1.0.0+build.5"))
    }

    @Test
    fun parse_rejectsInvalid() {
        assertNull(SemVer.parse("1.0"))
        assertNull(SemVer.parse("latest"))
        assertNull(SemVer.parse("v1.x.0"))
        assertNull(SemVer.parse(""))
    }

    @Test
    fun numericComparison_notString() {
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.2.10") > v("0.2.9"))
        assertEquals(0, v("v0.1.0").compareTo(v("0.1.0")))
    }

    @Test
    fun releaseIsNewerThanPreRelease() {
        assertTrue(v("1.0.0") > v("1.0.0-beta.1"))
        assertTrue(v("1.0.0-beta.2") > v("1.0.0-beta.1"))
        assertTrue(v("1.0.0-beta.11") > v("1.0.0-beta.2"))
        assertTrue(v("1.0.0-beta") > v("1.0.0-alpha"))
        assertTrue(v("1.0.0-alpha.1") > v("1.0.0-alpha"))
    }

    @Test
    fun isNewerVersion() {
        assertTrue(isNewerVersion("v0.2.0", "0.1.0"))
        assertFalse(isNewerVersion("v0.1.0", "0.1.0"))
        assertFalse(isNewerVersion("v0.1.0", "0.1.0-debug"))
        assertFalse(isNewerVersion("v0.0.9", "0.1.0"))
        assertFalse(isNewerVersion("nightly", "0.1.0"))
    }
}
