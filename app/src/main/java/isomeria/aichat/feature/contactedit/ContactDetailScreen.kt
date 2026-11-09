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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
import isomeria.aichat.data.llm.ConfigurationIssue
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.llm.RequestTemperature
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.MemoryRepository
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.feature.settings.ConfigActionRow
import isomeria.aichat.navigation.ContactDetailRoute
import isomeria.aichat.ui.components.configurationIssueText
import isomeria.aichat.ui.components.temperatureText
import isomeria.aichat.ui.components.thinkingDisplayName
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.GroupSpacer
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
    val thinkingName: String? = null,
    /** 聊天请求里实际的温度；null 时按保存的值显示 */
    val temperature: RequestTemperature? = null,
    /** 聊天和记忆各自的配置问题；不等聊天失败才告诉用户 */
    val chatIssue: ConfigurationIssue? = null,
    val memoryIssue: ConfigurationIssue? = null,
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
                val chatModel = models.firstOrNull { it.modelId == contact.model }
                val chatOption = contact.thinkingOption ?: ModelRules.legacyOption(contact.thinking)
                ContactDetailState(
                    loaded = true,
                    contact = contact,
                    modelName = models.firstOrNull { it.modelId == contact.model }?.displayName ?: contact.model,
                    providerName = config?.name.orEmpty(),
                    memoryCount = memoryCount,
                    thinkingName = contact.thinkingOption?.let { value ->
                        thinkingDisplayName(ModelRules.options(models.firstOrNull { it.modelId == contact.model }), value)
                    },
                    temperature = config?.let { ModelRules.requestTemperature(it, chatModel, chatOption, contact.temperature) },
                    chatIssue = config?.let { ModelRules.validate(it, chatModel, chatOption, contact.temperature) },
                    memoryIssue = config?.let {
                        val memoryModel = providers.memoryModel(contact, it)
                        ModelRules.validate(it, models.firstOrNull { m -> m.modelId == memoryModel }, contact.memoryThinkingOption, 0.3f)
                    },
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactDetailState())
}

/** 联系人详情：仿好友资料页，大头像、名称、服务商、模型、思考强度、温度；「朋友资料」进入编辑，「记忆」，按钮「发消息」。 */
@Composable
fun ContactDetailScreen(
    onBack: () -> Unit,
    onSendMessage: () -> Unit,
    onEdit: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenModel: (Long, String) -> Unit = { _, _ -> },
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 联系人被删除后自动返回
    if (state.loaded && state.contact == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
    }
    ContactDetailContent(state, onBack, onSendMessage, onEdit, onOpenMemory, onOpenModel)
}

@Composable
fun ContactDetailContent(
    state: ContactDetailState,
    onBack: () -> Unit,
    onSendMessage: () -> Unit,
    onEdit: () -> Unit,
    onOpenMemory: () -> Unit,
    onOpenModel: (Long, String) -> Unit = { _, _ -> },
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val contact = state.contact
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = "", onBack = onBack, background = colors.listBackground)
        if (contact == null) return@Column
        // 字体调大、又有待修正的行时，下面的操作按钮可能超出屏幕
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
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
                        state.thinkingName?.takeIf { it.isNotBlank() } ?: stringResource(
                            when (contact.thinkingOption ?: contact.thinking.name.lowercase()) {
                                "off" -> R.string.thinking_off
                                "on" -> R.string.thinking_on
                                "high" -> R.string.thinking_high
                                "max" -> R.string.thinking_max
                                else -> R.string.rule_unknown
                            },
                        ),
                    ),
                    style = WeTheme.typography.small,
                    color = colors.textTertiary,
                )
                Text(
                    stringResource(R.string.contact_temperature_line, temperatureLabel(state.temperature ?: RequestTemperature.Blocked(contact.temperature))),
                    style = WeTheme.typography.small,
                    color = colors.textTertiary,
                )
            }
        }
        if (state.chatIssue != null || state.memoryIssue != null) GroupSpacer()
        state.chatIssue?.let { issue ->
            ConfigActionRow(stringResource(R.string.contact_chat_issue), stringResource(configurationIssueText(issue)), titleColor = colors.dangerText, showDivider = state.memoryIssue != null) {
                if (ModelRules.fixedInModel(issue)) onOpenModel(contact.providerId, contact.model) else onEdit()
            }
        }
        state.memoryIssue?.let { issue ->
            ConfigActionRow(stringResource(R.string.contact_memory_issue), stringResource(configurationIssueText(issue, memory = true)), titleColor = colors.dangerText, onClick = onOpenMemory)
        }
        GroupSpacer()
        ConfigActionRow(
            title = stringResource(R.string.contact_profile),
            value = "",
            detail = contactProfileHint(),
            showDivider = true,
            onClick = onEdit,
        )
        WeListRow(
            title = stringResource(R.string.memory_title),
            value = if (state.memoryCount > 0) stringResource(R.string.memory_count, state.memoryCount) else null,
            onClick = onOpenMemory,
        )
        GroupSpacer()
        ActionRow(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.contact_send_message), onSendMessage)
        }
    }
}

/** 温度一行的值：实际发送的数值；模型固定时标「固定」；不发送时如实说不发送 */
@Composable
internal fun temperatureLabel(value: RequestTemperature): String = when (value) {
    is RequestTemperature.Sent -> temperatureText(value.value)
    is RequestTemperature.Fixed -> stringResource(R.string.temperature_value_fixed, temperatureText(value.value))
    RequestTemperature.NotSent -> stringResource(R.string.rule_no_send)
    is RequestTemperature.Blocked -> temperatureText(value.saved)
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
    temperature = isomeria.aichat.data.llm.RequestTemperature.Sent(1.3f),
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
