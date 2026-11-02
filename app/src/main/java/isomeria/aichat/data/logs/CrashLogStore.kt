package isomeria.aichat.data.logs

import java.io.File
import java.util.UUID

/** Has no Android, Room or settings dependency; also usable from an uncaught handler. */
class CrashLogStore(private val directory: File, private val environment: String) {
    @Synchronized
    fun record(error: Throwable, time: Long = System.currentTimeMillis()): File =
        write(time, "JAVA", safeStack(error))

    @Synchronized
    fun recordExit(time: Long, kind: String, trace: Sequence<String>): File =
        write(time, kind, mainThreadFrames(trace))

    private fun write(time: Long, kind: String, stack: String): File {
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "$time-${UUID.randomUUID()}.txt")
        val temporary = File(directory, "${target.name}.tmp")
        try {
            temporary.outputStream().use {
                it.write("time=$time\n$environment\nkind=$kind\n$stack".toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            check(temporary.renameTo(target))
        } finally { temporary.delete() }
        list().drop(LIMIT).forEach { check(it.delete()) }
        return target
    }

    @Synchronized fun list(): List<File> {
        if (!directory.exists()) return emptyList()
        check(directory.isDirectory)
        return (directory.listFiles() ?: throw java.io.IOException("LogDirectoryUnavailable"))
            .filter { it.isFile && NAME.matches(it.name) }.sortedByDescending { it.name }
    }

    @Synchronized fun read(name: String): String = resolve(name).readText(Charsets.UTF_8)
    @Synchronized fun clear() { list().forEach { check(it.delete()) } }
    private fun resolve(name: String): File {
        require(NAME.matches(name))
        return File(directory, name)
    }

    companion object {
        const val LIMIT = 20
        private val NAME = Regex("[0-9]+-[a-f0-9-]{36}\\.txt")
        private fun token(value: String): String = value.take(240).replace(Regex("[^a-zA-Z0-9_.$<>/-]"), "_")
        fun safeStack(error: Throwable): String = buildString {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var current: Throwable? = error
            repeat(8) {
                val item = current ?: return@buildString
                if (!seen.add(item)) return@buildString
                append(token(item.javaClass.name)).append('\n')
                item.stackTrace.take(64).forEach { frame ->
                    append("  at ").append(token(frame.className)).append('.').append(token(frame.methodName))
                    append('(').append(token(frame.fileName ?: "UnknownSource")).append(':').append(frame.lineNumber).append(")\n")
                }
                current = item.cause
            }
        }

        /** Accept stack frames only. No thread names, messages, registers, paths or native trace text. */
        fun mainThreadFrames(lines: Sequence<String>): String = buildString {
            var inMain = false
            var frames = 0
            for (line in lines.take(8192)) {
                if (line.startsWith('"')) {
                    if (inMain) break
                    inMain = line.startsWith("\"main\"")
                }
                val frame = line.trim()
                if (inMain && frames < 64 && Regex("at [a-zA-Z0-9_.$<>]+\\([a-zA-Z0-9_.$ :/-]+\\)").matches(frame)) {
                    append(frame.take(512)).append('\n')
                    frames++
                }
            }
        }
    }
}

/** Always delegate, including when recording itself fails. */
class RecordingExceptionHandler(
    private val record: (Throwable) -> Unit,
    private val next: Thread.UncaughtExceptionHandler,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { record(error) } catch (_: Throwable) { /* No message logging, especially on OOM. */ }
        finally { next.uncaughtException(thread, error) }
    }
}
