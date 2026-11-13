package isomeria.aichat.feature.chat

import isomeria.aichat.data.db.Message
import isomeria.aichat.data.repo.canQuote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * 输入栏上方的引用条（0.9.0）：长按“引用”放进来，下一条文字或语音发出去时取走（引用条收起）；
 * 发图片不取，引用条保留。只收这个聊天里能引用的消息
 */
class QuoteDraft(private val contactId: Long) {
    private val _state = MutableStateFlow<Message?>(null)
    val state: StateFlow<Message?> = _state

    fun start(message: Message) {
        if (canQuote(message) && message.contactId == contactId) _state.value = message
    }

    fun cancel() { _state.value = null }

    /** 发送时调用：返回要带上的引用并清空 */
    fun take(): Message? = _state.getAndUpdate { null }
}
