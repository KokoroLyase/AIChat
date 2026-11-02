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
        StorageUsage(
            size(File(context.filesDir, "images")), size(File(context.filesDir, "avatars")),
            size(File(context.filesDir, "logs")), size(context.cacheDir),
            size(requireNotNull(context.getDatabasePath(AppDatabase.NAME).parentFile)),
        )
    }

    private suspend fun checkMediaIdle() {
        if (db.restoreInProgress.get() > 0 || db.messageDao().hasAnySending()) throw StorageBusyException()
    }

    private suspend fun references(): Set<String> = mediaReferences(
        db.contactDao().avatarPaths(), db.messageDao().allImagePaths(), settings.avatarPath.value,
    )

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
