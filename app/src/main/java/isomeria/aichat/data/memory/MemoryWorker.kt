package isomeria.aichat.data.memory

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 回复成功后在后台检查要不要总结记忆。失败时不重试，下次回复后还会再检查。 */
@HiltWorker
class MemoryWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: MemoryEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val contactId = inputData.getLong(KEY_CONTACT_ID, -1L)
        if (contactId < 0) return Result.failure()
        engine.autoRun(contactId)
        return Result.success()
    }

    companion object {
        const val KEY_CONTACT_ID = "contact_id"
    }
}

/** 每个联系人最多一个记忆任务：已经有一个在排队或进行中时不再加。 */
@Singleton
class MemoryScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    fun enqueue(contactId: Long) {
        val request = OneTimeWorkRequestBuilder<MemoryWorker>()
            .setInputData(workDataOf(MemoryWorker.KEY_CONTACT_ID to contactId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("memory-$contactId", ExistingWorkPolicy.KEEP, request)
    }
}
