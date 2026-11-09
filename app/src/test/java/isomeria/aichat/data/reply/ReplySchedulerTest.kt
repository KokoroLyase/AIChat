package isomeria.aichat.data.reply

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 回复任务 `reply-{id}` 和投递任务 `deliver-{id}` 是两个唯一任务：用户又发消息（REPLACE 回复任务）
 * 或点「取消」（取消回复任务）时，正在投递的不受影响；删除聊天等才取消投递。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReplySchedulerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val release = CompletableDeferred<Unit>()
    private lateinit var scheduler: ReplyScheduler

    /** 两种任务都换成等 [release] 的空任务，这样能看清谁被取消了 */
    private inner class Blocking(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
        override suspend fun doWork(): Result {
            release.await()
            return Result.success()
        }
    }

    @Before
    fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                Blocking(appContext, workerParameters)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
        scheduler = ReplyScheduler(context)
    }

    @After
    fun tearDown() {
        release.complete(Unit)
    }

    private fun states(name: String): List<WorkInfo.State> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().map { it.state }

    private suspend fun awaitRunning(name: String) = withTimeout(10_000) {
        while (WorkInfo.State.RUNNING !in states(name)) delay(20)
    }

    @Test
    fun replacingOrCancellingReplyNeverCancelsDelivery() = runBlocking {
        scheduler.enqueueDelivery(1)
        awaitRunning("deliver-1")
        scheduler.enqueue(1)
        scheduler.enqueue(1) // 用户又发了一条：REPLACE
        scheduler.cancel(1) // 用户点了取消
        delay(200)
        assertEquals(listOf(WorkInfo.State.RUNNING), states("deliver-1"))
        assertTrue(scheduler.isDelivering(1))
        assertFalse(scheduler.isActive(1))

        // 投递期间又生成了一轮：接在后面排队，不替换正在跑的
        scheduler.enqueueDelivery(1)
        delay(200)
        assertEquals(listOf(WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED), states("deliver-1").sorted())

        // 删除聊天、清空、恢复时取消投递
        scheduler.cancelDelivery(1)
        withTimeout(10_000) { while (scheduler.isDelivering(1)) delay(20) }
        assertTrue(states("deliver-1").all { it == WorkInfo.State.CANCELLED })
    }

    @Test
    fun restartAtLaunch_replacesTheLeftoverDelivery_insteadOfQueueingBehindIt() = runBlocking {
        // 上一个进程留下的投递任务还没结束（真机上它在重试退避里）
        scheduler.enqueueDelivery(2)
        awaitRunning("deliver-2")
        val leftover = WorkManager.getInstance(context).getWorkInfosForUniqueWork("deliver-2").get().single().id

        scheduler.restartDelivery(2)
        // 新任务立即在跑，没有排在旧任务后面（BLOCKED）；旧任务不再运行
        val infos = withTimeout(10_000) {
            var infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("deliver-2").get()
            while (infos.none { it.id != leftover && it.state == WorkInfo.State.RUNNING }) {
                delay(20)
                infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork("deliver-2").get()
            }
            infos
        }
        assertEquals(listOf(WorkInfo.State.RUNNING), infos.filter { it.id != leftover }.map { it.state })
        assertTrue(infos.filter { it.id == leftover }.all { it.state == WorkInfo.State.CANCELLED })
    }
}
