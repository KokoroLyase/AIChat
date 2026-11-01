package isomeria.aichat.feature.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.feature.chatlist.ChatListScreen
import isomeria.aichat.feature.contacts.ContactsScreen
import isomeria.aichat.feature.me.MeScreen
import isomeria.aichat.ui.components.TabItem
import isomeria.aichat.ui.components.WeBottomBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(chatRepository: ChatRepository) : ViewModel() {
    val totalUnread: StateFlow<Int> = chatRepository.observeTotalUnread()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/** 底部三个 Tab：智信、通讯录、我。可以左右滑动切换。 */
@Composable
fun MainScreen(
    onOpenChat: (Long) -> Unit,
    onOpenContact: (Long) -> Unit,
    onNewContact: () -> Unit,
    onSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val unread by viewModel.totalUnread.collectAsStateWithLifecycle()
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val tabs = listOf(
        TabItem(stringResource(R.string.tab_chats), Icons.Outlined.ChatBubbleOutline, Icons.Filled.ChatBubble, unread),
        TabItem(stringResource(R.string.tab_contacts), Icons.Outlined.Contacts, Icons.Filled.Contacts),
        TabItem(stringResource(R.string.tab_me), Icons.Outlined.Person, Icons.Filled.Person),
    )
    Column(Modifier.fillMaxSize().background(WeTheme.colors.pageBackground)) {
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
            beyondViewportPageCount = 2,
        ) { page ->
            when (page) {
                0 -> ChatListScreen(
                    totalUnread = unread,
                    onOpenChat = onOpenChat,
                    onNewContact = onNewContact,
                    onSearch = onSearch,
                    onGoContacts = { scope.launch { pager.animateScrollToPage(1) } },
                )
                1 -> ContactsScreen(
                    onOpenContact = onOpenContact,
                    onNewContact = onNewContact,
                    onSearch = onSearch,
                )
                else -> MeScreen(onOpenSettings = onOpenSettings, onOpenProfile = onOpenProfile)
            }
        }
        WeBottomBar(
            items = tabs,
            selectedIndex = pager.currentPage,
            onSelect = { scope.launch { pager.scrollToPage(it) } },
        )
    }
}
