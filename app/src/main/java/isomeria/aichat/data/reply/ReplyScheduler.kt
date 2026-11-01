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

/**
 * 把「等待回复」交给 WorkManager：离开聊天页或切到后台时请求继续进行。
 *
 * 每个联系人一个唯一任务。等待期间又发了新消息时替换旧任务（REPLACE），
 * 新请求会带上所有还在等待回复的消息，联系人像真人一样看完再一起回。
 */
@Singleton
class ReplyScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val workManager get() = WorkManager.getInstance(context)

    fun enqueue(contactId: Long) {
        val request = OneTimeWorkRequestBuilder<ReplyWorker>()
            .setInputData(workDataOf(ReplyWorker.KEY_CONTACT_ID to contactId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(uniqueName(contactId), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(contactId: Long) {
        workManager.cancelUniqueWork(uniqueName(contactId))
    }

    /** 这个联系人是否还有排队或进行中的回复任务 */
    suspend fun isActive(contactId: Long): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName(contactId)).first().any { !it.state.isFinished }

    private fun uniqueName(contactId: Long) = "reply-$contactId"

    private companion object {
        const val TAG = "reply"
    }
}
