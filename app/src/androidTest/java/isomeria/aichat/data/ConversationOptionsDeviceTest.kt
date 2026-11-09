package isomeria.aichat.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isomeria.aichat.data.backup.AppSettingsSnapshot
import isomeria.aichat.data.backup.BackupManager
import isomeria.aichat.data.backup.BackupSettingsAccess
import isomeria.aichat.data.backup.RestoreMode
import isomeria.aichat.data.crypto.KeystoreSecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.reply.ActiveChatTracker
import isomeria.aichat.data.reply.ReplyAlerts
import isomeria.aichat.data.reply.ReplyNotifications
import isomeria.aichat.data.reply.ReplyQueue
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 真机上的 0.4.2 会话设置：系统解码器读相册图片并压缩成聊天背景（单元测试里解码器不可用）、
 * 带背景的整文件加密导出与恢复、免打扰抑制通知。只用内存数据库；不发真实通知（通知换成记录调用的假实现）。
 * 创建的背景和临时文件在测试结束时删除。
 */
@RunWith(AndroidJUnit4::class)
class ConversationOptionsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private lateinit var chats: ChatRepository
    private val notified = mutableListOf<Long>()
    private val created = mutableListOf<File>()

    private val notifications = object : ReplyNotifications {
        override fun notifyReply(contact: Contact, parts: List<String>, alert: Boolean) { notified += contact.id }
        override fun cancelReply(contactId: Long) = Unit
    }
    private val queue = object : ReplyQueue {
        override fun enqueue(contactId: Long) = Unit
        override fun enqueueDelivery(contactId: Long) = Unit
        override fun cancel(contactId: Long) = Unit
        override suspend fun isActive(contactId: Long) = false
        override fun cancelDelivery(contactId: Long) = Unit
        override suspend fun isDelivering(contactId: Long) = false
    }

    @Before
    fun setUp() {
        require(context.packageName == "isomeria.aichat.debug")
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).build()
        files = FileStorage(context)
        chats = ChatRepository(context, db, db.contactDao(), db.conversationDao(), db.messageDao(), files, queue, notifications)
        runBlocking {
            db.contactDao().insert(Contact(id = 1, name = "测试联系人", avatarColor = 0, providerId = 1, model = "m", createdAt = 1, updatedAt = 1))
            db.conversationDao().upsert(Conversation(contactId = 1, lastMessageTime = 1, lastMessagePreview = "预览"))
        }
    }

    @After
    fun tearDown() {
        runBlocking { db.conversationDao().backgroundPaths().forEach { files.delete(it) } }
        db.close()
        created.forEach { it.delete() }
    }

    private fun picked(width: Int, height: Int): Uri {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        val file = File(context.cacheDir, "background-fixture-${System.nanoTime()}.png").also(created::add)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(file)
    }

    @Test
    fun pickedImageIsCompressedIntoBackgroundDir_andReplacedFileIsDeleted() = runBlocking {
        chats.setBackground(1, picked(3000, 1200))
        val first = File(db.conversationDao().get(1)!!.backgroundPath!!)
        assertEquals(File(context.filesDir, FileStorage.BACKGROUND_DIR).canonicalPath, first.parentFile!!.canonicalPath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(first.path, bounds)
        assertEquals("长边压到 2048", 2048, maxOf(bounds.outWidth, bounds.outHeight))
        assertEquals("image/jpeg", bounds.outMimeType)

        chats.setBackground(1, picked(640, 480))
        val second = File(db.conversationDao().get(1)!!.backgroundPath!!)
        assertFalse(first.exists())
        assertTrue(second.isFile)
        chats.clearBackground(1)
        assertFalse(second.exists())
    }

    @Test
    fun backgroundAndMuteSurviveEncryptedExportAndRestore() = runBlocking {
        chats.setBackground(1, picked(800, 600))
        chats.setMuted(1, true)
        val original = File(db.conversationDao().get(1)!!.backgroundPath!!)
        val bytes = original.readBytes()
        val settings = object : BackupSettingsAccess {
            var value = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true)
            override fun read() = value
            override fun write(snapshot: AppSettingsSnapshot) { value = snapshot }
        }
        val backup = BackupManager(context, db, files, KeystoreSecretCipher(), settings, queue)
        val out = ByteArrayOutputStream()
        backup.export(out, "pw123456".toCharArray(), includeApiKey = false)
        val file = File(context.cacheDir, "background-backup-test.aichat").apply { writeBytes(out.toByteArray()) }.also(created::add)
        try {
            chats.clearBackground(1)
            chats.setMuted(1, false)
            val preview = backup.inspect(file, "pw123456".toCharArray())
            backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
            val restored = db.conversationDao().get(1)!!
            assertTrue(restored.muted)
            assertNotEquals(original.path, restored.backgroundPath)
            assertTrue(bytes.contentEquals(File(restored.backgroundPath!!).readBytes()))
        } finally {
            backup.snapshotFile.delete()
        }
    }

    @Test
    fun mutedChatGetsNoReplyNotification_otherChatsDo() = runBlocking {
        db.contactDao().insert(Contact(id = 2, name = "另一个", avatarColor = 0, providerId = 1, model = "m", createdAt = 2, updatedAt = 2))
        db.conversationDao().upsert(Conversation(contactId = 2))
        val alerts = ReplyAlerts(db.conversationDao(), notifications, ActiveChatTracker())
        chats.setMuted(1, true)
        alerts.afterReply(db.contactDao().get(1)!!, listOf("在吗"))
        alerts.afterReply(db.contactDao().get(2)!!, listOf("在吗"))
        assertEquals(listOf(2L), notified)
    }
}
