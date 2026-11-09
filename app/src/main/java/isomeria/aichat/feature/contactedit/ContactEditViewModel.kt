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
    settings: SettingsStore,
    memories: MemoryRepository,
    private val contextBuilder: ContextBuilder,
    private val clock: Clock,
) : ViewModel() {
    private val contactId = savedState.toRoute<ContactEditRoute>().contactId
    private var existing: Contact? = null

    /** 编辑过程中新生成、但最终没有保存的头像文件，离开时删掉 */
    private val pendingAvatars = mutableSetOf<String>()
    private var saved = false

    private val _state = MutableStateFlow(ContactEditState())
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
            useGlobalMemory = form.useGlobalMemory, useReplyPreferences = form.useReplyPreferences, createdAt = 0, updatedAt = 0,
        )
        val chosen = MemoryPolicy.forRequest(memoryRows, MemoryPolicy.memoryBudget(form.contextTokens), estimator)
        val promptGlobals = PromptGlobals(
            globalMemory = if (form.useGlobalMemory) tree.entries() else emptyList(),
            replyStyle = if (form.useReplyPreferences) prefs else null,
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
        // 没保存就离开，或保存时用的是另一张：删掉编辑中生成的头像文件
        val keep = if (saved) _state.value.form.avatarPath else existing?.avatarPath
        pendingAvatars.filter { it != keep }.forEach(files::delete)
    }
}
