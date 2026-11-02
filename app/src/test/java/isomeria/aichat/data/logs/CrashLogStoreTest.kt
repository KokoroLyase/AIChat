package isomeria.aichat.data.logs

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun keepsNewestTwentyAndDoesNotWriteMessagesOrSuppressed() {
        val store = CrashLogStore(temporary.newFolder(), "app=fixture")
        val error = IllegalStateException("SECRET_CHAT_KEY", RuntimeException("PRIVATE_URL"))
        error.addSuppressed(IllegalArgumentException("SUPPRESSED_SECRET"))
        repeat(25) { store.record(error, 1_700_000_000_000L + it) }
        assertEquals(20, store.list().size)
        val text = store.read(store.list().first().name)
        assertTrue(text.contains("CrashLogStoreTest.kt:"))
        assertTrue(text.contains("java.lang.IllegalStateException"))
        listOf("SECRET_CHAT_KEY", "PRIVATE_URL", "SUPPRESSED_SECRET").forEach { assertFalse(text.contains(it)) }
        store.clear()
        assertTrue(store.list().isEmpty())
    }
    @Test fun failedWriteStillDelegatesOriginalThrowable() {
        val error = RuntimeException("private")
        var delegated: Throwable? = null
        val handler = RecordingExceptionHandler({ throw java.io.IOException() }, { _, caught -> delegated = caught })
        handler.uncaughtException(Thread.currentThread(), error)
        assertSame(error, delegated)
    }
    @Test fun successfulWriteDelegatesExactlyOnce() {
        var recorded = 0
        var delegated = 0
        RecordingExceptionHandler({ recorded++ }, { _, _ -> delegated++ })
            .uncaughtException(Thread.currentThread(), Error())
        assertEquals(1, recorded)
        assertEquals(1, delegated)
    }
    @Test fun anrOnlyIncludesMainFrames() {
        val text = CrashLogStore.mainThreadFrames(sequenceOf(
            "PRIVATE_HEADER", "\"main\" prio=5", "  at isomeria.aichat.MainActivity.onCreate(MainActivity.kt:42)",
            "PRIVATE_MESSAGE", "\"worker\" prio=5", "  at private.Worker.run(Secret.kt:8)",
        ))
        assertTrue(text.contains("MainActivity.kt:42"))
        assertFalse(text.contains("PRIVATE"))
        assertFalse(text.contains("Secret"))
    }
    @Test fun cannotReadOutsideLogDirectory() {
        val store = CrashLogStore(temporary.newFolder(), "fixture")
        assertThrows(IllegalArgumentException::class.java) { store.read("../key") }
    }
}
