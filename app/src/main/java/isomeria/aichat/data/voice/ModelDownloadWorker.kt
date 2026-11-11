package isomeria.aichat.data.voice

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.data.reply.ReplyNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 下载离线识别模型的后台任务（0.7.1）。用户在「离线识别」页点了下载才安排；转成前台任务（带进度的常驻通知），
 * 回到桌面也接着下。网络断了时 WorkManager 等网络恢复再接着下（已下载的部分不丢）；
 * 校验不对、空间不够、服务器报错时结束，等用户再点。
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: OfflineModelStore,
    private val notifier: ReplyNotifier,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        setForeground(foreground(0))
        var lastPercent = -1
        val ok = store.download { bytes ->
            val percent = (bytes * 100 / store.totalBytes).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                runCatching { setForeground(foreground(percent)) }
            }
        }
        val state = store.state.value
        return when {
            ok -> Result.success()
            // 断网：等网络恢复后重试（WorkManager 有退避），已下载的部分接着用
            state is ModelState.Failed && state.failure == ModelFailure.NETWORK && runAttemptCount < MAX_NETWORK_RETRIES -> Result.retry()
            else -> Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground(0)

    private fun foreground(percent: Int): ForegroundInfo =
        ForegroundInfo(ReplyNotifier.MODEL_DOWNLOAD_ID, notifier.modelDownloadNotification(percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private companion object {
        const val MAX_NETWORK_RETRIES = 5
    }
}

/** 下载任务现在是什么情况（界面用）：没有、在等网络、在下载 */
enum class ModelDownloadWork { NONE, WAITING, RUNNING }

/** 安排和取消模型下载任务（0.7.1）。 */
interface ModelDownloadControl {
    fun start()

    fun cancel()

    val work: Flow<ModelDownloadWork>
}

@Singleton
class OfflineModelDownloads @Inject constructor(@ApplicationContext private val context: Context) : ModelDownloadControl {
    private val manager get() = WorkManager.getInstance(context)

    override fun start() {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(WORK_NAME)
            .build()
        manager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        manager.cancelUniqueWork(WORK_NAME)
    }

    override val work: Flow<ModelDownloadWork> = manager.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
        when {
            infos.any { it.state == WorkInfo.State.RUNNING } -> ModelDownloadWork.RUNNING
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> ModelDownloadWork.WAITING
            else -> ModelDownloadWork.NONE
        }
    }

    companion object {
        const val WORK_NAME = "offline-asr-model"
    }
}
