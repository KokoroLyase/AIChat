package isomeria.aichat.data.repo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StorageGateTest {
    @Test fun cleanupRefusesWhileReplyRestoreOrUpdateLeaseIsActive() = runTest {
        val gate = StorageGate()
        val lease = gate.acquire()
        var blocked = false
        try { gate.clean { fail("Cleanup must not run") } } catch (_: StorageBusyException) { blocked = true }
        assertTrue(blocked)
        lease.close(); lease.close()
        assertEquals(42, gate.clean { 42 })
    }
    @Test fun activityStartingDuringCleanupWaitsUntilCleanupFinishes() = runTest {
        val gate = StorageGate()
        val release = CompletableDeferred<Unit>()
        val cleaner = launch { gate.clean { release.await() } }
        runCurrent()
        var entered = false
        val activity = async { gate.activity { entered = true } }
        runCurrent()
        assertFalse(entered)
        release.complete(Unit)
        cleaner.join(); activity.await()
        assertTrue(entered)
    }
    @Test fun cancellationAndExceptionsReleaseProtection() = runTest {
        val gate = StorageGate()
        val job = launch { gate.activity { CompletableDeferred<Unit>().await() } }
        runCurrent(); job.cancelAndJoin()
        assertEquals("clean", gate.clean { "clean" })
        try { gate.clean { throw java.io.IOException() } } catch (_: java.io.IOException) { }
        gate.activity { }
        assertEquals("clean", gate.clean { "clean" })
    }
}
