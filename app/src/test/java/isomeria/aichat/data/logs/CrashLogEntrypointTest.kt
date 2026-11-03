package isomeria.aichat.data.logs

import android.app.Application
import android.content.ComponentName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CrashLogEntrypointTest {
    @Test fun externalAppsCannotDirectlyOpenDiagnostics() {
        val context = RuntimeEnvironment.getApplication()
        val component = ComponentName(context.packageName, "isomeria.aichat.feature.settings.CrashLogsActivity")
        @Suppress("DEPRECATION") // Robolectric does not implement the typed-flags overload.
        val activity = context.packageManager.getActivityInfo(component, 0)
        assertFalse(activity.exported)
        assertEquals(":diagnostics", activity.processName.removePrefix(context.packageName))
    }
}
