package isomeria.aichat.data.reply

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.core.Clock
import isomeria.aichat.core.ProactivePolicy
import isomeria.aichat.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主动聊天的后台任务（0.6.0），都交给 WorkManager，不用精确闹钟、不申请额外权限：
 *
 * - `proactive-check`：周期任务，间隔 15 分钟（系统允许的最小值），需要联网。**只在至少一个联系人开启主动聊天时注册**，
 *   全部关闭时取消；新装、升级、恢复旧备份后没有联系人开启，所以不会注册
 * - `proactive-reminder`：一次性任务，按最近的一个约定提醒排延迟；没有约定时取消
 *
 * 两个名字都和回复任务 `reply-{contactId}`、投递任务 `deliver-{contactId}` 不同，也不按标签取消，互不影响。
 *
 * 周期任务不保证准时：系统会按省电策略推迟；部分系统（真机 HyperOS）在 App 退到后台后冻结进程，
 * 强制停止后要等下次打开才恢复。所以 App 回到前台时也会检查一次，状态页显示最近运行时间。
 */
@Singleton
class ProactiveScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val clock: Clock,
) : ProactiveControl {
    private val workManager get() = WorkManager.getInstance(context)

    override suspend fun sync() {
        val proactive = db.proactiveDao()
        proactive.deleteRemindersOfDisabled()
        if (db.contactDao().proactiveCount() == 0) {
            workManager.cancelUniqueWork(CHECK_NAME)
            workManager.cancelUniqueWork(REMINDER_NAME)
            return
        }
        val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        // 已经注册过就保留原来的，不重置它的周期
        workManager.enqueueUniquePeriodicWork(
            CHECK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ProactiveWorker>(ProactivePolicy.CHECK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
                .setConstraints(connected)
                .addTag(TAG)
                .build(),
        )

        val running = workManager.getWorkInfosForUniqueWorkFlow(REMINDER_NAME).first().any { it.state == WorkInfo.State.RUNNING }
        val next = proactive.nextReminderAt()
        if (next == null) {
            // 约定任务自己跑完时也会走到这里：正在跑的不取消，让它正常结束
            if (!running) workManager.cancelUniqueWork(REMINDER_NAME)
            return
        }
        // 已经到点却还留着的约定（正在等回复、今天次数用完）：隔一会儿再试，不连着重跑
        val now = clock.now()
        val delay = if (next > now) next - now else OVERDUE_RETRY_MILLIS
        workManager.enqueueUniqueWork(
            REMINDER_NAME,
            // 正在跑的任务（多半就是调用方自己）不能被替换掉：接在它后面；否则换成按最新约定时间排的
            if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ProactiveReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(connected)
                .addTag(TAG)
                .build(),
        )
    }

    /** 周期检查是否已注册（排队或正在运行）；状态页用 */
    fun observeCheckScheduled(): Flow<Boolean> =
        workManager.getWorkInfosForUniqueWorkFlow(CHECK_NAME).map { infos -> infos.any { !it.state.isFinished } }

    suspend fun isCheckScheduled(): Boolean = observeCheckScheduled().first()

    suspend fun isReminderScheduled(): Boolean =
        workManager.getWorkInfosForUniqueWorkFlow(REMINDER_NAME).first().any { !it.state.isFinished }

    companion object {
        const val CHECK_NAME = "proactive-check"
        const val REMINDER_NAME = "proactive-reminder"
        const val TAG = "proactive"
        const val OVERDUE_RETRY_MILLIS = 5 * 60_000L
    }
}

/**
 * 周期检查。不转前台任务（周期任务在后台不允许启动前台服务），所以可能被系统冻结或中途停止：
 * 请求没做完就算了，不重试，下一个周期再看。
 */
@HiltWorker
class ProactiveWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: ProactiveEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            engine.run()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 不重试，也不让周期任务因为一次意外而停掉
        }
        return Result.success()
    }
}

/** 约定提醒到点：只处理到点的约定，不掷概率。 */
@HiltWorker
class ProactiveReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: ProactiveEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        try {
            engine.run(remindersOnly = true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        return Result.success()
    }
}
