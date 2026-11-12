package isomeria.aichat.feature.contactedit

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import isomeria.aichat.R
import isomeria.aichat.core.ContextBudgetSteps
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.feature.settings.ConfigActionRow
import isomeria.aichat.ui.components.ModelParameterControls
import isomeria.aichat.ui.components.Avatar
import isomeria.aichat.ui.components.AvatarCropDialog
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.ErrorNote
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSegmented
import isomeria.aichat.ui.components.WeSmallButton
import isomeria.aichat.ui.components.WeSwitch
import isomeria.aichat.ui.components.WeTopBar
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.launch

/** 联系人编辑：头像、名称、服务商、模型、思考强度、温度、系统提示词、上下文预算、全局记忆、回复偏好、分条显示、自动记忆、主动聊天。 */
@Composable
fun ContactEditScreen(
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
    onDeleted: () -> Unit,
    onOpenProvider: (Long) -> Unit = {},
    onOpenModel: (Long, String) -> Unit = { _, _ -> },
    onOpenPrompt: () -> Unit = {},
    viewModel: ContactEditViewModel = hiltViewModel(),
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val state by viewModel.state.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val providerList by viewModel.providerList.collectAsStateWithLifecycle()
    val promptUsage by viewModel.promptUsage.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val form = state.form
    var showAvatarSheet by remember { mutableStateOf(false) }
    var showProviderSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showProactiveIntro by remember { mutableStateOf(false) }
    var showReplyTypeSheet by remember { mutableStateOf(false) }
    val globalReplyType by viewModel.globalReplyType.collectAsStateWithLifecycle()
    val providerVoice by viewModel.providerVoice.collectAsStateWithLifecycle()
    val canPickVoice by viewModel.canPickVoice.collectAsStateWithLifecycle()
    val voiceChoices by viewModel.voiceChoices.collectAsStateWithLifecycle()
    val globalReplyPreferences by viewModel.globalReplyPreferences.collectAsStateWithLifecycle()
    var sourceSheet by remember { mutableStateOf<isomeria.aichat.data.db.ProviderKind?>(null) }
    var showReplyPreferences by rememberSaveable { mutableStateOf(false) }
    val picker by viewModel.picker.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickAvatar(uri)
    }

    fun tryBack() {
        if (state.dirty) showDiscard = true else onBack()
    }
    // 0.7.2 选音色页：盖住整页，返回时回到编辑页
    if (picker.open) {
        BackHandler { viewModel.picker.close() }
        isomeria.aichat.feature.voice.VoicePickerContent(
            picker, viewModel.picker::setQuery, viewModel.picker::search, viewModel::pickVoice, viewModel.picker::preview, viewModel.picker::close,
        )
        return
    }
    // 0.7.2 联系人的回复偏好：同样盖住整页，改动跟着联系人的「保存」
    if (showReplyPreferences && state.loaded) {
        BackHandler { showReplyPreferences = false }
        ContactReplyPreferencesContent(
            form, globalReplyPreferences, viewModel::setReplyMode, viewModel::updateReplyPreferences, onBack = { showReplyPreferences = false },
        )
        return
    }
    BackHandler(enabled = state.dirty) { showDiscard = true }
    ImeBackHandler()

    fun save() {
        scope.launch {
            val id = viewModel.save() ?: return@launch
            if (state.isNew) onCreated(id) else onBack()
        }
    }

    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(
            title = stringResource(if (state.isNew) R.string.contact_edit_title_new else R.string.contact_edit_title),
            onBack = ::tryBack,
        ) {
            WeSmallButton(stringResource(R.string.action_save), ::save, enabled = state.loaded)
        }
        if (!state.loaded) return@Column

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.contact_avatar),
                onClick = { showAvatarSheet = true },
                showDivider = true,
                trailing = {
                    Avatar(
                        form.avatarPath,
                        form.name.ifBlank { "?" },
                        form.avatarColor,
                        dimens.listRowAvatar,
                    )
                },
            )
            FormRow(stringResource(R.string.contact_name)) {
                InlineField(
                    value = form.name,
                    onValueChange = viewModel::setName,
                    hint = stringResource(R.string.contact_name_hint),
                )
            }
            if (state.showNameError) {
                Text(
                    stringResource(R.string.contact_name_required),
                    style = WeTheme.typography.small,
                    color = colors.dangerText,
                    modifier = Modifier.padding(horizontal = dimens.horizontalPadding, vertical = 6.dp),
                )
            }

            GroupSpacer()
            val provider = providerList.firstOrNull { it.id == form.providerId }
            ConfigActionRow(
                title = stringResource(R.string.contact_provider),
                value = provider?.name.orEmpty(),
                showDivider = true,
                onClick = { showProviderSheet = true },
            )
            val currentModel = models.firstOrNull { it.modelId == form.model }
            val modelPending = currentModel != null && !ModelRules.complete(currentModel)
            ConfigActionRow(
                title = stringResource(R.string.contact_model),
                value = currentModel?.displayName ?: form.model.ifEmpty { stringResource(R.string.llm_no_model) },
                showDivider = (currentModel != null && !modelPending) || provider?.compatibility == "LEGACY",
                onClick = { showModelSheet = true },
            )
            if (state.showModelError) {
                ErrorNote(stringResource(if (form.model.isBlank()) R.string.contact_model_required else R.string.contact_model_pending_required))
            }
            // 确实没有模型时不展示虚构的强度和温度；旧服务商（LEGACY）没有模型也沿用原有选项
            if (currentModel == null && provider?.compatibility != "LEGACY") {
                FootnoteText(stringResource(if (form.model.isBlank()) R.string.contact_model_pick_first else R.string.contact_model_unavailable))
            } else if (currentModel != null && modelPending) {
                // 模型没配置好时档位和温度都无从选择，直接带用户去配置
                if (!state.showModelError) ErrorNote(stringResource(R.string.contact_model_pending))
                WeListRow(stringResource(R.string.action_configure_model), onClick = { onOpenModel(form.providerId, currentModel.modelId) })
            } else {
                ModelParameterControls(provider, currentModel, form.thinkingOption ?: ModelRules.legacyOption(form.thinking), form.temperature,
                    onOption = { value -> viewModel.update { it.copy(thinkingOption = value) } },
                    onTemperature = { value -> viewModel.update { it.copy(temperature = value) } })
            }

            GroupSpacer()
            PromptRow(form.systemPrompt, promptUsage, onOpenPrompt)

            GroupSpacer()
            ContextBudgetRow(form.contextTokens, viewModel::setContextValue, state.contextInput,
                viewModel::setContextInput, viewModel::finishContextInput, state.contextFocusRequest)
            GlobalSwitchRows(form, viewModel::update, onOpenReplyPreferences = { showReplyPreferences = true })
            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.contact_auto_memory),
                showArrow = false,
                trailing = {
                    WeSwitch(form.autoMemory, { on -> viewModel.update { it.copy(autoMemory = on) } }, label = stringResource(R.string.contact_auto_memory))
                },
            )
            FootnoteText(stringResource(R.string.contact_auto_memory_hint))

            GroupSpacer()
            ProactiveRows(
                enabled = form.proactiveEnabled,
                frequency = form.proactiveFrequency,
                // 第一次开启先看说明；关掉、或以后再开都直接生效
                onEnabled = { on ->
                    if (on && viewModel.needsProactiveIntro) showProactiveIntro = true else viewModel.update { it.copy(proactiveEnabled = on) }
                },
                onFrequency = { value -> viewModel.update { it.copy(proactiveFrequency = value) } },
            )

            GroupSpacer()
            ContactVoiceRows(
                form, globalReplyType, providerVoice, onPickReplyType = { showReplyTypeSheet = true }, onUpdate = viewModel::update,
                canPickVoice = canPickVoice, onPickVoice = viewModel::openVoicePicker,
                choices = voiceChoices, onPickSource = { sourceSheet = it },
            )

            if (!state.isNew) {
                GroupSpacer()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.listBackground)
                        .clickable { showDelete = true }
                        .heightIn(min = dimens.listRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.contact_delete), style = WeTheme.typography.listTitle, color = colors.danger)
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showAvatarSheet) {
        WeActionSheet(
            options = buildList {
                add(
                    SheetOption(stringResource(R.string.contact_avatar_pick)) {
                        pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                if (form.avatarPath != null) {
                    add(SheetOption(stringResource(R.string.contact_avatar_reset)) { viewModel.resetAvatar() })
                }
            },
            onDismiss = { showAvatarSheet = false },
        )
    }
    sourceSheet?.let { kind ->
        VoiceSourceSheet(kind, form, voiceChoices, onPick = { source -> viewModel.setVoiceSource(kind, source) }) { sourceSheet = null }
    }
    if (showReplyTypeSheet) {
        ReplyTypeSheet(form.replyType, globalReplyType, onPick = { type -> viewModel.update { it.copy(replyType = type) } }) {
            showReplyTypeSheet = false
        }
    }
    if (showProviderSheet) {
        ProviderSheet(providerList, form.providerId, onPick = viewModel::selectProvider) {
            showProviderSheet = false
        }
    }
    if (showModelSheet) {
        ModelSheet(models.filter { m -> providerList.firstOrNull { it.id == form.providerId }?.let { ModelRules.usable(it, m) } == true }, form.model, onPick = viewModel::selectModel, onConfigure = { onOpenProvider(form.providerId) }) {
            showModelSheet = false
        }
    }
    state.cropSource?.let { bitmap ->
        AvatarCropDialog(bitmap, onDismiss = viewModel::cancelCrop, onCropped = viewModel::applyCrop)
    }
    if (showProactiveIntro) {
        WeDialog(
            title = stringResource(R.string.contact_proactive_intro_title),
            message = stringResource(R.string.contact_proactive_intro),
            confirmText = stringResource(R.string.contact_proactive_intro_confirm),
            onDismiss = { showProactiveIntro = false },
            onConfirm = {
                showProactiveIntro = false
                viewModel.confirmProactiveIntro()
                // 主动消息靠通知才看得到：这时就申请通知权限（之前可能还没申请过，或者已经被拒绝）
                val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!granted) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            },
        )
    }
    if (showDiscard) {
        WeDialog(
            message = stringResource(R.string.contact_discard_title),
            confirmText = stringResource(R.string.contact_discard),
            destructive = true,
            onDismiss = { showDiscard = false },
            onConfirm = {
                showDiscard = false
                onBack()
            },
        )
    }
    if (showDelete) {
        WeDialog(
            message = stringResource(R.string.contact_delete_confirm, form.name),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { showDelete = false },
            onConfirm = {
                showDelete = false
                viewModel.delete(onDeleted)
            },
        )
    }
}

