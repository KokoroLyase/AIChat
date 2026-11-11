package isomeria.aichat.feature.voice

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.BuildConfig
import isomeria.aichat.R
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ReplyType
import isomeria.aichat.data.llm.LlmEndpoint
import isomeria.aichat.data.repo.BaseUrlRules
import isomeria.aichat.data.repo.VoiceProviders
import isomeria.aichat.data.repo.VoiceRepository
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.voice.VoicePreset
import isomeria.aichat.data.voice.VoicePresets
import isomeria.aichat.data.voice.VoiceProtocol
import isomeria.aichat.feature.settings.ChoiceRow
import isomeria.aichat.feature.settings.KeyMoveDialog
import isomeria.aichat.feature.settings.keyMovesTo
import isomeria.aichat.navigation.VoiceProviderEditRoute
import isomeria.aichat.navigation.VoiceProviderListRoute
import isomeria.aichat.navigation.NEW_PROVIDER
import isomeria.aichat.ui.components.ErrorNote
import isomeria.aichat.ui.components.FootnoteText
import isomeria.aichat.ui.components.FormRow
import isomeria.aichat.ui.components.GroupSpacer
import isomeria.aichat.ui.components.ImeBackHandler
import isomeria.aichat.ui.components.PasswordField
import isomeria.aichat.ui.components.SectionHeader
import isomeria.aichat.ui.components.SheetOption
import isomeria.aichat.ui.components.WeActionSheet
import isomeria.aichat.ui.components.WeDialog
import isomeria.aichat.ui.components.WeDivider
import isomeria.aichat.ui.components.WeIcons
import isomeria.aichat.ui.components.WeListRow
import isomeria.aichat.ui.components.WeSegmented
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

/** 消息发送类型的显示名 */
internal fun replyTypeLabel(type: ReplyType): Int = when (type) {
    ReplyType.FOLLOW -> R.string.voice_reply_follow
    ReplyType.TEXT -> R.string.voice_reply_text
    ReplyType.VOICE -> R.string.voice_reply_voice
}

internal fun protocolLabel(protocol: String): Int = when (protocol) {
    VoiceProtocol.CHAT_AUDIO -> R.string.voice_protocol_chat_audio
    else -> R.string.voice_protocol_openai_audio
}

// ---------- 我 → 语音消息 ----------

data class VoiceSettingsUiState(
    val voice: VoiceSettings = VoiceSettings(),
    val ttsName: String? = null,
    val asrName: String? = null,
)

@HiltViewModel
class VoiceSettingsViewModel @Inject constructor(
    private val settings: SettingsStore,
    voices: VoiceRepository,
) : ViewModel() {
    val state: StateFlow<VoiceSettingsUiState> = combine(
        settings.voiceSettings,
        voices.observe(ProviderKind.TTS, settings.voiceSettings),
        voices.observe(ProviderKind.ASR, settings.voiceSettings),
    ) { voice, tts, asr -> VoiceSettingsUiState(voice, tts.selectedName(), asr.selectedName()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceSettingsUiState(settings.voiceSettings.value))

    fun setReplyType(type: ReplyType) = settings.setVoiceSettings(settings.voiceSettings.value.copy(replyType = type))
}

internal fun VoiceProviders.selectedName(): String? = providers.firstOrNull { it.id == selectedId }?.name

/**
 * 「我 → 语音消息」（0.7.0）：消息发送类型的全局默认（联系人可以单独设置），以及语音合成、识别各用哪个服务商。
 * 页面写明录音和回复文字会发给哪类服务商。
 */
@Composable
fun VoiceSettingsScreen(
    onBack: () -> Unit,
    onOpenProviders: (ProviderKind) -> Unit,
    viewModel: VoiceSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    VoiceSettingsContent(state, onBack, viewModel::setReplyType, onOpenProviders)
}

@Composable
fun VoiceSettingsContent(
    state: VoiceSettingsUiState,
    onBack: () -> Unit,
    onReplyType: (ReplyType) -> Unit,
    onOpenProviders: (ProviderKind) -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(title = stringResource(R.string.voice_settings_title), onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            Column(
                Modifier.fillMaxWidth().background(colors.listBackground).padding(horizontal = dimens.horizontalPadding, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.voice_reply_type), style = WeTheme.typography.listTitle, color = colors.textPrimary)
                Spacer(Modifier.height(10.dp))
                WeSegmented(
                    options = ReplyType.entries.map { it to stringResource(replyTypeLabel(it)) },
                    selected = state.voice.replyType,
                    onSelect = onReplyType,
                )
            }
            FootnoteText(stringResource(R.string.voice_reply_type_note))
            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.voice_tts_source),
                value = state.ttsName ?: stringResource(R.string.voice_source_unset),
                onClick = { onOpenProviders(ProviderKind.TTS) },
                showDivider = true,
            )
            WeListRow(
                title = stringResource(R.string.voice_asr_source),
                value = state.asrName ?: stringResource(R.string.voice_source_unset),
                onClick = { onOpenProviders(ProviderKind.ASR) },
            )
            FootnoteText(stringResource(R.string.voice_privacy_note))
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------- 服务商列表 ----------

