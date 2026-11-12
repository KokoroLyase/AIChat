package isomeria.aichat.feature.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.data.repo.MessageSearchResult
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.SearchResultRow
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.WeSearchBar
import isomeria.aichat.ui.components.highlightMatch
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
    val messages: List<MessageSearchResult> = emptyList(),
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
            if (q.isBlank()) SearchResults() else SearchResults(q, chats.searchContacts(q), chats.searchMessageGroups(q))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())
}

/**
 * 搜索（聊天列表和通讯录顶栏进入）：同时搜联系人名称和聊天内容。
 *
 * 聊天记录按聊天分组：只命中一条时显示那条消息，点击直接跳到它的位置；
 * 命中多条时显示「N条相关聊天记录」，点击进入这个聊天的查找结果。
 */
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    onOpenMessage: (contactId: Long, messageId: Long) -> Unit,
    onOpenChatSearch: (contactId: Long, query: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val colors = WeTheme.colors
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeSearchBar(query = query, onQueryChange = { viewModel.query.value = it }, onCancel = onBack, focusRequester = focus)

        val now = System.currentTimeMillis()
        val brand = colors.brand
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
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
                itemsIndexed(results.contacts, key = { _, it -> "c${it.id}" }) { index, contact ->
                    SearchResultRow(
                        avatarPath = contact.avatarPath,
                        name = contact.name,
                        avatarColor = contact.avatarColor,
                        title = highlightMatch(contact.name, results.query, brand, trimBefore = false),
                        subtitle = null,
                        onClick = { onOpenChat(contact.id) },
                        showDivider = index != results.contacts.lastIndex,
                    )
                }
            }
            if (results.messages.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.search_messages)) }
                itemsIndexed(results.messages, key = { _, it -> "m${it.group.contactId}" }) { index, result ->
                    val group = result.group
                    val single = result.single
                    SearchResultRow(
                        avatarPath = group.contactAvatarPath,
                        name = group.contactName,
                        avatarColor = group.contactAvatarColor,
                        title = AnnotatedString(group.contactName),
                        subtitle = if (single != null) {
                            voicePrefix(single) + highlightMatch(single.content.replace('\n', ' '), results.query, brand)
                        } else {
                            AnnotatedString(stringResource(R.string.search_related_count, group.hitCount))
                        },
                        trailing = single?.let { viewModel.formatter.forList(it.createdAt, now) },
                        showDivider = index != results.messages.lastIndex,
                        onClick = {
                            if (single != null) {
                                onOpenMessage(group.contactId, single.id)
                            } else {
                                onOpenChatSearch(group.contactId, results.query)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 命中的是语音的转写文字（0.7.0）：前面标上「[语音]」；命中的是通话里的话（0.8.0）：前面标上「[通话]」 */
@Composable
private fun voicePrefix(message: isomeria.aichat.data.db.Message): AnnotatedString =
    AnnotatedString(
        when (message.type) {
            isomeria.aichat.data.db.MessageType.VOICE -> stringResource(R.string.preview_voice) + " "
            isomeria.aichat.data.db.MessageType.CALL -> stringResource(R.string.preview_call) + " "
            else -> ""
        },
    )
