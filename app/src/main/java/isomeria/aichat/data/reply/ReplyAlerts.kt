package isomeria.aichat.data.reply

import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ConversationDao
import javax.inject.Inject
import javax.inject.Singleton

/** 回复到达通知；[ReplyNotifier] 的实现会再看全局开关和系统权限。 */
interface ReplyNotifications {
    /** [alert] 为 false 时只更新已有的通知，不再响铃震动（同一次回复的后几条） */
    fun notifyReply(contact: Contact, parts: List<String>, alert: Boolean = true)

    fun cancelReply(contactId: Long)
}

/** 排队等待回复和投递；[ReplyScheduler] 用 WorkManager 实现。 */
interface ReplyQueue : ReplyControl {
    fun enqueue(contactId: Long)

    fun enqueueDelivery(contactId: Long)
}

/**
 * 回复送达后决定要不要发系统通知：正在看这个聊天，或这个聊天开了消息免打扰，就不发。
 * 0.5.0 起由 [DeliveryEngine] 在每次投递时调用。
 *
 * 免打扰只管这条到达通知：未读数、会话预览、生成和记忆照常处理；
 * 等待较久时的「正在接收消息」前台通知不含联系人信息，是系统要求的后台运行提示，不受免打扰影响。
 */
@Singleton
class ReplyAlerts @Inject constructor(
    private val conversationDao: ConversationDao,
    private val notifications: ReplyNotifications,
    private val activeChat: ActiveChatTracker,
) {
    suspend fun afterReply(contact: Contact, parts: List<String>, alert: Boolean = true) {
        if (activeChat.isViewing(contact.id)) return
        if (conversationDao.get(contact.id)?.muted == true) return
        notifications.notifyReply(contact, parts, alert)
    }
}
