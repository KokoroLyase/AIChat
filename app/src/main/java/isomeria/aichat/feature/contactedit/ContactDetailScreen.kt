package isomeria.aichat.feature.contactedit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.R
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.MemoryRepository
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.navigation.ContactDetailRoute
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.AIChatTheme
import isomeria.aichat.ui.theme.AvatarPalette
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ContactDetailState(
    val loaded: Boolean = false,
    val contact: Contact? = null,
    val modelName: String = "",
    val providerName: String = "",
    val memoryCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    contacts: ContactRepository,
    providers: ProviderRepository,
    memories: MemoryRepository,
) : ViewModel() {
    private val id = savedState.toRoute<ContactDetailRoute>().contactId

    val state: StateFlow<ContactDetailState> = contacts.observeContact(id).flatMapLatest { contact ->
        if (contact == null) {
            flowOf(ContactDetailState(loaded = true))
        } else {
            combine(
                providers.observeModels(contact.providerId),
                providers.observeConfig(contact.providerId),
                memories.observeCount(id),
            ) { models, config, memoryCount ->
                ContactDetailState(
                    loaded = true,
                    contact = contact,
                    modelName = models.firstOrNull { it.modelId == contact.model }?.displayName ?: contact.model,
                    providerName = config?.name.orEmpty(),
                    memoryCount = memoryCount,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactDetailState())
}

/** 联系人详情：仿好友资料页，大头像、名称、当前模型；按钮「发消息」「编辑」。 */
@Composable
fun ContactDetailScreen(
    onBack: () -> Unit,
    onSendMessage: () -> Unit,
    onEdit: () -> Unit,
    onOpenMemory: () -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 联系人被删除后自动返回
    if (state.loaded && state.contact == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
    }
    ContactDetailContent(state, onBack, onSendMessage, onEdit, onOpenMemory)
}

@Composable
fun ContactDetailContent(
    state: ContactDetailState,
    onBack: () -> Unit,
    onSendMessage: () -> Unit,
    onEdit: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val contact = state.contact
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = "", onBack = onBack, background = colors.listBackground)
        if (contact == null) return@Column
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.listBackground)
                .padding(start = 24.dp, end = dimens.horizontalPadding, top = 8.dp, bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(
                contact.avatarPath,
                contact.name,
                contact.avatarColor,
                dimens.profileAvatar,
                corner = dimens.profileAvatarCorner,
            )
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    contact.name,
                    style = WeTheme.typography.contactName,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.providerName.isNotEmpty()) {
                    Text(
                        stringResource(R.string.contact_provider_line, state.providerName),
                        style = WeTheme.typography.small,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.contact_model_line, state.modelName),
                    style = WeTheme.typography.small,
                    color = colors.textTertiary,
                )
                Text(
                    stringResource(
                        R.string.contact_thinking_line,
                        stringResource(
                            when (contact.thinking) {
                                ThinkingLevel.OFF -> R.string.thinking_off
                                ThinkingLevel.HIGH -> R.string.thinking_high
                                ThinkingLevel.MAX -> R.string.thinking_max
                            },
                        ),
                    ),
                    style = WeTheme.typography.small,
                    color = colors.textTertiary,
                )
            }
        }
        GroupSpacer()
        WeListRow(
            title = stringResource(R.string.memory_title),
            value = if (state.memoryCount > 0) stringResource(R.string.memory_count, state.memoryCount) else null,
            onClick = onOpenMemory,
        )
        GroupSpacer()
        ActionRow(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.contact_send_message), onSendMessage)
        WeDivider()
        ActionRow(Icons.Outlined.Edit, stringResource(R.string.contact_edit), onEdit)
    }
}

@Composable
private fun ActionRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    val colors = WeTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .clickable(onClick = onClick)
            .heightIn(min = WeTheme.dimens.listRowHeight),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = colors.link, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, style = WeTheme.typography.listTitle, color = colors.link)
        }
    }
}

internal val previewState = ContactDetailState(
    loaded = true,
    contact = Contact(1, "小雨", null, AvatarPalette[0], 1, "deepseek-flash", createdAt = 0, updatedAt = 0),
    modelName = "DeepSeek V4.1 Flash",
    providerName = "DeepSeek",
    memoryCount = 3,
)

@Preview(name = "联系人详情 浅色", showBackground = true)
@Composable
private fun ContactDetailLightPreview() {
    AIChatTheme(darkTheme = false) { ContactDetailContent(previewState, {}, {}, {}, {}) }
}

@Preview(name = "联系人详情 深色", showBackground = true)
@Composable
private fun ContactDetailDarkPreview() {
    AIChatTheme(darkTheme = true) { ContactDetailContent(previewState, {}, {}, {}, {}) }
}