@HiltViewModel
class VoiceProviderListViewModel @Inject constructor(
    savedState: SavedStateHandle,
    settings: SettingsStore,
    voices: VoiceRepository,
) : ViewModel() {
    val kind: ProviderKind = ProviderKind.valueOf(savedState.toRoute<VoiceProviderListRoute>().kind)
    val providers: StateFlow<VoiceProviders> = voices.observe(kind, settings.voiceSettings)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VoiceProviders(emptyList(), null))
}

/** 模型服务 → 语音合成 / 语音识别：这一类的服务商列表，打勾的是正在用的。 */
@Composable
fun VoiceProviderListScreen(
    onBack: () -> Unit,
    onOpen: (ProviderKind, Long) -> Unit,
    onAdd: (ProviderKind) -> Unit,
    viewModel: VoiceProviderListViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    VoiceProviderListContent(viewModel.kind, providers, onBack, { onOpen(viewModel.kind, it) }, { onAdd(viewModel.kind) })
}

@Composable
fun VoiceProviderListContent(
    kind: ProviderKind,
    state: VoiceProviders,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Column(Modifier.fillMaxSize().background(colors.pageBackground)) {
        WeTopBar(
            title = stringResource(if (kind == ProviderKind.TTS) R.string.voice_providers_tts_title else R.string.voice_providers_asr_title),
            onBack = onBack,
        ) {
            WeSmallButton(stringResource(R.string.action_add), onAdd)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            GroupSpacer()
            if (state.providers.isEmpty()) FootnoteText(stringResource(R.string.voice_providers_empty))
            state.providers.forEachIndexed { index, provider ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.listBackground)
                        .clickable(role = Role.Button) { onOpen(provider.id) }
                        .heightIn(min = dimens.listRowHeight)
                        .padding(horizontal = dimens.horizontalPadding, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, style = WeTheme.typography.listTitle, color = colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        // 放在名称下面单独一行：大字体、窄屏时不挤掉名称
                        if (provider.id == state.selectedId) {
                            Text(stringResource(R.string.voice_provider_in_use), style = WeTheme.typography.listTime, color = colors.brandText)
                        }
                        Text(stringResource(protocolLabel(provider.provider)) + " · " + provider.defaultModel,
                            style = WeTheme.typography.listTime, color = colors.textTertiary)
                        Text(
                            stringResource(if (provider.apiKeyEncrypted.isNullOrEmpty()) R.string.llm_api_key_missing else R.string.llm_api_key_set),
                            style = WeTheme.typography.listTime,
                            color = colors.textTertiary,
                        )
                    }
                    Icon(WeIcons.ChevronRight, null, tint = colors.arrow, modifier = Modifier.padding(start = 4.dp).size(18.dp))
                }
                if (index != state.providers.lastIndex) WeDivider(startIndent = dimens.horizontalPadding)
            }
            FootnoteText(stringResource(if (kind == ProviderKind.TTS) R.string.voice_providers_tts_hint else R.string.voice_providers_asr_hint))
            FootnoteText(stringResource(R.string.voice_privacy_note))
        }
    }
}

