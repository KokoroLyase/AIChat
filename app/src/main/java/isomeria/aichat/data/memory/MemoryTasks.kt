package isomeria.aichat.data.memory

import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.FailReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class MemoryTaskState(
    val queued: Int = 0,
    val generating: Boolean = false,
    val lastSuccess: Long? = null,
    val lastFailure: Long? = null,
    val failure: FailReason? = null,
)

/** 只保存安全的瞬时状态；每个联系人锁包含整个任务，网络等待只占两个全局许可之一。 */
@Singleton
class MemoryTasks @Inject constructor() {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val permits = Semaphore(2)
    private val _states = MutableStateFlow<Map<Long, MemoryTaskState>>(emptyMap())
    val states = _states.asStateFlow()
    private fun change(id: Long, block: (MemoryTaskState) -> MemoryTaskState) =
        _states.update { it + (id to block(it[id] ?: MemoryTaskState())) }

    suspend fun <T> run(id: Long, block: suspend () -> T): T {
        change(id) { it.copy(queued = it.queued + 1) }
        var started = false
        try {
            return locks.getOrPut(id) { Mutex() }.withLock {
                permits.withPermit {
                    started = true
                    change(id) { it.copy(queued = it.queued - 1, generating = true) }
                    try { block() } finally { change(id) { it.copy(generating = false) } }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = if (e is LlmException) FailReason.of(e) else FailReason.UNKNOWN
            change(id) { it.copy(lastFailure = System.currentTimeMillis(), failure = reason) }
            throw e
        } finally {
            if (!started) change(id) { it.copy(queued = (it.queued - 1).coerceAtLeast(0)) }
        }
    }

    fun succeeded(id: Long) = change(id) { it.copy(lastSuccess = System.currentTimeMillis(), failure = null) }
}
