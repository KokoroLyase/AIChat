package isomeria.aichat.feature.contactedit

import android.app.Application
import isomeria.aichat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「朋友资料」的小字由 [ContactEditField] 拼出来；这里保证它和编辑联系人页一致：
 * 编辑页新加了配置项（新的 contact_ 标题文字）却没加进 [ContactEditField]，或者删了配置项还留在小字里，都会失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ContactEditFieldsGuardTest {
    private val app = RuntimeEnvironment.getApplication()
    private val editScreen = File("src/main/java/isomeria/aichat/feature/contactedit/ContactEditScreen.kt").readText()

    /** 思考强度和温度由共用的 ModelParameterControls 画，编辑页调用它 */
    private val parameters = File("src/main/java/isomeria/aichat/ui/components/ModelParameters.kt").readText()

    private fun name(id: Int) = app.resources.getResourceEntryName(id)

    /** 编辑页上不是配置项标题的 contact_ 文字：提示、错误、弹窗、按钮。新加的文字要么是配置项，要么登记在这里 */
    private val notFields = setOf(
        "contact_auto_memory_hint", "contact_avatar_pick", "contact_avatar_reset", "contact_context_decrease",
        "contact_context_increase", "contact_context_invalid", "contact_context_tokens_hint", "contact_delete",
        "contact_delete_confirm", "contact_discard", "contact_discard_title", "contact_edit_title", "contact_edit_title_new",
        "contact_model_empty", "contact_model_image", "contact_model_pending", "contact_model_pending_required",
        "contact_model_pick", "contact_model_pick_first", "contact_model_required", "contact_model_thinking",
        "contact_model_unavailable", "contact_name_hint", "contact_name_required", "contact_prompt_empty", "contact_prompt_hint",
        "contact_provider_pick", "contact_split_hint",
    )

    @Test
    fun everyListedFieldIsOnTheEditPage() {
        assertTrue(editScreen.contains("ModelParameterControls("))
        for (field in ContactEditField.entries) {
            val res = "R.string.${name(field.label)}"
            assertTrue("$field 不在编辑页上", editScreen.contains(res) || parameters.contains(res))
        }
    }

    @Test
    fun everyFieldOnTheEditPageIsListed() {
        val used = Regex("""R\.string\.(contact_[a-z_]+)""").findAll(editScreen).map { it.groupValues[1] }.toSet()
        val listed = ContactEditField.entries.map { name(it.label) }.toSet()
        val unknown = used - notFields - listed
        assertEquals("编辑页出现了新的配置项，请加进 ContactEditField（小字会跟着更新），或登记为非配置项", emptySet<String>(), unknown)
    }

    @Test
    fun hintWordingMatchesPlan() {
        val separator = app.getString(R.string.list_separator)
        val text = app.getString(R.string.contact_profile_hint, ContactEditField.entries.joinToString(separator) { app.getString(it.hint) })
        assertEquals("编辑朋友的头像、名称、服务商、模型、思考强度、温度、系统提示词、上下文上限、是否分条显示、自动记忆等", text)
    }

    /** 显示用的小字只在项目字间多了不可见的 WORD JOINER：去掉后和原文一样，“、”前后不加 */
    @Test
    fun keepTogetherOnlyJoinsInsideItems() {
        assertEquals("思\u2060考\u2060强\u2060度", "思考强度".keepTogether())
        assertEquals("头", "头".keepTogether())
        val separator = app.getString(R.string.list_separator)
        val shown = app.getString(R.string.contact_profile_hint, ContactEditField.entries.joinToString(separator) { app.getString(it.hint).keepTogether() })
        assertEquals("编辑朋友的头像、名称、服务商、模型、思考强度、温度、系统提示词、上下文上限、是否分条显示、自动记忆等", shown.replace("\u2060", ""))
        assertTrue(shown.split(separator).all { !it.startsWith("\u2060") && !it.endsWith("\u2060") })
    }
}
