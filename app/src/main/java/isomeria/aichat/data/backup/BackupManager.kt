package isomeria.aichat.data.backup

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.BuildConfig
import isomeria.aichat.R
import isomeria.aichat.data.crypto.SecretCipher
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Conversation
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageKey
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderConfig
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.ProviderModel
import isomeria.aichat.data.reply.ReplyControl
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.MediaKind
import isomeria.aichat.data.settings.FontSize
import isomeria.aichat.data.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
) {
    private val apiKey: SecretDto? get() = data.providers.firstOrNull { it.kind == ProviderKind.LLM }?.apiKey

    /** 备份里有用密码加密的 API Key，恢复它需要输入导出时设的密码 */
    val needsPassword: Boolean get() = apiKey?.scheme == SecretDto.SCHEME_PASSWORD

    val contactCount: Int get() = data.contacts.size
    val messageCount: Int get() = data.messages.size
    val imageCount: Int get() = data.messages.count { it.type == MessageType.IMAGE }
}

data class ExportSummary(val contacts: Int, val messages: Int, val images: Int, val includesApiKey: Boolean)

data class RestoreSummary(val contactsAdded: Int, val messagesAdded: Int, val apiKeyRestored: Boolean)

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
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: FileStorage,
    private val cipher: SecretCipher,
    private val settings: BackupSettingsAccess,
    private val replies: ReplyControl,
) {
    private val json = BackupFormat.json
    private val migrations = BackupMigrations()
    private val mutex = Mutex()

    private val contactDao get() = db.contactDao()
    private val conversationDao get() = db.conversationDao()
    private val messageDao get() = db.messageDao()
    private val providerDao get() = db.providerDao()

    /** 恢复前自动备份的位置 */
    val snapshotFile: File get() = File(files.backupsDir, SNAPSHOT_NAME)

    /**
     * 导出到 [out]（写完后关闭）。[apiKeyPassword] 不为 null 时包含 API Key，用这个密码加密。
     */
    suspend fun export(out: OutputStream, apiKeyPassword: CharArray?): ExportSummary = mutex.withLock {
        withContext(Dispatchers.IO) {
            writeBackup(out, if (apiKeyPassword != null) KeyExport.Password(apiKeyPassword) else KeyExport.None)
        }
    }

    /** 读取并校验备份文件；见类注释第 1 步。 */
    suspend fun inspect(file: File): BackupPreview = withContext(Dispatchers.IO) { readBackup(file) }

    /**
     * 恢复。备份里有加密的 API Key 但 [password] 为空时，跳过 Key、照常恢复其他数据；
     * 密码错误时抛 [WrongPasswordException]，本机数据不变。
     */
    suspend fun restore(preview: BackupPreview, mode: RestoreMode, password: CharArray?): RestoreSummary =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val apiKey = importedApiKey(preview, password)
                writeSnapshot()
                if (mode == RestoreMode.OVERWRITE) stopAllReplies()
                apply(preview, mode, apiKey, exactKey = false, rollback = snapshotFile)
            }
        }

    // ---------- 导出 ----------

    private data class Tables(
        val contacts: List<Contact>,
        val conversations: List<Conversation>,
        val messages: List<Message>,
        val configs: List<ProviderConfig>,
        val models: Map<Long, List<ProviderModel>>,
    )

    private sealed interface KeyExport {
        data object None : KeyExport
        class Password(val password: CharArray) : KeyExport
        /** 本机自动备份：直接存 Keystore 密文 */
        data object Keystore : KeyExport
    }

    private suspend fun writeBackup(out: OutputStream, key: KeyExport): ExportSummary {
        val (contacts, conversations, messages, configs, models) = db.withTransaction {
            val configs = providerDao.listConfigs()
            Tables(
                contactDao.listAll(),
                conversationDao.listAll(),
                messageDao.listAll(),
                configs,
                configs.associate { it.id to providerDao.models(it.id) },
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
            conversations = conversations.map { it.toDto() },
            messages = messages.map { it.toDto(media.add(it.imagePath, BackupFormat.IMAGES_DIR)) },
            providers = providers,
            settings = appSettings.toDto(media.add(appSettings.avatarPath, BackupFormat.AVATARS_DIR)),
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
        val contacts = data.contacts.distinctBy { it.id }
            .map { it.copy(avatar = it.avatar?.takeIf(BackupFormat.mediaNamePattern::matches)) }
        val ids = contacts.mapTo(HashSet()) { it.id }
        return data.copy(
            contacts = contacts,
            conversations = data.conversations.filter { it.contactId in ids }.distinctBy { it.contactId },
            messages = data.messages.filter { it.contactId in ids }.distinctBy { it.id }
                .map { it.copy(image = it.image?.takeIf(BackupFormat.mediaNamePattern::matches)) },
            settings = data.settings.copy(avatar = data.settings.avatar?.takeIf(BackupFormat.mediaNamePattern::matches)),
        )
    }

    // ---------- 恢复 ----------

    /** 要写进数据库的 API Key（Keystore 密文）；备份里没有或没给密码时为 null */
    private fun importedApiKey(preview: BackupPreview, password: CharArray?): String? {
        val secret = preview.data.providers.firstOrNull { it.kind == ProviderKind.LLM }?.apiKey ?: return null
        return when (secret.scheme) {
            SecretDto.SCHEME_PASSWORD ->
                if (password == null || password.isEmpty()) null else cipher.encrypt(PasswordCipher.decrypt(secret, password))
            // 只有在同一台设备上才能解开
            SecretDto.SCHEME_KEYSTORE -> secret.data.takeIf { cipher.decrypt(it) != null }
            else -> null
        }
    }

    /** 覆盖前停掉所有等待中的回复，最多等 3 秒，免得旧任务把回复写进新数据 */
    private suspend fun stopAllReplies() {
        val ids = contactDao.listAll().map { it.id }
        ids.forEach(replies::cancel)
        withTimeoutOrNull(3_000) {
            while (ids.any { replies.isActive(it) }) delay(100)
        }
    }

    /**
     * [exactKey] 为 true 时（用自动备份回滚）API Key 也恢复成备份里的样子，备份里没有就清掉；
     * 否则备份里没有 Key 时保留本机的。
     */
    private suspend fun apply(
        preview: BackupPreview,
        mode: RestoreMode,
        apiKey: String?,
        exactKey: Boolean,
        rollback: File?,
    ): RestoreSummary {
        val created = ArrayList<File>()
        try {
            val paths = ZipFile(preview.file).use { zip -> extractMedia(zip, preview.data, created) }
            val summary = writeDatabase(preview.data, mode, paths, apiKey, exactKey)
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
        }
    }

    private suspend fun rollbackFrom(snapshot: File) {
        val preview = readBackup(snapshot)
        apply(preview, RestoreMode.OVERWRITE, importedApiKey(preview, null), exactKey = true, rollback = null)
    }

    /** 解压备份里用到的头像和图片，换成随机的新文件名；返回 zip 内路径 → 本机路径 */
    private fun extractMedia(zip: ZipFile, data: BackupData, created: MutableList<File>): Map<String, String> {
        val names = LinkedHashSet<String>()
        data.contacts.mapNotNullTo(names) { it.avatar }
        data.messages.mapNotNullTo(names) { it.image }
        data.settings.avatar?.let(names::add)
        val result = HashMap<String, String>()
        for (name in names) {
            val entry = zip.getEntry(name) ?: continue
            val kind = if (name.startsWith(BackupFormat.AVATARS_DIR)) MediaKind.AVATAR else MediaKind.IMAGE
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
        apiKey: String?,
        exactKey: Boolean,
    ): RestoreSummary = db.withTransaction {
        val llm = providerDao.getLlm() ?: error("缺少默认服务商配置")
        val source = data.providers.firstOrNull { it.kind == ProviderKind.LLM }
        when (mode) {
            RestoreMode.OVERWRITE -> {
                messageDao.deleteAll()
                conversationDao.deleteAll()
                contactDao.deleteAll()

                var config = llm
                if (source != null) {
                    config = config.copy(
                        baseUrl = source.baseUrl.ifBlank { llm.baseUrl },
                        defaultModel = source.defaultModel.ifBlank { llm.defaultModel },
                        enabled = source.enabled,
                    )
                    providerDao.deleteModels(llm.id)
                    providerDao.upsertModels(source.models.map { it.toEntity(llm.id) })
                }
                // 备份里没有 Key 时保留本机的 Key：Key 不算聊天数据，导出默认也不带
                if (apiKey != null || exactKey) config = config.copy(apiKeyEncrypted = apiKey)
                providerDao.update(config)

                contactDao.insertAll(data.contacts.map { it.toEntity(llm.id, it.avatar?.let(paths::get)) })
                val withConversation = data.conversations.mapTo(HashSet()) { it.contactId }
                conversationDao.upsertAll(
                    data.conversations.map { it.toEntity() } +
                        data.contacts.filter { it.id !in withConversation }.map { Conversation(contactId = it.id) },
                )
                messageDao.insertAll(data.messages.map { it.toEntity(it.contactId, it.image?.let(paths::get)) })
                RestoreSummary(data.contacts.size, data.messages.size, apiKey != null)
            }
            RestoreMode.MERGE -> merge(data, paths, apiKey, llm, source)
        }
    }

    private suspend fun merge(
        data: BackupData,
        paths: Map<String, String>,
        apiKey: String?,
        llm: ProviderConfig,
        source: ProviderDto?,
    ): RestoreSummary {
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
                    contactDao.insert(contact.toEntity(llm.id, contact.avatar?.let(paths::get)))
                    idMap[contact.id] = contact.id
                    contactsAdded++
                }
                else -> needNewId += contact
            }
        }
        for (contact in needNewId) {
            idMap[contact.id] = contactDao.insert(contact.toEntity(llm.id, contact.avatar?.let(paths::get)).copy(id = 0))
            contactsAdded++
        }

        // 已有联系人的会话（置顶、草稿、未读）保留本机的
        val localConversations = conversationDao.listAll().mapTo(HashSet()) { it.contactId }
        for (conversation in data.conversations) {
            val target = idMap[conversation.contactId] ?: continue
            if (target in localConversations) continue
            conversationDao.upsert(conversation.toEntity().copy(contactId = target))
            localConversations += target
        }
        for (target in idMap.values) {
            if (target !in localConversations) conversationDao.upsert(Conversation(contactId = target))
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

        var config = llm
        var keyRestored = false
        if (config.apiKeyEncrypted.isNullOrEmpty() && apiKey != null) {
            config = config.copy(apiKeyEncrypted = apiKey)
            keyRestored = true
        }
        providerDao.update(config)
        if (source != null) {
            val known = providerDao.models(llm.id).mapTo(HashSet()) { it.modelId }
            providerDao.upsertModels(source.models.filter { it.modelId !in known }.map { it.toEntity(llm.id) })
        }
        return RestoreSummary(contactsAdded, newMessages.size, keyRestored)
    }

    private suspend fun refreshLastMessage(contactId: Long) {
        val latest = messageDao.latest(contactId) ?: return
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
        settings.read().avatarPath?.let(referenced::add)
        files.deleteUnreferenced(referenced)
    }

    // ---------- 数据库实体 ↔ 导出格式 ----------

    private fun Contact.toDto(avatar: String?) = ContactDto(
        id = id, name = name, avatar = avatar, avatarColor = avatarColor, providerId = providerId, model = model,
        thinking = thinking, temperature = temperature, systemPrompt = systemPrompt, contextCount = contextCount,
        splitReplies = splitReplies, createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun ContactDto.toEntity(providerId: Long, avatarPath: String?) = Contact(
        id = id, name = name, avatarPath = avatarPath, avatarColor = avatarColor, providerId = providerId, model = model,
        thinking = thinking, temperature = temperature, systemPrompt = systemPrompt, contextCount = contextCount,
        splitReplies = splitReplies, createdAt = createdAt, updatedAt = updatedAt,
    )

    private fun Conversation.toDto() = ConversationDto(
        contactId = contactId, pinned = pinned, unreadCount = unreadCount, markedUnread = markedUnread,
        draft = draft, lastMessageTime = lastMessageTime, lastMessagePreview = lastMessagePreview,
    )

    private fun ConversationDto.toEntity() = Conversation(
        contactId = contactId, pinned = pinned, unreadCount = unreadCount, markedUnread = markedUnread,
        draft = draft, lastMessageTime = lastMessageTime, lastMessagePreview = lastMessagePreview,
    )

    private fun Message.toDto(image: String?) = MessageDto(
        id = id, contactId = contactId, role = role, type = type, content = content, image = image,
        imageWidth = imageWidth, imageHeight = imageHeight, reasoning = reasoning, reasoningMillis = reasoningMillis,
        status = status, errorCode = errorCode, model = model, promptTokens = promptTokens,
        completionTokens = completionTokens, replyGroup = replyGroup, createdAt = createdAt,
    )

    /** 导出时还在等待回复的消息：恢复后标记为失败，可以点击重试 */
    private fun MessageDto.toEntity(contactId: Long, imagePath: String?): Message {
        val sending = status == MessageStatus.SENDING
        return Message(
            id = id, contactId = contactId, role = role, type = type, content = content, imagePath = imagePath,
            imageWidth = imageWidth, imageHeight = imageHeight, reasoning = reasoning, reasoningMillis = reasoningMillis,
            status = if (sending) MessageStatus.FAILED else status,
            errorCode = if (sending) FailReason.INTERRUPTED.code else errorCode,
            model = model, promptTokens = promptTokens, completionTokens = completionTokens,
            replyGroup = replyGroup, createdAt = createdAt,
        )
    }

    private fun ProviderConfig.toDto(models: List<ProviderModel>, apiKey: SecretDto?) = ProviderDto(
        id = id, kind = kind, provider = provider, baseUrl = baseUrl, defaultModel = defaultModel, enabled = enabled,
        models = models.map {
            ProviderModelDto(it.modelId, it.displayName, it.imageInput, it.imageInputUserMark, it.sortOrder)
        },
        apiKey = apiKey,
    )

    private fun ProviderModelDto.toEntity(providerId: Long) = ProviderModel(
        providerId = providerId, modelId = modelId, displayName = displayName, imageInput = imageInput,
        imageInputUserMark = imageInputUserMark, sortOrder = sortOrder,
    )

    private fun AppSettingsSnapshot.toDto(avatar: String?) = SettingsDto(
        themeMode = themeMode.name, fontSize = fontSize.name, nickname = nickname, avatar = avatar,
        notifyEnabled = notifyEnabled, notifyPreview = notifyPreview, autoCheckUpdate = autoCheckUpdate,
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
