package isomeria.aichat.data.backup

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.BuildConfig
import isomeria.aichat.R
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.core.MemoryPolicy
import isomeria.aichat.core.ProactiveSettings
import isomeria.aichat.core.ReplyPreferences
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.GlobalMemoryCategory
import isomeria.aichat.data.db.GlobalMemoryItem
import isomeria.aichat.data.db.GlobalMemoryTopic
import isomeria.aichat.data.db.Memory
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageKey
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.db.UsageDaily
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.BaseUrlRules
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.StorageOperations
import isomeria.aichat.data.repo.MediaKind
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

enum class RestoreMode {
    /** 按 id 去重，保留本机已有数据，只加入备份里有、本机没有的 */
    MERGE,

    /** 先清空本机数据，再导入备份 */
    OVERWRITE,
}

/** 读出来、校验过、还没恢复的备份。 */
data class BackupPreview(
    /** 复制到应用缓存目录里的备份文件 */
    val file: File,
    val manifest: BackupManifest,
    val data: BackupData,
    val decryptedTemporary: Boolean = false,
) {
    /** 备份里有用密码加密的 API Key，恢复它需要输入导出时设的密码 */
    val needsPassword: Boolean get() = data.providers.any { it.apiKey?.scheme == SecretDto.SCHEME_PASSWORD }

    val contactCount: Int get() = data.contacts.size
    val messageCount: Int get() = data.messages.size
    val imageCount: Int get() = data.messages.count { it.type == MessageType.IMAGE }

    /** 备份里的全局记忆；0.4.2 及更早的备份没有 */
    val globalMemory: GlobalMemoryCount get() = GlobalMemoryCount(data.globalCategories.size, data.globalItems.size)
}

/** 全局记忆的类别数和条数。类别数为 0 时就是没有全局记忆 */
data class GlobalMemoryCount(val categories: Int, val items: Int) {
    val isEmpty: Boolean get() = categories == 0
}

data class ExportSummary(val contacts: Int, val messages: Int, val images: Int, val includesApiKey: Boolean, val encrypted: Boolean = false)

data class RestoreSummary(val contactsAdded: Int, val messagesAdded: Int, val apiKeyRestored: Boolean, val cleanupPending: Boolean = false)

