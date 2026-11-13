package isomeria.aichat.feature.globalmemory

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.TokenEstimator
import isomeria.aichat.data.db.GlobalMemoryCategory
import isomeria.aichat.data.db.GlobalMemoryItem
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.GlobalMemoryRepository
import isomeria.aichat.data.repo.GlobalMemoryTree
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.navigation.GlobalMemoryItemRoute
import isomeria.aichat.navigation.NEW_GLOBAL_ITEM
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.TopBarIcon
import isomeria.aichat.ui.components.WeActionRow
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeIcon
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 全局记忆页面共用的状态：全部数据、用量、开启的联系人和会收到内容的服务商。 */
data class GlobalMemoryState(
    val loaded: Boolean = false,
    val tree: GlobalMemoryTree = GlobalMemoryTree(),
    val enabledCount: Int = 0,
    val chars: Int = 0,
    val tokens: Int = 0,
    val contactsUsing: Int = 0,
    val contactsTotal: Int = 0,
    /** 开启了全局记忆的联系人所用的服务商名称，去重 */
    val providerNames: List<String> = emptyList(),
)

/** 全局记忆各页的操作；测试和截图里用默认的空实现 */
data class GlobalMemoryActions(
    val addCategory: (String) -> Unit = {},
    val renameCategory: (Long, String) -> Unit = { _, _ -> },
    val deleteCategory: (Long) -> Unit = {},
    val moveCategory: (Long, Int) -> Unit = { _, _ -> },
    val addTopic: (Long, String) -> Unit = { _, _ -> },
    val renameTopic: (Long, String) -> Unit = { _, _ -> },
    val deleteTopic: (Long) -> Unit = {},
    val moveTopic: (Long, Int) -> Unit = { _, _ -> },
    val setItemEnabled: (Long, Boolean) -> Unit = { _, _ -> },
    val moveItem: (Long, Int) -> Unit = { _, _ -> },
    val deleteItem: (Long) -> Unit = {},
    val setAllContacts: (Boolean) -> Unit = {},
)

