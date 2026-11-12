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
    /** 单独设置的回复偏好（0.7.2）：null 跟随「我 → 回复偏好」；只在 [useReplyPreferences] 时生效 */
    val replyPreferences: isomeria.aichat.core.ReplyPreferences? = null,
    /** 单独选的语音合成、识别来源（0.7.2）：null 跟随「我 → 语音消息」 */
    val ttsSource: String? = null,
    val asrSource: String? = null,
) {
    val replyMode: ReplyPreferencesMode
        get() = when {
            !useReplyPreferences -> ReplyPreferencesMode.OFF
            replyPreferences != null -> ReplyPreferencesMode.CUSTOM
            else -> ReplyPreferencesMode.FOLLOW
        }
}

/** 联系人的回复偏好（0.7.2）：跟随全局、单独设置、不使用（按分条显示的旧行为） */
enum class ReplyPreferencesMode { FOLLOW, CUSTOM, OFF }

/**
 * 换回复偏好的方式。单独设置第一次打开时复制 [global]，之前单独设置过的值还在就接着用；
 * 跟随全局时丢掉单独设置的值，不使用时保留（之后再选单独设置还在）
 */
internal fun ContactForm.withReplyMode(mode: ReplyPreferencesMode, global: isomeria.aichat.core.ReplyPreferences): ContactForm = when (mode) {
    ReplyPreferencesMode.FOLLOW -> copy(useReplyPreferences = true, replyPreferences = null)
    ReplyPreferencesMode.CUSTOM -> copy(useReplyPreferences = true, replyPreferences = replyPreferences ?: global)
    ReplyPreferencesMode.OFF -> copy(useReplyPreferences = false)
}

/**
 * 换语音合成来源（0.7.2，null 跟随全局，[global] 是全局现在用的）：实际用的来源变了时清空音色——一家的音色 ID 拿到另一家用不了
 */
internal fun ContactForm.withTtsSource(source: String?, global: String): ContactForm {
    val before = ttsSource ?: global
    val after = source ?: global
    return copy(ttsSource = source, ttsVoice = if (before == after) ttsVoice else "")
}

/**
 * 保存前去掉指向已删除服务商的来源（0.7.2）：编辑期间服务商被删了，和删除时一样改为跟随全局；
 * 本机来源和还在的服务商原样保留
 */
internal fun ContactForm.withoutMissingSources(choices: ContactVoiceChoices): ContactForm {
    fun keep(source: String?, providers: List<ProviderConfig>): String? {
        val parsed = source?.let(isomeria.aichat.core.VoiceSource::parse) as? isomeria.aichat.core.VoiceSource.Provider ?: return source
        return source.takeIf { providers.any { it.id == parsed.id } }
    }
    return copy(ttsSource = keep(ttsSource, choices.tts), asrSource = keep(asrSource, choices.asr))
}

