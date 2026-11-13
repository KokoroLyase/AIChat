package isomeria.aichat.data.repo

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.reply.ReplyNotifications
import isomeria.aichat.data.reply.ReplyQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 0.9.0 收藏在数据层：每条单独一份快照，语音和通话的音频复制到 files/favorites；
 * 图片、等待回复中、正在进行的通话不收藏，同一条不重复收藏；删除原消息、清空和删除聊天、删除联系人都不影响收藏；
 * 删除收藏只删它自己的文件；来源联系人按 id 和创建时间一起认；孤立文件检查认得收藏的音频。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FavoriteDataTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var filesDir: File
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private lateinit var chats: ChatRepository
    private lateinit var contacts: ContactRepository
    private lateinit var favorites: FavoriteRepository

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        val cacheDir = temporary.newFolder("cache")
        val app = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(app) {
            override fun getFilesDir() = this@FavoriteDataTest.filesDir
            override fun getCacheDir() = cacheDir
        }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).allowMainThreadQueries().build()
        files = FileStorage(context)
        chats = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), files, FakeQueue(), FakeNotifications(), MessagePreviewer.PLAIN)
        contacts = ContactRepository(db, db.contactDao(), db.conversationDao(), db.messageDao(), db.memoryDao(), db.usageDao(), files, { it }, FakeQueue())
        favorites = FavoriteRepository(context, db, files)
        runBlocking {
            db.contactDao().insert(Contact(id = 1, name = "小林", avatarColor = 7, providerId = 1, model = "m", createdAt = 10, updatedAt = 10))
            db.conversationDao().upsert(Conversation(contactId = 1))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun media(kind: MediaKind, extension: String, fill: Byte): File = files.newMediaFile(kind, extension).apply { writeBytes(ByteArray(40) { fill }) }

    private class Seed(val text: Long, val voice: Long, val call: Long, val image: Long, val sending: Long, val ongoing: Long, val voiceFile: File, val clips: List<File>)

    private suspend fun seed(): Seed {
        val voiceFile = media(MediaKind.VOICE, "m4a", 1)
        val clips = listOf(media(MediaKind.CALL, "m4a", 2), media(MediaKind.CALL, "mp3", 3))
        val dao = db.messageDao()
        val text = dao.insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "周末去爬山吗", status = MessageStatus.SUCCESS, createdAt = 100))
        val voice = dao.insert(Message(contactId = 1, role = Role.USER, type = MessageType.VOICE, content = "好呀", voicePath = voiceFile.absolutePath, voiceMillis = 1_500, status = MessageStatus.SUCCESS, createdAt = 200))
        val call = dao.insert(Message(contactId = 1, role = Role.USER, type = MessageType.CALL, voiceMillis = 9_000, status = MessageStatus.SUCCESS, createdAt = 300))
        db.callDao().insertAll(
            listOf(
                CallTurn(messageId = call, seq = 0, role = Role.USER, text = "在吗", audioPath = clips[0].absolutePath, audioMillis = 900, createdAt = 1_000),
                CallTurn(messageId = call, seq = 1, role = Role.USER, text = "", status = CallTurnStatus.ASR_FAILED, createdAt = 2_000),
                CallTurn(messageId = call, seq = 2, role = Role.ASSISTANT, text = "在呢", audioPath = clips[1].absolutePath, audioMillis = 800, createdAt = 3_000),
            ),
        )
        val image = dao.insert(Message(contactId = 1, role = Role.USER, type = MessageType.IMAGE, imagePath = media(MediaKind.IMAGE, "jpg", 4).absolutePath, status = MessageStatus.SUCCESS, createdAt = 400))
        val sending = dao.insert(Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "等待回复中", status = MessageStatus.SENDING, createdAt = 500))
        val ongoing = dao.insert(Message(contactId = 1, role = Role.USER, type = MessageType.CALL, voiceMillis = 0, status = MessageStatus.SUCCESS, createdAt = 600))
        return Seed(text, voice, call, image, sending, ongoing, voiceFile, clips)
    }

    private fun favoritesDir() = File(filesDir, FileStorage.FAVORITES_DIR)

    @Test
    fun add_snapshotsTextVoiceCall_copiesAudio_skipsImageWaitingOngoing() = runBlocking {
        val seed = seed()
        val result = favorites.add(listOf(seed.ongoing, seed.sending, seed.image, seed.call, seed.voice, seed.text), now = 10_000)
        assertEquals(FavoriteResult(3, imagesSkipped = true), result)
        val list = db.favoriteDao().listAll()
        // 按原消息时间先后收藏，列表按收藏时间倒序：最早的消息在最后
        assertEquals(listOf(MessageType.CALL, MessageType.VOICE, MessageType.TEXT), list.map { it.type })
        assertTrue(list.all { it.contactId == 1L && it.contactCreatedAt == 10L && it.contactName == "小林" && it.contactAvatarColor == 7 })
        val text = list.single { it.type == MessageType.TEXT }
        assertEquals("周末去爬山吗", text.content)
        assertEquals(Role.ASSISTANT, text.role)
        assertEquals(seed.text, text.messageId)
        assertEquals(100L, text.messageCreatedAt)
        val voice = list.single { it.type == MessageType.VOICE }
        assertEquals("好呀", voice.content)
        assertEquals(1_500L, voice.audioMillis)
        assertNotEquals("复制了一份，不和原消息共用", seed.voiceFile.canonicalPath, File(voice.audioPath!!).canonicalPath)
        assertEquals(favoritesDir().canonicalPath, File(voice.audioPath!!).parentFile!!.canonicalPath)
        assertArrayEquals(seed.voiceFile.readBytes(), File(voice.audioPath!!).readBytes())
        val call = list.single { it.type == MessageType.CALL }
        assertEquals(9_000L, call.audioMillis)
        assertEquals("", call.content)
        val turns = db.favoriteDao().turns(call.id)
        assertEquals(listOf("在吗", "", "在呢"), turns.map { it.text })
        assertEquals(listOf(CallTurnStatus.OK, CallTurnStatus.ASR_FAILED, CallTurnStatus.OK), turns.map { it.status })
        assertEquals(listOf(900L, 0L, 800L), turns.map { it.audioMillis })
        assertArrayEquals(seed.clips[1].readBytes(), File(turns[2].audioPath!!).readBytes())
        assertEquals("音频都在 files/favorites，固定已经解除", 3, favoritesDir().listFiles()!!.size)
        assertTrue(StorageOperations.pinnedPaths().none { it.startsWith(favoritesDir().canonicalPath) })
    }

    @Test
    fun add_sameMessageTwice_notDuplicated_imagesOnly_reportsSkipped() = runBlocking {
        val seed = seed()
        favorites.add(listOf(seed.text))
        assertEquals(FavoriteResult(0, imagesSkipped = false), favorites.add(listOf(seed.text)))
        assertEquals(FavoriteResult(0, imagesSkipped = true), favorites.add(listOf(seed.image)))
        assertEquals(1, db.favoriteDao().listAll().size)
    }

    @Test
    fun voiceFileMissing_keepsTranscriptOnly() = runBlocking {
        val seed = seed()
        seed.voiceFile.delete()
        favorites.add(listOf(seed.voice))
        val voice = db.favoriteDao().listAll().single()
        assertNull(voice.audioPath)
        assertEquals(0L, voice.audioMillis)
        assertEquals("好呀", voice.content)
    }

    @Test
    fun favoritesSurviveDeletingMessagesChatAndContact() = runBlocking {
        val seed = seed()
        favorites.add(listOf(seed.text, seed.voice, seed.call))
        val before = db.favoriteDao().listAll()
        val audio = db.favoriteDao().allAudioPaths()
        chats.deleteMessages(listOf(seed.text, seed.voice, seed.call))
        assertFalse("原消息的文件跟着原消息删了", seed.voiceFile.exists())
        chats.clearHistory(1)
        chats.deleteConversation(1)
        contacts.delete(1)
        assertEquals(before, db.favoriteDao().listAll())
        assertEquals(3, db.favoriteDao().allTurns().size)
        assertTrue("收藏的音频都还在", audio.all { File(it).isFile })
        // 联系人删了：来源对不上，用存下的名字
        val entries = favorites.observeEntries().first()
        assertTrue(entries.all { it.source == null && it.favorite.contactName == "小林" })
        // 孤立文件检查不把收藏的音频当成没人用的
        assertTrue(files.unreferenced(mediaReferencesOf(db, null)).none { it.parentFile!!.name == FileStorage.FAVORITES_DIR })
    }

    @Test
    fun source_matchesOnlySameContact_idReusedByAnotherIsNotTheSource() = runBlocking {
        val seed = seed()
        favorites.add(listOf(seed.text))
        assertEquals("小林", favorites.observeEntries().first().single().source?.name)
        db.contactDao().update(db.contactDao().get(1)!!.copy(name = "小林改名了"))
        assertEquals("改名跟着变", "小林改名了", favorites.observeEntries().first().single().source?.name)
        // 同一个 id 换成了另一个人（例如覆盖恢复了别的备份）：不是来源
        db.contactDao().update(db.contactDao().get(1)!!.copy(createdAt = 99))
        assertNull(favorites.observeEntries().first().single().source)
    }

    @Test
    fun delete_removesFavoriteTurnsAndOwnFilesOnly() = runBlocking {
        val seed = seed()
        favorites.add(listOf(seed.voice, seed.call))
        val call = db.favoriteDao().listAll().single { it.type == MessageType.CALL }
        val callAudio = db.favoriteDao().audioPathsOf(call.id)
        assertEquals(2, callAudio.size)
        favorites.delete(call.id)
        assertEquals(listOf(MessageType.VOICE), db.favoriteDao().listAll().map { it.type })
        assertTrue(db.favoriteDao().allTurns().isEmpty())
        assertTrue(callAudio.none { File(it).exists() })
        assertTrue("原消息的音频不受影响", seed.clips.all { it.exists() } && seed.voiceFile.exists())
        assertEquals(1, favoritesDir().listFiles()!!.size)
        assertTrue(files.unreferenced(mediaReferencesOf(db, null)).isEmpty())
    }

    @Test
    fun copyText_sameRulesAsChat() = runBlocking {
        val seed = seed()
        favorites.add(listOf(seed.text, seed.voice, seed.call))
        val entries = favorites.observeEntries().first()
        assertEquals("周末去爬山吗", favorites.copyText(entries.single { it.favorite.type == MessageType.TEXT }))
        assertEquals("好呀", favorites.copyText(entries.single { it.favorite.type == MessageType.VOICE }))
        val call = favorites.copyText(entries.single { it.favorite.type == MessageType.CALL }).lines()
        assertTrue(call[0], call[0].startsWith("语音通话 ") && call[0].endsWith(" · 00:09"))
        assertEquals(listOf("我：在吗", "我：（没有识别出文字）", "小林：在呢"), call.drop(1))
    }

    private class FakeNotifications : ReplyNotifications {
        override fun notifyReply(contact: Contact, parts: List<String>, alert: Boolean) = Unit
        override fun cancelReply(contactId: Long) = Unit
    }

    private class FakeQueue : ReplyQueue {
        override fun enqueue(contactId: Long) = Unit
        override fun enqueueDelivery(contactId: Long) = Unit
        override fun cancel(contactId: Long) = Unit
        override suspend fun isActive(contactId: Long) = false
        override fun cancelDelivery(contactId: Long) = Unit
        override suspend fun isDelivering(contactId: Long) = false
    }
}
