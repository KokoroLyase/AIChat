package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.CallEndReason
import isomeria.aichat.data.db.CallTurnStatus
import isomeria.aichat.data.db.Favorite
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProactiveFrequency
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ReplyType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 0.8.0 导出的真实文件（`src/test/resources/backup080`，2026-11-13 在 v0.8.0 的临时工作树里用当时的导出代码和虚构数据生成，
 * 服务商用 App 自己的代码建）：明文 zip 和整文件加密 .aichat（带 Key，密码 pw123456）都能在 0.9.0 恢复。
 * 备份内层是 v9，有通话（每一句和录音），没有收藏、引用和译文；文件的模块里没有“收藏”，恢复时本机的收藏不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class Backup080FixtureTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var db: AppDatabase
    private lateinit var backup: BackupManager
    private val cipher = object : SecretCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(encoded: String) = if (encoded.startsWith("enc:")) encoded.removePrefix("enc:") else null
    }
    private val settings = object : BackupSettingsAccess {
        var v = AppSettingsSnapshot(ThemeMode.SYSTEM, FontSize.STANDARD, "", null, true, true, true, ReplyPreferences(), ProactiveSettings())
        override fun read() = v
        override fun write(snapshot: AppSettingsSnapshot) { v = snapshot }
    }

    @Before
    fun setUp() {
        listOf("avatars", "images", "background", "voice", "calls", "favorites", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).addCallback(AppDatabase.seedCallback).build()
        backup = BackupManager(context, db, FileStorage(context), cipher, settings, object : ReplyControl {
            override fun cancel(contactId: Long) = Unit
            override suspend fun isActive(contactId: Long) = false
            override fun cancelDelivery(contactId: Long) = Unit
            override suspend fun isDelivering(contactId: Long) = false
        })
    }

    @After
    fun tearDown() = db.close()

    private fun fixture(name: String): File {
        val target = File(context.cacheDir, name)
        javaClass.classLoader!!.getResourceAsStream("backup080/$name")!!.use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private suspend fun assertRestored(keyRestored: Boolean) {
        val contacts = db.contactDao().listAll()
        assertEquals(listOf("虚构甲", "虚构乙"), contacts.map { it.name })
        val first = contacts[0]
        assertEquals(true to ProactiveFrequency.HIGH, first.proactiveEnabled to first.proactiveFrequency)
        assertEquals(ReplyType.VOICE, first.replyType)
        assertEquals("甲的音色", first.ttsVoice)
        assertEquals(7, first.replyPreferences!!.waitSeconds)
        val tts = db.providerDao().listByKind(ProviderKind.TTS).single()
        val asr = db.providerDao().listByKind(ProviderKind.ASR).single()
        assertEquals("单独选的合成服务商换成恢复后的 id", VoiceSource.provider(tts.id), first.ttsSource)
        assertEquals(VoiceSource.of(VoiceSource.OFFLINE_ASR), first.asrSource)
        assertEquals("乙的服务商是 App 自己建的", "虚构 MiMo", db.providerDao().get(contacts[1].providerId)!!.name)
        assertEquals(listOf<Byte>(1, 2, 3, 4), File(first.avatarPath!!).readBytes().toList())

        val messages = db.messageDao().listByContact(1)
        assertEquals(listOf("虚构问题", "", "第一条", "我说的语音", "联系人的语音", "", "第二条"), messages.map { it.content })
        assertEquals(
            listOf(MessageType.TEXT, MessageType.IMAGE, MessageType.TEXT, MessageType.VOICE, MessageType.VOICE, MessageType.CALL, MessageType.TEXT),
            messages.map { it.type },
        )
        assertTrue("导出时待投递的回复按已送达", messages.all { it.status == MessageStatus.SUCCESS })
        assertEquals(MessageOrigin.PROACTIVE, messages.last().origin)
        assertTrue("0.8.0 没有引用和译文", messages.all { it.quoteId == null && it.quoteText == null && it.translation == null && !it.translationShown })
        assertEquals(listOf(300L, 200L), messages.filter { it.type == MessageType.VOICE }.map { File(it.voicePath!!).length() })
        assertEquals(1024, File(messages.single { it.type == MessageType.IMAGE }.imagePath!!).length())

        val call = messages.single { it.type == MessageType.CALL }
        assertEquals(9_000L to CallEndReason.HANG_UP.code, call.voiceMillis to call.errorCode)
        val turns = db.callDao().turns(call.id)
        assertEquals(listOf("通话里我说的", "", "通话里联系人说的", "合成失败的一句"), turns.map { it.text })
        assertEquals(listOf(Role.USER, Role.USER, Role.ASSISTANT, Role.ASSISTANT), turns.map { it.role })
        assertEquals(listOf(CallTurnStatus.OK, CallTurnStatus.ASR_FAILED, CallTurnStatus.OK, CallTurnStatus.TTS_FAILED), turns.map { it.status })
        assertEquals(listOf(120L, 110L, 90L, null), turns.map { turn -> turn.audioPath?.let { File(it).length() } })
        assertEquals(listOf("m4a", "m4a", "mp3"), turns.mapNotNull { it.audioPath?.substringAfterLast('.') })

        val conversation = db.conversationDao().get(1)!!
        assertTrue(conversation.pinned && conversation.muted)
        assertEquals("草稿", conversation.draft)
        assertEquals(512L, File(conversation.backgroundPath!!).length())
        assertEquals(listOf("在吗"), db.messageDao().listByContact(2).map { it.content })
        val memories = db.memoryDao().listByContact(1)
        assertEquals(listOf("虚构核心记忆" to true, "虚构近期记忆" to false), memories.map { it.content to it.core })
        assertEquals(setOf(UsagePurpose.CHAT, UsagePurpose.TTS, UsagePurpose.ASR), db.usageDao().listAll().map { it.purpose }.toSet())
        assertEquals(listOf("住在虚构市"), db.globalMemoryDao().items().map { it.content })
        assertEquals("测试用户", settings.v.nickname)
        assertEquals(ReplyPreferences(5, 20, 2, 4, false, 3, 6), settings.v.replyPreferences)
        assertEquals(VoiceSettings(ReplyType.FOLLOW, VoiceSource.provider(tts.id), VoiceSource.provider(asr.id)), settings.v.voice)
        assertEquals("虚构音色", tts.voice)
        assertTrue("App 建的语音服务商没有兼容方式", listOf(tts, asr).all { it.compatibility == "" })
        assertEquals(if (keyRestored) "sk-fixture-not-real" else null, db.providerDao().get(1)!!.apiKeyEncrypted?.let(cipher::decrypt))
        assertEquals(if (keyRestored) "sk-tts-not-real" else null, tts.apiKeyEncrypted?.let(cipher::decrypt))
        assertEquals(if (keyRestored) "sk-asr-not-real" else null, asr.apiKeyEncrypted?.let(cipher::decrypt))
    }

    private suspend fun addLocalFavorite() = db.favoriteDao().insert(
        Favorite(
            type = MessageType.TEXT, role = Role.ASSISTANT, contactId = 9, contactCreatedAt = 9, contactName = "本机的联系人", contactAvatarColor = 1,
            content = "本机的收藏", messageCreatedAt = 5, createdAt = 6,
        ),
    )

    @Test
    fun plainZipFrom080Restores_localFavoritesKept() = runBlocking {
        addLocalFavorite()
        val preview = backup.inspect(fixture("aichat-080-plain.zip"))
        assertEquals(9, preview.manifest.formatVersion)
        assertEquals(800, preview.manifest.appVersionCode)
        assertEquals(2, preview.voiceCount)
        assertEquals(1, preview.callCount)
        assertEquals(0, preview.favoriteCount)
        assertTrue("0.8.0 的文件没有“收藏”这一项", BackupModule.FAVORITES !in preview.modules)
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        assertRestored(keyRestored = false)
        assertEquals("覆盖恢复不动文件里没有的收藏", listOf("本机的收藏"), db.favoriteDao().listAll().map { it.content })
    }

    @Test
    fun encryptedFileFrom080Restores_withKeys() = runBlocking {
        val preview = backup.inspect(fixture("aichat-080-encrypted.aichat"), "pw123456".toCharArray())
        assertTrue(preview.decryptedTemporary)
        assertEquals(9, preview.manifest.formatVersion)
        backup.restore(preview, RestoreMode.OVERWRITE, "pw123456".toCharArray())
        assertRestored(keyRestored = true)
    }

    @Test
    fun mergingA080Backup_intoAnEmptyPhone() = runBlocking {
        backup.restore(backup.inspect(fixture("aichat-080-plain.zip")), RestoreMode.MERGE, null)
        assertEquals(listOf("虚构甲", "虚构乙"), db.contactDao().listAll().map { it.name })
        val messages = db.messageDao().listByContact(1)
        assertEquals(7, messages.size)
        assertEquals(4, db.callDao().turns(messages.single { it.type == MessageType.CALL }.id).size)
    }

    /** 0.8.0 的备份恢复后再从 0.9.0 导出：v10，通话和录音都在，模块里有“收藏” */
    @Test
    fun restoredFrom080_exportsAsV10WithTheCall() = runBlocking {
        backup.restore(backup.inspect(fixture("aichat-080-plain.zip")), RestoreMode.OVERWRITE, null)
        val again = File(context.cacheDir, "again.zip")
        again.outputStream().use { backup.export(it, null) }
        val preview = backup.inspect(again)
        assertEquals(10, preview.manifest.formatVersion)
        assertEquals(1, preview.callCount)
        assertEquals(listOf(true, true, true, false), preview.data.messages.single { it.type == MessageType.CALL }.call.map { it.audio != null })
        assertTrue(BackupModule.FAVORITES in preview.modules)
    }
}