/**
 * 0.5.0 的两个开关和分条显示：「使用全局记忆」「使用回复偏好」默认开启；
 * 开启回复偏好时由它拆条，分条显示的说明随之改变。
 */
@Composable
internal fun GlobalSwitchRows(form: ContactForm, update: ((ContactForm) -> ContactForm) -> Unit, onOpenReplyPreferences: () -> Unit = {}) {
    GroupSpacer()
    WeListRow(
        title = stringResource(R.string.contact_use_global_memory),
        showArrow = false,
        trailing = {
            WeSwitch(form.useGlobalMemory, { on -> update { it.copy(useGlobalMemory = on) } }, label = stringResource(R.string.contact_use_global_memory))
        },
    )
    FootnoteText(stringResource(R.string.contact_use_global_memory_hint))
    GroupSpacer()
    // 0.7.2：跟随全局、单独设置或不使用，点开子页选
    isomeria.aichat.feature.settings.ConfigActionRow(
        title = stringResource(R.string.contact_reply_preferences),
        value = replyModeName(form.replyMode),
        showDivider = true,
        onClick = onOpenReplyPreferences,
    )
    WeListRow(
        title = stringResource(R.string.contact_split),
        showArrow = false,
        trailing = {
            WeSwitch(form.splitReplies, { on -> update { it.copy(splitReplies = on) } }, label = stringResource(R.string.contact_split))
        },
    )
    FootnoteText(stringResource(R.string.contact_use_reply_preferences_hint) + stringResource(if (form.useReplyPreferences) R.string.contact_split_hint_overridden else R.string.contact_split_hint))
}

