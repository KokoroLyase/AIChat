package isomeria.aichat.feature.contactedit

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import isomeria.aichat.R

/**
 * 编辑联系人页的配置项，按页面上的顺序。联系人详情「朋友资料」下的小字由它拼出来，
 * 编辑页加减配置项时改这里，小字跟着变；`ContactEditFieldsGuardTest` 会检查编辑页和这里是否一致。
 *
 * [label] 是编辑页上的标题；[hint] 是小字里的叫法，和标题不同时单独给。
 */
enum class ContactEditField(@StringRes val label: Int, @StringRes val hint: Int = label) {
    AVATAR(R.string.contact_avatar),
    NAME(R.string.contact_name),
    PROVIDER(R.string.contact_provider),
    MODEL(R.string.contact_model),
    THINKING(R.string.contact_thinking),
    TEMPERATURE(R.string.contact_temperature),
    PROMPT(R.string.contact_prompt),
    CONTEXT(R.string.contact_context_tokens, R.string.contact_profile_item_context),
    SPLIT(R.string.contact_split, R.string.contact_profile_item_split),
    AUTO_MEMORY(R.string.contact_auto_memory),
}

/** 「朋友资料」下的小字：编辑朋友的头像、名称……等 */
@Composable
fun contactProfileHint(): String {
    val separator = stringResource(R.string.list_separator)
    val items = ContactEditField.entries.map { stringResource(it.hint) }
    return stringResource(R.string.contact_profile_hint, items.joinToString(separator))
}
