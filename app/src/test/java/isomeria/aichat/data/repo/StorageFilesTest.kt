package isomeria.aichat.data.repo

import android.app.Application
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StorageFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var filesDir: File
    private lateinit var cacheDir: File
    private lateinit var storage: FileStorage
    @Before fun setup() {
        filesDir = temporary.newFolder("files"); cacheDir = temporary.newFolder("cache")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = this@StorageFilesTest.filesDir
            override fun getCacheDir() = this@StorageFilesTest.cacheDir
        }
        storage = FileStorage(context)
    }
    private fun file(root: File, name: String): File = File(root, name).apply { parentFile!!.mkdirs(); writeText("fixture") }
    @Test fun referencesIncludeOwnMmkvAvatarAndPendingEditorFile() {
        val own = file(filesDir, "avatars/own.jpg")
        val contact = file(filesDir, "avatars/contact.jpg")
        val image = file(filesDir, "images/used.jpg")
        val orphan = file(filesDir, "images/orphan.jpg")
        val editor = storage.newMediaFile(MediaKind.AVATAR).apply { writeText("pending"); StorageOperations.pin(this) }
        val refs = mediaReferences(listOf(contact.path), listOf(image.path), own.path)
        assertEquals(listOf(orphan.canonicalPath), storage.unreferenced(refs).map { it.canonicalPath })
        storage.deleteUnreferenced(refs)
        assertTrue(own.exists()); assertTrue(contact.exists()); assertTrue(image.exists()); assertTrue(editor.exists())
        assertFalse(orphan.exists())
        storage.delete(editor.path)
    }
    @Test fun confirmationRescansReferencesAndOnlyDeletesReviewedUnchangedFiles() {
        val adopted = file(filesDir, "images/adopted.jpg")
        val orphan = file(filesDir, "images/orphan.jpg")
        val changed = file(filesDir, "images/changed.jpg")
        val preview = storage.unreferenced(emptySet()).map { OrphanFile(it.canonicalPath, it.length(), it.lastModified()) }
        val createdLater = file(filesDir, "images/later.jpg")
        changed.appendText("changed")
        val result = storage.deleteUnreferenced(setOf(adopted.path), preview)
        assertEquals(1, result.count)
        assertFalse(orphan.exists())
        assertTrue(adopted.exists()); assertTrue(changed.exists()); assertTrue(createdLater.exists())
    }
    @Test fun cacheCleanupPreservesAllMediaDatabaseLogsAndPinnedRestore() {
        val image = file(filesDir, "images/used.jpg")
        val avatar = file(filesDir, "avatars/own.jpg")
        val log = file(filesDir, "logs/fixture.txt")
        val snapshot = file(filesDir, "backups/before-restore.zip")
        val pinned = file(cacheDir, "restore/selected.zip").also(StorageOperations::pin)
        val camera = file(cacheDir, "camera/stale.jpg")
        val update = file(cacheDir, "updates/stale.apk")
        val restore = file(cacheDir, "restore/stale.zip")
        val export = file(cacheDir, "crash-exports/crash.txt")
        val other = file(cacheDir, "unrelated/keep")
        val result = storage.clearRegenerableCacheDirectories()
        assertEquals(4, result.count)
        listOf(image, avatar, log, snapshot, pinned, other).forEach { assertTrue(it.exists()) }
        listOf(camera, update, restore, export).forEach { assertFalse(it.exists()) }
        StorageOperations.unpin(pinned)
    }
    @Test fun privateDeletionCannotRemoveSiblingDirectoryFiles() {
        val outside = file(temporary.root, "files-other/keep.jpg")
        storage.delete(outside.path)
        assertTrue(outside.exists())
    }
}
