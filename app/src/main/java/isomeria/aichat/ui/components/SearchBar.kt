package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme

/** 搜索页顶部：圆角输入框（左侧放大镜）+ 右侧「取消」 */
@Composable
fun WeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onCancel: () -> Unit,
    focusRequester: FocusRequester,
    hint: String = stringResource(R.string.search_hint),
    /** 点键盘上的「搜索」时（0.7.2 选音色时到服务商搜索）；不设时只收起键盘 */
    onSearch: (() -> Unit)? = null,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val keyboard = LocalSoftwareKeyboardController.current
    // 带着关键词进来时（如「N条相关聊天记录」）光标放在末尾
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.pageBackground)
            .statusBarsPadding()
            .height(dimens.topBarHeight)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.inputBackground)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(WeIcons.Search, null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(hint, style = WeTheme.typography.listTitle, color = colors.textSecondary, maxLines = 1)
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        field = it
                        if (it.text != query) onQueryChange(it.text)
                    },
                    singleLine = true,
                    textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); onSearch?.invoke() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
        }
        Box(
            Modifier
                .clickable(onClick = onCancel)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.action_cancel), style = WeTheme.typography.listTitle, color = colors.link)
        }
    }
}

/**
 * 把命中的关键词标成绿色（和微信一致）。命中位置太靠后时截掉前面一段，保证关键词在一行里看得到。
 */
fun highlightMatch(text: String, query: String, color: Color, trimBefore: Boolean = true): AnnotatedString =
    buildAnnotatedString {
        val keyword = query.trim()
        val index = if (keyword.isEmpty()) -1 else text.indexOf(keyword, ignoreCase = true)
        if (index < 0) {
            append(text)
            return@buildAnnotatedString
        }
        val start = if (trimBefore && index > 12) index - 8 else 0
        if (start > 0) append("…")
        append(text.substring(start, index))
        withStyle(SpanStyle(color = color)) { append(text.substring(index, index + keyword.length)) }
        append(text.substring(index + keyword.length))
    }

/** 搜索结果的一行：头像、标题（右侧可有时间）、副标题 */
@Composable
fun SearchResultRow(
    avatarPath: String?,
    name: String,
    avatarColor: Int,
    title: AnnotatedString,
    subtitle: AnnotatedString?,
    onClick: () -> Unit,
    trailing: String? = null,
    showDivider: Boolean = true,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.listBackground)
                .clickable(onClick = onClick)
                .heightIn(min = dimens.listRowHeight)
                .padding(horizontal = dimens.horizontalPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(avatarPath, name, avatarColor, dimens.listRowAvatar)
            Spacer(Modifier.width(dimens.listRowTextStart - dimens.horizontalPadding - dimens.listRowAvatar))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = WeTheme.typography.listTitle,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (trailing != null) {
                        Text(
                            trailing,
                            style = WeTheme.typography.listTime,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = WeTheme.typography.small,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (showDivider) WeDivider(startIndent = dimens.listRowTextStart)
    }
}