/**
 * 语音消息（0.7.0）：消息发送类型（默认使用「我 → 语音消息」的全局设置）、音色（留空用语音合成服务商的音色）、语气和风格。
 */
@Composable
internal fun ContactVoiceRows(
    form: ContactForm,
    globalType: isomeria.aichat.data.db.ReplyType,
    providerVoice: String,
    onPickReplyType: () -> Unit,
    onUpdate: ((ContactForm) -> ContactForm) -> Unit,
    canPickVoice: Boolean = false,
    onPickVoice: () -> Unit = {},
    choices: ContactVoiceChoices = ContactVoiceChoices(),
    onPickSource: (isomeria.aichat.data.db.ProviderKind) -> Unit = {},
) {
    isomeria.aichat.feature.settings.ConfigActionRow(
        title = stringResource(R.string.contact_reply_type),
        value = replyTypeLabel(form.replyType, globalType),
        showDivider = true,
        onClick = onPickReplyType,
    )
    // 0.7.2：单独选语音合成来源；音色、语气和风格跟着这个来源
    isomeria.aichat.feature.settings.ConfigActionRow(
        title = stringResource(R.string.voice_tts_source),
        value = contactSourceLabel(isomeria.aichat.data.db.ProviderKind.TTS, form.ttsSource, choices),
        showDivider = true,
        onClick = { onPickSource(isomeria.aichat.data.db.ProviderKind.TTS) },
    )
    FormRow(stringResource(R.string.contact_tts_voice), showDivider = true) {
        InlineField(
            value = form.ttsVoice,
            onValueChange = { value -> onUpdate { it.copy(ttsVoice = value.take(Contact.VOICE_MAX_LENGTH)) } },
            hint = if (providerVoice.isBlank()) stringResource(R.string.contact_tts_voice_hint_empty) else stringResource(R.string.contact_tts_voice_hint, providerVoice),
        )
    }
    // 单独一行：大字体、窄屏时不挤占音色输入框
    if (canPickVoice) WeListRow(stringResource(R.string.voice_voice_pick), onClick = onPickVoice, showDivider = true)
    FormRow(stringResource(R.string.contact_tts_style)) {
        InlineField(
            value = form.ttsStyle,
            onValueChange = { value -> onUpdate { it.copy(ttsStyle = value.take(Contact.STYLE_MAX_LENGTH)) } },
            hint = stringResource(R.string.contact_tts_style_hint),
        )
    }
    isomeria.aichat.feature.settings.ConfigActionRow(
        title = stringResource(R.string.voice_asr_source),
        value = contactSourceLabel(isomeria.aichat.data.db.ProviderKind.ASR, form.asrSource, choices),
        onClick = { onPickSource(isomeria.aichat.data.db.ProviderKind.ASR) },
    )
    FootnoteText(stringResource(R.string.contact_voice_hint))
}

