package isomeria.aichat.data.logs

import android.app.Application
import android.content.ContextWrapper
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Uses the production factory, including the diagnostics process's dependency-free path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CrashLogClearTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun clearRemovesOriginalAndShareCopyWithoutTouchingOtherCache() {
        val files = temporary.newFolder("files")
        val cache = temporary.newFolder("cache")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
        }
        val store = CrashLogs.store(context)
        val original = store.record(IllegalStateException())
        val copy = File(cache, "crash-exports/crash.txt").apply { parentFile!!.mkdirs(); writeText(store.read(original.name)) }
        val unrelated = File(cache, "other").apply { writeText("fixture") }
        // A new instance represents opening the separate diagnostics entry.
        CrashLogs.store(context).clear()
        assertFalse(original.exists())
        assertFalse(copy.exists())
        assertTrue(unrelated.exists())
        // Clearing a second time, including an already empty directory, is safe.
        CrashLogs.store(context).clear()
    }
}
