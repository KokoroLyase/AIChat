package isomeria.aichat.feature.chatlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import isomeria.aichat.ui.components.StickToTop
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import isomeria.aichat.R
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.MenuItem
import isomeria.aichat.ui.components.TopBarIcon
import isomeria.aichat.ui.components.UnreadBadge
import isomeria.aichat.ui.components.WeButton
import isomeria.aichat.ui.components.WeContextMenu
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeDropdownMenu
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.AvatarPalette
import isomeria.aichat.ui.theme.WeTheme
import kotlin.math.roundToInt

/** 智信（聊天列表） */
@Composable
fun ChatListScreen(
    totalUnread: Int,
    onOpenChat: (Long) -> Unit,
    onNewContact: () -> Unit,
    onSearch: () -> Unit,
    onGoContacts: () -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<ConversationUi?>(null) }
    ChatListContent(
        items = items,
        totalUnread = totalUnread,
        onOpenChat = onOpenChat,
        onNewContact = onNewContact,
        onSearch = onSearch,
        onGoContacts = onGoContacts,
        onTogglePin = viewModel::togglePin,
        onToggleUnread = viewModel::toggleUnread,
        onDelete = { confirmDelete = it },
    )
    confirmDelete?.let { item ->
        WeDialog(
            message = stringResource(R.string.chat_delete_confirm),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmDelete = null },
            onConfirm = {
                viewModel.delete(item.contactId)
                confirmDelete = null
            },
        )
    }
}

@Composable
fun ChatListContent(
    items: List<ConversationUi>?,
    totalUnread: Int,
    onOpenChat: (Long) -> Unit,
    onNewContact: () -> Unit,
    onSearch: () -> Unit,
    onGoContacts: () -> Unit,
    onTogglePin: (ConversationUi) -> Unit,
    onToggleUnread: (ConversationUi) -> Unit,
    onDelete: (ConversationUi) -> Unit,
) {
    val colors = WeTheme.colors
    var showAddMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(
            title = if (totalUnread > 0) {
                stringResource(R.string.title_chats_unread, totalUnread)
            } else {
                stringResource(R.string.tab_chats)
            },
        ) {
            TopBarIcon(WeIcons.Search, stringResource(R.string.action_search), onSearch)
            Box {
                TopBarIcon(WeIcons.AddCircle, stringResource(R.string.action_add), { showAddMenu = true })
                if (showAddMenu) {
                    WeDropdownMenu(
                        items = listOf(
                            MenuItem(stringResource(R.string.menu_new_contact), WeIcons.PersonAdd, onNewContact),
                        ),
                        onDismiss = { showAddMenu = false },
                    )
                }
            }
        }
        when {
            items == null -> Unit
            items.isEmpty() -> EmptyChats(onGoContacts)
            else -> {
                // 0.9.1 审计：会话排到第一位时还停在最顶上；会话换位置、删除时滑动和淡出，和收藏、聊天消息一样
                val listState = rememberLazyListState()
                StickToTop(listState, items.first().contactId)
                LazyColumn(Modifier.fillMaxSize().background(colors.listBackground), state = listState) {
                itemsIndexed(items, key = { _, item -> item.contactId }) { index, item ->
                    ConversationRow(
                        modifier = Modifier.animateItem(),
                        item = item,
                        showDivider = index != items.lastIndex,
                        onClick = { onOpenChat(item.contactId) },
                        onTogglePin = { onTogglePin(item) },
                        onToggleUnread = { onToggleUnread(item) },
                        onDelete = { onDelete(item) },
                    )
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    item: ConversationUi,
    showDivider: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onToggleUnread: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    var pressAt by remember { mutableStateOf(Offset.Zero) }
    var menuAt by remember { mutableStateOf<IntOffset?>(null) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.chatRowHeight)
                .background(if (item.pinned) colors.pinnedBackground else colors.listBackground)
                .pointerInput(Unit) {
                    // 只记录按下位置，用来在手指处弹出菜单，不消费事件
                    awaitEachGesture {
                        pressAt = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
                    }
                }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuAt = IntOffset(pressAt.x.roundToInt(), pressAt.y.roundToInt()) },
                )
                .drawBehind {
                    if (showDivider) {
                        val y = size.height - dimens.dividerThickness.toPx() / 2
                        drawLine(
                            colors.divider,
                            Offset(dimens.chatRowTextStart.toPx(), y),
                            Offset(size.width, y),
                            dimens.dividerThickness.toPx(),
                        )
                    }
                }
                .padding(horizontal = dimens.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Avatar(item.avatarPath, item.name, item.avatarColor, dimens.chatRowAvatar)
                when {
                    item.unread > 0 -> UnreadBadge(
                        item.unread,
                        Modifier.align(Alignment.TopEnd).offset(x = 7.dp, y = (-7).dp),
                    )
                    item.markedUnread -> UnreadBadge(
                        0,
                        Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp),
                        dotOnly = true,
                    )
                }
            }
            Spacer(Modifier.width(dimens.chatRowTextStart - dimens.horizontalPadding - dimens.chatRowAvatar))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name,
                        style = WeTheme.typography.listTitle,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        item.time,
                        style = WeTheme.typography.listTime,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                val draftLabel = stringResource(R.string.preview_draft)
                Text(
                    buildAnnotatedString {
                        if (item.isDraft) {
                            withStyle(SpanStyle(color = colors.danger)) { append(draftLabel) }
                            append(" ")
                        }
                        append(item.preview)
                    },
                    style = WeTheme.typography.small,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        menuAt?.let { at ->
            WeContextMenu(
                pressOffset = at,
                items = listOf(
                    MenuItem(
                        stringResource(if (item.pinned) R.string.chat_menu_unpin else R.string.chat_menu_pin),
                        onClick = onTogglePin,
                    ),
                    MenuItem(
                        stringResource(
                            if (item.unread > 0 || item.markedUnread) R.string.chat_menu_mark_read else R.string.chat_menu_mark_unread,
                        ),
                        onClick = onToggleUnread,
                    ),
                    MenuItem(stringResource(R.string.chat_menu_delete), onClick = onDelete),
                ),
                onDismiss = { menuAt = null },
            )
        }
    }
}

@Composable
private fun EmptyChats(onGoContacts: () -> Unit) {
    val colors = WeTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.chat_list_empty), style = WeTheme.typography.listTitle, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.chat_list_empty_hint), style = WeTheme.typography.small, color = colors.textTertiary)
        Spacer(Modifier.height(24.dp))
        WeButton(stringResource(R.string.chat_list_go_contacts), onGoContacts, Modifier.width(184.dp))
        Spacer(Modifier.height(96.dp))
    }
}

internal val previewItems = listOf(
    ConversationUi(1, "小雨", null, AvatarPalette[0], "晚安，明天见～", false, "21:30", 2, false, true),
    ConversationUi(2, "阿哲", null, AvatarPalette[3], "[图片]", false, "昨天", 0, true, false),
    ConversationUi(3, "Mia", null, AvatarPalette[5], "周末一起去看展吗", true, "星期三", 0, false, false),
)

@Preview(name = "聊天列表 浅色", showBackground = true)
@Composable
private fun ChatListLightPreview() {
    AIChatTheme(darkTheme = false) {
        ChatListContent(previewItems, 2, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(name = "聊天列表 深色", showBackground = true)
@Composable
private fun ChatListDarkPreview() {
    AIChatTheme(darkTheme = true) {
        ChatListContent(previewItems, 2, {}, {}, {}, {}, {}, {}, {})
    }
}
