package isomeria.aichat.data.memory

import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.FailReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryTasksTest {
    @Test fun twoContactsRunInParallelAndSameContactWaitsWithoutTakingPermit() = runTest {
        val tasks = MemoryTasks()
        val gates = List(4) { CompletableDeferred<Unit>() }
        val started = mutableListOf<Int>()
        var active = 0
        var max = 0
        listOf(1L, 1L, 2L, 3L).forEachIndexed { index, id -> launch {
            tasks.run(id) { started += index; active++; max = maxOf(max, active); gates[index].await(); active-- }
        } }
        runCurrent()
        assertEquals(listOf(0, 2), started)
        assertEquals(2, max)
        assertTrue(tasks.states.value[1]!!.generating)
        assertEquals(1, tasks.states.value[1]!!.queued)
        assertEquals(1, tasks.states.value[3]!!.queued)
        gates[0].complete(Unit)
        runCurrent()
        assertEquals(3, started.last()) // 先排队的其他联系人获得释放的许可
        gates[2].complete(Unit)
        runCurrent()
        assertEquals(1, started.last())
        gates.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(2, max)
        assertTrue(tasks.states.value.values.all { !it.generating && it.queued == 0 })
    }
    @Test fun onlyOneManualSummaryPerContactAtATime() {
        val tasks = MemoryTasks()
        assertTrue(tasks.beginManual(1))
        assertTrue(tasks.states.value[1]!!.manual)
        assertFalse("同一联系人不能再发起", tasks.beginManual(1))
        assertTrue("其他联系人不受影响", tasks.beginManual(2))
        tasks.endManual(1)
        assertFalse(tasks.states.value[1]!!.manual)
        assertTrue(tasks.states.value[2]!!.manual)
        assertTrue("结束后可以再发起", tasks.beginManual(1))
    }
    @Test fun failureAndCancellationReleaseLocksAndOnlyKeepSafeCategory() = runTest {
        val tasks = MemoryTasks()
        try { tasks.run(1) { throw LlmException(LlmError.CONFIGURATION, message = "sensitive must never be saved") } } catch (_: LlmException) { }
        assertEquals(FailReason.CONFIGURATION, tasks.states.value[1]!!.failure)
        val gate = CompletableDeferred<Unit>()
        val job = launch { tasks.run(1) { gate.await() } }
        runCurrent()
        job.cancel()
        runCurrent()
        tasks.run(1) { tasks.succeeded(1) }
        assertFalse(tasks.states.value[1]!!.generating)
        assertEquals(0, tasks.states.value[1]!!.queued)
        assertNull(tasks.states.value[1]!!.failure)
        assertNotNull(tasks.states.value[1]!!.lastSuccess)
        assertFalse(tasks.states.value.toString().contains("sensitive"))
    }
}