@HiltViewModel
class GlobalMemoryViewModel @Inject constructor(
    private val repo: GlobalMemoryRepository,
    contacts: ContactRepository,
    providers: ProviderRepository,
    private val contextBuilder: ContextBuilder,
) : ViewModel() {
    val state: StateFlow<GlobalMemoryState> = combine(repo.tree, contacts.observeContacts(), providers.llmConfigs) { tree, all, configs ->
        val entries = tree.entries()
        val using = all.filter { it.useGlobalMemory }
        val names = using.mapNotNull { contact -> configs.firstOrNull { it.id == contact.providerId }?.name }.distinct()
        GlobalMemoryState(
            loaded = true,
            tree = tree,
            enabledCount = entries.size,
            chars = entries.sumOf { it.content.length },
            // 跨服务商按较高的估算显示：汉字按 1 个 token
            tokens = if (entries.isEmpty()) 0 else CONSERVATIVE.text(contextBuilder.globalMemoryBlock(entries)),
            contactsUsing = using.size,
            contactsTotal = all.size,
            providerNames = names,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlobalMemoryState())

    val actions = GlobalMemoryActions(
        addCategory = { name -> launch { repo.addCategory(name) } },
        renameCategory = { id, name -> launch { repo.renameCategory(id, name) } },
        deleteCategory = { id -> launch { repo.deleteCategory(id) } },
        moveCategory = { id, delta -> launch { repo.moveCategory(id, delta) } },
        addTopic = { category, name -> launch { repo.addTopic(category, name) } },
        renameTopic = { id, name -> launch { repo.renameTopic(id, name) } },
        deleteTopic = { id -> launch { repo.deleteTopic(id) } },
        moveTopic = { id, delta -> launch { repo.moveTopic(id, delta) } },
        setItemEnabled = { id, on -> launch { repo.setItemEnabled(id, on) } },
        moveItem = { id, delta -> launch { repo.moveItem(id, delta) } },
        deleteItem = { id -> launch { repo.deleteItem(id) } },
        setAllContacts = { on -> launch { repo.setUseGlobalMemoryForAll(on) } },
    )

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private companion object {
        val CONSERVATIVE = TokenEstimator(1.0)
    }
}

// ---------- 第一页：全局记忆 ----------

@Composable
fun GlobalMemoryScreen(onBack: () -> Unit, onOpenCategory: (Long) -> Unit, viewModel: GlobalMemoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlobalMemoryContent(state, viewModel.actions, onBack, onOpenCategory)
}

@Composable
fun GlobalMemoryContent(
    state: GlobalMemoryState,
    actions: GlobalMemoryActions,
    onBack: () -> Unit,
    onOpenCategory: (Long) -> Unit,
) {
    val colors = WeTheme.colors
    var adding by rememberSaveable { mutableStateOf(false) }
    var menuFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    var contactsSheet by rememberSaveable { mutableStateOf(false) }
    val tree = state.tree

    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.global_memory_title), onBack = onBack)
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            FootnoteText(stringResource(R.string.global_memory_notice))
            FootnoteText(
                if (state.providerNames.isEmpty()) stringResource(R.string.global_memory_providers_none)
                else stringResource(R.string.global_memory_providers, state.providerNames.joinToString(stringResource(R.string.list_separator))),
            )
            GroupSpacer()
            ValueRow(
                title = stringResource(R.string.global_memory_contacts),
                value = stringResource(R.string.global_memory_contacts_value, state.contactsUsing, state.contactsTotal),
                onClick = { contactsSheet = true },
            )
            FootnoteText(stringResource(R.string.global_memory_usage, state.enabledCount, state.chars, state.tokens))
            FootnoteText(stringResource(R.string.global_memory_usage_note) + stringResource(R.string.global_memory_send_all))

            SectionHeader(stringResource(R.string.global_memory_section_categories))
            tree.categories.forEachIndexed { index, category ->
                val topics = tree.topicsOf(category.id)
                OrderedRow(
                    title = category.name,
                    detail = stringResource(R.string.global_memory_category_detail, topics.size, tree.itemsInCategory(category.id).size),
                    onClick = { onOpenCategory(category.id) },
                    onLongClick = { menuFor = category.id },
                    canMoveUp = index > 0,
                    canMoveDown = index < tree.categories.lastIndex,
                    onMove = { actions.moveCategory(category.id, it) },
                    showDivider = true,
                )
            }
            AddRow(stringResource(R.string.global_memory_category_add)) { adding = true }
            if (tree.categories.isEmpty()) FootnoteText(stringResource(R.string.global_memory_empty))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (contactsSheet) {
        WeActionSheet(
            title = stringResource(R.string.global_memory_contacts),
            options = listOf(
                SheetOption(stringResource(R.string.global_memory_all_on)) { actions.setAllContacts(true) },
                SheetOption(stringResource(R.string.global_memory_all_off), destructive = true) { actions.setAllContacts(false) },
            ),
            onDismiss = { contactsSheet = false },
        )
    }
    if (adding) {
        NameDialog(stringResource(R.string.global_memory_category_name), "", onDismiss = { adding = false }) { actions.addCategory(it) }
    }
    val menuTarget = tree.categories.indexOfFirst { it.id == menuFor }
    if (menuTarget >= 0) {
        val category = tree.categories[menuTarget]
        OrderMenu(
            canMoveUp = menuTarget > 0,
            canMoveDown = menuTarget < tree.categories.lastIndex,
            deleteLabel = stringResource(R.string.global_memory_delete_category),
            onRename = { renaming = category.id },
            onMove = { actions.moveCategory(category.id, it) },
            onDelete = { deleting = category.id },
            onDismiss = { menuFor = null },
        )
    }
    tree.categories.firstOrNull { it.id == renaming }?.let { category ->
        NameDialog(stringResource(R.string.global_memory_category_name), category.name, onDismiss = { renaming = null }) {
            actions.renameCategory(category.id, it)
        }
    }
    tree.categories.firstOrNull { it.id == deleting }?.let { category ->
        DeleteCategoryDialog(category, tree, onDismiss = { deleting = null }) { actions.deleteCategory(category.id) }
    }
}

@Composable
private fun DeleteCategoryDialog(category: GlobalMemoryCategory, tree: GlobalMemoryTree, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val topics = tree.topicsOf(category.id).size
    val items = tree.itemsInCategory(category.id).size
    WeDialog(
        message = if (topics == 0) stringResource(R.string.global_memory_delete_category_empty, category.name)
        else stringResource(R.string.global_memory_delete_category_confirm, category.name, topics, items),
        confirmText = stringResource(R.string.action_delete),
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            onConfirm()
        },
    )
}

