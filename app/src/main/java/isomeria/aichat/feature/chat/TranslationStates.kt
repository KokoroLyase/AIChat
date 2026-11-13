package isomeria.aichat.feature.chat

import isomeria.aichat.data.repo.FailReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 一条消息翻译的进行状态（0.9.0）；翻好的译文存在消息上，不在这里 */
sealed interface TranslationUi {
    data object Loading : TranslationUi
    data class Failed(val reason: FailReason) : TranslationUi
}

/**
 * 聊天页里各条消息“正在翻译”“翻译失败”的状态（0.9.0）：同一条正在翻译时不重复请求；失败时记下原因，点一下重试。
 * 只在内存里，离开聊天页就没有了（失败的再点“翻译”就是重试）
 */
class TranslationStates(
    private val scope: CoroutineScope,
    private val translate: suspend (Long) -> FailReason?,
) {
    private val _state = MutableStateFlow<Map<Long, TranslationUi>>(emptyMap())
    val state: StateFlow<Map<Long, TranslationUi>> = _state

    fun start(messageId: Long) {
        var started = false
        _state.update { current ->
            started = current[messageId] != TranslationUi.Loading
            if (started) current + (messageId to TranslationUi.Loading) else current
        }
        if (!started) return
        scope.launch {
            val failure = try {
                translate(messageId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it - messageId }
                throw e
            } catch (e: Exception) {
                FailReason.UNKNOWN
            }
            _state.update { if (failure == null) it - messageId else it + (messageId to TranslationUi.Failed(failure)) }
        }
    }

    /** 隐藏翻译、删掉消息时：失败提示一起去掉 */
    fun clear(messageId: Long) = _state.update { it - messageId }
}
