package isomeria.aichat.feature.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.MessageSearchHit
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class SearchResults(
    val query: String = "",
    val contacts: List<Contact> = emptyList(),
    val messages: List<MessageSearchHit> = emptyList(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val chats: ChatRepository,
    val formatter: ChatTimeFormatter,
) : ViewModel() {
    val query = MutableStateFlow("")

    val results: StateFlow<SearchResults> = query
        .debounce(200)
        .mapLatest { q ->
            if (q.isBlank()) SearchResults() else SearchResults(q, chats.searchContacts(q), chats.searchMessages(q))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())
}

/** 搜索联系人和聊天记录 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
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
                        Text(stringResource(R.string.search_hint), style = WeTheme.typography.listTitle, color = colors.textSecondary)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { viewModel.query.value = it },
                        singleLine = true,
                        textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.brand),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
            Box(
                Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.action_cancel), style = WeTheme.typography.listTitle, color = colors.link)
            }
        }

        val now = System.currentTimeMillis()
        LazyColumn(Modifier.fillMaxSize()) {
            if (results.query.isNotBlank() && results.contacts.isEmpty() && results.messages.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.search_no_result),
                        style = WeTheme.typography.small,
                        color = colors.textTertiary,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
            }
            if (results.contacts.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.search_contacts)) }
                items(results.contacts, key = { "c${it.id}" }) { contact ->
                    ResultRow(
                        avatarPath = contact.avatarPath,
                        name = contact.name,
                        avatarColor = contact.avatarColor,
                        title = highlight(contact.name, results.query),
                        subtitle = null,
                        onClick = { onOpenChat(contact.id) },
                    )
                    WeDivider(startIndent = dimens.listRowTextStart)
                }
            }
            if (results.messages.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.search_messages)) }
                items(results.messages, key = { "m${it.message.id}" }) { hit ->
                    ResultRow(
                        avatarPath = hit.contactAvatarPath,
                        name = hit.contactName,
                        avatarColor = hit.contactAvatarColor,
                        title = buildAnnotatedString { append(hit.contactName) },
                        subtitle = highlight(hit.message.content.replace('\n', ' '), results.query),
                        trailing = viewModel.formatter.forList(hit.message.createdAt, now),
                        onClick = { onOpenChat(hit.message.contactId) },
                    )
                    WeDivider(startIndent = dimens.listRowTextStart)
                }
            }
        }
    }
}

@Composable
private fun highlight(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val brand = WeTheme.colors.brand
    return highlight(text, query, brand)
}

private fun highlight(text: String, query: String, color: androidx.compose.ui.graphics.Color) = buildAnnotatedString {
    val index = text.indexOf(query, ignoreCase = true)
    if (query.isBlank() || index < 0) {
        append(text)
        return@buildAnnotatedString
    }
    // 命中位置太靠后时截掉前面一段，保证关键词可见
    val start = if (index > 12) index - 8 else 0
    if (start > 0) append("…")
    append(text.substring(start, index))
    withStyle(SpanStyle(color = color)) { append(text.substring(index, index + query.length)) }
    append(text.substring(index + query.length))
}

@Composable
private fun ResultRow(
    avatarPath: String?,
    name: String,
    avatarColor: Int,
    title: androidx.compose.ui.text.AnnotatedString,
    subtitle: androidx.compose.ui.text.AnnotatedString?,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
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
            Row {
                Text(
                    title,
                    style = WeTheme.typography.listTitle,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (trailing != null) {
                    Text(trailing, style = WeTheme.typography.listTime, color = colors.textSecondary)
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
}