// ---------- 第二页：类别 ----------

@Composable
fun GlobalMemoryCategoryScreen(
    categoryId: Long,
    onBack: () -> Unit,
    onOpenTopic: (Long) -> Unit,
    viewModel: GlobalMemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlobalMemoryCategoryContent(categoryId, state, viewModel.actions, onBack, onOpenTopic)
}

@Composable
fun GlobalMemoryCategoryContent(
    categoryId: Long,
    state: GlobalMemoryState,
    actions: GlobalMemoryActions,
    onBack: () -> Unit,
    onOpenTopic: (Long) -> Unit,
) {
    val colors = WeTheme.colors
    val tree = state.tree
    val category = tree.categories.firstOrNull { it.id == categoryId }
    // 删除后（或恢复数据后）这个类别不在了：回到上一页
    LaunchedEffect(state.loaded, category == null) { if (state.loaded && category == null) onBack() }
    var pageMenu by rememberSaveable { mutableStateOf(false) }
    var renamingSelf by rememberSaveable { mutableStateOf(false) }
    var deletingSelf by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var menuFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val topics = tree.topicsOf(categoryId)

    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = category?.name.orEmpty(), onBack = onBack) {
            if (category != null) TopBarIcon(WeIcons.More, stringResource(R.string.global_memory_more), { pageMenu = true })
        }
        if (category == null) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            topics.forEachIndexed { index, topic ->
                val items = tree.itemsOf(topic.id)
                OrderedRow(
                    title = topic.name,
                    detail = stringResource(R.string.global_memory_topic_detail, items.size, items.count { it.enabled }),
                    onClick = { onOpenTopic(topic.id) },
                    onLongClick = { menuFor = topic.id },
                    canMoveUp = index > 0,
                    canMoveDown = index < topics.lastIndex,
                    onMove = { actions.moveTopic(topic.id, it) },
                    showDivider = true,
                )
            }
            AddRow(stringResource(R.string.global_memory_topic_add)) { adding = true }
            if (topics.isEmpty()) FootnoteText(stringResource(R.string.global_memory_topics_empty))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (category == null) return
    if (pageMenu) {
        WeActionSheet(
            options = listOf(
                SheetOption(stringResource(R.string.global_memory_rename)) { renamingSelf = true },
                SheetOption(stringResource(R.string.global_memory_delete_category), destructive = true) { deletingSelf = true },
            ),
            onDismiss = { pageMenu = false },
        )
    }
    if (renamingSelf) {
        NameDialog(stringResource(R.string.global_memory_category_name), category.name, onDismiss = { renamingSelf = false }) {
            actions.renameCategory(category.id, it)
        }
    }
    if (deletingSelf) DeleteCategoryDialog(category, tree, onDismiss = { deletingSelf = false }) { actions.deleteCategory(category.id) }
    if (adding) NameDialog(stringResource(R.string.global_memory_topic_name), "", onDismiss = { adding = false }) { actions.addTopic(category.id, it) }
    val menuTarget = topics.indexOfFirst { it.id == menuFor }
    if (menuTarget >= 0) {
        val topic = topics[menuTarget]
        OrderMenu(
            canMoveUp = menuTarget > 0,
            canMoveDown = menuTarget < topics.lastIndex,
            deleteLabel = stringResource(R.string.global_memory_delete_topic),
            onRename = { renaming = topic.id },
            onMove = { actions.moveTopic(topic.id, it) },
            onDelete = { deleting = topic.id },
            onDismiss = { menuFor = null },
        )
    }
    topics.firstOrNull { it.id == renaming }?.let { topic ->
        NameDialog(stringResource(R.string.global_memory_topic_name), topic.name, onDismiss = { renaming = null }) { actions.renameTopic(topic.id, it) }
    }
    topics.firstOrNull { it.id == deleting }?.let { topic ->
        DeleteTopicDialog(topic.name, tree.itemsOf(topic.id).size, onDismiss = { deleting = null }) { actions.deleteTopic(topic.id) }
    }
}

@Composable
private fun DeleteTopicDialog(name: String, items: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    WeDialog(
        message = if (items == 0) stringResource(R.string.global_memory_delete_topic_empty, name)
        else stringResource(R.string.global_memory_delete_topic_confirm, name, items),
        confirmText = stringResource(R.string.action_delete),
        destructive = true,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            onConfirm()
        },
    )
}

