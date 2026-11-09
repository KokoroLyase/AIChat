package isomeria.aichat.data.reply

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** 取消回复和投递任务。恢复数据时用：覆盖前先停掉所有等待中的回复和投递。 */
interface ReplyControl {
    /** 取消还没生成的回复请求；已经生成、等着投递的不受影响 */
    fun cancel(contactId: Long)

    /** 这个联系人是否还有排队或进行中的回复任务 */
    suspend fun isActive(contactId: Long): Boolean

    /** 取消投递任务：删除聊天、清空、删除联系人、覆盖恢复时用（待投递的消息由调用方一起删掉） */
    fun cancelDelivery(contactId: Long)

    suspend fun isDelivering(contactId: Long): Boolean
}

/**
 * 把「等待回复」交给 WorkManager：离开聊天页或切到后台时请求继续进行。
 *
 * 每个联系人一个唯一任务。等待期间又发了新消息时替换旧任务（REPLACE），
 * 新请求会带上所有还在等待回复的消息，联系人像真人一样看完再一起回。
 *
 * 投递是另一个唯一任务 `deliver-{contactId}`（0.5.0），用 APPEND_OR_REPLACE 接在正在跑的投递后面，
 * 回复任务被替换时不会连带取消。
 */
@Singleton
class ReplyScheduler @Inject constructor(@ApplicationContext private val context: Context) : ReplyQueue {
    private val workManager get() = WorkManager.getInstance(context)

    override fun enqueue(contactId: Long) {
        val request = OneTimeWorkRequestBuilder<ReplyWorker>()
            .setInputData(workDataOf(ReplyWorker.KEY_CONTACT_ID to contactId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName(contactId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(contactId: Long) {
        workManager.cancelUniqueWork(uniqueName(contactId))
    }

    override suspend fun isActive(contactId: Long): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName(contactId)).first().any { !it.state.isFinished }

    override fun enqueueDelivery(contactId: Long) = enqueueDelivery(contactId, ExistingWorkPolicy.APPEND_OR_REPLACE)

    override fun restartDelivery(contactId: Long) = enqueueDelivery(contactId, ExistingWorkPolicy.REPLACE)

    private fun enqueueDelivery(contactId: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setInputData(workDataOf(DeliveryWorker.KEY_CONTACT_ID to contactId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(DELIVERY_TAG)
            .build()
        workManager.enqueueUniqueWork(deliveryName(contactId), policy, request)
    }

    override fun cancelDelivery(contactId: Long) {
        workManager.cancelUniqueWork(deliveryName(contactId))
    }

    override suspend fun isDelivering(contactId: Long): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(deliveryName(contactId)).first().any { !it.state.isFinished }

    private fun uniqueName(contactId: Long) = "reply-$contactId"

    private fun deliveryName(contactId: Long) = "deliver-$contactId"

    private companion object {
        const val TAG = "reply"
        const val DELIVERY_TAG = "deliver"
    }
}
