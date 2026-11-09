package isomeria.aichat.data.repo

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.Clock
import isomeria.aichat.core.GlobalMemoryEntry
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.GlobalMemoryCategory
import isomeria.aichat.data.db.GlobalMemoryItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 全局记忆的增删改、排序、级联删除，以及每个联系人请求里带什么。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class GlobalMemoryRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var repo: GlobalMemoryRepository
    private var prefs = ReplyPreferences(minCount = 2)
    private var now = 1_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = GlobalMemoryRepository(db, { prefs }, Clock { now++ })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun names() = repo.snapshot().let { tree ->
        tree.categories.map { c -> c.name to tree.topicsOf(c.id).map { t -> t.name to tree.itemsOf(t.id).map { it.content } } }
    }

    @Test
    fun addKeepsInsertionOrder_trimsAndLimitsNames() = runBlocking {
        val a = repo.addCategory("  个人信息  ")
        val b = repo.addCategory("工".repeat(30))
        val t = repo.addTopic(a, "基本情况")
        repo.addItem(t, "  住在杭州 ")
        repo.addItem(t, "长".repeat(GlobalMemoryItem.CONTENT_MAX_LENGTH + 5))
        val tree = repo.snapshot()
        assertEquals(listOf("个人信息", "工".repeat(GlobalMemoryCategory.NAME_MAX_LENGTH)), tree.categories.map { it.name })
        assertEquals(listOf(a, b), tree.categories.map { it.id })
        assertEquals("住在杭州", tree.itemsOf(t)[0].content)
        assertEquals(GlobalMemoryItem.CONTENT_MAX_LENGTH, tree.itemsOf(t)[1].content.length)
    }

    @Test
    fun moveUpAndDown_atEachLevel_andEdgesDoNothing() = runBlocking {
        val a = repo.addCategory("甲")
        val b = repo.addCategory("乙")
        val c = repo.addCategory("丙")
        repo.moveCategory(c, -1)
        assertEquals(listOf("甲", "丙", "乙"), names().map { it.first })
        repo.moveCategory(a, -1) // 已在最前
        repo.moveCategory(b, 1) // 已在最后
        assertEquals(listOf("甲", "丙", "乙"), names().map { it.first })

        val t1 = repo.addTopic(a, "一")
        val t2 = repo.addTopic(a, "二")
        repo.addTopic(b, "别的类别")
        repo.moveTopic(t2, -1)
        assertEquals(listOf("二", "一"), repo.snapshot().topicsOf(a).map { it.name })

        val i1 = repo.addItem(t1, "x")
        repo.addItem(t1, "y")
        val i3 = repo.addItem(t1, "z")
        repo.moveItem(i3, -2)
        repo.moveItem(i1, 1)
        assertEquals(listOf("z", "y", "x"), repo.snapshot().itemsOf(t1).map { it.content })
        assertEquals(listOf(0, 1, 2), repo.snapshot().itemsOf(t1).map { it.sortOrder })
        assertTrue(t2 > 0)
    }

    @Test
    fun deleteCategory_cascadesToTopicsAndItems_otherCategoriesKept() = runBlocking {
        val a = repo.addCategory("甲")
        val b = repo.addCategory("乙")
        val ta = repo.addTopic(a, "主题")
        val tb = repo.addTopic(b, "主题")
        repo.addItem(ta, "删掉的")
        repo.addItem(tb, "留下的")
        repo.deleteCategory(a)
        val tree = repo.snapshot()
        assertEquals(listOf(b), tree.categories.map { it.id })
        assertEquals(listOf(tb), tree.topics.map { it.id })
        assertEquals(listOf("留下的"), tree.items.map { it.content })
        repo.deleteTopic(tb)
        assertTrue(repo.snapshot().items.isEmpty())
    }

    @Test
    fun entries_onlyEnabledNonBlank_inCategoryTopicItemOrder() = runBlocking {
        val work = repo.addCategory("工作")
        val me = repo.addCategory("个人")
        repo.moveCategory(me, -1)
        val job = repo.addTopic(work, "职业")
        val food = repo.addTopic(me, "饮食")
        val home = repo.addTopic(me, "住处")
        repo.addItem(job, "后端开发")
        repo.addItem(home, "杭州")
        val off = repo.addItem(food, "关掉的")
        repo.addItem(food, "不吃香菜")
        repo.setItemEnabled(off, false)
        assertEquals(
            listOf(
                GlobalMemoryEntry("个人", "饮食", "不吃香菜"),
                GlobalMemoryEntry("个人", "住处", "杭州"),
                GlobalMemoryEntry("工作", "职业", "后端开发"),
            ),
            repo.snapshot().entries(),
        )
        // 编辑内容和开关
        repo.updateItem(off, "又打开了", true)
        assertEquals("又打开了", repo.snapshot().entries()[0].content)
        assertEquals(repo.snapshot(), repo.tree.first())
    }

    @Test
    fun forContact_followsTheTwoSwitches() = runBlocking {
        repo.addItem(repo.addTopic(repo.addCategory("个人"), "住处"), "杭州")
        fun contact(global: Boolean, reply: Boolean) = Contact(
            name = "a", avatarColor = 0, providerId = 1, model = "m", useGlobalMemory = global, useReplyPreferences = reply, createdAt = 0, updatedAt = 0,
        )
        repo.forContact(contact(true, true)).let {
            assertEquals(listOf(GlobalMemoryEntry("个人", "住处", "杭州")), it.globalMemory)
            assertEquals(prefs, it.replyStyle)
        }
        repo.forContact(contact(false, true)).let { assertTrue(it.globalMemory.isEmpty()); assertEquals(prefs, it.replyStyle) }
        repo.forContact(contact(true, false)).let { assertEquals(1, it.globalMemory.size); assertNull(it.replyStyle) }
        repo.forContact(contact(false, false)).let { assertTrue(it.globalMemory.isEmpty()); assertNull(it.replyStyle) }
    }

    @Test
    fun applyToAllContacts_setsBothSwitchesIndependently() = runBlocking {
        db.providerDao().insert(isomeria.aichat.data.db.ProviderConfig(id = 1, kind = isomeria.aichat.data.db.ProviderKind.LLM, provider = "openai", baseUrl = "https://x", defaultModel = ""))
        repeat(3) { db.contactDao().insert(Contact(name = "c$it", avatarColor = 0, providerId = 1, model = "m", createdAt = 0, updatedAt = 0)) }
        repo.setUseGlobalMemoryForAll(false)
        assertTrue(db.contactDao().listAll().all { !it.useGlobalMemory && it.useReplyPreferences })
        repo.setUseReplyPreferencesForAll(false)
        repo.setUseGlobalMemoryForAll(true)
        assertTrue(db.contactDao().listAll().all { it.useGlobalMemory && !it.useReplyPreferences })
    }
}