// ---------- 第三页：主题 ----------

@Composable
fun GlobalMemoryTopicScreen(
    topicId: Long,
    onBack: () -> Unit,
    onOpenItem: (topicId: Long, itemId: Long) -> Unit,
    viewModel: GlobalMemoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GlobalMemoryTopicContent(topicId, state, viewModel.actions, onBack, onOpenItem)
}

@Composable
fun GlobalMemoryTopicContent(
    topicId: Long,
    state: GlobalMemoryState,
    actions: GlobalMemoryActions,
    onBack: () -> Unit,
    onOpenItem: (topicId: Long, itemId: Long) -> Unit,
) {
    val colors = WeTheme.colors
    val tree = state.tree
    val topic = tree.topics.firstOrNull { it.id == topicId }
    LaunchedEffect(state.loaded, topic == null) { if (state.loaded && topic == null) onBack() }
    var pageMenu by rememberSaveable { mutableStateOf(false) }
    var renamingSelf by rememberSaveable { mutableStateOf(false) }
    var deletingSelf by rememberSaveable { mutableStateOf(false) }
    var menuFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val items = tree.itemsOf(topicId)

    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = topic?.name.orEmpty(), onBack = onBack) {
            if (topic != null) TopBarIcon(WeIcons.More, stringResource(R.string.global_memory_more), { pageMenu = true })
        }
        if (topic == null) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            items.forEachIndexed { index, item ->
                ItemRow(
                    item = item,
                    onClick = { onOpenItem(topicId, item.id) },
                    onLongClick = { menuFor = item.id },
                    onEnabled = { actions.setItemEnabled(item.id, it) },
                    canMoveUp = index > 0,
                    canMoveDown = index < items.lastIndex,
                    onMove = { actions.moveItem(item.id, it) },
                )
            }
            AddRow(stringResource(R.string.global_memory_item_add)) { onOpenItem(topicId, NEW_GLOBAL_ITEM) }
            if (items.isEmpty()) FootnoteText(stringResource(R.string.global_memory_items_empty))
            Spacer(Modifier.height(24.dp))
        }
    }

    if (topic == null) return
    if (pageMenu) {
        WeActionSheet(
            options = listOf(
                SheetOption(stringResource(R.string.global_memory_rename)) { renamingSelf = true },
                SheetOption(stringResource(R.string.global_memory_delete_topic), destructive = true) { deletingSelf = true },
            ),
            onDismiss = { pageMenu = false },
        )
    }
    if (renamingSelf) {
        NameDialog(stringResource(R.string.global_memory_topic_name), topic.name, onDismiss = { renamingSelf = false }) { actions.renameTopic(topic.id, it) }
    }
    if (deletingSelf) DeleteTopicDialog(topic.name, items.size, onDismiss = { deletingSelf = false }) { actions.deleteTopic(topic.id) }
    val menuTarget = items.indexOfFirst { it.id == menuFor }
    if (menuTarget >= 0) {
        val item = items[menuTarget]
        OrderMenu(
            canMoveUp = menuTarget > 0,
            canMoveDown = menuTarget < items.lastIndex,
            deleteLabel = stringResource(R.string.global_memory_item_delete),
            onRename = null,
            onMove = { actions.moveItem(item.id, it) },
            onDelete = { deleting = item.id },
            onDismiss = { menuFor = null },
        )
    }
    if (items.any { it.id == deleting }) {
        val id = deleting!!
        WeDialog(
            message = stringResource(R.string.global_memory_item_delete_confirm),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                actions.deleteItem(id)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: GlobalMemoryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
) {
    val colors = WeTheme.colors
    val d = WeTheme.dimens
    val moveActions = moveActions(canMoveUp, canMoveDown, onMove)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .semantics { customActions = moveActions }
            .heightIn(min = d.listRowHeight)
            .dividerBelow(colors.divider, d.horizontalPadding, d.dividerThickness)
            .padding(start = d.horizontalPadding, end = d.horizontalPadding, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.content,
                style = WeTheme.typography.listTitle,
                color = if (item.enabled) colors.textPrimary else colors.textTertiary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.enabled) {
                Text(stringResource(R.string.global_memory_item_disabled), style = WeTheme.typography.listTime, color = colors.textTertiary)
            }
        }
        Spacer(Modifier.width(16.dp))
        WeSwitch(item.enabled, onEnabled, label = stringResource(R.string.global_memory_item_enabled))
    }
}

