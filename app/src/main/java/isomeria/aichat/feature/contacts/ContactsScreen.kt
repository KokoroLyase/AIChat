package isomeria.aichat.feature.contacts

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.ContactGrouper
import isomeria.aichat.core.ContactSection
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.TopBarIcon
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.AvatarPalette
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(contacts: ContactRepository) : ViewModel() {
    val sections: StateFlow<List<ContactSection<Contact>>?> = contacts.observeSections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** 通讯录：第一行「新建联系人」，联系人按拼音首字母分组，右侧 A–Z 索引条。 */
@Composable
fun ContactsScreen(
    onOpenContact: (Long) -> Unit,
    onNewContact: () -> Unit,
    onSearch: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    ContactsContent(sections.orEmpty(), onOpenContact, onNewContact, onSearch)
}

@Composable
fun ContactsContent(
    sections: List<ContactSection<Contact>>,
    onOpenContact: (Long) -> Unit,
    onNewContact: () -> Unit,
    onSearch: () -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val scope = rememberCoroutineScope()
    var touchedLetter by remember { mutableStateOf<Char?>(null) }

    // 每个分组标题在列表里的位置：第 0 行是「新建联系人」
    val headerIndex = remember(sections) {
        var index = 1
        sections.associate { section ->
            val at = index
            index += 1 + section.items.size
            section.letter to at
        }
    }
    val total = sections.sumOf { it.items.size }

    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.tab_contacts)) {
            TopBarIcon(WeIcons.Search, stringResource(R.string.action_search), onSearch)
            TopBarIcon(WeIcons.AddCircle, stringResource(R.string.action_add), onNewContact)
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "new") {
                    WeListRow(
                        title = stringResource(R.string.contacts_new),
                        onClick = onNewContact,
                        showArrow = false,
                        leading = {
                            Box(
                                Modifier
                                    .size(dimens.listRowAvatar)
                                    .clip(RoundedCornerShape(dimens.avatarCorner))
                                    .background(colors.brand),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(WeIcons.PersonAdd, null, tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        },
                    )
                }
                sections.forEach { section ->
                    item(key = "h${section.letter}") { SectionHeader(section.letter.toString()) }
                    itemsIndexed(section.items, key = { _, c -> c.id }) { index, contact ->
                        WeListRow(
                            title = contact.name,
                            onClick = { onOpenContact(contact.id) },
                            showArrow = false,
                            showDivider = index != section.items.lastIndex,
                            leading = {
                                Avatar(contact.avatarPath, contact.name, contact.avatarColor, dimens.listRowAvatar)
                            },
                        )
                    }
                }
                item(key = "footer") {
                    Text(
                        if (total == 0) {
                            stringResource(R.string.contacts_empty_hint)
                        } else {
                            stringResource(R.string.contacts_count, total)
                        },
                        style = WeTheme.typography.small,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 32.dp),
                    )
                }
            }

            if (sections.isNotEmpty()) {
                IndexBar(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    onLetter = { letter ->
                        touchedLetter = letter
                        // 没有这个字母的分组时，跳到它后面最近的一组
                        val target = ContactGrouper.INDEX_LETTERS
                            .dropWhile { it != letter }
                            .firstNotNullOfOrNull { headerIndex[it] }
                            ?: headerIndex.values.maxOrNull()
                        if (target != null) scope.launch { listState.scrollToItem(target) }
                    },
                    onRelease = { touchedLetter = null },
                )
            }

            touchedLetter?.let { letter ->
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.popupBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(letter.toString(), style = WeTheme.typography.indexOverlay, color = Color.White)
                }
            }
        }
    }
}

/** 右侧 A–Z 索引条，支持点按和滑动定位 */
@Composable
private fun IndexBar(modifier: Modifier, onLetter: (Char) -> Unit, onRelease: () -> Unit) {
    val colors = WeTheme.colors
    val letters = ContactGrouper.INDEX_LETTERS
    val itemHeight = 16.dp
    var active by remember { mutableStateOf<Char?>(null) }
    Column(
        modifier
            .padding(end = 2.dp)
            .width(20.dp)
            .pointerInput(letters) {
                val step = itemHeight.toPx()
                fun pick(y: Float) {
                    val letter = letters[(y / step).toInt().coerceIn(0, letters.lastIndex)]
                    if (letter != active) {
                        active = letter
                        onLetter(letter)
                    }
                }
                // 按下即定位，手指滑动时跟着切换，抬起时收起中间的放大字母
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    pick(down.position.y)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        pick(change.position.y)
                    }
                    active = null
                    onRelease()
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { letter ->
            Box(
                Modifier
                    .height(itemHeight)
                    .size(itemHeight)
                    .clip(CircleShape)
                    .background(if (letter == active) colors.brand else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    letter.toString(),
                    style = WeTheme.typography.indexLetter,
                    color = if (letter == active) Color.White else colors.indexLetter,
                )
            }
        }
    }
}

internal val previewSections = listOf(
    ContactSection(
        'A',
        listOf(Contact(1, "阿哲", null, AvatarPalette[1], 1, "deepseek-flash", createdAt = 0, updatedAt = 0)),
    ),
    ContactSection(
        'X',
        listOf(
            Contact(2, "小雨", null, AvatarPalette[0], 1, "deepseek-flash", createdAt = 0, updatedAt = 0),
            Contact(3, "夏天", null, AvatarPalette[4], 1, "deepseek-flash", createdAt = 0, updatedAt = 0),
        ),
    ),
)

@Preview(name = "通讯录 浅色", showBackground = true)
@Composable
private fun ContactsLightPreview() {
    AIChatTheme(darkTheme = false) { ContactsContent(previewSections, {}, {}, {}) }
}

@Preview(name = "通讯录 深色", showBackground = true)
@Composable
private fun ContactsDarkPreview() {
    AIChatTheme(darkTheme = true) { ContactsContent(previewSections, {}, {}, {}) }
}
