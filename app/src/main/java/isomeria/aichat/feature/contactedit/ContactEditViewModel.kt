package isomeria.aichat.feature.contactedit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.R
import isomeria.aichat.core.Clock
import isomeria.aichat.core.ContextBuilder
import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.core.PromptGlobals
import isomeria.aichat.core.TokenEstimator
import isomeria.aichat.data.repo.GlobalMemoryRepository
import isomeria.aichat.data.repo.MemoryRepository
import isomeria.aichat.data.settings.SettingsStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.ThinkingLevel
import isomeria.aichat.data.llm.ModelRules
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.ProviderRepository
import isomeria.aichat.di.ApplicationScope
import isomeria.aichat.navigation.ContactEditRoute
import isomeria.aichat.navigation.NEW_CONTACT
import isomeria.aichat.ui.components.randomAvatarColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ContactForm(
    val name: String = "",
    val avatarPath: String? = null,
    val avatarColor: Int = 0,
    val providerId: Long = 0,
    val model: String = "",
    val thinking: ThinkingLevel = ThinkingLevel.OFF,
    val thinkingOption: String? = null,
    val temperature: Float = Contact.DEFAULT_TEMPERATURE,
    val systemPrompt: String = "",
    val contextTokens: Int = Contact.DEFAULT_CONTEXT_TOKENS,
    val splitReplies: Boolean = true,
    val autoMemory: Boolean = true,
    val useGlobalMemory: Boolean = true,
    val useReplyPreferences: Boolean = true,
    /** 主动聊天（0.6.0）：默认关闭 */
    val proactiveEnabled: Boolean = false,
    val proactiveFrequency: ProactiveFrequency = ProactiveFrequency.MEDIUM,
    /** 语音消息（0.7.0）：消息发送类型（空 = 使用全局设置）、音色（空 = 服务商的音色）、语气和风格 */
    val replyType: isomeria.aichat.data.db.ReplyType? = null,
    val ttsVoice: String = "",
    val ttsStyle: String = "",
)

/**
 * 系统提示词（人设 + 全局记忆 + 记忆 + 回复要求 + 时间昵称）合计占多少上下文。
 * 按这个联系人服务商的估算方式算，和发请求时扣预算的算法一样。
 */
data class PromptUsage(val tokens: Int, val budget: Int) {
    val percent: Int get() = if (budget <= 0) 0 else (tokens * 100L / budget).toInt()

    /** 超过上下文上限的一半时在编辑页提示 */
    val overHalf: Boolean get() = tokens * 2L > budget
}

data class ContactEditState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val form: ContactForm = ContactForm(),
    val original: ContactForm? = null,
    val showNameError: Boolean = false,
    val showModelError: Boolean = false,
    val contextInput: String = form.contextTokens.toString(),
    val contextFocusRequest: Int = 0,
    /** 正在裁剪的图片 */
    val cropSource: Bitmap? = null,
) {
    val dirty: Boolean get() = loaded && (form != original || contextInput != form.contextTokens.toString())
}

/** 输入草稿不强制截断或钳位，避免把用户的数字改成另一个值。 */
internal fun parseContextInput(text: String): Int? = text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
    ?.toIntOrNull()?.takeIf { it in Contact.MIN_CONTEXT_TOKENS..Contact.MAX_CONTEXT_TOKENS }

/**
 * 模型是否挡住保存。已有联系人没换模型时不挡：改名、改提示词不用先修模型，
 * 发送前的校验仍会拦下无效请求。新建联系人或换了模型，选的模型必须配置好。[saved] 是已有联系人保存时的内容。
 */
