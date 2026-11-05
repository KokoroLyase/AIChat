package isomeria.aichat.data

import android.app.Application
import isomeria.aichat.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 新联系人预填的模板：不提第三方产品名，占位符继续能被 ContextBuilder 识别。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PromptTemplateTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun templateDoesNotNameWeChat() {
        assertFalse(app.getString(R.string.prompt_template).contains("微信"))
    }

    @Test
    fun templateKeepsPlaceholders() {
        val template = app.getString(R.string.prompt_template)
        assertTrue(template.contains(app.getString(R.string.prompt_name_placeholder)))
        assertTrue(template.contains(app.getString(R.string.prompt_persona_placeholder_prefix)))
    }
}
