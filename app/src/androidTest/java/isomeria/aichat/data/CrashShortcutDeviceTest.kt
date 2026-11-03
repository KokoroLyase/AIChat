package isomeria.aichat.data

import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test

class CrashShortcutDeviceTest {
    @Test fun systemRegistersTheShortcutForThisPackage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shortcut = context.getSystemService(ShortcutManager::class.java).manifestShortcuts
            .single { it.id == "crash_logs" }
        assertEquals(context.packageName, shortcut.intent!!.component!!.packageName)
        assertEquals("isomeria.aichat.feature.settings.CrashLogsActivity", shortcut.intent!!.component!!.className)
    }
}