/** 一个来源的名称：系统语音、离线识别、服务商名称；没选或服务商不在了是「未设置」 */
@Composable
internal fun voiceSourceName(kind: isomeria.aichat.data.db.ProviderKind, source: String, choices: ContactVoiceChoices): String {
    val providers = if (kind == isomeria.aichat.data.db.ProviderKind.TTS) choices.tts else choices.asr
    return when (val parsed = isomeria.aichat.core.VoiceSource.parse(source)) {
        isomeria.aichat.core.VoiceSource.SYSTEM_TTS -> stringResource(R.string.voice_local_system_tts)
        isomeria.aichat.core.VoiceSource.OFFLINE_ASR -> stringResource(R.string.voice_local_offline_asr)
        is isomeria.aichat.core.VoiceSource.Provider -> providers.firstOrNull { it.id == parsed.id }?.name
        else -> null
    } ?: stringResource(R.string.voice_source_unset)
}

/** 联系人那一行的值：单独选了写来源名称，跟随时写「使用全局设置（全局的来源）」 */
@Composable
internal fun contactSourceLabel(kind: isomeria.aichat.data.db.ProviderKind, own: String?, choices: ContactVoiceChoices): String {
    val global = if (kind == isomeria.aichat.data.db.ProviderKind.TTS) choices.globalTts else choices.globalAsr
    return if (own == null) stringResource(R.string.voice_reply_global_value, voiceSourceName(kind, global, choices))
    else voiceSourceName(kind, own, choices)
}

