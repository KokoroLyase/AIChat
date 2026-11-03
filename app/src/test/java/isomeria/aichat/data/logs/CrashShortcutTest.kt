package isomeria.aichat.data.logs

import android.app.Application
import android.content.Intent
import android.util.Xml
import isomeria.aichat.BuildConfig
import isomeria.aichat.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CrashShortcutTest {
    @Test fun parsedShortcutTargetsThisInstalledVariant() {
        val context = RuntimeEnvironment.getApplication()
        context.resources.getXml(R.xml.shortcuts).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "intent") {
                    val intent = Intent.parseIntent(context.resources, parser, Xml.asAttributeSet(parser))
                    assertEquals(BuildConfig.APPLICATION_ID, intent.component?.packageName)
                    assertEquals("isomeria.aichat.feature.settings.CrashLogsActivity", intent.component?.className)
                    return
                }
            }
        }
        fail("Missing crash log shortcut intent")
    }

}
