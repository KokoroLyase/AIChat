package isomeria.aichat.data.reply

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import isomeria.aichat.core.Clock
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.ProactiveReminder
import isomeria.aichat.data.repo.ContactRepository
import isomeria.aichat.data.repo.FileStorage
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
 * 主动聊天的后台任务：周期检查只在至少一个联系人开启时注册，全部关闭时取消；默认状态下什么也不注册；
 * 约定提醒按最近的时间排一次性任务；和回复任务 `reply-{id}`、投递任务 `deliver-{id}` 互不取消。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ProactiveSchedulerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val release = CompletableDeferred<Unit>()
    private val now = 1_800_000_000_000L
    private lateinit var db: AppDatabase
    private lateinit var scheduler: ProactiveScheduler
    private lateinit var replies: ReplyScheduler
    private lateinit var contacts: ContactRepository

    /** 所有任务都换成等 [release] 的空任务：只看谁被注册、谁被取消，不会真的调用模型 */
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
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().addCallback(AppDatabase.seedCallback).build()
        scheduler = ProactiveScheduler(context, db, Clock { now })
        replies = ReplyScheduler(context)
        contacts = ContactRepository(db, db.contactDao(), db.conversationDao(), db.messageDao(), db.memoryDao(), db.usageDao(), FileStorage(context), { it }, replies, scheduler)
    }

    @After
    fun tearDown() {
        release.complete(Unit)
        db.close()
    }

    private fun infos(name: String): List<WorkInfo> = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
    private fun states(name: String): List<WorkInfo.State> = infos(name).map { it.state }
    private fun active(name: String): List<WorkInfo> = infos(name).filter { !it.state.isFinished }

    private fun contact(name: String, proactive: Boolean) =
        Contact(name = name, avatarColor = 0, providerId = 1, model = "m", proactiveEnabled = proactive, createdAt = 1, updatedAt = 1)

    private suspend fun awaitRunning(name: String) = withTimeout(10_000) {
        while (WorkInfo.State.RUNNING !in states(name)) delay(20)
    }

    @Test
    fun byDefault_nothingIsRegistered_evenAfterCreatingContactsAndSyncing() = runBlocking {
        scheduler.sync()
        contacts.create(contact("默认联系人", proactive = false))
        contacts.create(Contact(name = "不写开关的联系人", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1))
        scheduler.sync()
        assertTrue(infos(ProactiveScheduler.CHECK_NAME).isEmpty())
        assertTrue(infos(ProactiveScheduler.REMINDER_NAME).isEmpty())
        assertFalse(scheduler.isCheckScheduled())
        assertTrue("没有任何带主动聊天标签的任务", WorkManager.getInstance(context).getWorkInfosByTag(ProactiveScheduler.TAG).get().isEmpty())
    }

    @Test
    fun periodicCheck_isRegisteredWhileAnyContactIsEnabled_andCancelledWhenAllAreOff() = runBlocking {
        val first = contacts.create(contact("甲", proactive = true))
        val check = active(ProactiveScheduler.CHECK_NAME).single()
        assertTrue(scheduler.isCheckScheduled())
        assertEquals("周期 15 分钟", 15 * 60_000L, check.periodicityInfo!!.repeatIntervalMillis)
        assertEquals("需要联网", NetworkType.CONNECTED, check.constraints.requiredNetworkType)

        // 再开一个、再同步：还是同一个任务，不重置周期
        val second = contacts.create(contact("乙", proactive = true))
        scheduler.sync()
        assertEquals(check.id, active(ProactiveScheduler.CHECK_NAME).single().id)

        // 关掉一个：还有人开着，保留
        contacts.update(db.contactDao().get(first)!!.copy(proactiveEnabled = false))
        assertEquals(check.id, active(ProactiveScheduler.CHECK_NAME).single().id)

        // 全部关闭：取消
        contacts.update(db.contactDao().get(second)!!.copy(proactiveEnabled = false))
        assertFalse(scheduler.isCheckScheduled())
        assertTrue(states(ProactiveScheduler.CHECK_NAME).all { it == WorkInfo.State.CANCELLED })

        // 重新开启：重新注册；删除这个联系人：又取消
        contacts.update(db.contactDao().get(second)!!.copy(proactiveEnabled = true))
        assertTrue(scheduler.isCheckScheduled())
        contacts.delete(second)
        assertFalse(scheduler.isCheckScheduled())
    }

    @Test
    fun reminderTask_followsTheEarliestReminder_andIsCancelledWithoutOne() = runBlocking {
        val id = contacts.create(contact("甲", proactive = true))
        assertTrue("没有约定时不排提醒任务", active(ProactiveScheduler.REMINDER_NAME).isEmpty())

        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = now + 3_600_000, note = "", createdAt = now))
        scheduler.sync()
        val later = active(ProactiveScheduler.REMINDER_NAME).single()
        assertEquals(3_600_000L, later.initialDelayMillis)
        assertEquals(NetworkType.CONNECTED, later.constraints.requiredNetworkType)

        // 又约了一个更早的：换成按更早的时间排
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = now + 600_000, note = "", createdAt = now))
        scheduler.sync()
        val earlier = active(ProactiveScheduler.REMINDER_NAME).single()
        assertEquals(600_000L, earlier.initialDelayMillis)
        assertTrue(earlier.id != later.id)

        // 已经到点却还留着（被挡住）的约定：隔 5 分钟再试，不立刻连着跑
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = now - 1_000, note = "", createdAt = now))
        scheduler.sync()
        assertEquals(ProactiveScheduler.OVERDUE_RETRY_MILLIS, active(ProactiveScheduler.REMINDER_NAME).single().initialDelayMillis)

        // 约定都用掉了：取消
        db.proactiveDao().deleteAllReminders()
        scheduler.sync()
        assertFalse(scheduler.isReminderScheduled())
        assertTrue(scheduler.isCheckScheduled())
    }

    @Test
    fun turningTheSwitchOff_dropsThatContactsReminders_andAllOffCancelsBothTasks() = runBlocking {
        val first = contacts.create(contact("甲", proactive = true))
        val second = contacts.create(contact("乙", proactive = true))
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = first, dueAt = now + 600_000, note = "", createdAt = now))
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = second, dueAt = now + 900_000, note = "", createdAt = now))
        scheduler.sync()
        contacts.update(db.contactDao().get(first)!!.copy(proactiveEnabled = false))
        assertEquals(listOf(second), db.proactiveDao().remindersOf(second).map { it.contactId })
        assertTrue(db.proactiveDao().remindersOf(first).isEmpty())
        assertEquals(900_000L, active(ProactiveScheduler.REMINDER_NAME).single().initialDelayMillis)

        contacts.update(db.contactDao().get(second)!!.copy(proactiveEnabled = false))
        assertEquals(0, db.proactiveDao().reminderCount())
        assertFalse(scheduler.isCheckScheduled())
        assertFalse(scheduler.isReminderScheduled())
    }

    @Test
    fun proactiveTasks_andReplyOrDeliveryTasks_neverCancelEachOther() = runBlocking {
        // 回复和投递正在进行
        replies.enqueue(1)
        replies.enqueueDelivery(1)
        awaitRunning("reply-1")
        awaitRunning("deliver-1")

        // 注册、再取消主动聊天的任务：回复和投递不受影响
        val id = contacts.create(contact("甲", proactive = true))
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = now + 600_000, note = "", createdAt = now))
        scheduler.sync()
        contacts.update(db.contactDao().get(id)!!.copy(proactiveEnabled = false))
        delay(200)
        assertEquals(listOf(WorkInfo.State.RUNNING), states("reply-1"))
        assertEquals(listOf(WorkInfo.State.RUNNING), states("deliver-1"))

        // 反过来：用户又发消息（REPLACE 回复任务）、取消回复、取消投递，主动聊天的任务还在
        contacts.update(db.contactDao().get(id)!!.copy(proactiveEnabled = true))
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = now + 600_000, note = "", createdAt = now))
        scheduler.sync()
        val check = active(ProactiveScheduler.CHECK_NAME).single().id
        val reminder = active(ProactiveScheduler.REMINDER_NAME).single().id
        replies.enqueue(1)
        replies.enqueue(id)
        replies.cancel(1)
        replies.cancel(id)
        replies.cancelDelivery(1)
        replies.cancelDelivery(id)
        delay(200)
        assertEquals(check, active(ProactiveScheduler.CHECK_NAME).single().id)
        assertEquals(reminder, active(ProactiveScheduler.REMINDER_NAME).single().id)
    }

    @Test
    fun aRunningReminderTask_isNotReplacedByTheSyncItTriggersItself() = runBlocking {
        val id = contacts.create(contact("甲", proactive = true))
        db.proactiveDao().insertReminder(ProactiveReminder(contactId = id, dueAt = now - 1_000, note = "", createdAt = now))
        scheduler.sync()
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val first = active(ProactiveScheduler.REMINDER_NAME).single().id
        driver.setInitialDelayMet(first)
        driver.setAllConstraintsMet(first)
        awaitRunning(ProactiveScheduler.REMINDER_NAME)

        // 任务结束前会再同步一次：还有约定时接在后面，没有时也不取消正在跑的自己
        scheduler.sync()
        assertEquals(WorkInfo.State.RUNNING, infos(ProactiveScheduler.REMINDER_NAME).single { it.id == first }.state)
        assertEquals(2, active(ProactiveScheduler.REMINDER_NAME).size)
        db.proactiveDao().deleteAllReminders()
        scheduler.sync()
        assertEquals(WorkInfo.State.RUNNING, infos(ProactiveScheduler.REMINDER_NAME).single { it.id == first }.state)
    }
}