// ---------- 编辑一个服务商 ----------

data class VoiceProviderEditState(
    val loaded: Boolean = false,
    val kind: ProviderKind = ProviderKind.TTS,
    /** null 表示还没保存的新服务商 */
    val providerId: Long? = null,
    val presetId: String? = null,
    val protocol: String = VoiceProtocol.OPENAI_AUDIO,
    val name: String = "",
    val baseUrl: String = "",
    val keyInput: String = "",
    val savedKeyMasked: String? = null,
    val model: String = "",
    val voice: String = "",
    /** 保存后是不是正在用的 */
    val use: Boolean = false,
    val errorRes: Int? = null,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    val keyMoveUrl: String? = null,
    val savedForm: List<Any?> = emptyList(),
) {
    val form: List<Any?> get() = listOf(presetId, protocol, name, baseUrl, model, voice, use)
    val dirty: Boolean get() = loaded && (form != savedForm || keyInput.isNotEmpty())
}

@HiltViewModel
class VoiceProviderEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val voices: VoiceRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    private val route = savedState.toRoute<VoiceProviderEditRoute>()
    private val kind = ProviderKind.valueOf(route.kind)
    private val _state = MutableStateFlow(VoiceProviderEditState(kind = kind))
    val state: StateFlow<VoiceProviderEditState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            if (route.providerId == NEW_PROVIDER) {
                val first = voices.selected(kind) == null
                val preset = VoicePresets.forKind(kind).first()
                _state.update { it.copy(loaded = true, use = first) }
                selectPreset(preset)
                // 没动过的新表单不算修改
                _state.update { it.copy(savedForm = it.form) }
            } else {
                reload(route.providerId)
            }
        }
    }

    private suspend fun reload(id: Long) {
        val config = voices.config(id) ?: return
        val use = voices.selected(kind)?.id == id
        _state.update {
            it.copy(
                loaded = true, providerId = id, presetId = config.presetId, protocol = config.provider, name = config.name,
                baseUrl = config.baseUrl, keyInput = "", savedKeyMasked = voices.maskedKey(config), model = config.defaultModel,
                voice = config.voice, use = use, errorRes = null,
            )
        }
        _state.update { it.copy(savedForm = it.form) }
    }

    /** 选预置或自定义：只给新服务商填默认值，不改已保存的地址 */
    fun selectPreset(preset: VoicePreset?, customName: String = "") = _state.update {
        if (it.providerId != null) it else if (preset == null) {
            it.copy(presetId = null, name = customName, baseUrl = "", model = "", voice = "", errorRes = null)
        } else {
            it.copy(
                presetId = preset.id, protocol = preset.protocol, name = customName.ifBlank { context.getString(preset.nameRes) }, baseUrl = preset.baseUrl,
                model = preset.model(kind).orEmpty(), voice = if (kind == ProviderKind.TTS) preset.defaultVoice else "", errorRes = null,
            )
        }
    }

    fun setName(v: String) = _state.update { it.copy(name = v.take(NAME_MAX_LENGTH), errorRes = null) }
    fun setProtocol(v: String) = _state.update { it.copy(protocol = v, errorRes = null) }
    fun setBaseUrl(v: String) = _state.update { it.copy(baseUrl = v.trim(), errorRes = null) }
    fun setKey(v: String) = _state.update { it.copy(keyInput = v.trim(), errorRes = null) }
    fun setModel(v: String) = _state.update { it.copy(model = v.trim(), errorRes = null) }
    fun setVoice(v: String) = _state.update { it.copy(voice = v.take(isomeria.aichat.data.db.Contact.VOICE_MAX_LENGTH), errorRes = null) }
    fun setUse(v: Boolean) = _state.update { it.copy(use = v) }

    private var pendingKeyMove: (() -> Unit)? = null

    fun save() {
        val s = _state.value
        val error = when {
            s.name.isBlank() -> R.string.llm_name_required
            BaseUrlRules.normalize(s.baseUrl, allowLocalHttp = BuildConfig.DEBUG) == null -> R.string.llm_base_url_invalid
            s.model.isBlank() -> R.string.voice_model_required
            s.keyInput.isNotEmpty() && !LlmEndpoint.isUsableKey(s.keyInput) -> R.string.llm_api_key_invalid_chars
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(errorRes = error) }
            return
        }
        val url = BaseUrlRules.normalize(s.baseUrl, allowLocalHttp = BuildConfig.DEBUG)!!
        viewModelScope.launch {
            val saved = s.providerId?.let { voices.config(it) }
            // 改了地址又没填新 Key：先确认 Key 会发给新地址（和大语言模型服务商一样）
            if (saved?.keyMovesTo(url, s.keyInput) == true) {
                pendingKeyMove = { persist(url) }
                _state.update { it.copy(keyMoveUrl = url) }
            } else {
                persist(url)
            }
        }
    }

    private fun persist(url: String) {
        val s = _state.value
        viewModelScope.launch {
            val id = s.providerId?.also { voices.save(it, s.protocol, s.name, url, s.keyInput.ifEmpty { null }, s.model, s.voice) }
                ?: voices.create(kind, s.protocol, s.name, url, s.keyInput.ifEmpty { null }, s.model, s.voice, s.presetId)
            val selected = voices.selected(kind)?.id
            if (s.use && selected != id) voices.select(kind, id)
            if (!s.use && selected == id) voices.select(kind, null)
            reload(id)
            _state.update { it.copy(saved = true) }
        }
    }

    fun confirmKeyMove() {
        val action = pendingKeyMove ?: return
        pendingKeyMove = null
        _state.update { it.copy(keyMoveUrl = null) }
        action()
    }

    fun dismissKeyMove() {
        pendingKeyMove = null
        _state.update { it.copy(keyMoveUrl = null) }
    }

    fun clearKey() {
        val id = _state.value.providerId ?: return
        viewModelScope.launch {
            val saved = voices.config(id) ?: return@launch
            voices.save(id, saved.provider, saved.name, saved.baseUrl, "", saved.defaultModel, saved.voice)
            _state.update { it.copy(savedKeyMasked = null) }
        }
    }

    fun delete() {
        val id = _state.value.providerId ?: return
        viewModelScope.launch {
            voices.delete(id)
            _state.update { it.copy(deleted = true) }
        }
    }

    fun consumeSaved() = _state.update { it.copy(saved = false) }

    private companion object {
        const val NAME_MAX_LENGTH = 30
    }
}

