package isomeria.aichat.data.reply

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.Clock
import isomeria.aichat.core.Sleeper
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.memory.MemoryQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 可靠投递（0.5.0）：模型返回后，各条回复先以 PENDING 写库，到了计划时间再转成 SUCCESS。
 *
 * - 每次送达在一个事务里改状态、更新会话预览和未读；正在看这个聊天时不加未读
 * - 送达后经 [ReplyAlerts] 发通知（正在看或已免打扰就不发）；同一次回复只在第一条送达时响，之后只更新内容
 * - 可以有几处同时投递（投递任务、等着发新请求的回复任务、启动恢复）：每条只会在一个事务里被转成 SUCCESS，
 *   通知按数据库里的状态决定，不会重复响
 * - App 被杀后：启动时 [recover] 把到期的送达，没到期的重新排队；投递任务重试时照常继续，不当成失败
 */
@Singleton
class DeliveryEngine @Inject constructor(
    private val db: AppDatabase,
    private val alerts: ReplyAlerts,
    private val activeChat: ActiveChatTracker,
    private val memoryQueue: MemoryQueue,
    private val queue: ReplyQueue,
    private val clock: Clock,
    private val sleeper: Sleeper,
) {
    private val messages get() = db.messageDao()
    private val conversations get() = db.conversationDao()

    /** 把到期的 PENDING 转成 SUCCESS；返回这次送达的几条（按时间正序），没有到期的时为空 */
    suspend fun deliverDue(contactId: Long): List<Message> = db.withTransaction {
        val due = messages.duePending(contactId, clock.now())
        if (due.isEmpty()) return@withTransaction emptyList()
        messages.setStatus(due.map { it.id }, MessageStatus.SUCCESS, 0)
        val last = due.last()
        // 送达的这条比会话里记的最后一条新才更新预览；用户在投递期间发的更新的消息保持在预览里
        val conversation = conversations.get(contactId)
        if (conversation != null && last.createdAt >= conversation.lastMessageTime) {
            conversations.setLastMessage(contactId, last.createdAt, last.content)
        }
        if (!activeChat.isViewing(contactId)) conversations.addUnread(contactId, due.size)
        due
    }

    /** 送达到期的并发通知；有送达时返回 true */
    suspend fun deliverDueAndNotify(contactId: Long): Boolean {
        val delivered = deliverDue(contactId)
        if (delivered.isEmpty()) return false
        val contact = db.contactDao().get(contactId) ?: return true
        val group = delivered.last().replyGroup
        val parts = group?.let { messages.deliveredParts(contactId, it) }?.takeIf { it.isNotEmpty() } ?: delivered.map { it.content }
        val first = group == null || delivered.any { it.id == messages.firstOfGroup(contactId, group)?.id }
        alerts.afterReply(contact, parts, alert = first)
        debugLog("delivered: ${delivered.size} first=$first")
        return true
    }

    /** 投递到这个联系人没有 PENDING 为止；送达过就排一次自动记忆检查 */
    suspend fun run(contactId: Long) {
        var delivered = false
        while (true) {
            if (deliverDueAndNotify(contactId)) delivered = true
            val next = messages.nextPendingAt(contactId) ?: break
            sleeper.sleep((next - clock.now()).coerceAtLeast(1))
        }
        if (delivered && db.contactDao().get(contactId)?.autoMemory == true) memoryQueue.enqueue(contactId)
    }

    /** 启动时：到期的直接送达，没到期的交给投递任务 */
    suspend fun recover() {
        for (contactId in messages.contactsWithPending()) {
            val delivered = deliverDueAndNotify(contactId)
            if (messages.hasPending(contactId)) {
                queue.enqueueDelivery(contactId)
            } else if (delivered && db.contactDao().get(contactId)?.autoMemory == true) {
                memoryQueue.enqueue(contactId)
            }
        }
    }

    /** 只在 debug 包输出；不记录消息内容 */
    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "DeliveryEngine"
    }
}

/**
 * 投递任务：每个联系人一个唯一任务 `deliver-{contactId}`，和回复任务 `reply-{contactId}` 分开，
 * 用户又发消息替换回复任务时不会把投递一起取消。重试时（runAttemptCount > 0）照常继续投递。
 */
@HiltWorker
class DeliveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: DeliveryEngine,
    private val notifier: ReplyNotifier,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val contactId = inputData.getLong(KEY_CONTACT_ID, -1L)
        if (contactId < 0) return Result.failure()
        engine.run(contactId)
        return Result.success()
    }

    /** 只在系统要求时用（Android 12 以下的加急任务）；平时投递不显示前台通知 */
    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.receivingForegroundInfo()

    companion object {
        const val KEY_CONTACT_ID = "contact_id"
    }
}
