package isomeria.aichat.data.reply

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记录用户此刻正在看哪个聊天（聊天页可见且 App 在前台）。
 * 正在看该聊天时，回复到达不发通知、不加未读。
 */
@Singleton
class ActiveChatTracker @Inject constructor() {
    private val viewing = AtomicLong(NONE)

    fun enter(contactId: Long) {
        viewing.set(contactId)
    }

    fun leave(contactId: Long) {
        viewing.compareAndSet(contactId, NONE)
    }

    fun isViewing(contactId: Long): Boolean = viewing.get() == contactId

    private companion object {
        const val NONE = -1L
    }
}
