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
import kotlinx.coroutines.CancellationException
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
                queue.restartDelivery(contactId)
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
 *
 * 开始时和回复任务一样申请转为前台任务：条与条之间要等几秒，只靠普通任务时，部分系统（真机 HyperOS）
 * 会在 App 退到后台约 2 秒后冻结进程，后几条要等系统解冻（实测约 1 分钟）才送达。投递通常在回复任务
 * 还是前台任务时排上，这时允许；系统不允许时照常以普通任务执行。Android 12 起前台通知延迟约 10 秒显示，
 * 所以只有投递较久时才会看到「正在接收消息」。
 */
@HiltWorker
class DeliveryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: DeliveryEngine,
    private val foreground: ReceivingForeground,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val contactId = inputData.getLong(KEY_CONTACT_ID, -1L)
        if (contactId < 0) return Result.failure()
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 系统不允许从后台启动前台服务，继续以普通任务执行
        }
        engine.run(contactId)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground.receivingForegroundInfo()

    companion object {
        const val KEY_CONTACT_ID = "contact_id"
    }
}
