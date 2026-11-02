package isomeria.aichat.data.db

import android.app.Application
import androidx.paging.PagingSource
import androidx.room.Room
import isomeria.aichat.data.repo.ChatPaging
import isomeria.aichat.data.repo.ChatMessageActions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PagingQueriesTest {
    private lateinit var db: AppDatabase
    private val messages get() = db.messageDao()
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java).build()
        for (id in 1L..2L) {
            db.contactDao().insert(Contact(id = id, name = "fixture", avatarColor = 0, providerId = 1, model = "fixture", createdAt = 1, updatedAt = 1))
            db.conversationDao().upsert(Conversation(contactId = id))
        }
    }
    @After fun close() { db.close() }
    private fun message(id: Long, role: Role = Role.ASSISTANT, time: Long = id, group: String? = null, status: MessageStatus = MessageStatus.SUCCESS, contact: Long = 1) =
        Message(id = id, contactId = contact, role = role, type = MessageType.TEXT, status = status, createdAt = time, replyGroup = group)

    @Test fun initialOffsetLoadsSearchTargetWithStableTimestampTies() = runBlocking {
        messages.insertAll((1L..240L).map { message(it, time = it / 3) })
        val entry = ChatPaging(db).entry(1, 120)
        assertEquals(120, entry.offset)
        val page = messages.pagingByContact(1).load(PagingSource.LoadParams.Refresh(entry.offset, 40, false)) as PagingSource.LoadResult.Page<Int, Message>
        assertEquals(40, page.data.size)
        assertTrue(page.data.any { it.id == 120L })
        assertEquals(page.data.sortedWith(compareByDescending<Message> { it.createdAt }.thenByDescending { it.id }), page.data)
    }
    @Test fun pageBoundariesDoNotDuplicateOrDropEqualTimestamps() = runBlocking {
        messages.insertAll((1L..95L).map { message(it, time = it / 3) })
        val source = messages.pagingByContact(1)
        val ids = mutableListOf<Long>()
        var page = source.load(PagingSource.LoadParams.Refresh(0, 40, false)) as PagingSource.LoadResult.Page<Int, Message>
        ids += page.data.map { it.id }
        while (page.nextKey != null) {
            page = source.load(PagingSource.LoadParams.Append(requireNotNull(page.nextKey), 40, false)) as PagingSource.LoadResult.Page<Int, Message>
            ids += page.data.map { it.id }
        }
        assertEquals((95L downTo 1L).toList(), ids)
    }
    @Test fun unreadAnchorIsCapturedBeforeMarkReadAndRedDotUsesLatest() = runBlocking {
        messages.insertAll((1L..12L).map { message(it, role = if (it % 4 == 0L) Role.USER else Role.ASSISTANT) })
        db.conversationDao().upsert(Conversation(contactId = 1, unreadCount = 3))
        val entry = ChatPaging(db).entry(1, 0)
        db.conversationDao().markRead(1)
        assertEquals(9L, entry.targetId)
        assertEquals(3, entry.offset)
        db.conversationDao().markUnread(1)
        assertEquals(0L, ChatPaging(db).entry(1, 0).targetId)
    }
    @Test fun searchOverridesUnreadAndCannotTargetAnotherChat() = runBlocking {
        messages.insertAll(listOf(message(1), message(2), message(3, contact = 2)))
        db.conversationDao().upsert(Conversation(contactId = 1, unreadCount = 1))
        assertEquals(1L, ChatPaging(db).entry(1, 1).targetId)
        assertEquals(0L, ChatPaging(db).entry(1, 3).targetId)
        assertEquals(0L, ChatPaging(db).entry(1, 999).targetId)
    }
    @Test fun newMessageCountExcludesFutureRowsAndDeletedRows() = runBlocking {
        messages.insertAll(listOf(message(1, time = 100), message(2, time = 100), message(3, time = 1000)))
        assertEquals(1, messages.countNewerVisible(1, 100, 1, 500))
        messages.delete(2)
        assertEquals(0, messages.countNewerVisible(1, 100, 1, 500))
        assertEquals(1, messages.countNewerVisible(1, 100, 1, 1000))
    }
    @Test fun timelineHidesScheduledRowsAndReportsTyping() = runBlocking {
        val now = System.currentTimeMillis()
        messages.insertAll(listOf(message(1, role = Role.USER, time = now - 1, status = MessageStatus.SENDING), message(2, time = now + 60_000)))
        val timeline = ChatPaging(db).timeline(1).first()
        assertEquals(1L, timeline.visibleLatest?.id)
        assertTrue(timeline.waiting)
        assertTrue(timeline.pending)
    }
    @Test fun retryOnlyChangesFailedUsersAfterLastReplyIncludingTimestampTies() = runBlocking {
        messages.insertAll(listOf(message(1, Role.USER, time = 10, status = MessageStatus.FAILED), message(2, time = 10),
            message(3, Role.USER, time = 10, status = MessageStatus.FAILED), message(4, Role.USER, time = 10)))
        assertTrue(ChatMessageActions(db).retry(1))
        assertEquals(MessageStatus.FAILED, messages.get(1)?.status)
        assertEquals(MessageStatus.SENDING, messages.get(3)?.status)
        assertEquals(MessageStatus.SUCCESS, messages.get(4)?.status)
    }
    @Test fun regenerateDeletesLastGroupOnlyInThisChatAndResendsItsUserTurn() = runBlocking {
        messages.insertAll(listOf(message(1, Role.USER), message(2, group = "old"), message(3, Role.USER),
            message(4, group = "last"), message(5, group = "last"), message(6, group = "last", contact = 2)))
        var refreshed = false
        assertTrue(ChatMessageActions(db).regenerate(1) { refreshed = true })
        assertTrue(refreshed)
        assertNotNull(messages.get(2))
        assertNull(messages.get(4)); assertNull(messages.get(5))
        assertNotNull(messages.get(6))
        assertEquals(MessageStatus.SENDING, messages.get(3)?.status)
        assertEquals(MessageStatus.SUCCESS, messages.get(1)?.status)
    }
    @Test fun regenerateIsRefusedForNewUserOrPendingReveal() = runBlocking {
        messages.insertAll(listOf(message(1, Role.USER), message(2), message(3, Role.USER)))
        assertFalse(ChatMessageActions(db).regenerate(1))
        messages.delete(3)
        messages.insert(message(4, time = System.currentTimeMillis() + 60_000))
        assertFalse(ChatMessageActions(db).regenerate(1))
        assertNotNull(messages.get(2))
    }
    @Test fun deletingLastUserTurnMakesRegenerationUnavailableWithoutDeletingReply() = runBlocking {
        messages.insertAll(listOf(message(1, Role.USER), message(2, group = "old"), message(3, group = "last")))
        assertFalse(ChatPaging(db).timeline(1).first().regenerationAllowed)
        assertFalse(ChatMessageActions(db).regenerate(1))
        assertNotNull(messages.get(3))
    }
}
