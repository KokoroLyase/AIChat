package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Favorite
import isomeria.aichat.data.db.FavoriteTurn
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.StorageOperations
import isomeria.aichat.data.repo.mediaReferencesOf
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 0.9.0 收藏、引用和译文的导出与恢复（备份 v10）：收藏不依赖联系人，音频放在 zip 的 favorites/ 下；
 * 引用和译文跟着「聊天记录」；覆盖恢复只在选了收藏时替换本机的收藏；合并时去重，来源联系人、原消息和引用改成本机的 id；
 * 没写导出内容的旧文件不算有收藏；孤立文件检查和恢复后的清理都把收藏的音频算作在用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackupFavoriteTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var files: FileStorage
    private lateinit var backup: BackupManager
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:") else null
    }
    private val settings = object : BackupSettingsAccess {
        var v = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true)
        override fun read() = v
        override fun write(snapshot: AppSettingsSnapshot) { v = snapshot }
    }

    @Before
    fun setUp() {
        listOf("avatars", "images", "background", "voice", "calls", "favorites", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).build()
        files = FileStorage(context)
        backup = BackupManager(context, db, files, cipher, settings, object : ReplyControl {
            override fun cancel(contactId: Long) = Unit
            override suspend fun isActive(contactId: Long) = false
            override fun cancelDelivery(contactId: Long) = Unit
            override suspend fun isDelivering(contactId: Long) = false
        })
    }

    @After
    fun tearDown() = db.close()

    /** 收藏时复制出来的一份音频（files/favorites/），写库后解除固定 */
    private fun favoriteAudio(extension: String, fill: Byte): String {
        val source = files.newVoiceFile(extension).apply { writeBytes(ByteArray(80) { fill }) }
        files.commitAvatar(source.absolutePath)
        val copy = requireNotNull(files.copyToFavorites(source.absolutePath))
        files.commitAvatar(copy)
        source.delete()
        return copy
    }

    private var questionId = 0L
    private var replyId = 0L
    private var voiceFavorite = 0L
    private var callFavorite = 0L

    private suspend fun seed() {
        db.contactDao().insert(Contact(id = 1, name = "甲", avatarColor = 3, providerId = 1, model = "m", createdAt = 10, updatedAt = 10))
        db.conversationDao().upsert(Conversation(contactId = 1, lastMessageTime = 200, lastMessagePreview = "好呀"))
        questionId = db.messageDao().insert(Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.TEXT, content = "周末去爬山吗", status = MessageStatus.SUCCESS, createdAt = 100))
        replyId = db.messageDao().insert(
            Message(
                contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "好呀", status = MessageStatus.SUCCESS, createdAt = 200,
                quoteId = questionId, quoteRole = Role.ASSISTANT, quoteType = MessageType.TEXT, quoteText = "周末去爬山吗",
                translation = "Sure", translationShown = true,
            ),
        )
        db.favoriteDao().insert(
            Favorite(type = MessageType.TEXT, role = Role.ASSISTANT, contactId = 1, contactCreatedAt = 10, contactName = "甲", contactAvatarColor = 3, content = "周末去爬山吗", messageId = questionId, messageCreatedAt = 100, createdAt = 300),
        )
        voiceFavorite = db.favoriteDao().insert(
            Favorite(type = MessageType.VOICE, role = Role.USER, contactId = 1, contactCreatedAt = 10, contactName = "甲", contactAvatarColor = 3, content = "", audioPath = favoriteAudio("m4a", 1), audioMillis = 1_500, messageId = null, messageCreatedAt = 150, createdAt = 310),
        )
        callFavorite = db.favoriteDao().insert(
            Favorite(type = MessageType.CALL, role = Role.USER, contactId = 1, contactCreatedAt = 10, contactName = "甲", contactAvatarColor = 3, audioMillis = 9_000, messageId = 77, messageCreatedAt = 120, createdAt = 320),
        )
        db.favoriteDao().insertTurns(
            listOf(
                FavoriteTurn(favoriteId = callFavorite, seq = 0, role = Role.USER, text = "在吗", audioPath = favoriteAudio("m4a", 2), audioMillis = 900, createdAt = 1_000),
                FavoriteTurn(favoriteId = callFavorite, seq = 1, role = Role.ASSISTANT, text = "在呢", audioPath = favoriteAudio("mp3", 3), audioMillis = 800, createdAt = 2_000),
                FavoriteTurn(favoriteId = callFavorite, seq = 2, role = Role.ASSISTANT, text = "合成失败的有字没音频", status = CallTurnStatus.TTS_FAILED, createdAt = 3_000),
            ),
        )
    }

    private suspend fun export(modules: Set<BackupModule> = BackupModule.ALL): File {
        val file = File(context.cacheDir, "favorite-backup.zip")
        backup.export(file.outputStream(), null, modules = modules)
        return file
    }

    private fun favoritesDirFiles(): Set<String> =
        File(context.filesDir, FileStorage.FAVORITES_DIR).listFiles().orEmpty().map { it.canonicalPath }.toSet()

    private suspend fun favoriteAudioPaths(): Set<String> = db.favoriteDao().allAudioPaths().map { File(it).canonicalPath }.toSet()

    private suspend fun snapshot() = db.favoriteDao().listAll().map { favorite ->
        listOf(favorite.type, favorite.role, favorite.contactName, favorite.content, favorite.audioMillis, favorite.messageCreatedAt, favorite.createdAt) +
            db.favoriteDao().turns(favorite.id).map { listOf(it.seq, it.role, it.text, it.status, it.audioMillis, it.createdAt) }
    }

    /** 改 zip 里的 data.json 或 manifest.json（只换文字） */
    private fun rewrite(zip: File, name: String, edit: (String) -> String): File {
        val target = File(context.cacheDir, "edited-${System.nanoTime()}.zip")
        ZipFile(zip).use { source ->
            ZipOutputStream(target.outputStream()).use { out ->
                for (entry in source.entries()) {
                    var bytes = source.getInputStream(entry).readBytes()
                    if (entry.name == name) bytes = edit(bytes.toString(Charsets.UTF_8)).toByteArray()
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        return target
    }

    @Test
    fun export_favoritesUnderFavoritesDir_quoteAndTranslationKept() = runBlocking {
        seed()
        val zip = export()
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map(ZipEntry::getName).filter { it.startsWith(BackupFormat.FAVORITES_DIR) }
            assertEquals("语音一份、通话两句各一份", listOf("m4a", "m4a", "mp3"), names.map { it.substringAfterLast('.') }.sorted())
        }
        val preview = backup.inspect(zip)
        assertEquals(10, preview.manifest.formatVersion)
        assertTrue(BackupModule.FAVORITES.name in preview.manifest.modules.orEmpty())
        assertEquals(3, preview.favoriteCount)
        val call = preview.data.favorites.single { it.type == MessageType.CALL }
        assertEquals(listOf("在吗", "在呢", "合成失败的有字没音频"), call.call.map { it.text })
        assertNull(call.call[2].audio)
        assertTrue(call.call.take(2).all { it.audio!!.startsWith(BackupFormat.FAVORITES_DIR) })
        assertEquals(10L, call.contactCreatedAt)
        val reply = preview.data.messages.single { it.id == replyId }
        assertEquals(questionId, reply.quoteId)
        assertEquals(Role.ASSISTANT, reply.quoteRole)
        assertEquals(MessageType.TEXT, reply.quoteType)
        assertEquals("周末去爬山吗", reply.quoteText)
        assertEquals("Sure", reply.translation)
        assertTrue(reply.translationShown)
    }

    @Test
    fun favoritesModule_independentOfContacts() = runBlocking {
        seed()
        // 不选收藏：没有收藏，也不打包收藏的音频
        val chatsOnly = export(setOf(BackupModule.CONTACTS, BackupModule.CHATS))
        ZipFile(chatsOnly).use { z -> assertTrue(z.entries().toList().none { it.name.startsWith(BackupFormat.FAVORITES_DIR) }) }
        assertEquals(0, backup.inspect(chatsOnly).favoriteCount)
        // 只选收藏：不依赖联系人
        val only = export(setOf(BackupModule.FAVORITES))
        val preview = backup.inspect(only)
        assertEquals(setOf(BackupModule.FAVORITES), preview.modules)
        assertEquals(3, preview.favoriteCount)
        assertTrue(preview.data.contacts.isEmpty())
        assertTrue(preview.data.messages.isEmpty())
        assertFalse(BackupModule.FAVORITES.requiresContacts)
    }

    @Test
    fun overwrite_replacesFavorites_audioRestored_oldFilesCleaned() = runBlocking {
        seed()
        val before = snapshot()
        val bytes = db.favoriteDao().allAudioPaths().map { File(it).readBytes().joinToString(",") }.sorted()
        val zip = export()
        // 导出后本机删了一条收藏、又收藏了一条新的
        db.favoriteDao().allTurns().filter { it.favoriteId == callFavorite }.forEach { files.delete(it.audioPath) }
        db.favoriteDao().deleteTurns(callFavorite)
        db.favoriteDao().delete(callFavorite)
        db.favoriteDao().insert(Favorite(type = MessageType.VOICE, role = Role.USER, contactId = 1, contactCreatedAt = 10, contactName = "甲", contactAvatarColor = 3, audioPath = favoriteAudio("m4a", 9), audioMillis = 100, messageCreatedAt = 400, createdAt = 500))

        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)

        assertEquals(before, snapshot())
        assertEquals(bytes, db.favoriteDao().allAudioPaths().map { File(it).readBytes().joinToString(",") }.sorted())
        assertTrue("恢复到 files/favorites", db.favoriteDao().allAudioPaths().all { File(it).parentFile!!.name == FileStorage.FAVORITES_DIR })
        // 本机那条新收藏的音频已经没有记录引用，恢复后清掉；留下的正好是被引用的
        assertEquals(favoriteAudioPaths(), favoritesDirFiles())
        assertEquals(3, favoritesDirFiles().size)
    }

    @Test
    fun overwrite_withoutFavorites_keepsLocalFavoritesAndTheirAudio() = runBlocking {
        seed()
        val zip = export(setOf(BackupModule.CONTACTS, BackupModule.CHATS))
        val before = snapshot()
        val audio = favoriteAudioPaths()
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        assertEquals(before, snapshot())
        // 恢复后的清理把收藏的音频算作在用（0.9.0 前的清单里没有它，会被删掉）
        assertEquals(audio, favoritesDirFiles())
        assertTrue(audio.all { File(it).isFile })
    }

    @Test
    fun overwrite_restoresQuoteAndTranslation_idsKept() = runBlocking {
        seed()
        val zip = export()
        db.messageDao().deleteByContact(1)
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        val reply = db.messageDao().get(replyId)!!
        assertEquals(questionId, reply.quoteId)
        assertEquals("周末去爬山吗", reply.quoteText)
        assertEquals("Sure", reply.translation)
        assertTrue(reply.translationShown)
    }

    @Test
    fun merge_dedupesFavorites_remapsQuoteContactAndMessageIds() = runBlocking {
        seed()
        val zip = export()
        // 本机：同 id 的联系人是另一个人（创建时间不同），备份里的联系人和消息都要换新 id；本机还没有收藏
        db.favoriteDao().deleteAllTurns()
        db.favoriteDao().deleteAll()
        db.messageDao().deleteByContact(1)
        db.contactDao().delete(1)
        db.contactDao().insert(Contact(id = 1, name = "本机的人", avatarColor = 1, providerId = 1, model = "m", createdAt = 99, updatedAt = 99))
        db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "本机的", status = MessageStatus.SUCCESS, createdAt = 10))

        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        val restored = db.contactDao().listAll().single { it.name == "甲" }
        assertNotEquals(1L, restored.id)
        val messages = db.messageDao().listByContact(restored.id)
        val question = messages.single { it.content == "周末去爬山吗" }
        val reply = messages.single { it.content == "好呀" }
        assertEquals("引用指向本机的新 id", question.id, reply.quoteId)
        assertEquals("Sure", reply.translation)
        val favorites = db.favoriteDao().listAll()
        assertEquals(3, favorites.size)
        assertTrue("来源联系人换成本机的新 id", favorites.all { it.contactId == restored.id && it.contactCreatedAt == 10L })
        assertEquals("原消息换成本机的新 id", question.id, favorites.single { it.type == MessageType.TEXT }.messageId)
        assertEquals("原消息不在这次恢复里时保留备份里的 id", 77L, favorites.single { it.type == MessageType.CALL }.messageId)
        assertEquals(3, db.favoriteDao().turns(favorites.single { it.type == MessageType.CALL }.id).size)

        // 同一个文件再合并一次：收藏不重复
        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        assertEquals(3, db.favoriteDao().listAll().size)
        assertEquals(3, db.favoriteDao().allTurns().size)
        assertEquals(favoriteAudioPaths(), favoritesDirFiles())
    }

    @Test
    fun merge_quotedMessageMissing_quoteCleared_snapshotKept() = runBlocking {
        seed()
        // 被引用的那条在导出前删掉了：备份里的引用对不上，只留快照
        db.messageDao().delete(questionId)
        val zip = export()
        val reply = backup.inspect(zip).data.messages.single { it.id == replyId }
        assertNull(reply.quoteId)
        assertEquals("周末去爬山吗", reply.quoteText)
        db.messageDao().deleteByContact(1)
        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        val restored = db.messageDao().listByContact(1).single { it.content == "好呀" }
        assertNull(restored.quoteId)
        assertEquals("周末去爬山吗", restored.quoteText)
        assertEquals(Role.ASSISTANT, restored.quoteRole)
    }

    @Test
    fun legacyFileWithoutModules_doesNotTouchLocalFavorites() = runBlocking {
        seed()
        val zip = export()
        // 改成 0.7.1 的 manifest（没写导出了哪些内容）：备份里就算有收藏也不算，覆盖恢复不清掉本机的
        val legacy = rewrite(zip, BackupFormat.MANIFEST) { text ->
            text.replace(Regex("\"formatVersion\":10"), "\"formatVersion\":7").replace(Regex(",\"modules\":\\[[^\\]]*]"), "")
        }
        val preview = backup.inspect(legacy)
        assertEquals(BackupModule.LEGACY, preview.modules)
        db.favoriteDao().deleteTurns(callFavorite)
        db.favoriteDao().delete(callFavorite)
        val before = snapshot()
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertEquals(before, snapshot())
    }

    @Test
    fun sanitize_dropsImageFavorites_badAudioNames_foreignQuotes() = runBlocking {
        seed()
        val zip = export()
        val edited = rewrite(zip, BackupFormat.DATA) { text ->
            text
                // 语音收藏的音频名改成不合规的、放到别的目录下的
                .replaceFirst(Regex("\"audio\":\"favorites/[^\"]+\\.mp3\""), "\"audio\":\"favorites/../evil.mp3\"")
                // 引用指向不存在的消息
                .replace("\"quoteId\":$questionId", "\"quoteId\":12345")
                // 文字收藏改成图片类型
                .replaceFirst("\"type\":\"TEXT\",\"role\":\"ASSISTANT\",\"contactId\":1", "\"type\":\"IMAGE\",\"role\":\"ASSISTANT\",\"contactId\":1")
        }
        val preview = backup.inspect(edited)
        assertEquals("图片不能收藏", 2, preview.favoriteCount)
        assertTrue(preview.data.favorites.flatMap { it.call }.none { it.audio?.contains("..") == true })
        assertNull(preview.data.messages.single { it.id == replyId }.quoteId)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        val call = db.favoriteDao().listAll().single { it.type == MessageType.CALL }
        val turns = db.favoriteDao().turns(call.id)
        assertEquals("文字都还在", listOf("在吗", "在呢", "合成失败的有字没音频"), turns.map { it.text })
        assertEquals("不合规的音频只留文字", 1, turns.count { it.audioPath != null })
        assertTrue(turns.filter { it.audioPath == null }.all { it.audioMillis == 0L })
    }

    @Test
    fun orphanCheck_coversFavoritesDir_referencedAudioKept() = runBlocking {
        seed()
        val stray = File(context.filesDir, "${FileStorage.FAVORITES_DIR}/stray.m4a").apply { writeBytes(ByteArray(10)) }
        val orphans = files.unreferenced(mediaReferencesOf(db, null)).map { it.canonicalPath }
        assertEquals("只有没有记录引用的那个", listOf(stray.canonicalPath), orphans)
        // 复制中（还没写库）的文件固定着，不算孤立
        val pending = favoriteAudio("m4a", 5)
        db.favoriteDao().insert(Favorite(type = MessageType.VOICE, role = Role.USER, contactId = 1, contactCreatedAt = 10, contactName = "甲", contactAvatarColor = 3, audioPath = pending, audioMillis = 1, messageCreatedAt = 1, createdAt = 1))
        val copying = requireNotNull(files.copyToFavorites(pending))
        assertTrue(File(copying).canonicalPath in StorageOperations.pinnedPaths())
        assertFalse(files.unreferenced(mediaReferencesOf(db, null)).any { it.canonicalPath == File(copying).canonicalPath })
        files.delete(copying)
        assertNull("原文件不在时不复制", files.copyToFavorites(File(context.filesDir, "voice/none.m4a").absolutePath))
    }
}
