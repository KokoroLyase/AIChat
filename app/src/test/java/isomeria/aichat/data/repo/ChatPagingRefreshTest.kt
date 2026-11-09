package isomeria.aichat.data.repo

import android.app.Application
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingDataEvent
import androidx.paging.PagingDataPresenter
import androidx.room.Room
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.Executors

/**
 * 用和 ChatViewModel 相同的 Pager 配置：停在历史时，一次消息表写入不能让窗口跳走。
 *
 * PagingDataPresenter 不是线程安全的，真实页面里它只在主线程上更新和读取。这里用一条单独的线程 [ui] 扮演主线程：
 * presenter 在 [ui] 上应用分页事件，测试读快照和访问条目也都切到 [ui]，并且“按快照算下标”和“访问这个下标”放在同一次调度里，
 * 中间不会插进插入新页、丢弃旧页的事件。以前测试在别的线程直接读，偶尔会读到插入后、丢弃前的中间态，下标随后越界。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ChatPagingRefreshTest {
    private lateinit var db: AppDatabase
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "paging-ui") }.asCoroutineDispatcher()

    private class Presenter(context: CoroutineContext) : PagingDataPresenter<Message>(context) {
        override suspend fun presentPagingDataEvent(event: PagingDataEvent<Message>) = Unit
        val ids get() = snapshot().items.map { it.id }
    }

    /** 在 [ui] 线程上读当前加载的消息 id */
    private suspend fun Presenter.loaded(): List<Long> = withContext(ui) { ids }

    /** 在 [ui] 线程上按当前快照选下标并访问它（触发预取），返回被访问的消息 id */
    private suspend fun Presenter.access(pick: (List<Long>) -> Int): Long = withContext(ui) {
        val current = ids
        val index = pick(current)
        get(index)
        current[index]
    }

    private fun message(id: Long) = Message(
        id = id, contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, status = MessageStatus.SUCCESS, createdAt = id,
    )

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        runBlocking {
            db.contactDao().insert(Contact(id = 1, name = "fixture", avatarColor = 0, providerId = 1, model = "fixture", createdAt = 1, updatedAt = 1))
            db.messageDao().insertAll((1L..3000L).map(::message))
        }
    }

    @After fun close() {
        db.close()
        ui.close()
    }

    private fun CoroutineScope.open(initialKey: Int): Pair<Presenter, Job> {
        val presenter = Presenter(ui)
        val pager = Pager(
            PagingConfig(pageSize = 40, initialLoadSize = 40, prefetchDistance = 40, maxSize = 200, enablePlaceholders = false),
            initialKey = initialKey,
        ) { AbsoluteRefreshPagingSource(db.messageDao().pagingByContact(1)) }
        return presenter to launch(Dispatchers.Default) { pager.flow.collectLatest(presenter::collectFrom) }
    }

    private suspend fun waitUntil(what: String, check: suspend () -> Boolean) = withTimeout(20_000) {
        while (!check()) delay(20)
        assertTrue(what, check())
    }

    /** 写入后窗口会换成新的一批，等它落定后再看正在看的消息还在不在。 */
    private suspend fun refreshAfterWrite(presenter: Presenter, id: Long, write: suspend () -> Unit) {
        val before = presenter.loaded()
        write()
        waitUntil("refresh after write") { presenter.loaded().let { it.isNotEmpty() && it != before } }
        delay(300)
        val after = presenter.loaded()
        assertTrue("message $id left the window: ${after.first()}..${after.last()}", id in after)
    }

    @Test fun searchTargetStaysLoadedAfterNewMessage() = runBlocking {
        val (presenter, job) = open(initialKey = 1500)
        waitUntil("initial load") { presenter.loaded().isNotEmpty() }
        val viewed = presenter.access { 20 }
        refreshAfterWrite(presenter, viewed) { db.messageDao().insert(message(9001)) }
        job.cancel()
    }

    @Test fun deepHistoryStaysLoadedAfterNewMessage() = runBlocking {
        val (presenter, job) = open(initialKey = 0)
        waitUntil("initial load") { presenter.loaded().isNotEmpty() }
        // 一直往旧的方向滑，直到超出 maxSize，最新的页已被丢弃
        withTimeout(20_000) {
            while (presenter.loaded().first() > 2600) {
                presenter.access { it.lastIndex }
                delay(20)
            }
        }
        val viewed = presenter.access { it.size / 2 }
        refreshAfterWrite(presenter, viewed) { db.messageDao().insert(message(9001)) }
        job.cancel()
    }

    @Test fun refreshNearNewestStillIncludesNewMessage() = runBlocking {
        val (presenter, job) = open(initialKey = 0)
        waitUntil("initial load") { presenter.loaded().isNotEmpty() }
        presenter.access { 0 }
        db.messageDao().insert(message(9001))
        waitUntil("new message loaded") { presenter.loaded().firstOrNull() == 9001L }
        assertEquals(3000L, presenter.loaded()[1])
        job.cancel()
    }
}
