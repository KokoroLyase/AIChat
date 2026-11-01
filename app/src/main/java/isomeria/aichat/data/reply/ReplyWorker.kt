package isomeria.aichat.data.reply

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/**
 * 后台等待回复。用户发送时 App 在前台，这时立即申请转为前台任务；
 * Android 12 起系统会延迟约 10 秒才显示前台通知，所以只有等待较久时才会看到「正在接收消息」。
 */
@HiltWorker
class ReplyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: ReplyEngine,
    private val notifier: ReplyNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val contactId = inputData.getLong(KEY_CONTACT_ID, -1L)
        if (contactId < 0) return Result.failure()

        // 被系统中断后重新调度的任务不再自动重试：标记失败，用户可以点击重试
        if (runAttemptCount > 0) {
            engine.failInterrupted(contactId)
            return Result.success()
        }

        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // App 已在后台时系统不允许启动前台服务，继续以普通任务执行
        }

        engine.run(contactId)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = notifier.receivingForegroundInfo()

    companion object {
        const val KEY_CONTACT_ID = "contact_id"
    }
}
