package isomeria.aichat.feature.contactedit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.feature.replyprefs.ReplyPreferencesFields
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeIcon
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme

/** 回复偏好方式的名称：联系人编辑页那一行的值和子页的选项共用 */
@Composable
internal fun replyModeName(mode: ReplyPreferencesMode): String = stringResource(
    when (mode) {
        ReplyPreferencesMode.FOLLOW -> R.string.contact_reply_mode_follow
        ReplyPreferencesMode.CUSTOM -> R.string.contact_reply_mode_custom
        ReplyPreferencesMode.OFF -> R.string.contact_reply_mode_off
    },
)

/**
 * 联系人的回复偏好（0.7.2）：盖在联系人编辑页上，改动跟着联系人的「保存」一起生效。
 * 选跟随全局、单独设置或不使用；单独设置时下面是和「我 → 回复偏好」一样的各项，跟随全局时写出全局现在的值
 */
@Composable
fun ContactReplyPreferencesContent(
    form: ContactForm,
    global: ReplyPreferences,
    onMode: (ReplyPreferencesMode) -> Unit,
    onChange: ((ReplyPreferences) -> ReplyPreferences) -> Unit,
    onBack: () -> Unit,
) {
    val colors = WeTheme.colors
    val mode = form.replyMode
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.contact_reply_preferences), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            val modes = ReplyPreferencesMode.entries
            modes.forEachIndexed { index, item ->
                ModeChoice(
                    title = replyModeName(item),
                    detail = stringResource(
                        when (item) {
                            ReplyPreferencesMode.FOLLOW -> R.string.contact_reply_mode_follow_desc
                            ReplyPreferencesMode.CUSTOM -> R.string.contact_reply_mode_custom_desc
                            ReplyPreferencesMode.OFF -> R.string.contact_reply_mode_off_desc
                        },
                    ),
                    selected = item == mode,
                    showDivider = index != modes.lastIndex,
                    onClick = { onMode(item) },
                )
            }
            when (mode) {
                ReplyPreferencesMode.FOLLOW -> FootnoteText(
                    stringResource(
                        R.string.contact_reply_follow_summary,
                        global.waitSeconds, global.targetLength, global.minCount, global.maxCount,
                        global.minIntervalSeconds, global.maxIntervalSeconds,
                    ),
                )
                ReplyPreferencesMode.CUSTOM -> form.replyPreferences?.let { ReplyPreferencesFields(it, onChange) }
                ReplyPreferencesMode.OFF -> FootnoteText(stringResource(R.string.contact_reply_off_summary))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 单选的一行：名称、说明，选中的打勾 */
@Composable
private fun ModeChoice(title: String, detail: String, selected: Boolean, showDivider: Boolean, onClick: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = dimens.listRowHeight)
            .padding(horizontal = dimens.horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = WeTheme.typography.listTitle, color = colors.textPrimary)
            Text(detail, style = WeTheme.typography.listTime, color = colors.textTertiary)
        }
        if (selected) WeIcon(WeIcons.Check, null, tint = colors.brand, modifier = Modifier.padding(start = 8.dp).size(20.dp))
    }
    if (showDivider) WeDivider(startIndent = dimens.horizontalPadding)
}
