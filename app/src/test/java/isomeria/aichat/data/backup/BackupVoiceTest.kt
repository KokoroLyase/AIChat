package isomeria.aichat.data.backup

import android.app.Application
import androidx.room.Room
import isomeria.aichat.core.VoiceSettings
import isomeria.aichat.core.VoiceSource
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ReplyType
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * 0.7.0 语音消息的导出与恢复：语音文件放在 zip 的 voice/ 下、保留扩展名，消息的语音字段、联系人的发送类型和音色、
 * 语音服务商（Key 默认不导出）和语音设置都随备份；恢复时来源里的服务商 id 换成本机的。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackupVoiceTest {
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
        listOf("avatars", "images", "background", "voice", "backups").forEach { File(context.filesDir, it).deleteRecursively() }
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

    private var tts = 0L
    private var asr = 0L

    // 语音服务商和 App 里一样没有兼容方式（VoiceRepository.create 写的是空串）
    private suspend fun seed() {
        tts = db.providerDao().insert(ProviderConfig(kind = ProviderKind.TTS, provider = "mimo-audio", name = "MiMo", baseUrl = "https://api.xiaomimimo.com/v1",
            apiKeyEncrypted = "enc:sk-tts", defaultModel = "mimo-v2.5-tts", presetId = "mimo", voice = "茉莉", compatibility = ""))
        asr = db.providerDao().insert(ProviderConfig(kind = ProviderKind.ASR, provider = "openai-audio", name = "硅基", baseUrl = "https://api.siliconflow.cn/v1",
            apiKeyEncrypted = "enc:sk-asr", defaultModel = "FunAudioLLM/SenseVoiceSmall", compatibility = ""))
        settings.v = settings.v.copy(voice = VoiceSettings(ReplyType.VOICE, VoiceSource.provider(tts), VoiceSource.provider(asr)))
        db.contactDao().insert(Contact(id = 1, name = "甲", avatarColor = 1, providerId = 1, model = "m", createdAt = 10, updatedAt = 10,
            replyType = ReplyType.FOLLOW, ttsVoice = "苏打", ttsStyle = "慢一点"))
        db.conversationDao().upsert(Conversation(contactId = 1, lastMessageTime = 300, lastMessagePreview = "[语音]"))
        val mine = files.newVoiceFile("m4a").apply { writeBytes(ByteArray(300) { 1 }) }
        val theirs = files.newVoiceFile("mp3").apply { writeBytes(ByteArray(200) { 2 }) }
        // 写库后解除固定（真实代码在写库成功后调用）
        files.commitAvatar(mine.absolutePath)
        files.commitAvatar(theirs.absolutePath)
        db.messageDao().insertAll(listOf(
            Message(contactId = 1, role = Role.USER, type = MessageType.VOICE, content = "我说的话", voicePath = mine.absolutePath, voiceMillis = 3_200,
                voicePlayed = true, status = MessageStatus.SUCCESS, createdAt = 100),
            Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "联系人说的话", voicePath = theirs.absolutePath, voiceMillis = 5_000,
                voicePlayed = true, voiceShowText = true, status = MessageStatus.SUCCESS, replyGroup = "g", createdAt = 200),
            Message(contactId = 1, role = Role.ASSISTANT, type = MessageType.VOICE, content = "还在合成", status = MessageStatus.PENDING, replyGroup = "h", createdAt = 300),
        ))
        db.usageDao().add(20_000, 1, UsagePurpose.ASR, 3, 0)
        db.usageDao().add(20_000, 1, UsagePurpose.TTS, 6, 5)
    }

    private suspend fun export(): File {
        val file = File(context.cacheDir, "voice-backup.zip")
        backup.export(file.outputStream(), null)
        return file
    }

    @Test
    fun export_putsVoiceFilesUnderVoiceDir_keepsExtension_noVoiceKeys_heldReplyAsText() = runBlocking {
        seed()
        val zip = export()
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map(ZipEntry::getName).filter { it.startsWith(BackupFormat.VOICE_DIR) }
            assertEquals(listOf("m4a", "mp3"), names.map { it.substringAfterLast('.') }.sorted())
            val raw = z.getInputStream(z.getEntry(BackupFormat.DATA)).readBytes().toString(Charsets.UTF_8)
            assertFalse("语音服务商的 Key 默认也不导出", raw.contains("sk-tts") || raw.contains("sk-asr"))
        }
        val data = backup.inspect(zip).data
        assertEquals(10, backup.inspect(zip).manifest.formatVersion)
        assertEquals("恢复前的摘要：语音消息条数（合成中的那条按文字导出）", 2, backup.inspect(zip).voiceCount)
        val messages = data.messages.sortedBy { it.createdAt }
        assertEquals(listOf(MessageType.VOICE, MessageType.VOICE, MessageType.TEXT), messages.map { it.type })
        assertEquals("还在合成的语音按文字、已送达写入", MessageStatus.SUCCESS, messages[2].status)
        assertNull(messages[2].voice)
        assertEquals(listOf(3_200L, 5_000L), messages.take(2).map { it.voiceMillis })
        assertTrue(messages[1].voiceShowText)
        assertEquals(Triple(ReplyType.FOLLOW, "苏打", "慢一点"), data.contacts.single().let { Triple(it.replyType, it.ttsVoice, it.ttsStyle) })
        assertEquals(setOf(ProviderKind.LLM, ProviderKind.TTS, ProviderKind.ASR), data.providers.map { it.kind }.toSet())
        assertEquals("茉莉", data.providers.single { it.kind == ProviderKind.TTS }.voice)
        assertEquals(VoiceSettingsDto(ReplyType.VOICE, VoiceSource.provider(tts), VoiceSource.provider(asr)), data.settings.voice)
    }

    /** 0.7.0–0.8.0 开发版：语音服务商的兼容方式是空串，恢复时当成格式不对，整份带语音服务商的备份都恢复不了 */
    @Test
    fun voiceProvidersWithoutCompatibility_backupCanBeReadAndRestored() = runBlocking {
        seed()
        val zip = export()
        val preview = backup.inspect(zip)
        assertEquals(listOf("", ""), preview.data.providers.filter { it.kind != ProviderKind.LLM }.map { it.compatibility })
        db.providerDao().deleteAllVoice()
        backup.restore(preview, RestoreMode.OVERWRITE, null)
        val voice = db.providerDao().listConfigs().filter { it.kind != ProviderKind.LLM }
        assertEquals(setOf(ProviderKind.TTS, ProviderKind.ASR), voice.map { it.kind }.toSet())
        assertTrue(voice.all { it.compatibility == "" })
    }

    /** 聊天模型的兼容方式仍然要认得出来，不认识的照旧拒绝 */
    @Test(expected = InvalidBackupException::class)
    fun chatProviderWithUnknownCompatibility_stillRejected() = runBlocking<Unit> {
        seed()
        val zip = export()
        val broken = File(context.cacheDir, "broken.zip")
        ZipFile(zip).use { source ->
            ZipOutputStream(broken.outputStream()).use { out ->
                source.entries().toList().forEach { entry ->
                    var bytes = source.getInputStream(entry).readBytes()
                    if (entry.name == BackupFormat.DATA) {
                        val text = bytes.toString(Charsets.UTF_8)
                        val fixed = Regex("\"compatibility\":\"[A-Z_]+\"").replaceFirst(text, "\"compatibility\":\"\"")
                        check(fixed != text)
                        bytes = fixed.toByteArray()
                    }
                    out.putNextEntry(ZipEntry(entry.name)); out.write(bytes); out.closeEntry()
                }
            }
        }
        backup.inspect(broken)
    }

    @Test
    fun overwrite_restoresVoiceFilesFieldsProvidersAndSettings() = runBlocking {
        seed()
        val zip = export()
        val before = db.messageDao().listByContact(1).filter { it.voicePath != null }.map { File(it.voicePath!!).readBytes().toList() }
        // 导出后本机改了：删掉语音服务商、改了设置
        db.providerDao().deleteAllVoice()
        settings.v = settings.v.copy(voice = VoiceSettings())

        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)

        val messages = db.messageDao().listByContact(1)
        val voices = messages.filter { it.type == MessageType.VOICE }
        assertEquals(before, voices.map { File(it.voicePath!!).readBytes().toList() })
        assertEquals(listOf("m4a", "mp3"), voices.map { File(it.voicePath!!).extension })
        assertTrue("恢复到 files/voice", voices.all { File(it.voicePath!!).parentFile!!.name == "voice" })
        assertEquals(listOf("我说的话", "联系人说的话", "还在合成"), messages.map { it.content })
        assertEquals(MessageType.TEXT, messages[2].type)
        val providers = db.providerDao().listConfigs().associateBy { it.kind }
        assertEquals("茉莉", providers[ProviderKind.TTS]!!.voice)
        assertNull("备份里没有 Key，本机也删了", providers[ProviderKind.TTS]!!.apiKeyEncrypted)
        assertEquals(VoiceSettings(ReplyType.VOICE, VoiceSource.provider(providers[ProviderKind.TTS]!!.id), VoiceSource.provider(providers[ProviderKind.ASR]!!.id)), settings.v.voice)
        assertEquals(setOf(UsagePurpose.ASR, UsagePurpose.TTS), db.usageDao().listAll().map { it.purpose }.toSet())
        // 只有被引用的语音文件留下
        assertEquals(voices.map { File(it.voicePath!!).canonicalPath }.toSet(), File(context.filesDir, "voice").listFiles()!!.map { it.canonicalPath }.toSet())
    }

    @Test
    fun overwrite_sameVoiceProviderWithoutKeyInBackup_keepsLocalKey() = runBlocking {
        seed()
        val zip = export()
        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
        val tts = db.providerDao().listByKind(ProviderKind.TTS).single()
        assertEquals("同一个语音服务商（格式和地址都相同）沿用本机的 Key", "sk-tts", cipher.decrypt(tts.apiKeyEncrypted!!))
    }

    @Test
    fun merge_keepsLocalVoiceSettings_fillsEmptySourcesFromBackup() = runBlocking {
        seed()
        val zip = export()
        // 本机：同一个合成服务商还在，识别服务商删了，识别来源没有选
        db.providerDao().delete(asr)
        settings.v = settings.v.copy(voice = VoiceSettings(ReplyType.TEXT, VoiceSource.provider(tts), ""))
        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        val asrNow = db.providerDao().listByKind(ProviderKind.ASR).single()
        assertEquals(1, db.providerDao().listByKind(ProviderKind.TTS).size)
        assertEquals(VoiceSettings(ReplyType.TEXT, VoiceSource.provider(tts), VoiceSource.provider(asrNow.id)), settings.v.voice)
    }

    /** 0.7.2：联系人单独设置的回复偏好和语音来源 */
    private val ownPrefs = isomeria.aichat.core.ReplyPreferences(waitSeconds = 7, targetLength = 50, stripPunctuation = false)

    private suspend fun withOverrides() {
        val contact = db.contactDao().get(1)!!
        db.contactDao().update(
            contact.copy(replyPreferences = ownPrefs, ttsSource = VoiceSource.provider(tts), asrSource = VoiceSource.of(VoiceSource.OFFLINE_ASR)),
        )
    }

    @Test
    fun contactOverrides_roundTrip_providerIdsRemappedOnMerge() = runBlocking {
        seed()
        withOverrides()
        val zip = export()
        val dto = backup.inspect(zip).data.contacts.single()
        assertEquals(VoiceSource.provider(tts), dto.ttsSource)
        assertEquals(7, dto.replyPreferences!!.waitSeconds)
        // 本机：联系人和语音服务商都删了，另一个合成服务商占了 id
        db.contactDao().delete(1)
        db.providerDao().deleteAllVoice()
        db.providerDao().insert(ProviderConfig(kind = ProviderKind.TTS, compatibility = "", provider = "openai-audio", name = "别的", baseUrl = "https://other.test", defaultModel = "x"))

        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        val restored = db.contactDao().get(1)!!
        val mimo = db.providerDao().listByKind(ProviderKind.TTS).single { it.baseUrl == "https://api.xiaomimimo.com/v1" }
        assertFalse("合并时新加的服务商换了 id", mimo.id == tts)
        assertEquals("单独选的服务商换成本机的 id", VoiceSource.provider(mimo.id), restored.ttsSource)
        assertEquals("本机来源原样", VoiceSource.of(VoiceSource.OFFLINE_ASR), restored.asrSource)
        assertEquals(ownPrefs, restored.replyPreferences)
    }

    @Test
    fun contactLocalSource_ofTheWrongKindOrUnknown_followsGlobalAfterRestore() = runBlocking {
        seed()
        db.contactDao().update(db.contactDao().get(1)!!.copy(ttsSource = "local:offline-asr", asrSource = "local:future-engine"))
        val zip = export()
        db.contactDao().delete(1)
        backup.restore(backup.inspect(zip), RestoreMode.MERGE, null)
        val restored = db.contactDao().get(1)!!
        assertNull("合成来源放了离线识别：跟随全局", restored.ttsSource)
        assertNull("以后版本的新来源认不出：跟随全局", restored.asrSource)
    }

    @Test
    fun restoreWithoutProviders_unmatchedContactSourceFollowsGlobal() = runBlocking {
        seed()
        db.contactDao().update(db.contactDao().get(1)!!.copy(ttsSource = VoiceSource.provider(tts), asrSource = VoiceSource.provider(asr)))
        val zip = export()
        // 本机的合成服务商换了地址（不再是同一个），识别服务商没变
        db.providerDao().update(db.providerDao().get(tts)!!.copy(baseUrl = "https://changed.test"))

        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null, BackupModule.ALL - BackupModule.PROVIDERS)
        val restored = db.contactDao().listAll().single()
        assertNull("找不到同一个服务商：跟随全局", restored.ttsSource)
        assertEquals("按格式和地址找到本机的同一个", VoiceSource.provider(asr), restored.asrSource)
    }

    @Test
    fun overwriteProvidersOnly_localContactSourcesFollowReplacedProviders() = runBlocking {
        seed()
        val zip = export()
        // 导出后本机加了一个合成服务商，联系人单独选了它；识别单独选了备份里也有的那个
        val extra = db.providerDao().insert(ProviderConfig(kind = ProviderKind.TTS, compatibility = "", provider = "openai-audio", name = "新加的", baseUrl = "https://extra.test", defaultModel = "x"))
        db.contactDao().update(db.contactDao().get(1)!!.copy(ttsSource = VoiceSource.provider(extra), asrSource = VoiceSource.provider(asr)))

        backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null, setOf(BackupModule.PROVIDERS))
        val contact = db.contactDao().get(1)!!
        assertNull("替换后没有这个服务商了：跟随全局", contact.ttsSource)
        val asrNow = db.providerDao().listByKind(ProviderKind.ASR).single()
        assertEquals("替换后同一个服务商的 id", VoiceSource.provider(asrNow.id), contact.asrSource)
    }

    /** 0.7.1：本机来源原样导出和恢复（不改数据格式）；离线识别模型和系统音色不进备份 */
    @Test
    fun localSources_roundTripUnchanged_modelFilesNotExported() = runBlocking {
        seed()
        settings.v = settings.v.copy(voice = VoiceSettings(ReplyType.VOICE, VoiceSource.of(VoiceSource.SYSTEM_TTS), VoiceSource.of(VoiceSource.OFFLINE_ASR)))
        val model = File(context.filesDir, "asr-models/x/model.int8.onnx").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(64) { 9 }) }
        try {
            val zip = export()
            ZipFile(zip).use { z ->
                assertTrue("模型文件不进备份", z.entries().toList().none { it.name.contains("asr-models") || it.name.endsWith(".onnx") })
            }
            assertEquals(10, backup.inspect(zip).manifest.formatVersion)
            assertEquals(VoiceSettingsDto(ReplyType.VOICE, "local:system-tts", "local:offline-asr"), backup.inspect(zip).data.settings.voice)
            settings.v = settings.v.copy(voice = VoiceSettings())
            backup.restore(backup.inspect(zip), RestoreMode.OVERWRITE, null)
            assertEquals(VoiceSettings(ReplyType.VOICE, "local:system-tts", "local:offline-asr"), settings.v.voice)
            assertTrue("覆盖恢复不动模型文件", model.isFile)
        } finally {
            File(context.filesDir, "asr-models").deleteRecursively()
        }
    }

    /** zip 里缺了语音文件：联系人的语音按文字恢复（转写文字还在），用户的语音留着、没有文件 */
    @Test
    fun missingVoiceFile_contactVoiceBecomesText() = runBlocking {
        seed()
        val zip = export()
        val stripped = File(context.cacheDir, "stripped.zip")
        ZipFile(zip).use { input ->
            ZipOutputStream(stripped.outputStream()).use { out ->
                for (entry in input.entries()) {
                    if (entry.name.startsWith(BackupFormat.VOICE_DIR)) continue
                    out.putNextEntry(ZipEntry(entry.name))
                    input.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
        }
        backup.restore(backup.inspect(stripped), RestoreMode.OVERWRITE, null)
        val messages = db.messageDao().listByContact(1)
        assertEquals(listOf(MessageType.VOICE, MessageType.TEXT, MessageType.TEXT), messages.map { it.type })
        assertNull(messages[0].voicePath)
        assertEquals("联系人说的话", messages[1].content)
    }

    @Test
    fun v6ToV7_stepAddsVoiceDefaults_andDropsStrayVoiceSettings() {
        val v6 = BackupFormat.json.parseToJsonElement(
            """{"contacts":[{"id":1,"name":"甲","avatarColor":1,"providerId":1,"model":"m","temperature":1.0,"createdAt":1,"updatedAt":1}],
               "messages":[{"id":1,"contactId":1,"role":"USER","type":"TEXT","status":"SUCCESS","createdAt":1}],
               "providers":[{"id":1,"kind":"LLM","provider":"openai","baseUrl":"https://x.test","defaultModel":""}],
               "settings":{"voice":{"replyType":"VOICE"}}}""",
        ).jsonObject
        val v7 = BackupMigrations.v6ToV7(v6)
        val contact = (v7["contacts"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, contact["replyType"])
        assertEquals("", contact["ttsStyle"]!!.jsonPrimitive.content)
        val message = (v7["messages"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("0", message["voiceMillis"]!!.jsonPrimitive.content)
        assertFalse((v7["settings"] as JsonObject).containsKey("voice"))
        val decoded = BackupFormat.json.decodeFromJsonElement(BackupData.serializer(), v7)
        assertNull(decoded.settings.voice)
        assertNull(decoded.contacts.single().replyType)
    }
}
