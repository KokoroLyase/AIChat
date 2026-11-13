package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallEndReason
import isomeria.aichat.data.db.CallTurn
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 0.8.0 通话的导出与恢复：通话消息带着每一条话导出（备份 v9），音频放在 zip 的 calls/ 下、保留扩展名，
 * 跟着「聊天记录」；覆盖恢复时消息 id 不变，合并恢复时每一条话跟着换了 id 的通话消息；还在进行的通话按导出时的样子写成结束。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackupCallTest {
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
        listOf("avatars", "images", "background", "voice", "calls", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
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

    private fun audio(extension: String, fill: Byte): File = files.newCallFile(extension).apply {
        writeBytes(ByteArray(120) { fill })
        files.commitAvatar(absolutePath)
    }

    private var callId = 0L

    private suspend fun seed() {
        db.contactDao().insert(Contact(id = 1, name = "甲", avatarColor = 1, providerId = 1, model = "m", createdAt = 10, updatedAt = 10))
        db.conversationDao().upsert(Conversation(contactId = 1, lastMessageTime = 100, lastMessagePreview = "[通话] 00:09"))
        db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "先发条文字", status = MessageStatus.SUCCESS, createdAt = 50))
        callId = db.messageDao().insert(
            Message(contactId = 1, role = Role.USER, type = MessageType.CALL, status = MessageStatus.SUCCESS, voiceMillis = 9_000, errorCode = CallEndReason.HANG_UP.code, createdAt = 100),
        )
        db.callDao().insertAll(
            listOf(
                CallTurn(messageId = callId, seq = 0, role = Role.USER, text = "在吗", audioPath = audio("m4a", 1).absolutePath, audioMillis = 1_000, createdAt = 1_100),
                CallTurn(messageId = callId, seq = 1, role = Role.USER, text = "", audioPath = audio("m4a", 2).absolutePath, audioMillis = 700, status = CallTurnStatus.ASR_FAILED, createdAt = 2_500),
                CallTurn(messageId = callId, seq = 2, role = Role.ASSISTANT, text = "在呢", audioPath = audio("mp3", 3).absolutePath, audioMillis = 900, createdAt = 4_000),
                CallTurn(messageId = callId, seq = 3, role = Role.ASSISTANT, text = "没放出来的句子不存，合成失败的有字没音频", status = CallTurnStatus.TTS_FAILED, createdAt = 5_000),
            ),
        )
    }

    private suspend fun export(modules: Set<BackupModule> = BackupModule.ALL): File {
        val file = File(context.cacheDir, "call-backup.zip")
        backup.export(file.outputStream(), null, modules = modules)
        return file
    }

    private fun audioBytes(turns: List<CallTurn>) = turns.map { turn -> turn.audioPath?.let { File(it).readBytes().toList() } }

    @Test
    fun export_putsCallAudioUnderCallsDir_withTurns() = runBlocking {
        seed()
        val zip = export()
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map(ZipEntry::getName).filter { it.startsWith(BackupFormat.CALLS_DIR) }
            assertEquals(listOf("m4a", "m4a", "mp3"), names.map { it.substringAfterLast('.') }.sorted())
        }
        val preview = backup.inspect(zip)
        assertEquals(9, preview.manifest.formatVersion)
        assertEquals(1, preview.callCount)
        val call = preview.data.messages.single { it.type == MessageType.CALL }
        assertEquals(9_000L, call.voiceMillis)
        assertEquals(listOf("在吗", "", "在呢", "没放出来的句子不存，合成失败的有字没音频"), call.call.map { it.text })
        assertEquals(listOf(CallTurnStatus.OK, CallTurnStatus.ASR_FAILED, CallTurnStatus.OK, CallTurnStatus.TTS_FAILED), call.call.map { it.status })
        assertNull(call.call[3].audio)
        assertTrue(preview.data.messages.filter { it.type != MessageType.CALL }.all { it.call.isEmpty() })
    }

    @Test
    fun withoutChats_noCallsAndNoCallAudio() = runBlocking {
        seed()
        val zip = export(setOf(BackupModule.CONTACTS))
        ZipFile(zip).use { z -> assertTrue(z.entries().toList().none { it.name.startsWith(BackupFormat.CALLS_DIR) }) }
        assertEquals(0, backup.inspect(zip).callCount)
    }

    @Test
    fun overwrite_restoresCallTurnsAndAudio() = runBlocking {
        seed()
        val before = db.callDao().turns(callId)
        val bytes = audioBytes(before)
        val zip = export()
        // 导出后本机删掉了这次通话
        db.messageDao().deleteByContact(1)
        db.callDao().deleteOrphans()

        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)

        val call = db.messageDao().listByContact(1).single { it.type == MessageType.CALL }
        assertEquals(9_000L, call.voiceMillis)
        val turns = db.callDao().turns(call.id)
        assertEquals(before.map { listOf(it.seq, it.role, it.text, it.status, it.audioMillis, it.createdAt) }, turns.map { listOf(it.seq, it.role, it.text, it.status, it.audioMillis, it.createdAt) })
        assertEquals(bytes, audioBytes(turns))
        assertTrue("恢复到 files/calls", turns.mapNotNull { it.audioPath }.all { File(it).parentFile!!.name == FileStorage.CALLS_DIR })
        assertEquals(listOf("m4a", "m4a", "mp3"), turns.mapNotNull { it.audioPath }.map { File(it).extension })
        // 只有被引用的通话音频留下（导出前的旧文件已经没有记录引用，恢复后清掉）
        assertEquals(turns.mapNotNull { it.audioPath }.map { File(it).canonicalPath }.toSet(), File(context.filesDir, FileStorage.CALLS_DIR).listFiles()!!.map { it.canonicalPath }.toSet())
    }

    @Test
    fun merge_turnsFollowNewMessageId() = runBlocking {
        seed()
        val zip = export()
        // 本机：同 id 的联系人是另一个人（创建时间不同），备份里的联系人和消息都要换新 id
        db.messageDao().deleteByContact(1)
        db.callDao().deleteOrphans()
        db.contactDao().delete(1)
        db.contactDao().insert(Contact(id = 1, name = "本机的人", avatarColor = 1, providerId = 1, model = "m", createdAt = 99, updatedAt = 99))
        val local = db.messageDao().insert(Message(contactId = 1, role = Role.USER, type = MessageType.TEXT, content = "本机的", status = MessageStatus.SUCCESS, createdAt = 10))

        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        val restored = db.contactDao().listAll().single { it.name == "甲" }
        val call = db.messageDao().listByContact(restored.id).single { it.type == MessageType.CALL }
        assertFalse(call.id == callId && restored.id == 1L)
        assertEquals(listOf("在吗", "", "在呢", "没放出来的句子不存，合成失败的有字没音频"), db.callDao().turns(call.id).map { it.text })
        assertEquals(listOf(local), db.messageDao().listByContact(1).map { it.id })
        assertEquals(4, db.callDao().listAll().size)
    }

    @Test
    fun ongoingCall_exportedAsFinishedUpToLastTurn() = runBlocking {
        seed()
        db.callDao().finish(callId, 0, 0)
        assertTrue(db.messageDao().get(callId)!!.callOngoing)
        val call = backup.inspect(export()).data.messages.single { it.type == MessageType.CALL }
        // 最后一条话结束在 5_000（合成失败的那句没有音频），从 100 开始
        assertEquals(4_900L, call.voiceMillis)
        assertEquals(CallEndReason.INTERRUPTED.code, call.errorCode)
    }

    @Test
    fun restore_hangsUpACallFirst() = runBlocking {
        seed()
        val zip = export()
        val hungUp = mutableListOf<Long?>()
        val withCalls = BackupManager(context, db, files, cipher, settings, object : ReplyControl {
            override fun cancel(contactId: Long) = Unit
            override suspend fun isActive(contactId: Long) = false
            override fun cancelDelivery(contactId: Long) = Unit
            override suspend fun isDelivering(contactId: Long) = false
        }, calls = object : isomeria.aichat.data.call.CallControl {
            override fun inCall(contactId: Long) = false
            override suspend fun hangUpFor(contactId: Long?) { hungUp += contactId }
        })
        withCalls.restore(withCalls.inspect(zip), RestoreMode.MERGE, null)
        assertEquals(listOf<Long?>(null), hungUp)
    }

    @Test
    fun missingAudioInZip_keepsTextOnly_badNamesDropped() = runBlocking {
        seed()
        val zip = export()
        // 去掉 zip 里的一个 m4a，再把 mp3 那条话的音频名改成不合规的
        val trimmed = File(context.cacheDir, "trimmed.zip")
        ZipFile(zip).use { source ->
            ZipOutputStream(trimmed.outputStream()).use { out ->
                val firstAudio = source.entries().toList().map(ZipEntry::getName).filter { it.startsWith(BackupFormat.CALLS_DIR) && it.endsWith(".m4a") }.sorted().first()
                for (entry in source.entries()) {
                    if (entry.name == firstAudio) continue
                    var bytes = source.getInputStream(entry).readBytes()
                    if (entry.name == BackupFormat.DATA) {
                        bytes = bytes.toString(Charsets.UTF_8).replaceFirst(Regex("\"audio\":\"calls/[^\"]+\\.mp3\""), "\"audio\":\"calls/../evil.mp3\"").toByteArray()
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        val preview = backup.inspect(trimmed)
        assertTrue("不合规的名字去掉", preview.data.messages.flatMap { it.call }.none { it.audio?.contains("..") == true })
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        val turns = db.callDao().turns(db.messageDao().listByContact(1).single { it.type == MessageType.CALL }.id)
        assertEquals(4, turns.size)
        assertEquals("文字都还在", listOf("在吗", "", "在呢", "没放出来的句子不存，合成失败的有字没音频"), turns.map { it.text })
        assertEquals("缺了音频的两条只留文字", 1, turns.count { it.audioPath != null })
        assertTrue(turns.filter { it.audioPath == null }.all { it.audioMillis == 0L })
    }

    @Test
    fun callWithoutDuration_isRestoredAsFinished() = runBlocking {
        seed()
        val zip = export()
        // 改出来的文件（或别的工具写的）里通话没有时长：恢复后不能是“通话中”（删不掉、点开又去打电话）
        val edited = File(context.cacheDir, "no-duration.zip")
        ZipFile(zip).use { source ->
            ZipOutputStream(edited.outputStream()).use { out ->
                for (entry in source.entries()) {
                    var bytes = source.getInputStream(entry).readBytes()
                    if (entry.name == BackupFormat.DATA) {
                        bytes = bytes.toString(Charsets.UTF_8).replace("\"voiceMillis\":9000", "\"voiceMillis\":0").toByteArray()
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
        val preview = backup.inspect(edited)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        val call = db.messageDao().listByContact(1).single { it.type == MessageType.CALL }
        assertTrue(call.voiceMillis >= 1)
        assertTrue(!call.callOngoing)
    }
}
