package isomeria.aichat.data.logs

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.content.edit
import isomeria.aichat.BuildConfig
import java.io.File

object CrashLogs {
    const val EXPORT_DIRECTORY = "crash-exports"
    fun store(context: Context) = CrashLogStore(
        File(context.filesDir, "logs"),
        "app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\ndevice=${Build.MODEL.replace('\n', '_')}\nandroid=${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})",
        File(context.cacheDir, EXPORT_DIRECTORY),
    )

    fun install(context: Context) {
        val store = store(context)
        val next = Thread.getDefaultUncaughtExceptionHandler() ?: Thread.UncaughtExceptionHandler { _, _ ->
            Process.killProcess(Process.myPid())
            kotlin.system.exitProcess(10)
        }
        Thread.setDefaultUncaughtExceptionHandler(RecordingExceptionHandler({ store.record(it) }, next))
    }

    fun collectExits(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences("crash-exits", Context.MODE_PRIVATE)
            val last = prefs.getLong("timestamp", 0)
            val exits = context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(null, 0, 20).sortedBy { it.timestamp }
            val store = store(context)
            for (exit in exits.filter { it.timestamp > last }) {
                when (exit.reason) {
                    ApplicationExitInfo.REASON_ANR -> exit.traceInputStream?.bufferedReader()?.use {
                        store.recordExit(exit.timestamp, "ANR", it.lineSequence())
                    } ?: store.recordExit(exit.timestamp, "ANR", emptySequence())
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> store.recordExit(exit.timestamp, "NATIVE", emptySequence())
                    else -> Unit
                }
                prefs.edit(commit = true) { putLong("timestamp", exit.timestamp) }
            }
        }
    }
}
