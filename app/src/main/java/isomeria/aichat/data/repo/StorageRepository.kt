package isomeria.aichat.data.repo

import android.content.Context
import androidx.room.withTransaction
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.settings.SettingsStore
import isomeria.aichat.update.UpdateInstaller
import isomeria.aichat.update.UpdateInstallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StorageRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val files: FileStorage,
    private val settings: SettingsStore,
    private val updates: UpdateInstaller,
) {
    suspend fun usage(): StorageUsage = withContext(Dispatchers.IO) {
        fun size(root: File) = storageFiles(root).sumOf { it.length() }
        val diskCache = SingletonImageLoader.get(context).diskCache
        StorageUsage(
            size(File(context.filesDir, "images")), size(File(context.filesDir, "avatars")),
            size(File(context.filesDir, "logs")), cacheContentBytes(context.cacheDir, diskCache?.directory?.toFile(), diskCache?.size ?: 0L),
            size(requireNotNull(context.getDatabasePath(AppDatabase.NAME).parentFile)),
            backgrounds = size(File(context.filesDir, FileStorage.BACKGROUND_DIR)),
            voice = size(File(context.filesDir, FileStorage.VOICE_DIR)),
            // 0.8.0：通话里每一条话的音频
            calls = size(File(context.filesDir, FileStorage.CALLS_DIR)),
            // 0.7.1：离线识别模型（含没下完的部分）；不是可再生缓存，也不参与孤立文件清理，在「离线识别」页单独删除
            asrModels = size(File(context.filesDir, isomeria.aichat.data.voice.OfflineAsrModel.ROOT)),
        )
    }

    private suspend fun checkMediaIdle() {
        if (db.restoreInProgress.get() > 0 || db.messageDao().hasAnySending()) throw StorageBusyException()
    }

    private suspend fun references(): Set<String> = mediaReferencesOf(db, settings.avatarPath.value)

    suspend fun previewOrphans(): OrphanPreview = withContext(Dispatchers.IO) {
        StorageOperations.gate.clean {
            checkMediaIdle()
            db.withTransaction {
                OrphanPreview(files.unreferenced(references()).map { OrphanFile(it.canonicalPath, it.length(), it.lastModified()) })
            }
        }
    }

    suspend fun cleanOrphans(preview: OrphanPreview): CleanupResult = withContext(Dispatchers.IO) {
        StorageOperations.gate.clean {
            checkMediaIdle()
            db.withTransaction { files.deleteUnreferenced(references(), preview.files) }
        }
    }

    suspend fun cleanCache(): CleanupResult = withContext(Dispatchers.IO) {
        StorageOperations.gate.clean {
            if (db.restoreInProgress.get() > 0 || updates.state.value is UpdateInstallState.Downloading || updates.state.value is UpdateInstallState.Ready) throw StorageBusyException()
            val result = files.clearRegenerableCacheDirectories()
            val loader = SingletonImageLoader.get(context)
            val diskBytes = loader.diskCache?.size ?: 0
            val diskFiles = loader.diskCache?.directory?.toFile()?.let(::storageFiles)?.size ?: 0
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            val afterBytes = loader.diskCache?.size ?: 0
            val afterFiles = loader.diskCache?.directory?.toFile()?.let(::storageFiles)?.size ?: 0
            result.copy(count = result.count + (diskFiles - afterFiles).coerceAtLeast(0), bytes = result.bytes + (diskBytes - afterBytes).coerceAtLeast(0))
        }
    }
}

/** Coil keeps an empty journal after clear(); count its entries, not maintenance metadata. */
/** 孤立文件检查时算作“在用”的媒体文件：联系人头像、消息图片、自己的头像、聊天背景、语音和通话音频 */
internal suspend fun mediaReferencesOf(db: AppDatabase, ownAvatar: String?): Set<String> = mediaReferences(
    db.contactDao().avatarPaths(), db.messageDao().allImagePaths(), ownAvatar,
    db.conversationDao().backgroundPaths(), db.messageDao().allVoicePaths(),
)

internal fun cacheContentBytes(root: File, imageDirectory: File?, imageBytes: Long): Long {
    val rootPath = root.canonicalPath + File.separator
    val imagePath = imageDirectory?.canonicalPath?.takeIf { it.startsWith(rootPath) }?.plus(File.separator)
    return storageFiles(root).filterNot { imagePath != null && it.canonicalPath.startsWith(imagePath) }
        .sumOf { it.length() } + if (imagePath != null) imageBytes else 0L
}