// ---------- 第四页：编辑一条记忆 ----------

data class GlobalItemDraft(val loaded: Boolean = false, val exists: Boolean = false, val content: String = "", val enabled: Boolean = true)

@HiltViewModel
class GlobalMemoryItemViewModel @Inject constructor(savedState: SavedStateHandle, private val repo: GlobalMemoryRepository) : ViewModel() {
    private val route = savedState.toRoute<GlobalMemoryItemRoute>()
    private val _state = MutableStateFlow(GlobalItemDraft())
    val state: StateFlow<GlobalItemDraft> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val item = if (route.itemId == NEW_GLOBAL_ITEM) null else repo.item(route.itemId)
            _state.value = GlobalItemDraft(loaded = true, exists = item != null, content = item?.content.orEmpty(), enabled = item?.enabled ?: true)
        }
    }

    fun setContent(text: String) = _state.update { it.copy(content = text.take(GlobalMemoryItem.CONTENT_MAX_LENGTH)) }

    fun setEnabled(on: Boolean) = _state.update { it.copy(enabled = on) }

    suspend fun save() {
        val draft = _state.value
        if (draft.content.isBlank()) return
        if (draft.exists) repo.updateItem(route.itemId, draft.content, draft.enabled)
        else repo.addItem(route.topicId, draft.content, draft.enabled)
    }

    suspend fun delete() {
        if (_state.value.exists) repo.deleteItem(route.itemId)
    }
}

@Composable
fun GlobalMemoryItemScreen(onBack: () -> Unit, viewModel: GlobalMemoryItemViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(saving) { if (saving) { viewModel.save(); onBack() } }
    LaunchedEffect(deleting) { if (deleting) { viewModel.delete(); onBack() } }
    GlobalMemoryItemContent(
        state = state,
        onBack = onBack,
        onContent = viewModel::setContent,
        onEnabled = viewModel::setEnabled,
        onSave = { if (!saving) saving = true },
        onDelete = { deleting = true },
    )
}

@Composable
fun GlobalMemoryItemContent(
    state: GlobalItemDraft,
    onBack: () -> Unit,
    onContent: (String) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    var confirmDelete by remember { mutableStateOf(false) }
    val title = stringResource(if (state.exists) R.string.global_memory_item_edit else R.string.global_memory_item_add)
    ImeBackHandler()
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(title = title, onBack = onBack) {
            WeSmallButton(stringResource(R.string.action_save), onSave, enabled = state.loaded && state.content.isNotBlank())
        }
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            Column(Modifier.fillMaxWidth().background(colors.listBackground).padding(dimens.horizontalPadding)) {
                Box {
                    if (state.content.isEmpty()) {
                        Text(
                            stringResource(R.string.global_memory_item_hint),
                            style = WeTheme.typography.small,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                    BasicTextField(
                        value = state.content,
                        onValueChange = onContent,
                        textStyle = WeTheme.typography.small.copy(color = colors.textPrimary, lineHeight = WeTheme.typography.thinking.lineHeight),
                        cursorBrush = SolidColor(colors.brand),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp)
                            .clip(RoundedCornerShape(dimens.inputCorner))
                            .background(if (state.content.isEmpty()) Color.Transparent else colors.pageBackground)
                            .padding(10.dp)
                            .semantics { contentDescription = title },
                    )
                }
            }
            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.global_memory_item_enabled),
                showArrow = false,
                trailing = { WeSwitch(state.enabled, onEnabled, label = stringResource(R.string.global_memory_item_enabled)) },
            )
            FootnoteText(stringResource(R.string.global_memory_item_enabled_hint))
            if (state.exists) {
                GroupSpacer()
                WeActionRow(stringResource(R.string.global_memory_item_delete), onClick = { confirmDelete = true }, danger = true)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete) {
        WeDialog(
            message = stringResource(R.string.global_memory_item_delete_confirm),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
        )
    }
}

// ---------- 共用的行、菜单和弹窗 ----------