/** 联系人编辑页可选的语音来源（0.7.2）：各类的服务商，和「我 → 语音消息」现在用的来源 */
data class ContactVoiceChoices(
    val tts: List<ProviderConfig> = emptyList(),
    val asr: List<ProviderConfig> = emptyList(),
    val globalTts: String = "",
    val globalAsr: String = "",
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

    /** 「我 → 回复偏好」现在的值（0.7.2）：联系人跟随全局时写出来，单独设置第一次打开时复制 */
    val globalReplyPreferences: StateFlow<isomeria.aichat.core.ReplyPreferences> = settings.replyPreferences

    /** 可选的语音来源和全局现在用的（0.7.2） */
    val voiceChoices: StateFlow<ContactVoiceChoices> = combine(
        voices.observe(isomeria.aichat.data.db.ProviderKind.TTS, settings.voiceSettings),
        voices.observe(isomeria.aichat.data.db.ProviderKind.ASR, settings.voiceSettings),
    ) { tts, asr -> ContactVoiceChoices(tts.providers, asr.providers, tts.source, asr.source) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ContactVoiceChoices())

    /** 这个联系人实际用的语音合成来源：表单里单独选的，或者全局的 */
    private val ttsSource: kotlinx.coroutines.flow.Flow<Pair<String, ContactVoiceChoices>> =
        combine(_state.map { it.form.ttsSource }.distinctUntilChanged(), voiceChoices) { own, choices -> (own ?: choices.globalTts) to choices }

    /** 实际用的语音合成来源的默认音色：音色留空时用它（0.7.1 起系统语音用「系统语音」页选的音色） */
    val providerVoice: StateFlow<String> = ttsSource
        .map { (source, choices) ->
            when (val parsed = isomeria.aichat.core.VoiceSource.parse(source)) {
                isomeria.aichat.core.VoiceSource.SYSTEM_TTS -> settings.systemTtsVoice
                is isomeria.aichat.core.VoiceSource.Provider -> choices.tts.firstOrNull { it.id == parsed.id }?.voice.orEmpty()
                else -> ""
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 实际用的语音合成来源能不能打开选音色页（0.7.2）：系统语音，或有音色列表的服务商 */
    val canPickVoice: StateFlow<Boolean> = ttsSource
        .map { (source, choices) ->
            when (val parsed = isomeria.aichat.core.VoiceSource.parse(source)) {
                isomeria.aichat.core.VoiceSource.SYSTEM_TTS -> true
                is isomeria.aichat.core.VoiceSource.Provider ->
                    choices.tts.firstOrNull { it.id == parsed.id }?.let { isomeria.aichat.data.voice.VoiceCatalog.supports(it.presetId, it.provider) } == true
                else -> false
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 选音色页（0.7.2）：列的是这个联系人实际用的合成来源的音色 */
    val picker = isomeria.aichat.feature.voice.VoicePickerController(viewModelScope, catalog, preview, context.getString(R.string.voice_preview_text))

    /** 换回复偏好的方式（0.7.2）；单独设置第一次打开时复制全局现在的值 */
    fun setReplyMode(mode: ReplyPreferencesMode) = update { it.withReplyMode(mode, settings.replyPreferences.value) }

    /** 改单独设置的回复偏好（0.7.2） */
    fun updateReplyPreferences(transform: (isomeria.aichat.core.ReplyPreferences) -> isomeria.aichat.core.ReplyPreferences) =
        update { form -> form.replyPreferences?.let { form.copy(replyPreferences = transform(it).normalized()) } ?: form }

    /** 单独选语音来源（0.7.2）；null 跟随全局。合成来源实际变了时清空音色 */
    fun setVoiceSource(kind: isomeria.aichat.data.db.ProviderKind, source: String?) = update { form ->
        when (kind) {
            isomeria.aichat.data.db.ProviderKind.TTS -> form.withTtsSource(source, voiceChoices.value.globalTts)
            isomeria.aichat.data.db.ProviderKind.ASR -> form.copy(asrSource = source)
            isomeria.aichat.data.db.ProviderKind.LLM -> form
        }
    }

    fun openVoicePicker() {
        val current = _state.value.form.ttsVoice
        val kind = isomeria.aichat.data.db.ProviderKind.TTS
        val source = _state.value.form.ttsSource ?: voices.sourceFor(kind, null)
        viewModelScope.launch {
            if (voices.localOf(kind, source) == isomeria.aichat.core.VoiceSource.SYSTEM_TTS) {
                picker.open(isomeria.aichat.data.voice.VoiceTarget(null, "", "", null, "", systemTts = true), current)
                return@launch
            }
            val config = voices.providerOf(kind, source)
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
            replyStyle = if (form.useReplyPreferences) form.replyPreferences ?: prefs else null,
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
                        replyPreferences = it.replyPreferences,
                        ttsSource = it.ttsSource,
                        asrSource = it.asrSource,
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
        // 编辑期间单独选的语音服务商被删了：改为跟随全局（按数据库里现在的服务商判断，不用页面上可能还没读出来的列表）
        val providersNow = ContactVoiceChoices(
            tts = voices.list(isomeria.aichat.data.db.ProviderKind.TTS),
            asr = voices.list(isomeria.aichat.data.db.ProviderKind.ASR),
        )
        val form = _state.value.form.withoutMissingSources(providersNow)
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
                    replyPreferences = form.replyPreferences,
                    ttsSource = form.ttsSource,
                    asrSource = form.asrSource,
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
                replyPreferences = form.replyPreferences,
                ttsSource = form.ttsSource,
                asrSource = form.asrSource,
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