/** 选语音合成或识别来源（0.7.2）：使用全局设置、本机来源、各个服务商 */
@Composable
private fun VoiceSourceSheet(
    kind: isomeria.aichat.data.db.ProviderKind,
    form: ContactForm,
    choices: ContactVoiceChoices,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val tts = kind == isomeria.aichat.data.db.ProviderKind.TTS
    val global = if (tts) choices.globalTts else choices.globalAsr
    val local = if (tts) isomeria.aichat.core.VoiceSource.SYSTEM_TTS else isomeria.aichat.core.VoiceSource.OFFLINE_ASR
    val providers = if (tts) choices.tts else choices.asr
    WeActionSheet(
        title = stringResource(if (tts) R.string.contact_tts_source_sheet else R.string.contact_asr_source_sheet, form.name.trim().ifEmpty { stringResource(R.string.contact_source_sheet_this) }),
        options = buildList {
            add(SheetOption(stringResource(R.string.voice_reply_global_value, voiceSourceName(kind, global, choices))) { onPick(null) })
            add(SheetOption(stringResource(if (tts) R.string.voice_local_system_tts else R.string.voice_local_offline_asr)) { onPick(isomeria.aichat.core.VoiceSource.of(local)) })
            providers.forEach { provider -> add(SheetOption(provider.name) { onPick(isomeria.aichat.core.VoiceSource.provider(provider.id)) }) }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun replyTypeName(type: isomeria.aichat.data.db.ReplyType): String = stringResource(
    when (type) {
        isomeria.aichat.data.db.ReplyType.FOLLOW -> R.string.voice_reply_follow
        isomeria.aichat.data.db.ReplyType.TEXT -> R.string.voice_reply_text
        isomeria.aichat.data.db.ReplyType.VOICE -> R.string.voice_reply_voice
    },
)

@Composable
private fun replyTypeLabel(type: isomeria.aichat.data.db.ReplyType?, globalType: isomeria.aichat.data.db.ReplyType): String =
    if (type == null) stringResource(R.string.voice_reply_global_value, replyTypeName(globalType)) else replyTypeName(type)

/** 选消息发送类型：使用全局设置、跟随、文字、语音 */
@Composable
private fun ReplyTypeSheet(
    current: isomeria.aichat.data.db.ReplyType?,
    globalType: isomeria.aichat.data.db.ReplyType,
    onPick: (isomeria.aichat.data.db.ReplyType?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(null) + isomeria.aichat.data.db.ReplyType.entries
    isomeria.aichat.ui.components.WeActionSheet(
        title = stringResource(R.string.contact_reply_type),
        options = options.map { type ->
            isomeria.aichat.ui.components.SheetOption(replyTypeLabel(type, globalType), selected = type == current) { onPick(type) }
        },
        onDismiss = onDismiss,
    )
}

/**
 * 主动聊天（0.6.0）：开关默认关闭；开启后显示频率（偶尔 / 适中 / 频繁，默认适中）。
 * 免打扰时段、每日上限和运行状态是全局的，在「我 → 主动聊天」。
 */
@Composable
internal fun ProactiveRows(
    enabled: Boolean,
    frequency: ProactiveFrequency,
    onEnabled: (Boolean) -> Unit,
    onFrequency: (ProactiveFrequency) -> Unit,
) {
    WeListRow(
        title = stringResource(R.string.contact_proactive),
        showArrow = false,
        showDivider = enabled,
        trailing = { WeSwitch(enabled, onEnabled, label = stringResource(R.string.contact_proactive)) },
    )
    if (enabled) {
        // 字放大后三个选项和标题一行放不下：选项换到标题下面一行
        isomeria.aichat.feature.replyprefs.StackingRow(stringResource(R.string.contact_proactive_frequency)) {
            WeSegmented(
                options = listOf(
                    ProactiveFrequency.LOW to stringResource(R.string.proactive_frequency_low),
                    ProactiveFrequency.MEDIUM to stringResource(R.string.proactive_frequency_medium),
                    ProactiveFrequency.HIGH to stringResource(R.string.proactive_frequency_high),
                ),
                selected = frequency,
                onSelect = onFrequency,
            )
        }
    }
    FootnoteText(stringResource(R.string.contact_proactive_hint))
}

/**
 * 「系统提示词」：提示词可能很长，单独一页编辑；这里只显示开头两行，没写时显示「未填写」。
 * 下面一行是系统提示词合计占用（含全局记忆、记忆、回复偏好要求），超过上下文上限一半时改成提醒。
 */
@Composable
internal fun PromptRow(prompt: String, usage: PromptUsage? = null, onClick: () -> Unit) {
    val preview = promptPreview(prompt)
    DetailEntryRow(
        title = stringResource(R.string.contact_prompt),
        detail = preview ?: stringResource(R.string.contact_prompt_empty),
        detailMaxLines = 2,
        onClick = onClick,
    )
    PromptUsageNote(usage)
}

@Composable
internal fun PromptUsageNote(usage: PromptUsage?) {
    if (usage == null || usage.tokens == 0) return
    if (usage.overHalf) ErrorNote(stringResource(R.string.contact_prompt_usage_warning, usage.tokens))
    else FootnoteText(stringResource(R.string.contact_prompt_usage, usage.tokens, usage.percent))
}

@Composable
private fun ProviderSheet(
    providers: List<ProviderConfig>,
    current: Long,
    onPick: (ProviderConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val noKey = stringResource(R.string.llm_api_key_missing)
    WeActionSheet(
        title = stringResource(R.string.contact_provider_pick),
        options = providers.map { provider ->
            SheetOption(
                label = provider.name,
                subtitle = if (provider.apiKeyEncrypted.isNullOrEmpty()) noKey else null,
                selected = provider.id == current,
            ) { onPick(provider) }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun ModelSheet(models: List<ProviderModel>, current: String, onPick: (ProviderModel) -> Unit, onConfigure: () -> Unit, onDismiss: () -> Unit) {
    val choices = models.filter(ModelRules::complete)
    if (choices.isEmpty()) {
        WeActionSheet(
            title = stringResource(R.string.contact_model_empty),
            options = listOf(SheetOption(stringResource(R.string.action_configure_model), onClick = onConfigure)),
            onDismiss = onDismiss,
        )
        return
    }
    val imageLabel = stringResource(R.string.contact_model_image)
    val thinkingLabel = stringResource(R.string.contact_model_thinking)
    WeActionSheet(
        title = stringResource(R.string.contact_model_pick),
        options = choices.map { model ->
            SheetOption(
                label = model.displayName,
                subtitle = buildList {
                    add(model.modelId)
                    if (model.supportsImage) add(imageLabel)
                    if (model.supportsThinking) add(thinkingLabel)
                }.joinToString(" · "),
                selected = model.modelId == current,
            ) { onPick(model) }
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun InlineField(value: String, onValueChange: (String) -> Unit, hint: String) {
    val colors = WeTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (value.isEmpty()) Text(hint, style = WeTheme.typography.listTitle, color = colors.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.brand),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun ContextBudgetRow(value: Int, onChange: (Int) -> Unit,
    input: String = value.toString(), onInput: (String) -> Unit = { parseContextInput(it)?.let(onChange) },
    onFinish: () -> Unit = {}, focusRequest: Int = 0,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.listBackground)
            .padding(horizontal = dimens.horizontalPadding, vertical = 12.dp),
    ) {
        Layout(content = {
            Text(
                stringResource(R.string.contact_context_tokens),
                style = WeTheme.typography.listTitle,
                color = colors.textPrimary,
            )
            Stepper(input, onInput, onFinish, focusRequest, onChange)
        }) { measurables, constraints ->
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val title = measurables[0].measure(loose)
            val control = measurables[1].measure(loose)
            val gap = 8.dp.roundToPx()
            val stacked = title.width + control.width + gap > constraints.maxWidth
            val height = if (stacked) title.height + gap + control.height else maxOf(title.height, control.height)
            layout(constraints.maxWidth, height) {
                title.placeRelative(0, if (stacked) 0 else (height - title.height) / 2)
                control.placeRelative(constraints.maxWidth - control.width, if (stacked) title.height + gap else (height - control.height) / 2)
            }
        }
        if (parseContextInput(input) == null) ErrorNote(stringResource(R.string.contact_context_invalid))
        Text(
            stringResource(R.string.contact_context_tokens_hint),
            style = WeTheme.typography.listTime,
            color = colors.textTertiary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun Stepper(input: String, onInput: (String) -> Unit, onFinish: () -> Unit, focusRequest: Int, onChange: (Int) -> Unit) {
    val colors = WeTheme.colors
    val value = parseContextInput(input)
    val focus = remember { FocusRequester() }
    val bring = remember { BringIntoViewRequester() }
    val manager = LocalFocusManager.current
    var field by remember { mutableStateOf(TextFieldValue(input)) }
    var focused by remember { mutableStateOf(false) }
    // 首次触摸的光标定位会晚于焦点回调；等该帧结束再全选。
    LaunchedEffect(focused) {
        if (focused) {
            androidx.compose.runtime.withFrameNanos { }
            field = field.copy(selection = TextRange(0, field.text.length))
        }
    }
    val label = stringResource(R.string.contact_context_tokens)
    val style = WeTheme.typography.listTitle.copy(color = colors.textPrimary, textAlign = TextAlign.Center)
    val measured = rememberTextMeasurer().measure("200000", style).size.width
    val fieldWidth = with(LocalDensity.current) { measured.toDp() } + 16.dp
    LaunchedEffect(input) { if (field.text != input) field = TextFieldValue(input, TextRange(input.length)) }
    LaunchedEffect(focusRequest) { if (focusRequest > 0) { bring.bringIntoView(); focus.requestFocus() } }
    Row(
        // 按钮的点击区是 48dp，看到的灰底仍是原来的高度；字放大后灰底跟着变高
        Modifier.drawBehind {
            val minimum = 48.dp.toPx()
            val height = if (size.height > minimum) size.height else 32.dp.toPx()
            drawRoundRect(colors.pageBackground, Offset(0f, (size.height - height) / 2), Size(size.width, height), CornerRadius(6.dp.toPx()))
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−", stringResource(R.string.contact_context_decrease), enabled = value != null && value > Contact.MIN_CONTEXT_TOKENS) { value?.let { onChange(ContextBudgetSteps.decrease(it)) } }
        BasicTextField(
            value = field,
            onValueChange = { next -> if (next.text.all { it in '0'..'9' }) { field = next; onInput(next.text) } },
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(colors.brand),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onFinish(); if (value != null) manager.clearFocus() }),
            modifier = Modifier.widthIn(min = 48.dp, max = fieldWidth).heightIn(min = 48.dp)
                .focusRequester(focus).bringIntoViewRequester(bring)
                .onFocusChanged { focused = it.isFocused; if (!it.isFocused) onFinish() }
                .semantics { contentDescription = label },
            decorationBox = { inner -> Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) { inner() } },
        )
        StepButton("+", stringResource(R.string.contact_context_increase), enabled = value != null && value < Contact.MAX_CONTEXT_TOKENS) { value?.let { onChange(ContextBudgetSteps.increase(it)) } }
    }
}

@Composable
private fun StepButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = WeTheme.colors
    Box(
        Modifier
            .size(48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = WeTheme.typography.listTitle, color = if (enabled) colors.textPrimary else colors.disabled)
    }
}
