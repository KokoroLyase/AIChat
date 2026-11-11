package isomeria.aichat.data.repo

import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class StorageBusyException : Exception("StorageBusy")

/** Activities can overlap. Cleanup is exclusive; new activities wait until cleanup finishes. */
class StorageGate {
    private val mutex = Mutex()
    private val active = AtomicInteger()
    suspend fun acquire(): Closeable = mutex.withLock {
        active.incrementAndGet()
        val closed = AtomicBoolean()
        Closeable { if (closed.compareAndSet(false, true)) active.decrementAndGet() }
    }
    suspend fun <T> activity(block: suspend () -> T): T {
        val lease = acquire()
        try { return block() } finally { lease.close() }
    }
    suspend fun <T> clean(block: suspend () -> T): T {
        if (!mutex.tryLock()) throw StorageBusyException()
        try {
            if (active.get() > 0) throw StorageBusyException()
            return block()
        } finally { mutex.unlock() }
    }
}

/** Per-process transient protection. Pending editor/camera files are reconsidered after restart. */
object StorageOperations {
    val gate = StorageGate()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    fun pin(file: File) { pending.add(file.canonicalPath) }
    fun unpin(file: File) { pending.remove(file.canonicalPath) }
    fun pinnedPaths(): Set<String> = pending.toSet()
}

data class OrphanFile(val path: String, val bytes: Long, val modified: Long)
data class OrphanPreview(val files: List<OrphanFile>) {
    val count get() = files.size
    val bytes get() = files.sumOf { it.bytes }
}
data class StorageUsage(val images: Long, val avatars: Long, val logs: Long, val cache: Long, val database: Long, val backgrounds: Long = 0, val voice: Long = 0)
data class CleanupResult(val count: Int, val bytes: Long)

internal fun mediaReferences(avatars: List<String>, images: List<String>, ownAvatar: String?, backgrounds: List<String> = emptyList(), voices: List<String> = emptyList()): Set<String> =
    buildSet { addAll(avatars); addAll(images); ownAvatar?.let(::add); addAll(backgrounds); addAll(voices) }

/** Does not follow symlinks outside the requested root, including nested cache directories. */
internal fun storageFiles(root: File): List<File> {
    if (!root.exists()) return emptyList()
    val canonical = root.canonicalPath
    check(!java.nio.file.Files.isSymbolicLink(root.toPath()))
    return root.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) && (it.canonicalPath == canonical || it.canonicalPath.startsWith(canonical + File.separator)) }
        .onFail { _, error -> throw error }
        .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalPath.startsWith(canonical + File.separator) }.toList()
}
