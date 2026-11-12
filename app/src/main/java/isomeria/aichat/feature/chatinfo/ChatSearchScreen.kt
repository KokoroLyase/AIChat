package isomeria.aichat.feature.chatinfo

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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.ChatTimeFormatter
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.navigation.ChatSearchRoute
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.SearchResultRow
import isomeria.aichat.ui.components.WeSearchBar
import isomeria.aichat.ui.components.highlightMatch
import isomeria.aichat.ui.theme.SelfAvatarColor
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

data class ChatSearchResults(val query: String = "", val messages: List<Message> = emptyList())

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatSearchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    contacts: ContactRepository,
    private val chats: ChatRepository,
    settings: SettingsStore,
    val formatter: ChatTimeFormatter,
) : ViewModel() {
    private val route = savedState.toRoute<ChatSearchRoute>()
    val contactId: Long = route.contactId
    val query = MutableStateFlow(route.query)

    val contact: StateFlow<Contact?> = contacts.observeContact(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val myNickname: StateFlow<String> = settings.nickname
    val myAvatarPath: StateFlow<String?> = settings.avatarPath

    val results: StateFlow<ChatSearchResults> = query
        .debounce(200)
        .mapLatest { q -> ChatSearchResults(q, chats.searchInChat(contactId, q)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatSearchResults())
}

/** 聊天信息页「查找聊天记录」：在这个聊天里搜文字消息，点击跳到消息的位置并高亮 */
@Composable
fun ChatSearchScreen(
    onBack: () -> Unit,
    onOpenMessage: (contactId: Long, messageId: Long) -> Unit,
    viewModel: ChatSearchViewModel = hiltViewModel(),
) {
    val colors = WeTheme.colors
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val contact by viewModel.contact.collectAsStateWithLifecycle()
    val nickname by viewModel.myNickname.collectAsStateWithLifecycle()
    val myAvatar by viewModel.myAvatarPath.collectAsStateWithLifecycle()
    val myName = nickname.ifBlank { stringResource(R.string.default_nickname) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeSearchBar(query = query, onQueryChange = { viewModel.query.value = it }, onCancel = onBack, focusRequester = focus)
        val now = System.currentTimeMillis()
        val brand = colors.brand
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            when {
                results.query.isBlank() -> item {
                    Text(
                        stringResource(R.string.chat_search_intro, contact?.name.orEmpty()),
                        style = WeTheme.typography.small,
                        color = colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
                results.messages.isEmpty() -> item {
                    Text(
                        stringResource(R.string.search_no_result),
                        style = WeTheme.typography.small,
                        color = colors.textTertiary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                }
                else -> itemsIndexed(results.messages, key = { _, it -> it.id }) { index, message ->
                    val mine = message.role == Role.USER
                    val name = if (mine) myName else contact?.name.orEmpty()
                    SearchResultRow(
                        avatarPath = if (mine) myAvatar else contact?.avatarPath,
                        name = name,
                        avatarColor = if (mine) SelfAvatarColor else contact?.avatarColor ?: SelfAvatarColor,
                        title = AnnotatedString(name),
                        subtitle = voicePrefix(message) + highlightMatch(message.content.replace('\n', ' '), results.query, brand),
                        trailing = viewModel.formatter.forList(message.createdAt, now),
                        onClick = { onOpenMessage(message.contactId, message.id) },
                        showDivider = index != results.messages.lastIndex,
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
