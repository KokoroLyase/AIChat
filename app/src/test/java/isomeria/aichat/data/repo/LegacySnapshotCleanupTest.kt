package isomeria.aichat.data.repo

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LegacySnapshotCleanupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun snapshot() = File(context.filesDir, "backups/before-restore.zip").apply { parentFile!!.mkdirs(); writeText("fixture") }

    @Test fun deletesTheOldVersionSnapshotOnlyOnce() {
        val old = snapshot()
        FileStorage.clearLegacySnapshotOnce(context)
        assertFalse("0.3.1 留下的快照要删掉", old.exists())

        // 0.4.0 恢复失败留下的快照是回滚依据，之后启动不能再删
        val failed = snapshot()
        FileStorage.clearLegacySnapshotOnce(context)
        assertTrue(failed.exists())
    }

    @Test fun freshInstallWithoutSnapshotStillLaterKeepsFailureSnapshot() {
        FileStorage.clearLegacySnapshotOnce(context)
        val failed = snapshot()
        FileStorage.clearLegacySnapshotOnce(context)
        assertTrue(failed.exists())
    }
}
