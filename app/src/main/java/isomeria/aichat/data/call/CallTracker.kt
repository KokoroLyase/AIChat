package isomeria.aichat.data.call

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 别处要知道有没有在通话、或在删聊天和恢复数据前先挂断（0.8.0）。真实实现是 [CallTracker]，由 [CallEngine] 登记；
 * 单元测试里默认 [NONE]
 */
interface CallControl {
    fun inCall(contactId: Long): Boolean

    /** 挂断这个联系人（[contactId] 为 null 时是任何人）正在进行的通话，等收尾完成 */
    suspend fun hangUpFor(contactId: Long?)

    companion object {
        val NONE = object : CallControl {
            override fun inCall(contactId: Long) = false
            override suspend fun hangUpFor(contactId: Long?) = Unit
        }
    }
}

/**
 * 记下正在和谁通话（0.8.0）。不依赖别的东西，主动聊天、聊天和备份都可以放心地依赖它（不会和 [CallEngine] 互相依赖）
 */
@Singleton
class CallTracker @Inject constructor() : CallControl {
    private class Active(val contactId: Long, val hangUp: suspend () -> Unit)

    @Volatile private var active: Active? = null

    /** [CallEngine] 接通时登记，[hangUp] 挂断并等收尾完成 */
    fun enter(contactId: Long, hangUp: suspend () -> Unit) {
        active = Active(contactId, hangUp)
    }

    fun leave() {
        active = null
    }

    override fun inCall(contactId: Long): Boolean = active?.contactId == contactId

    override suspend fun hangUpFor(contactId: Long?) {
        val current = active ?: return
        if (contactId != null && current.contactId != contactId) return
        current.hangUp()
    }
}