/** 可点击进入、长按出菜单的行：标题在左，说明在右。读屏可以直接用「上移」「下移」 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OrderedRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    showDivider: Boolean,
) {
    val colors = WeTheme.colors
    val d = WeTheme.dimens
    val moveActions = moveActions(canMoveUp, canMoveDown, onMove)
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .semantics { customActions = moveActions }
            .heightIn(min = d.listRowHeight)
            .then(if (showDivider) Modifier.dividerBelow(colors.divider, d.horizontalPadding, d.dividerThickness) else Modifier)
            .padding(start = d.horizontalPadding, end = d.horizontalPadding, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 一行放得下时说明靠右；放不下（大字体、窄屏）时说明换到标题下面，标题不被挤掉
        Layout(
            content = {
                Text(title, style = WeTheme.typography.listTitle, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(detail, style = WeTheme.typography.listTitle, color = colors.textTertiary)
            },
            modifier = Modifier.weight(1f),
        ) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val titlePlaceable = measurables[0].measure(loose)
            val detailPlaceable = measurables[1].measure(loose)
            val gap = 16.dp.roundToPx()
            val width = constraints.maxWidth
            if (titlePlaceable.width + gap + detailPlaceable.width <= width) {
                val height = maxOf(titlePlaceable.height, detailPlaceable.height)
                layout(width, height) {
                    titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
                    detailPlaceable.placeRelative(width - detailPlaceable.width, (height - detailPlaceable.height) / 2)
                }
            } else {
                val spacing = 2.dp.roundToPx()
                layout(width, titlePlaceable.height + spacing + detailPlaceable.height) {
                    titlePlaceable.placeRelative(0, 0)
                    detailPlaceable.placeRelative(0, titlePlaceable.height + spacing)
                }
            }
        }
        WeIcon(WeIcons.ChevronRight, null, tint = colors.arrow, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

@Composable
private fun moveActions(canMoveUp: Boolean, canMoveDown: Boolean, onMove: (Int) -> Unit): List<CustomAccessibilityAction> {
    val up = stringResource(R.string.global_memory_move_up)
    val down = stringResource(R.string.global_memory_move_down)
    return buildList {
        if (canMoveUp) add(CustomAccessibilityAction(up) { onMove(-1); true })
        if (canMoveDown) add(CustomAccessibilityAction(down) { onMove(1); true })
    }
}

private fun Modifier.dividerBelow(color: Color, start: Dp, thickness: Dp): Modifier = drawBehind {
    val y = size.height - thickness.toPx() / 2
    drawLine(color, Offset(start.toPx(), y), Offset(size.width, y), thickness.toPx())
}

/** 标题可以换行、值和箭头始终留在右边的行：大字体时值不会被挤掉 */
@Composable
internal fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    val colors = WeTheme.colors
    val d = WeTheme.dimens
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = d.listRowHeight)
            .padding(start = d.horizontalPadding, end = d.horizontalPadding, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = WeTheme.typography.listTitle, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Text(value, style = WeTheme.typography.listTitle, color = colors.textTertiary, maxLines = 1)
        WeIcon(WeIcons.ChevronRight, null, tint = colors.arrow, modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    WeActionRow(text, onClick = onClick)
}

@Composable
private fun OrderMenu(
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    deleteLabel: String,
    onRename: (() -> Unit)?,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    WeActionSheet(
        options = buildList {
            if (onRename != null) add(SheetOption(stringResource(R.string.global_memory_rename), onClick = onRename))
            if (canMoveUp) add(SheetOption(stringResource(R.string.global_memory_move_up)) { onMove(-1) })
            if (canMoveDown) add(SheetOption(stringResource(R.string.global_memory_move_down)) { onMove(1) })
            add(SheetOption(deleteLabel, destructive = true, onClick = onDelete))
        },
        onDismiss = onDismiss,
    )
}

/** 输入类别或主题名称；空的不能确定 */
@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    var text by rememberSaveable { mutableStateOf(initial) }
    WeDialog(
        title = title,
        message = "",
        onDismiss = onDismiss,
        onConfirm = {
            if (text.isNotBlank()) {
                onDismiss()
                onConfirm(text.trim())
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.inputCorner))
                .background(colors.pageBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (text.isEmpty()) Text(stringResource(R.string.global_memory_name_hint), style = WeTheme.typography.listTitle, color = colors.textSecondary)
            BasicTextField(
                value = text,
                onValueChange = { text = it.replace("\n", "").take(GlobalMemoryCategory.NAME_MAX_LENGTH) },
                singleLine = true,
                textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.brand),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
            )
        }
    }
}
