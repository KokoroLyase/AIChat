package isomeria.aichat.feature.contactedit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import isomeria.aichat.R
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme

/**
 * 编辑联系人 →「系统提示词」：单独一页编辑。和编辑联系人页共用同一个 [ContactEditViewModel]，
 * 改动直接进编辑页的草稿，回到编辑页点「保存」才写入；不保存就离开编辑页时照常提示放弃修改。
 */
@Composable
fun ContactPromptScreen(onBack: () -> Unit, viewModel: ContactEditViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ImeBackHandler()
    ContactPromptContent(
        value = state.form.systemPrompt,
        onValueChange = { text -> viewModel.update { it.copy(systemPrompt = text) } },
        onBack = onBack,
    )
}

@Composable
internal fun ContactPromptContent(value: String, onValueChange: (String) -> Unit, onBack: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val title = stringResource(R.string.contact_prompt)
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(title = title, onBack = onBack) {
            WeSmallButton(stringResource(R.string.action_done), onBack)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            Column(Modifier.fillMaxWidth().background(colors.listBackground).padding(dimens.horizontalPadding)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = WeTheme.typography.small.copy(color = colors.textPrimary, lineHeight = WeTheme.typography.thinking.lineHeight),
                    cursorBrush = SolidColor(colors.brand),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp)
                        .clip(RoundedCornerShape(dimens.inputCorner))
                        .background(colors.pageBackground)
                        .padding(10.dp)
                        .semantics { contentDescription = title },
                )
            }
            FootnoteText(stringResource(R.string.contact_prompt_hint))
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 编辑页上那一行的预览：换行折成空格，最多两行；没写时显示「未填写」 */
internal fun promptPreview(prompt: String): String? =
    prompt.trim().replace(Regex("""\s*\n\s*"""), " ").takeIf { it.isNotEmpty() }