@Composable
fun VoiceProviderEditScreen(onBack: () -> Unit, viewModel: VoiceProviderEditViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(state.saved) {
        if (state.saved) {
            Toast.makeText(context, R.string.llm_saved, Toast.LENGTH_SHORT).show()
            viewModel.consumeSaved()
        }
    }
    BackHandler(enabled = state.dirty) { confirmDiscard = true }
    ImeBackHandler()
    VoiceProviderEditContent(
        state = state,
        onBack = { if (state.dirty) confirmDiscard = true else onBack() },
        actions = VoiceProviderEditActions(
            onSave = viewModel::save, onPreset = viewModel::selectPreset, onName = viewModel::setName, onProtocol = viewModel::setProtocol,
            onBaseUrl = viewModel::setBaseUrl, onKey = viewModel::setKey, onClearKey = viewModel::clearKey, onModel = viewModel::setModel,
            onVoice = viewModel::setVoice, onUse = viewModel::setUse, onDelete = viewModel::delete,
        ),
    )
    state.keyMoveUrl?.let { KeyMoveDialog(it, viewModel::dismissKeyMove, viewModel::confirmKeyMove) }
    if (confirmDiscard) {
        WeDialog(
            message = stringResource(R.string.contact_discard_title),
            confirmText = stringResource(R.string.contact_discard),
            destructive = true,
            onDismiss = { confirmDiscard = false },
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
        )
    }
}