/**
 * 导出与恢复。
 *
 * **导出：** 在一个读事务里取出全部数据，写成 zip（格式见 [BackupFormat]）。API Key 默认不导出；
 * 给了密码时用 [PasswordCipher] 加密后放进去。
 *
 * **恢复：**
 * 1. [inspect] 读 manifest：不是智信的备份、文件损坏时拒绝；数据格式比当前新时拒绝并提示先升级 App；
 *    比当前旧时先经 [BackupMigrations] 升级到当前格式
 * 2. [restore] 先用密码解开 API Key（密码错误时什么都不改），再把当前数据完整备份到私有目录
 *    （files/backups/before-restore.zip），然后解压图片、在一个数据库事务里写入
 * 3. 失败时回滚：数据库事务自动撤销，新解压的图片删掉；事务提交后的步骤（写设置）出错时，
 *    用第 2 步的自动备份覆盖恢复
 *
 * 合并时「同一个联系人 / 同一条消息」要求 id 和创建时间都相同：两台手机上的 id 都从 1 开始，
 * 只比 id 会把别的手机上同 id 的联系人混进本机联系人里。
 *
 * 「同一个服务商」要求请求格式和 Base URL 都相同。本机的 Key 只会留给同一个服务商：
 * 否则别人做的备份文件可以把 Base URL 改掉，让本机的 Key 发到别的地址。
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: FileStorage,
    private val cipher: SecretCipher,
    private val settings: BackupSettingsAccess,
    private val replies: ReplyControl,
    private val proactive: isomeria.aichat.data.reply.ProactiveControl = isomeria.aichat.data.reply.ProactiveControl.NONE,
) {
    private val json = BackupFormat.json
    private val migrations = BackupMigrations()
    private val mutex = Mutex()

    private val contactDao get() = db.contactDao()
    private val conversationDao get() = db.conversationDao()
    private val messageDao get() = db.messageDao()
    private val providerDao get() = db.providerDao()
    private val memoryDao get() = db.memoryDao()
    private val usageDao get() = db.usageDao()
    private val globalDao get() = db.globalMemoryDao()

    /** 恢复前自动备份的位置 */
    val snapshotFile: File get() = File(files.backupsDir, SNAPSHOT_NAME)

    /**
     * A password always encrypts the entire file. Including keys additionally uses the unchanged SecretDto scheme.
     */
    suspend fun export(out: OutputStream, password: CharArray?, includeApiKey: Boolean = password != null): ExportSummary = mutex.withLock {
        StorageOperations.gate.activity { withContext(Dispatchers.IO) {
            out.use { target ->
                require(!includeApiKey || password != null)
                val key = if (includeApiKey) KeyExport.Password(requireNotNull(password)) else KeyExport.None
                val stream = if (password != null) BackupContainer.encrypting(target, password) else target
                // writeBackup 的 zip 关闭时只收尾到这一层；成功才写认证的最后块
                try {
                    val summary = writeBackup(NonClosingOutputStream(stream), key).copy(encrypted = password != null)
                    stream.close()
                    summary
                } catch (error: Throwable) {
                    (stream as? AbortableOutputStream)?.abort()
                    throw error
                }
            }
        } }
    }

    /** 读取并校验备份文件；见类注释第 1 步。 */
    suspend fun inspect(file: File, password: CharArray? = null): BackupPreview {
        var temporary: File? = null
        try { return StorageOperations.gate.activity { withContext(Dispatchers.IO) {
            if (!BackupContainer.isEncrypted(file)) return@withContext readBackup(file)
            if (password == null) throw EncryptedPasswordRequiredException()
            val plain = File(files.restoreCacheDir, "decrypted-${UUID.randomUUID()}.zip")
            temporary = plain
            StorageOperations.pin(plain)
            try {
                val restoreContext = kotlin.coroutines.coroutineContext
                file.inputStream().use { input -> plain.outputStream().use { output -> BackupContainer.decrypt(input, output, password) { restoreContext.ensureActive() } } }
                readBackup(plain).copy(decryptedTemporary = true)
            } catch (error: Throwable) {
                plain.delete()
                StorageOperations.unpin(plain)
                throw error
            }
        } } } catch (error: Throwable) {
            temporary?.let { it.delete(); StorageOperations.unpin(it) }
            throw error
        }
    }

    /** Only deletes plaintext files created by inspect; never deletes a caller-owned legacy zip. */
    fun discardPreview(preview: BackupPreview?): Boolean {
        if (preview == null || !preview.decryptedTemporary) return true
        return try {
            val file = preview.file
            if (!file.name.matches(Regex("decrypted-[a-f0-9-]{36}\\.zip")) ||
                !file.canonicalPath.startsWith(files.restoreCacheDir.canonicalPath + File.separator)) return false
            val deleted = !file.exists() || file.delete()
            StorageOperations.unpin(file)
            deleted
        } catch (_: Exception) { false }
    }

    /** 本机现有的全局记忆；覆盖恢复前用它决定要不要问用户保留 */
    suspend fun localGlobalMemory(): GlobalMemoryCount = withContext(Dispatchers.IO) {
        GlobalMemoryCount(globalDao.categories().size, globalDao.items().size)
    }

    /**
     * 恢复。备份里有加密的 API Key 但 [password] 为空时，跳过 Key、照常恢复其他数据；
     * 密码错误时抛 [WrongPasswordException]，本机数据不变。
     *
     * [keepGlobalMemory] 只对覆盖恢复有效：为 true 时本机的全局记忆原样保留，不用备份里的
     * （0.4.2 及更早的备份没有全局记忆，不保留就会被清空）。由用户在覆盖前选择。
     */
    suspend fun restore(preview: BackupPreview, mode: RestoreMode, password: CharArray?, keepGlobalMemory: Boolean = false): RestoreSummary =
        try { mutex.withLock {
            StorageOperations.gate.activity { withContext(Dispatchers.IO) {
                val apiKeys = importedApiKeys(preview, password)
                writeSnapshot()
                if (mode == RestoreMode.OVERWRITE) stopAllReplies()
                val summary = apply(preview, mode, apiKeys, exactKey = false, rollback = snapshotFile, keepGlobalMemory = keepGlobalMemory)
                // apply includes post-transaction settings writes and any required rollback.
                val snapshotDeleted = !snapshotFile.exists() || snapshotFile.delete()
                val temporaryDeleted = discardPreview(preview)
                // 恢复后开启主动聊天的联系人可能变了（0.5.0 及更早的备份里都是关闭）：按恢复后的数据注册或取消后台任务
                proactive.sync()
                summary.copy(cleanupPending = !snapshotDeleted || !temporaryDeleted)
            } }
        } } finally { discardPreview(preview) }

    // ---------- 导出 ----------

    private data class Tables(
        val contacts: List<Contact>,
        val conversations: List<Conversation>,
        val messages: List<Message>,
        val configs: List<ProviderConfig>,
        val models: Map<Long, List<ProviderModel>>,
        val memories: List<Memory>,
        val usage: List<UsageDaily>,
        val globalCategories: List<GlobalMemoryCategory>,
        val globalTopics: List<GlobalMemoryTopic>,
        val globalItems: List<GlobalMemoryItem>,
    )

    private sealed interface KeyExport {
        data object None : KeyExport
        class Password(val password: CharArray) : KeyExport
        /** 本机自动备份：直接存 Keystore 密文 */
        data object Keystore : KeyExport
    }

    private suspend fun writeBackup(out: OutputStream, key: KeyExport): ExportSummary {
        val tables = db.withTransaction {
            val configs = providerDao.listConfigs()
            Tables(
                contactDao.listAll(),
                conversationDao.listAll(),
                messageDao.listAll(),
                configs,
                configs.associate { it.id to providerDao.models(it.id) },
                memoryDao.listAll(),
                usageDao.listAll(),
                globalDao.categories(),
                globalDao.topics(),
                globalDao.items(),
            )
        }
        val (contacts, rawConversations, rawMessages, configs, models) = tables
        // 待投递的回复按已送达写入：恢复后不会再投递一次；会话预览和未读也按送达后的样子
        val pending = rawMessages.filter { it.status == MessageStatus.PENDING }.groupBy { it.contactId }
        val messages = rawMessages.map { if (it.status == MessageStatus.PENDING) it.copy(status = MessageStatus.SUCCESS) else it }
        val conversations = rawConversations.map { conversation ->
            val rows = pending[conversation.contactId] ?: return@map conversation
            val last = rows.maxWith(compareBy<Message>({ it.createdAt }, { it.id }))
            val newer = last.createdAt >= conversation.lastMessageTime
            conversation.copy(
                lastMessageTime = if (newer) last.createdAt else conversation.lastMessageTime,
                lastMessagePreview = if (newer) last.content else conversation.lastMessagePreview,
                unreadCount = conversation.unreadCount + rows.size,
            )
        }
        val appSettings = settings.read()

        val media = MediaCollector()
        var keyIncluded = false
        val providers = configs.map { config ->
            val secret = when (key) {
                KeyExport.None -> null
                is KeyExport.Password ->
                    config.apiKeyEncrypted?.let(cipher::decrypt)?.let { PasswordCipher.encrypt(it, key.password) }
                KeyExport.Keystore -> config.apiKeyEncrypted?.let { SecretDto(SecretDto.SCHEME_KEYSTORE, data = it) }
            }
            if (secret != null) keyIncluded = true
            config.toDto(models[config.id].orEmpty(), secret)
        }
        val data = BackupData(
            contacts = contacts.map { it.toDto(media.add(it.avatarPath, BackupFormat.AVATARS_DIR)) },
            conversations = conversations.map { it.toDto(media.add(it.backgroundPath, BackupFormat.BACKGROUNDS_DIR)) },
            messages = messages.map { it.toDto(media.add(it.imagePath, BackupFormat.IMAGES_DIR)) },
            providers = providers,
            memories = tables.memories.map { it.toDto() },
            usage = tables.usage.map { it.toDto() },
            settings = appSettings.toDto(media.add(appSettings.avatarPath, BackupFormat.AVATARS_DIR)),
            globalCategories = tables.globalCategories.map { it.toDto() },
            globalTopics = tables.globalTopics.map { it.toDto() },
            globalItems = tables.globalItems.map { it.toDto() },
        )
        val summary = ExportSummary(
            contacts = contacts.size,
            messages = messages.size,
            images = messages.count { it.type == MessageType.IMAGE },
            includesApiKey = keyIncluded,
        )
        val manifest = BackupManifest(
            appVersion = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            formatVersion = BackupFormat.VERSION,
            exportedAt = System.currentTimeMillis(),
            includesApiKey = keyIncluded,
            contactCount = summary.contacts,
            messageCount = summary.messages,
            imageCount = summary.images,
        )

        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
            zip.write(json.encodeToString(BackupManifest.serializer(), manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(BackupFormat.DATA))
            encodeData(data, NonClosingOutputStream(zip))
            zip.closeEntry()

            // 图片本身已经是 JPEG，再压缩没有意义
            zip.setLevel(Deflater.NO_COMPRESSION)
            for ((name, file) in media.entries) {
                try {
                    file.inputStream().use { input ->
                        zip.putNextEntry(ZipEntry(name))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                } catch (_: FileNotFoundException) {
                    // 导出过程中被删掉的图片：恢复时这条图片消息没有图
                }
            }
        }
        return summary
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun encodeData(data: BackupData, out: OutputStream) {
        json.encodeToStream(BackupData.serializer(), data, out)
    }

    /** 收集要打包的头像和图片；同一个文件只打包一次 */
    private inner class MediaCollector {
        val entries = LinkedHashMap<String, File>()
        private val byPath = HashMap<String, String>()

        fun add(path: String?, dir: String): String? {
            if (path.isNullOrEmpty()) return null
            val file = File(path)
            if (!file.isFile || !files.isPrivate(file)) return null
            val canonical = file.canonicalPath
            byPath[canonical]?.let { return it }
            var name = dir + file.name
            if (!BackupFormat.mediaNamePattern.matches(name) || name in entries) name = dir + "${UUID.randomUUID()}.jpg"
            entries[name] = file
            byPath[canonical] = name
            return name
        }
    }

    private suspend fun writeSnapshot() {
        val target = snapshotFile
        val temp = File(target.parentFile, "$SNAPSHOT_NAME.tmp")
        writeBackup(FileOutputStream(temp), KeyExport.Keystore)
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "无法保存恢复前的备份" }
    }

    // ---------- 读取 ----------

    private fun readBackup(file: File): BackupPreview {
        try {
            ZipFile(file).use { zip ->
                val manifest = json.decodeFromJsonElement(
                    BackupManifest.serializer(),
                    zip.readJson(BackupFormat.MANIFEST, MAX_MANIFEST_BYTES),
                )
                if (manifest.app != BackupFormat.APP_ID) throw InvalidBackupException("不是智信的备份文件")
                if (manifest.formatVersion > BackupFormat.VERSION) throw BackupTooNewException(manifest.formatVersion)
                val element = zip.readJson(BackupFormat.DATA, MAX_DATA_BYTES) as? JsonObject
                    ?: throw InvalidBackupException("data.json 格式不对")
                val migrated = migrations.migrate(element, manifest.formatVersion)
                val data = json.decodeFromJsonElement(BackupData.serializer(), migrated)
                return BackupPreview(file, manifest, sanitize(data))
            }
        } catch (e: BackupTooNewException) {
            throw e
        } catch (e: InvalidBackupException) {
            throw e
        } catch (e: ZipException) {
            throw InvalidBackupException("文件不是 zip 或已损坏", e)
        } catch (e: SerializationException) {
            throw InvalidBackupException("内容格式不对", e)
        } catch (e: IllegalArgumentException) {
            throw InvalidBackupException("内容格式不对", e)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun ZipFile.readJson(name: String, limit: Long): JsonElement {
        val entry = getEntry(name) ?: throw InvalidBackupException("缺少 $name")
        return getInputStream(entry).use { input ->
            json.decodeFromStream(JsonElement.serializer(), LimitedInputStream(input, limit))
        }
    }

    /** 去掉重复 id、找不到联系人的会话和消息、不合规的文件名 */
    private fun sanitize(data: BackupData): BackupData {
        data.providers.forEach { p ->
            isomeria.aichat.data.llm.Compatibility.valueOf(p.compatibility)
            p.models.forEach { m ->
                isomeria.aichat.data.llm.ModelRules.decode(m.reportedConfig)
                isomeria.aichat.data.llm.ModelRules.decode(m.userConfig)
                isomeria.aichat.data.llm.ModelRules.override(m.compatibilityOverride)
            }
        }
        val contacts = data.contacts.distinctBy { it.id }
            .map { it.copy(avatar = it.avatar?.takeIf(BackupFormat.mediaNamePattern::matches)) }
        val ids = contacts.mapTo(HashSet()) { it.id }
        val categories = data.globalCategories.distinctBy { it.id }
            .map { it.copy(name = it.name.take(GlobalMemoryCategory.NAME_MAX_LENGTH)) }
        val categoryIds = categories.mapTo(HashSet()) { it.id }
        val topics = data.globalTopics.filter { it.categoryId in categoryIds }.distinctBy { it.id }
            .map { it.copy(name = it.name.take(GlobalMemoryCategory.NAME_MAX_LENGTH)) }
        val topicIds = topics.mapTo(HashSet()) { it.id }
        return data.copy(
            globalCategories = categories,
            globalTopics = topics,
            globalItems = data.globalItems.filter { it.topicId in topicIds && it.content.isNotBlank() }.distinctBy { it.id }
                .map { it.copy(content = it.content.take(GlobalMemoryItem.CONTENT_MAX_LENGTH)) },
            providers = data.providers.distinctBy { it.id },
            memories = data.memories.filter { it.contactId in ids && it.content.isNotBlank() }.distinctBy { it.id }
                .map { it.copy(content = it.content.take(MemoryPolicy.CONTENT_MAX_LENGTH)) },
            usage = data.usage.filter { it.contactId in ids || it.contactId == UsageDaily.DELETED_CONTACT }
                .distinctBy { Triple(it.day, it.contactId, it.purpose) }
                .map {
                    it.copy(
                        promptTokens = it.promptTokens.coerceAtLeast(0),
                        completionTokens = it.completionTokens.coerceAtLeast(0),
                        requests = it.requests.coerceAtLeast(0),
                    )
                },
            contacts = contacts,
            conversations = data.conversations.filter { it.contactId in ids }.distinctBy { it.contactId }
                .map { it.copy(background = it.background?.takeIf { name -> name.startsWith(BackupFormat.BACKGROUNDS_DIR) && BackupFormat.mediaNamePattern.matches(name) }) },
            messages = data.messages.filter { it.contactId in ids }.distinctBy { it.id }
                .map { it.copy(image = it.image?.takeIf(BackupFormat.mediaNamePattern::matches)) },
            settings = data.settings.copy(avatar = data.settings.avatar?.takeIf(BackupFormat.mediaNamePattern::matches)),
        )
    }

    // ---------- 恢复 ----------

    /**
     * 要写进数据库的 API Key（Keystore 密文）：备份里的服务商 id → 密文。
     * 备份里没有或没给密码时没有这一项；密码错误时抛 [WrongPasswordException]。
     */
    private fun importedApiKeys(preview: BackupPreview, password: CharArray?): Map<Long, String> =
        preview.data.providers.mapNotNull { provider ->
            val secret = provider.apiKey ?: return@mapNotNull null
            val key = when (secret.scheme) {
                SecretDto.SCHEME_PASSWORD ->
                    if (password == null || password.isEmpty()) null else cipher.encrypt(PasswordCipher.decrypt(secret, password))
                // 只有在同一台设备上才能解开
                SecretDto.SCHEME_KEYSTORE -> secret.data.takeIf { cipher.decrypt(it) != null }
                else -> null
            }
            key?.let { provider.id to it }
        }.toMap()

    /** 覆盖前停掉所有等待中的回复和投递，最多等 3 秒，免得旧任务把回复写进新数据 */
    private suspend fun stopAllReplies() {
        val ids = contactDao.listAll().map { it.id }
        ids.forEach(replies::cancel)
        ids.forEach(replies::cancelDelivery)
        withTimeoutOrNull(3_000) {
            while (ids.any { replies.isActive(it) || replies.isDelivering(it) }) delay(100)
        }
    }

    /**
     * [exactKey] 为 true 时（用自动备份回滚）API Key 也恢复成备份里的样子，备份里没有就清掉；
     * 否则备份里没有 Key 时保留本机同一个服务商的。
     */
    private suspend fun apply(
        preview: BackupPreview,
        mode: RestoreMode,
        apiKeys: Map<Long, String>,
        exactKey: Boolean,
        rollback: File?,
        keepGlobalMemory: Boolean = false,
    ): RestoreSummary {
        db.restoreInProgress.incrementAndGet()
        db.restoreGeneration.incrementAndGet()
        val created = ArrayList<File>()
        try {
            val paths = ZipFile(preview.file).use { zip -> extractMedia(zip, preview.data, created) }
            val summary = writeDatabase(preview.data, mode, paths, apiKeys, exactKey, keepGlobalMemory)
            try {
                writeSettings(preview.data.settings, mode, paths)
            } catch (e: Exception) {
                if (rollback != null) rollbackFrom(rollback)
                throw e
            }
            runCatching { deleteUnreferencedMedia() }
            return summary
        } catch (e: Throwable) {
            created.forEach { it.delete() }
            throw e
        } finally {
            db.restoreGeneration.incrementAndGet()
            db.restoreInProgress.decrementAndGet()
        }
    }

    private suspend fun rollbackFrom(snapshot: File) {
        val preview = readBackup(snapshot)
        apply(preview, RestoreMode.OVERWRITE, importedApiKeys(preview, null), exactKey = true, rollback = null)
    }

    /** 解压备份里用到的头像和图片，换成随机的新文件名；返回 zip 内路径 → 本机路径 */
    private fun extractMedia(zip: ZipFile, data: BackupData, created: MutableList<File>): Map<String, String> {
        val names = LinkedHashSet<String>()
        data.contacts.mapNotNullTo(names) { it.avatar }
        data.messages.mapNotNullTo(names) { it.image }
        data.settings.avatar?.let(names::add)
        data.conversations.mapNotNullTo(names) { it.background }
        val result = HashMap<String, String>()
        for (name in names) {
            val entry = zip.getEntry(name) ?: continue
            val kind = when {
                name.startsWith(BackupFormat.AVATARS_DIR) -> MediaKind.AVATAR
                name.startsWith(BackupFormat.BACKGROUNDS_DIR) -> MediaKind.BACKGROUND
                else -> MediaKind.IMAGE
            }
            val target = files.newMediaFile(kind)
            created += target
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { LimitedInputStream(input, MAX_MEDIA_BYTES).copyTo(it) }
            }
            result[name] = target.absolutePath
        }
        return result
    }

    private suspend fun writeDatabase(
        data: BackupData,
        mode: RestoreMode,
        paths: Map<String, String>,
        apiKeys: Map<Long, String>,
        exactKey: Boolean,
        keepGlobalMemory: Boolean,
    ): RestoreSummary = db.withTransaction {
        val sources = data.providers.filter { it.kind == ProviderKind.LLM }
        when (mode) {
            RestoreMode.OVERWRITE -> overwrite(data, sources, paths, apiKeys, exactKey, keepGlobalMemory)
            RestoreMode.MERGE -> merge(data, sources, paths, apiKeys)
        }
    }

    private suspend fun overwrite(
        data: BackupData,
        sources: List<ProviderDto>,
        paths: Map<String, String>,
        apiKeys: Map<Long, String>,
        exactKey: Boolean,
        keepGlobalMemory: Boolean,
    ): RestoreSummary {
        messageDao.deleteAll()
        memoryDao.deleteAll()
        usageDao.deleteAll()
        // 约定提醒跟着本机的聊天走，不在备份里；今日已用次数（proactive_state）是设备状态，覆盖恢复不动它
        db.proactiveDao().deleteAllReminders()
        conversationDao.deleteAll()
        contactDao.deleteAll()
        // 用户选了保留本机的全局记忆时不动它，备份里的全局记忆不导入
        if (!keepGlobalMemory) {
            globalDao.deleteAllItems()
            globalDao.deleteAllTopics()
            globalDao.deleteAllCategories()
            globalDao.insertCategories(data.globalCategories.map { it.toEntity() })
            globalDao.insertTopics(data.globalTopics.map { it.toEntity(it.categoryId) })
            globalDao.insertItems(data.globalItems.map { it.toEntity(it.topicId) })
        }

        val providerIds = overwriteProviders(sources, apiKeys, exactKey)
        val fallback = providerDao.llmList().firstOrNull()?.id ?: error("缺少服务商配置")

        contactDao.insertAll(
            data.contacts.map { it.toEntity(providerIds[it.providerId] ?: fallback, it.avatar?.let(paths::get)) },
        )
        val withConversation = data.conversations.mapTo(HashSet()) { it.contactId }
        conversationDao.upsertAll(
            data.conversations.map { it.toEntity(it.background?.let(paths::get)) } +
                data.contacts.filter { it.id !in withConversation }.map { Conversation(contactId = it.id, memoryCursor = null) },
        )
        messageDao.insertAll(data.messages.map { it.toEntity(it.contactId, it.image?.let(paths::get)) })
        memoryDao.insertAll(data.memories.map { it.toEntity(it.contactId) })
        usageDao.insertIgnore(data.usage.map { it.toEntity(it.contactId) })
        return RestoreSummary(data.contacts.size, data.messages.size, apiKeys.isNotEmpty())
    }

    /**
     * 服务商换成备份里的，id 不变。备份里没有服务商时（不应该出现）保留本机的。
     * 备份没带 Key 时保留本机同一个服务商的 Key：Key 不算聊天数据，导出默认也不带。
     */
    private suspend fun overwriteProviders(
        sources: List<ProviderDto>,
        apiKeys: Map<Long, String>,
        exactKey: Boolean,
    ): Map<Long, Long> {
        if (sources.isEmpty()) return emptyMap()
        val local = providerDao.llmList()
        local.forEach { providerDao.deleteModels(it.id) }
        providerDao.deleteAllLlm()
        return sources.associate { source ->
            val key = apiKeys[source.id]
                ?: if (exactKey) null else local.firstOrNull { it.isSameAs(source) }?.apiKeyEncrypted
            val id = insertProvider(source, key, keepId = true)
            source.id to id
        }
    }

    private suspend fun insertProvider(source: ProviderDto, apiKey: String?, keepId: Boolean): Long {
        val id = providerDao.insert(source.toEntity(if (keepId) source.id else 0, apiKey))
        providerDao.upsertModels(source.models.map { it.toEntity(id) })
        return id
    }

    private fun ProviderConfig.isSameAs(source: ProviderDto) =
        provider == source.provider && BaseUrlRules.isSameUrl(baseUrl, source.baseUrl)

    /**
     * 合并服务商：同一个服务商保留本机的配置，只补本机没有的 Key 和模型；其他的作为新服务商加入。
     * 返回备份里的 id → 本机的 id，以及有没有恢复 Key。
     */
    private suspend fun mergeProviders(sources: List<ProviderDto>, apiKeys: Map<Long, String>): Pair<Map<Long, Long>, Boolean> {
        val local = providerDao.llmList().toMutableList()
        val ids = HashMap<Long, Long>()
        var keyRestored = false
        for (source in sources) {
            val key = apiKeys[source.id]
            val same = local.firstOrNull { it.isSameAs(source) }
            if (same == null) {
                val id = insertProvider(source, key, keepId = false)
                local += providerDao.get(id) ?: continue
                ids[source.id] = id
                keyRestored = keyRestored || key != null
                continue
            }
            ids[source.id] = same.id
            if (same.apiKeyEncrypted.isNullOrEmpty() && key != null) {
                providerDao.update(same.copy(apiKeyEncrypted = key))
                keyRestored = true
            }
            val known = providerDao.models(same.id).mapTo(HashSet()) { it.modelId }
            providerDao.upsertModels(source.models.filter { it.modelId !in known }.map { it.toEntity(same.id) })
        }
        return ids to keyRestored
    }

    private suspend fun merge(
        data: BackupData,
        sources: List<ProviderDto>,
        paths: Map<String, String>,
        apiKeys: Map<Long, String>,
    ): RestoreSummary {
        val (providerIds, keyRestored) = mergeProviders(sources, apiKeys)
        val fallback = providerDao.llmList().firstOrNull()?.id ?: error("缺少服务商配置")
        fun providerOf(contact: ContactDto) = providerIds[contact.providerId] ?: fallback

        val local = contactDao.listAll().associateBy { it.id }
        val idMap = HashMap<Long, Long>()
        val same = HashSet<Long>()
        val needNewId = ArrayList<ContactDto>()
        var contactsAdded = 0
        // 先插入能沿用原 id 的，再插入要换新 id 的，避免自增 id 和后面要沿用的 id 撞上
        for (contact in data.contacts) {
            val existing = local[contact.id]
            when {
                existing != null && existing.createdAt == contact.createdAt -> {
                    idMap[contact.id] = existing.id
                    same += contact.id
                }
                existing == null -> {
                    contactDao.insert(contact.toEntity(providerOf(contact), contact.avatar?.let(paths::get)))
                    idMap[contact.id] = contact.id
                    contactsAdded++
                }
                else -> needNewId += contact
            }
        }
        for (contact in needNewId) {
            idMap[contact.id] = contactDao.insert(contact.toEntity(providerOf(contact), contact.avatar?.let(paths::get)).copy(id = 0))
            contactsAdded++
        }

        // 已有联系人的会话（置顶、草稿、未读）保留本机的
        val localConversations = conversationDao.listAll().mapTo(HashSet()) { it.contactId }
        for (conversation in data.conversations) {
            val target = idMap[conversation.contactId] ?: continue
            if (target in localConversations) continue
            conversationDao.upsert(conversation.toEntity(conversation.background?.let(paths::get)).copy(contactId = target))
            localConversations += target
        }
        for (target in idMap.values) {
            if (target !in localConversations) conversationDao.upsert(Conversation(contactId = target, memoryCursor = null))
        }

        val localKeys = messageDao.allKeys().toHashSet()
        val newMessages = data.messages.mapNotNull { message ->
            val target = idMap[message.contactId] ?: return@mapNotNull null
            if (message.contactId in same && MessageKey(message.id, target, message.createdAt) in localKeys) {
                return@mapNotNull null
            }
            message.toEntity(target, message.image?.let(paths::get)).copy(id = 0)
        }
        messageDao.insertAll(newMessages)
        // 已有的联系人多了消息：重新算聊天列表里的最后一条
        val sameTargets = same.mapNotNullTo(HashSet()) { idMap[it] }
        newMessages.mapTo(HashSet()) { it.contactId }.filter { it in sameTargets }.forEach { refreshLastMessage(it) }

        // 记忆：同一条（时间范围和内容都相同）不重复加入
        val existingMemories = memoryDao.listAll().mapTo(HashSet()) { listOf(it.contactId, it.fromTime, it.toTime, it.content) }
        memoryDao.insertAll(
            data.memories.mapNotNull { memory ->
                val target = idMap[memory.contactId] ?: return@mapNotNull null
                if (listOf(target, memory.fromTime, memory.toTime, memory.content) in existingMemories) return@mapNotNull null
                memory.toEntity(target).copy(id = 0)
            },
        )
        // 用量：本机已有的那一天、那个联系人保留本机的
        usageDao.insertIgnore(
            data.usage.mapNotNull { row ->
                val target = if (row.contactId == UsageDaily.DELETED_CONTACT) row.contactId else idMap[row.contactId]
                target?.let { row.toEntity(it) }
            },
        )
        mergeGlobalMemory(data)
        return RestoreSummary(contactsAdded, newMessages.size, keyRestored)
    }

    /**
     * 合并全局记忆：类别、主题按创建时间认「同一个」（两台手机的 id 都从 1 开始，只比 id 会混在一起；
     * 同一台手机导出的备份再合并时创建时间相同，不会重复），同一个保留本机的名称和顺序；
     * 条目在同一个主题下按内容去重。本机没有的加在后面。
     */
    private suspend fun mergeGlobalMemory(data: BackupData) {
        val localCategories = globalDao.categories()
        val categoryIds = HashMap<Long, Long>()
        var categoryOrder = globalDao.maxCategoryOrder()
        for (category in data.globalCategories) {
            val same = localCategories.firstOrNull { it.createdAt == category.createdAt && it.id == category.id }
                ?: localCategories.firstOrNull { it.createdAt == category.createdAt }
            categoryIds[category.id] = same?.id
                ?: globalDao.insertCategory(category.toEntity().copy(id = 0, sortOrder = ++categoryOrder))
        }
        val localTopics = globalDao.topics()
        val topicIds = HashMap<Long, Long>()
        for (topic in data.globalTopics) {
            val target = categoryIds[topic.categoryId] ?: continue
            val same = localTopics.firstOrNull { it.categoryId == target && it.createdAt == topic.createdAt }
            topicIds[topic.id] = same?.id ?: globalDao.insertTopic(
                topic.toEntity(target).copy(id = 0, sortOrder = globalDao.maxTopicOrder(target) + 1),
            )
        }
        val existing = globalDao.items().mapTo(HashSet()) { it.topicId to it.content }
        for (item in data.globalItems) {
            val target = topicIds[item.topicId] ?: continue
            if (!existing.add(target to item.content)) continue
            globalDao.insertItem(item.toEntity(target).copy(id = 0, sortOrder = globalDao.maxItemOrder(target) + 1))
        }
    }

    private suspend fun refreshLastMessage(contactId: Long) {
        val latest = messageDao.latestVisible(contactId) ?: return
        val preview = when (latest.type) {
            MessageType.TEXT -> latest.content
            MessageType.IMAGE -> context.getString(R.string.preview_image)
        }
        conversationDao.setLastMessage(contactId, latest.createdAt, preview)
    }

    private fun writeSettings(dto: SettingsDto, mode: RestoreMode, paths: Map<String, String>) {
        val current = settings.read()
        val next = when (mode) {
            RestoreMode.OVERWRITE -> AppSettingsSnapshot(
                themeMode = dto.themeMode?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: current.themeMode,
                fontSize = dto.fontSize?.let { name -> FontSize.entries.firstOrNull { it.name == name } } ?: current.fontSize,
                nickname = dto.nickname ?: current.nickname,
                avatarPath = dto.avatar?.let(paths::get),
                notifyEnabled = dto.notifyEnabled ?: current.notifyEnabled,
                notifyPreview = dto.notifyPreview ?: current.notifyPreview,
                autoCheckUpdate = dto.autoCheckUpdate ?: current.autoCheckUpdate,
                replyPreferences = dto.replyPreferences?.toModel() ?: current.replyPreferences,
                proactive = dto.proactive?.toModel() ?: current.proactive,
            )
            RestoreMode.MERGE -> current.copy(
                nickname = current.nickname.ifBlank { dto.nickname.orEmpty() },
                avatarPath = current.avatarPath ?: dto.avatar?.let(paths::get),
            )
        }
        if (next != current) settings.write(next)
    }

    private suspend fun deleteUnreferencedMedia() {
        val referenced = HashSet<String>()
        contactDao.listAll().mapNotNullTo(referenced) { it.avatarPath }
        referenced += messageDao.allImagePaths()
        referenced += conversationDao.backgroundPaths()
        settings.read().avatarPath?.let(referenced::add)
        files.deleteUnreferenced(referenced)
    }

    // ---------- 数据库实体 ↔ 导出格式 ----------

    private fun Contact.toDto(avatar: String?) = ContactDto(
        id = id, name = name, avatar = avatar, avatarColor = avatarColor, providerId = providerId, model = model,
        thinking = thinking, temperature = temperature, systemPrompt = systemPrompt, contextTokens = contextTokens,
        splitReplies = splitReplies, autoMemory = autoMemory, createdAt = createdAt, updatedAt = updatedAt,
        thinkingOption = thinkingOption, memoryModel = memoryModel, memoryThinkingOption = memoryThinkingOption,
        useGlobalMemory = useGlobalMemory, useReplyPreferences = useReplyPreferences,
        proactiveEnabled = proactiveEnabled, proactiveFrequency = proactiveFrequency,
    )

    private fun ContactDto.toEntity(providerId: Long, avatarPath: String?) = Contact(
        id = id, name = name, avatarPath = avatarPath, avatarColor = avatarColor, providerId = providerId, model = model,
        thinking = thinking, temperature = temperature, systemPrompt = systemPrompt,
        contextTokens = contextTokens.coerceIn(Contact.MIN_CONTEXT_TOKENS, Contact.MAX_CONTEXT_TOKENS),
        splitReplies = splitReplies, autoMemory = autoMemory, createdAt = createdAt, updatedAt = updatedAt,
        thinkingOption = thinkingOption, memoryModel = memoryModel, memoryThinkingOption = memoryThinkingOption,
        useGlobalMemory = useGlobalMemory, useReplyPreferences = useReplyPreferences,
        proactiveEnabled = proactiveEnabled, proactiveFrequency = proactiveFrequency,
    )

    // 会话的连续未回复次数和最近一次主动请求时间是设备状态：不导出，恢复出来的会话从 0 开始

    private fun Conversation.toDto(background: String?) = ConversationDto(
        contactId = contactId, pinned = pinned, unreadCount = unreadCount, markedUnread = markedUnread,
        draft = draft, lastMessageTime = lastMessageTime, lastMessagePreview = lastMessagePreview,
        memoryCursor = memoryCursor, memoryStart = memoryStart, muted = muted, background = background,
    )

    private fun ConversationDto.toEntity(backgroundPath: String?) = Conversation(
        contactId = contactId, pinned = pinned, unreadCount = unreadCount, markedUnread = markedUnread,
        draft = draft, lastMessageTime = lastMessageTime, lastMessagePreview = lastMessagePreview,
        memoryCursor = memoryCursor, memoryStart = memoryStart, muted = muted, backgroundPath = backgroundPath,
    )

    private fun Memory.toDto() = MemoryDto(
        id = id, contactId = contactId, content = content, fromTime = fromTime, toTime = toTime,
        edited = edited, createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun MemoryDto.toEntity(contactId: Long) = Memory(
        id = id, contactId = contactId, content = content, fromTime = fromTime, toTime = toTime,
        edited = edited, createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun GlobalMemoryCategory.toDto() = GlobalCategoryDto(id, name, sortOrder, createdAt, updatedAt)

    private fun GlobalCategoryDto.toEntity() = GlobalMemoryCategory(id, name, sortOrder, createdAt, updatedAt)

    private fun GlobalMemoryTopic.toDto() = GlobalTopicDto(id, categoryId, name, sortOrder, createdAt, updatedAt)

    private fun GlobalTopicDto.toEntity(categoryId: Long) = GlobalMemoryTopic(id, categoryId, name, sortOrder, createdAt, updatedAt)

    private fun GlobalMemoryItem.toDto() = GlobalItemDto(id, topicId, content, enabled, sortOrder, createdAt, updatedAt)

    private fun GlobalItemDto.toEntity(topicId: Long) = GlobalMemoryItem(id, topicId, content, enabled, sortOrder, createdAt, updatedAt)

    private fun ReplyPreferences.toDto() = ReplyPreferencesDto(
        waitSeconds, targetLength, minCount, maxCount, stripPunctuation, minIntervalSeconds, maxIntervalSeconds,
    )

    private fun ReplyPreferencesDto.toModel() = ReplyPreferences(
        waitSeconds, targetLength, minCount, maxCount, stripPunctuation, minIntervalSeconds, maxIntervalSeconds,
    ).normalized()

    private fun ProactiveSettings.toDto() = ProactiveSettingsDto(quietEnabled, quietStartMinutes, quietEndMinutes, dailyLimit)

    private fun ProactiveSettingsDto.toModel() = ProactiveSettings(quietEnabled, quietStartMinutes, quietEndMinutes, dailyLimit).normalized()

    private fun UsageDaily.toDto() = UsageDto(
        day = day, contactId = contactId, purpose = purpose, promptTokens = promptTokens,
        completionTokens = completionTokens, requests = requests,
    )

    private fun UsageDto.toEntity(contactId: Long) = UsageDaily(
        day = day, contactId = contactId, purpose = purpose, promptTokens = promptTokens,
        completionTokens = completionTokens, requests = requests,
    )

    private fun Message.toDto(image: String?) = MessageDto(
        id = id, contactId = contactId, role = role, type = type, content = content, image = image,
        imageWidth = imageWidth, imageHeight = imageHeight, reasoning = reasoning, reasoningMillis = reasoningMillis,
        status = status, errorCode = errorCode, model = model, promptTokens = promptTokens,
        completionTokens = completionTokens, replyGroup = replyGroup, createdAt = createdAt, origin = origin,
    )

    /** 导出时还在等待回复的消息：恢复后标记为失败，可以点击重试；待投递的按已送达（导出时已经这样写，这里再兜一次） */
    private fun MessageDto.toEntity(contactId: Long, imagePath: String?): Message {
        val sending = status == MessageStatus.SENDING
        return Message(
            id = id, contactId = contactId, role = role, type = type, content = content, imagePath = imagePath,
            imageWidth = imageWidth, imageHeight = imageHeight, reasoning = reasoning, reasoningMillis = reasoningMillis,
            status = when (status) {
                MessageStatus.SENDING -> MessageStatus.FAILED
                MessageStatus.PENDING -> MessageStatus.SUCCESS
                else -> status
            },
            errorCode = if (sending) FailReason.INTERRUPTED.code else errorCode,
            model = model, promptTokens = promptTokens, completionTokens = completionTokens,
            replyGroup = replyGroup, createdAt = createdAt, origin = origin,
        )
    }

    private fun ProviderConfig.toDto(models: List<ProviderModel>, apiKey: SecretDto?) = ProviderDto(
        id = id, kind = kind, provider = provider, name = name, baseUrl = baseUrl, defaultModel = defaultModel,
        enabled = enabled, presetId = presetId, compatibility = compatibility,
        models = models.map {
            ProviderModelDto(
                it.modelId, it.displayName, it.imageInput, it.imageInputUserMark, it.sortOrder,
                it.thinkingInput, it.thinkingUserMark, it.manual, it.legacy, it.reportedConfig, it.userConfig, it.compatibilityOverride, it.missingFromList,
            )
        },
        apiKey = apiKey,
    )

    private fun ProviderDto.toEntity(id: Long, apiKey: String?) = ProviderConfig(
        id = id, kind = kind, provider = provider, name = name, baseUrl = baseUrl, apiKeyEncrypted = apiKey,
        defaultModel = defaultModel, enabled = enabled, presetId = presetId, compatibility = compatibility,
    )

    private fun ProviderModelDto.toEntity(providerId: Long) = ProviderModel(
        providerId = providerId, modelId = modelId, displayName = displayName, imageInput = imageInput,
        imageInputUserMark = imageInputUserMark, sortOrder = sortOrder, thinkingInput = thinkingInput,
        thinkingUserMark = thinkingUserMark, manual = manual, legacy = legacy, reportedConfig = reportedConfig,
        userConfig = userConfig, compatibilityOverride = compatibilityOverride, missingFromList = missingFromList,
    )

    private fun AppSettingsSnapshot.toDto(avatar: String?) = SettingsDto(
        themeMode = themeMode.name, fontSize = fontSize.name, nickname = nickname, avatar = avatar,
        notifyEnabled = notifyEnabled, notifyPreview = notifyPreview, autoCheckUpdate = autoCheckUpdate,
        replyPreferences = replyPreferences.toDto(),
        proactive = proactive.toDto(),
    )

    /** 读取时限制大小，防止异常文件把内存或存储撑满 */
    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        private fun count(n: Long) {
            count += n
            if (count > limit) throw InvalidBackupException("备份里的文件过大")
        }
    }

    /** 写 data.json 时不让序列化库关掉外面的 zip 流 */
    private class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)

        override fun close() = flush()
    }

    companion object {
        const val SNAPSHOT_NAME = "before-restore.zip"
        private const val MAX_MANIFEST_BYTES = 1L shl 20
        private const val MAX_DATA_BYTES = 512L shl 20
        private const val MAX_MEDIA_BYTES = 64L shl 20
    }
}