internal fun modelBlocksSave(form: ContactForm, saved: ContactForm?, model: ProviderModel?): Boolean {
    if (saved != null && saved.providerId == form.providerId && saved.model == form.model) return false
    return form.model.isBlank() || model?.let(ModelRules::complete) == false
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val contacts: ContactRepository,
    private val providers: ProviderRepository,
    private val files: FileStorage,
    @ApplicationScope private val appScope: CoroutineScope,
    globals: GlobalMemoryRepository,
    private val settings: SettingsStore,
    memories: MemoryRepository,
    private val contextBuilder: ContextBuilder,
    private val clock: Clock,
    private val voices: isomeria.aichat.data.repo.VoiceRepository,
    catalog: isomeria.aichat.data.voice.VoiceCatalog,
    preview: isomeria.aichat.data.voice.VoicePreview,
) : ViewModel() {
    private val contactId = savedState.toRoute<ContactEditRoute>().contactId
    private var existing: Contact? = null

    /** 编辑过程中新生成、但最终没有保存的头像文件，离开时删掉 */
    private val pendingAvatars = mutableSetOf<String>()
    private var saved = false

    private val _state = MutableStateFlow(ContactEditState())

    /** 「我 → 语音消息」里的消息发送类型：「使用全局设置」后面显示 */
    val globalReplyType: StateFlow<isomeria.aichat.data.db.ReplyType> = settings.voiceSettings
        .map { it.replyType }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.voiceSettings.value.replyType)

    /** 正在用的语音合成来源的默认音色：音色留空时用它（0.7.1 起系统语音用「系统语音」页选的音色） */
    val providerVoice: StateFlow<String> = voices.observe(isomeria.aichat.data.db.ProviderKind.TTS, settings.voiceSettings)
        .map { list ->
            if (list.local == isomeria.aichat.core.VoiceSource.SYSTEM_TTS) settings.systemTtsVoice
            else list.providers.firstOrNull { it.id == list.selectedId }?.voice.orEmpty()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 正在用的语音合成来源能不能打开选音色页（0.7.2）：系统语音，或有音色列表的服务商 */
    val canPickVoice: StateFlow<Boolean> = voices.observe(isomeria.aichat.data.db.ProviderKind.TTS, settings.voiceSettings)
        .map { list ->
            list.local == isomeria.aichat.core.VoiceSource.SYSTEM_TTS ||
                list.providers.firstOrNull { it.id == list.selectedId }?.let { isomeria.aichat.data.voice.VoiceCatalog.supports(it.presetId, it.provider) } == true
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 选音色页（0.7.2）：列的是正在用的合成来源的音色 */
    val picker = isomeria.aichat.feature.voice.VoicePickerController(viewModelScope, catalog, preview, context.getString(R.string.voice_preview_text))

    fun openVoicePicker() {
        val current = _state.value.form.ttsVoice
        viewModelScope.launch {
            if (voices.selectedLocal(isomeria.aichat.data.db.ProviderKind.TTS) == isomeria.aichat.core.VoiceSource.SYSTEM_TTS) {
                picker.open(isomeria.aichat.data.voice.VoiceTarget(null, "", "", null, "", systemTts = true), current)
                return@launch
            }
            val config = voices.selected(isomeria.aichat.data.db.ProviderKind.TTS)
            if (config == null) {
                picker.open(null, current, R.string.voice_picker_no_source)
                return@launch
            }
            val url = isomeria.aichat.data.repo.BaseUrlRules.normalize(config.baseUrl, isomeria.aichat.BuildConfig.DEBUG)
            picker.open(url?.let { isomeria.aichat.data.voice.VoiceTarget(config.presetId, config.provider, it, voices.apiKey(config), config.defaultModel) }, current)
        }
    }

    fun pickVoice(id: String) {
        update { it.copy(ttsVoice = id.take(Contact.VOICE_MAX_LENGTH)) }
        picker.close()
    }

    val state: StateFlow<ContactEditState> = _state.asStateFlow()

    /** 可选的服务商 */
    val providerList: StateFlow<List<ProviderConfig>> = providers.llmConfigs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前选中的服务商的模型 */
    val models: StateFlow<List<ProviderModel>> = _state.map { it.form.providerId }
        .distinctUntilChanged()
        .flatMapLatest { providers.observeModels(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 系统提示词合计占用；随表单、全局记忆、回复偏好、记忆和昵称变化 */
    val promptUsage: StateFlow<PromptUsage?> = combine(
        _state.map { it.form }.distinctUntilChanged(),
        globals.tree,
        combine(settings.replyPreferences, settings.nickname) { prefs, nick -> prefs to nick },
        if (contactId == NEW_CONTACT) flowOf(emptyList()) else memories.observe(contactId),
        providers.llmConfigs,
    ) { form, tree, (prefs, nickname), memoryRows, configs ->
        val estimator = TokenEstimator.forProtocol(configs.firstOrNull { it.id == form.providerId }?.provider ?: ProviderProtocol.OPENAI)
        val contact = Contact(
            name = form.name.trim(), avatarColor = 0, providerId = form.providerId, model = form.model,
            systemPrompt = form.systemPrompt, contextTokens = form.contextTokens,
            useGlobalMemory = form.useGlobalMemory, useReplyPreferences = form.useReplyPreferences,
            proactiveEnabled = form.proactiveEnabled, createdAt = 0, updatedAt = 0,
        )
        val chosen = MemoryPolicy.forRequest(memoryRows, MemoryPolicy.memoryBudget(form.contextTokens), estimator)
        val promptGlobals = PromptGlobals(
            globalMemory = if (form.useGlobalMemory) tree.entries() else emptyList(),
            replyStyle = if (form.useReplyPreferences) prefs else null,
            reminderHint = form.proactiveEnabled,
        )
        val prompt = contextBuilder.systemPrompt(contact, nickname, clock.now(), chosen, promptGlobals)
        PromptUsage(prompt?.let(estimator::text) ?: 0, form.contextTokens)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val form = if (contactId == NEW_CONTACT) {
                val config = providers.defaultLlm()
                ContactForm(
                    avatarColor = randomAvatarColor(),
                    providerId = config.id,
                    model = config.defaultModel,
                    systemPrompt = context.getString(R.string.prompt_template),
                )
            } else {
                val contact = contacts.get(contactId)
                existing = contact
                contact?.let {
                    ContactForm(
                        name = it.name,
                        avatarPath = it.avatarPath,
                        avatarColor = it.avatarColor,
                        providerId = it.providerId,
                        model = it.model,
                        thinking = it.thinking,
                        thinkingOption = it.thinkingOption,
                        temperature = it.temperature,
                        systemPrompt = it.systemPrompt,
                        contextTokens = it.contextTokens,
                        splitReplies = it.splitReplies,
                        autoMemory = it.autoMemory,
                        useGlobalMemory = it.useGlobalMemory,
                        useReplyPreferences = it.useReplyPreferences,
                        proactiveEnabled = it.proactiveEnabled,
                        proactiveFrequency = it.proactiveFrequency,
                        replyType = it.replyType,
                        ttsVoice = it.ttsVoice.orEmpty(),
                        ttsStyle = it.ttsStyle,
                    )
                } ?: ContactForm(avatarColor = randomAvatarColor())
            }
            _state.value = ContactEditState(loaded = true, isNew = contactId == NEW_CONTACT, form = form, original = form)
        }
    }

    fun update(transform: (ContactForm) -> ContactForm) {
        _state.update { it.copy(form = transform(it.form), showNameError = false) }
    }

    fun setContextInput(text: String) {
        if (text.any { it !in '0'..'9' }) return
        _state.update { state -> state.copy(contextInput = text,
            form = parseContextInput(text)?.let { state.form.copy(contextTokens = it) } ?: state.form) }
    }

    fun setContextValue(value: Int) = setContextInput(value.toString())

    fun finishContextInput() {
        _state.update { state -> parseContextInput(state.contextInput)?.let { state.copy(contextInput = it.toString()) } ?: state }
    }

    /** 第一次开启主动聊天前要先看说明（会自动消耗 API 额度），看过一次就不再弹 */
    val needsProactiveIntro: Boolean get() = !settings.proactiveIntroShown

    fun confirmProactiveIntro() {
        settings.proactiveIntroShown = true
        update { it.copy(proactiveEnabled = true) }
    }

    fun setName(name: String) = update { it.copy(name = name.take(Contact.NAME_MAX_LENGTH)) }

    /** 换服务商：模型换成它的默认模型；没有默认模型时留空，由用户主动选择，不自动选第一个 */
    fun selectProvider(config: ProviderConfig) {
        if (config.id == _state.value.form.providerId) return
        viewModelScope.launch {
            val model = config.defaultModel
            _state.update { state ->
                state.copy(
                    showModelError = false,
                    form = state.form.copy(
                        providerId = config.id,
                        model = model,

                    ),
                )
            }
        }
    }

    /** 换模型：新模型不支持思考时，思考强度改回「关」 */
    fun selectModel(model: ProviderModel) {
        _state.update { state ->
            state.copy(
                showModelError = false,
                form = state.form.copy(
                    model = model.modelId,

                ),
            )
        }
    }

    fun pickAvatar(uri: Uri) {
        viewModelScope.launch {
            val bitmap = runCatching { files.decodeForCrop(uri) }.getOrNull() ?: return@launch
            _state.update { it.copy(cropSource = bitmap) }
        }
    }

    fun cancelCrop() = _state.update { it.copy(cropSource = null) }

    fun applyCrop(bitmap: Bitmap) {
        viewModelScope.launch {
            val path = files.saveAvatar(bitmap)
            pendingAvatars += path
            _state.update { it.copy(cropSource = null, form = it.form.copy(avatarPath = path)) }
        }
    }

    fun resetAvatar() = update { it.copy(avatarPath = null) }

    /** 保存。名称为空或没选模型时提示，返回 null；成功时返回联系人 id。 */
    suspend fun save(): Long? {
        if (parseContextInput(_state.value.contextInput) == null) {
            _state.update { it.copy(contextFocusRequest = it.contextFocusRequest + 1) }
            return null
        }
        finishContextInput()
        val form = _state.value.form
        val name = form.name.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(showNameError = true) }
            return null
        }
        if (modelBlocksSave(form, existing?.let { _state.value.original }, providers.model(form.providerId, form.model))) {
            _state.update { it.copy(showModelError = true) }
            return null
        }
        val now = System.currentTimeMillis()
        val id = existing?.let { old ->
            contacts.update(
                old.copy(
                    name = name,
                    avatarPath = form.avatarPath,
                    avatarColor = form.avatarColor,
                    providerId = form.providerId,
                    model = form.model,
                    thinking = form.thinking,
                    thinkingOption = form.thinkingOption,
                    temperature = form.temperature,
                    systemPrompt = form.systemPrompt,
                    contextTokens = form.contextTokens,
                    splitReplies = form.splitReplies,
                    autoMemory = form.autoMemory,
                    useGlobalMemory = form.useGlobalMemory,
                    useReplyPreferences = form.useReplyPreferences,
                    proactiveEnabled = form.proactiveEnabled,
                    proactiveFrequency = form.proactiveFrequency,
                    replyType = form.replyType,
                    ttsVoice = form.ttsVoice.trim().ifEmpty { null },
                    ttsStyle = form.ttsStyle.trim(),
                ),
            )
            old.id
        } ?: contacts.create(
            Contact(
                name = name,
                avatarPath = form.avatarPath,
                avatarColor = form.avatarColor,
                providerId = form.providerId,
                model = form.model,
                thinking = form.thinking,
                thinkingOption = form.thinkingOption,
                temperature = form.temperature,
                systemPrompt = form.systemPrompt,
                contextTokens = form.contextTokens,
                splitReplies = form.splitReplies,
                autoMemory = form.autoMemory,
                useGlobalMemory = form.useGlobalMemory,
                useReplyPreferences = form.useReplyPreferences,
                proactiveEnabled = form.proactiveEnabled,
                proactiveFrequency = form.proactiveFrequency,
                replyType = form.replyType,
                ttsVoice = form.ttsVoice.trim().ifEmpty { null },
                ttsStyle = form.ttsStyle.trim(),
                createdAt = now,
                updatedAt = now,
            ),
        )
        saved = true
        pendingAvatars.remove(form.avatarPath)
        files.commitAvatar(form.avatarPath)
        return id
    }

    fun delete(onDone: () -> Unit) {
        val id = existing?.id ?: return
        appScope.launch {
            contacts.delete(id)
            launch(kotlinx.coroutines.Dispatchers.Main) { onDone() }
        }
    }

    override fun onCleared() {
        picker.close()
        // 没保存就离开，或保存时用的是另一张：删掉编辑中生成的头像文件
        val keep = if (saved) _state.value.form.avatarPath else existing?.avatarPath
        pendingAvatars.filter { it != keep }.forEach(files::delete)
    }
}
