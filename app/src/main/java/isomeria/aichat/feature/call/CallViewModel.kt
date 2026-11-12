package isomeria.aichat.feature.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import isomeria.aichat.data.call.CallBlock
import isomeria.aichat.data.call.CallEngine
import isomeria.aichat.data.call.CallState
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ContactDao
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.repo.VoiceRepository
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.data.voice.VoicePlayback
import isomeria.aichat.navigation.CallRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 打通话前的几步：检查 → 第一次的说明 → 麦克风权限 → 接通 */
sealed interface CallPrepare {
    data object Checking : CallPrepare

    /** 不能打，说明原因 */
    data class Blocked(val block: CallBlock) : CallPrepare

    /**
     * 第一次通话前的说明：录音发给谁、回复文字发给谁。名称为 null 表示是本机来源（录音或文字不离开手机）
     */
    data class Intro(val asrName: String?, val ttsName: String?) : CallPrepare

    /** 要麦克风权限（有了就直接接通） */
    data object NeedPermission : CallPrepare
    data object Starting : CallPrepare

    /** 已经在通话，看 [CallViewModel.call] */
    data object Running : CallPrepare
}

/**
 * 通话页（0.8.0）。通话本身在 [CallEngine]，离开页面（回到桌面、锁屏）也接着打；这里只负责发起前的几步和把按钮交给引擎。
 */
@HiltViewModel
class CallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: CallEngine,
    private val contacts: ContactDao,
    private val voices: VoiceRepository,
    private val settings: SettingsStore,
    private val playback: VoicePlayback,
) : ViewModel() {
    val contactId: Long = savedStateHandle.toRoute<CallRoute>().contactId

    val contact: StateFlow<Contact?> = contacts.observe(contactId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 这个联系人的通话；别人的不显示 */
    val call: StateFlow<CallState?> = engine.state.map { it?.takeIf { state -> state.contactId == contactId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, engine.state.value?.takeIf { it.contactId == contactId })

    private val _prepare = MutableStateFlow<CallPrepare>(CallPrepare.Checking)
    val prepare: StateFlow<CallPrepare> = _prepare

    private val _subtitles = MutableStateFlow(settings.callSubtitles)
    val subtitles: StateFlow<Boolean> = _subtitles

    var micPermissionAsked: Boolean
        get() = settings.micPermissionAsked
        set(value) { settings.micPermissionAsked = value }

    /** 进页面时调用一次：已经在和这个人通话就直接显示，否则检查能不能打 */
    fun prepare() {
        if (_prepare.value != CallPrepare.Checking) return
        viewModelScope.launch {
            if (engine.inCall(contactId)) {
                _prepare.value = CallPrepare.Running
                return@launch
            }
            val block = engine.check(contactId)
            _prepare.value = when {
                block != null -> CallPrepare.Blocked(block)
                !settings.callIntroShown -> intro()
                else -> CallPrepare.NeedPermission
            }
        }
    }

    private suspend fun intro(): CallPrepare.Intro = CallPrepare.Intro(sourceName(ProviderKind.ASR), sourceName(ProviderKind.TTS))

    /** 这个联系人实际用的来源的名称；本机来源为 null */
    private suspend fun sourceName(kind: ProviderKind): String? {
        val contact = contacts.get(contactId)
        return if (voices.selectedLocal(kind, contact) != null) null else voices.selected(kind, contact)?.name.orEmpty()
    }

    fun introAccepted() {
        settings.callIntroShown = true
        _prepare.value = CallPrepare.NeedPermission
    }

    /** 有麦克风权限了：接通。要在页面还在前台时调用（前台服务只能在前台启动） */
    fun start() {
        if (_prepare.value == CallPrepare.Starting || _prepare.value == CallPrepare.Running) return
        _prepare.value = CallPrepare.Starting
        viewModelScope.launch {
            // 正在放的语音消息先停下
            playback.stop()
            val block = engine.start(contactId, speakerphone = settings.callSpeakerphone)
            _prepare.value = if (block == null || block == CallBlock.SAME_CALL) CallPrepare.Running else CallPrepare.Blocked(block)
        }
    }

    fun hangUp() = engine.hangUp()

    fun setMuted(muted: Boolean) = engine.setMuted(muted)

    fun setSpeakerphone(on: Boolean) {
        settings.callSpeakerphone = on
        engine.setSpeakerphone(on)
    }

    fun finishSpeaking() = engine.finishSpeaking()

    fun interrupt() = engine.interrupt()

    fun dismissNotice() = engine.dismissNotice()

    fun setSubtitles(on: Boolean) {
        settings.callSubtitles = on
        _subtitles.value = on
    }
}
