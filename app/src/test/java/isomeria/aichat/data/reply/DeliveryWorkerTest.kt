package isomeria.aichat.data.reply

import android.app.Application
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.work.ForegroundInfo
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderProtocol
import isomeria.aichat.data.db.Role
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.UUID

/**
 * 投递任务开始时申请转为前台任务（真机 HyperOS 会冻结退到后台的普通任务，条与条之间的等待会被拖到约 1 分钟）；
 * 系统不允许时照常投递。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DeliveryWorkerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val start = 1_800_000_000_000L
    private lateinit var db: AppDatabase
    private lateinit var h: ReplyHarness
    private var id = 0L
    private val foregroundRequests = mutableListOf<Int>()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        h = ReplyHarness(context, db, ZoneId.of("Asia/Shanghai"), start)
        runBlocking {
            db.providerDao().insert(
                ProviderConfig(id = 1, kind = ProviderKind.LLM, provider = ProviderProtocol.OPENAI, name = "测试", baseUrl = "https://example.com/v1", defaultModel = "m"),
            )
            id = db.contactDao().insert(Contact(name = "小雨", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1))
            db.conversationDao().upsert(Conversation(contactId = id, lastMessageTime = start - 10_000, lastMessagePreview = "在吗"))
            db.messageDao().insertAll(listOf(
                Message(contactId = id, role = Role.ASSISTANT, type = MessageType.TEXT, content = "好的", status = MessageStatus.PENDING, replyGroup = "g", createdAt = start - 1_000),
                Message(contactId = id, role = Role.ASSISTANT, type = MessageType.TEXT, content = "马上来", status = MessageStatus.PENDING, replyGroup = "g", createdAt = start + 3_000),
            ))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun info(): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, "test").setSmallIcon(android.R.drawable.stat_notify_chat).build()
        return ForegroundInfo(RECEIVING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun worker(allowForeground: Boolean): ListenableWorker {
        val updater = ForegroundUpdater { _: Context, _: UUID, info: ForegroundInfo ->
            foregroundRequests += info.notificationId
            CallbackToFutureAdapter.getFuture<Void?> { completer ->
                if (allowForeground) completer.set(null) else completer.setException(IllegalStateException("后台不允许启动前台服务"))
                "foreground"
            }
        }
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                DeliveryWorker(appContext, workerParameters, h.delivery) { info() }
        }
        return TestListenableWorkerBuilder<DeliveryWorker>(context)
            .setInputData(workDataOf(DeliveryWorker.KEY_CONTACT_ID to id))
            .setWorkerFactory(factory)
            .setForegroundUpdater(updater)
            .build()
    }

    private suspend fun statuses() = db.messageDao().listByContact(id).map { it.content to it.status }

    @Test
    fun asksToRunInForeground_thenDeliversEveryPart() = runBlocking {
        val result = worker(allowForeground = true).startWork().get()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(RECEIVING), foregroundRequests)
        assertEquals(listOf("好的" to MessageStatus.SUCCESS, "马上来" to MessageStatus.SUCCESS), statuses())
    }

    @Test
    fun foregroundRefused_stillDeliversEveryPart() = runBlocking {
        val result = worker(allowForeground = false).startWork().get()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(RECEIVING), foregroundRequests)
        assertEquals(listOf("好的" to MessageStatus.SUCCESS, "马上来" to MessageStatus.SUCCESS), statuses())
    }

    private companion object {
        const val RECEIVING = 42
    }
}
