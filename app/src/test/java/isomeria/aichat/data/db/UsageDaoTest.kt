package isomeria.aichat.data.db

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 用量累加、删除联系人时合并到「已删除的联系人」。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class UsageDaoTest {
    private lateinit var db: AppDatabase
    private val usage get() = db.usageDao()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun add_accumulatesPerDayContactAndPurpose() = runBlocking {
        usage.add(100, 1, UsagePurpose.CHAT, 10, 1)
        usage.add(100, 1, UsagePurpose.CHAT, 20, 2)
        usage.add(100, 1, UsagePurpose.MEMORY, 5, 1)
        usage.add(101, 1, UsagePurpose.CHAT, 7, 7)
        assertEquals(
            listOf(
                UsageDaily(100, 1, UsagePurpose.CHAT, 30, 3, 2),
                UsageDaily(100, 1, UsagePurpose.MEMORY, 5, 1, 1),
                UsageDaily(101, 1, UsagePurpose.CHAT, 7, 7, 1),
            ),
            usage.listAll(),
        )
    }

    @Test
    fun mergeIntoDeleted_keepsTotals_acrossSeveralDeletedContacts() = runBlocking {
        usage.add(100, 1, UsagePurpose.CHAT, 10, 1)
        usage.add(100, 2, UsagePurpose.CHAT, 20, 2)
        usage.add(101, 2, UsagePurpose.MEMORY, 5, 1)
        usage.add(100, 3, UsagePurpose.CHAT, 40, 4)

        for (id in listOf(1L, 2L)) {
            usage.mergeIntoDeleted(id)
            usage.deleteByContact(id)
        }

        assertEquals(
            listOf(
                UsageDaily(100, UsageDaily.DELETED_CONTACT, UsagePurpose.CHAT, 30, 3, 2),
                UsageDaily(100, 3, UsagePurpose.CHAT, 40, 4, 1),
                UsageDaily(101, UsageDaily.DELETED_CONTACT, UsagePurpose.MEMORY, 5, 1, 1),
            ),
            usage.listAll(),
        )
    }
}