data class VoiceProviderEditActions(
    val onSave: () -> Unit = {},
    val onPreset: (VoicePreset?, String) -> Unit = { _, _ -> },
    val onName: (String) -> Unit = {},
    val onProtocol: (String) -> Unit = {},
    val onBaseUrl: (String) -> Unit = {},
    val onKey: (String) -> Unit = {},
    val onClearKey: () -> Unit = {},
    val onModel: (String) -> Unit = {},
    val onVoice: (String) -> Unit = {},
    val onUse: (Boolean) -> Unit = {},
    val onDelete: () -> Unit = {},
)

@Composable
fun VoiceProviderEditContent(state: VoiceProviderEditState, onBack: () -> Unit, actions: VoiceProviderEditActions) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    val tts = state.kind == ProviderKind.TTS
    var confirmDelete by remember { mutableStateOf(false) }
    var pickVoice by remember { mutableStateOf(false) }
    val preset = VoicePresets.byId(state.presetId)
    Column(Modifier.fillMaxSize().background(colors.pageBackground).imePadding()) {
        WeTopBar(
            title = stringResource(
                when {
                    state.providerId == null && tts -> R.string.voice_provider_add_tts
                    state.providerId == null -> R.string.voice_provider_add_asr
                    tts -> R.string.voice_provider_edit_tts
                    else -> R.string.voice_provider_edit_asr
                },
            ),
            onBack = onBack,
        ) {
            WeSmallButton(stringResource(R.string.action_save), actions.onSave, enabled = state.loaded)
        }
        if (!state.loaded) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (state.providerId == null) {
                val custom = stringResource(R.string.voice_provider_custom)
                val choices = VoicePresets.forKind(state.kind).map { stringResource(it.nameRes) to it } + listOf(custom to null)
                ChoiceRow(
                    stringResource(R.string.provider_preset),
                    preset?.let { stringResource(it.nameRes) } ?: custom,
                    options = choices,
                ) { picked -> actions.onPreset(picked, choices.first { it.second == picked }.first) }
            }
            SectionHeader(stringResource(if (tts) R.string.voice_providers_tts_title else R.string.voice_providers_asr_title))
            FormRow(stringResource(R.string.llm_name), showDivider = true) { VoiceTextInput(state.name, actions.onName, stringResource(R.string.llm_name_hint)) }
            if (preset == null) {
                ChoiceRow(
                    stringResource(R.string.voice_protocol),
                    stringResource(protocolLabel(state.protocol)),
                    options = VoiceProtocol.all.map { stringResource(protocolLabel(it)) to it },
                    showDivider = true,
                    onPick = actions.onProtocol,
                )
            } else {
                // 预置的格式不能改；值放不下时换行，不截断
                FormRow(stringResource(R.string.voice_protocol), showDivider = true) {
                    Text(stringResource(protocolLabel(state.protocol)), style = WeTheme.typography.listTitle, color = colors.textTertiary, modifier = Modifier.padding(vertical = 12.dp))
                }
            }
            FormRow(stringResource(R.string.llm_base_url), showDivider = true) {
                VoiceTextInput(state.baseUrl, actions.onBaseUrl, stringResource(R.string.llm_base_url_hint), KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
            }
            FormRow(stringResource(R.string.voice_model), showDivider = tts) {
                VoiceTextInput(state.model, actions.onModel, stringResource(R.string.voice_model_hint), KeyboardOptions(autoCorrectEnabled = false))
            }
            if (tts) {
                val voices = preset != null && preset.voicesRes != 0
                FormRow(stringResource(R.string.voice_voice), showDivider = voices) {
                    VoiceTextInput(state.voice, actions.onVoice, stringResource(R.string.voice_voice_hint), KeyboardOptions(autoCorrectEnabled = false))
                }
                // 单独一行：大字体、窄屏时不挤占音色输入框
                if (voices) WeListRow(stringResource(R.string.voice_voice_pick), onClick = { pickVoice = true })
            }
            state.errorRes?.let { ErrorNote(stringResource(it)) }
            FootnoteText(
                buildString {
                    append(stringResource(R.string.llm_base_url_note))
                    if (preset == null) append("\n").append(stringResource(R.string.voice_protocol_note))
                    if (tts) append("\n").append(stringResource(R.string.voice_voice_note)).append("\n").append(stringResource(R.string.voice_style_mimo_note))
                },
            )
            val uri = LocalUriHandler.current
            preset?.let { WeListRow(stringResource(R.string.provider_docs), onClick = { uri.openUri(VoicePresets.docs(it, state.kind)) }) }

            GroupSpacer()
            FormRow(stringResource(R.string.llm_api_key)) {
                PasswordField(state.keyInput, actions.onKey, stringResource(R.string.llm_api_key_hint))
            }
            if (state.savedKeyMasked != null) {
                if (state.keyInput.isEmpty()) FootnoteText(stringResource(R.string.llm_api_key_saved, state.savedKeyMasked))
                WeListRow(title = stringResource(R.string.llm_api_key_clear), titleColor = colors.danger, onClick = actions.onClearKey, showArrow = false)
            }

            GroupSpacer()
            WeListRow(
                title = stringResource(R.string.voice_use_this),
                showArrow = false,
                trailing = { WeSwitch(state.use, actions.onUse, label = stringResource(R.string.voice_use_this)) },
            )
            when {
                preset == VoicePresets.MIMO && tts -> FootnoteText(stringResource(R.string.voice_price_mimo_tts))
                preset == VoicePresets.MIMO -> FootnoteText(stringResource(R.string.voice_price_mimo_asr))
                preset == VoicePresets.SILICONFLOW -> FootnoteText(stringResource(R.string.voice_price_siliconflow))
            }
            if (state.providerId != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().background(colors.listBackground).clickable(role = Role.Button) { confirmDelete = true }.heightIn(min = dimens.listRowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.voice_delete_provider), style = WeTheme.typography.listTitle, color = colors.danger)
                }
            }
            FootnoteText(stringResource(R.string.llm_security_notice))
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickVoice && preset != null && preset.voicesRes != 0) {
        val voices = stringArrayResource(preset.voicesRes)
        WeActionSheet(
            title = stringResource(R.string.voice_voice_pick),
            options = voices.map { v -> SheetOption(v, selected = v == state.voice) { actions.onVoice(v) } },
            onDismiss = { pickVoice = false },
        )
    }
    if (confirmDelete) {
        WeDialog(
            message = stringResource(R.string.voice_delete_confirm, state.name),
            confirmText = stringResource(R.string.action_delete),
            destructive = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
        )
    }
}

@Composable
internal fun VoiceTextInput(value: String, onValueChange: (String) -> Unit, hint: String, keyboardOptions: KeyboardOptions = KeyboardOptions.Default) {
    val colors = WeTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        if (value.isEmpty()) {
            Text(hint, style = WeTheme.typography.listTitle, color = colors.textSecondary, softWrap = false, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = WeTheme.typography.listTitle.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.brand),
            keyboardOptions = keyboardOptions,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 截图和预览用 */
internal val previewVoiceProviders = listOf(
    ProviderConfig(id = 2, kind = ProviderKind.TTS, provider = VoiceProtocol.CHAT_AUDIO, name = "Xiaomi MiMo", baseUrl = "https://api.xiaomimimo.com/v1",
        apiKeyEncrypted = "x", defaultModel = "mimo-v2.5-tts", presetId = "mimo", voice = "mimo_default"),
    ProviderConfig(id = 3, kind = ProviderKind.TTS, provider = VoiceProtocol.OPENAI_AUDIO, name = "Custom", baseUrl = "https://tts.example.com/v1",
        defaultModel = "tts-1"),
)
