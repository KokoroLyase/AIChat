package isomeria.aichat.data.repo

import coil3.disk.DiskCache
import java.io.File
import okio.Path.Companion.toOkioPath
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheUsageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun clearingRealCoilCacheReportsZeroDespiteItsRetainedJournal() {
        val root = temporary.newFolder("cache")
        val directory = File(root, "coil3_disk_cache")
        val disk = DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(1_000_000).build()
        try {
            disk.clear()
            assertTrue("Coil retains housekeeping files", directory.walkTopDown().any { it.isFile && it.length() > 0 })
            assertEquals(0L, disk.size)
            assertEquals(0L, cacheContentBytes(root, directory, disk.size))
            val other = File(root, "updates/fixture.apk").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(137)) }
            assertEquals(137L, cacheContentBytes(root, directory, disk.size))
            assertTrue(other.exists())
        } finally { disk.shutdown() }
    }

    @Test fun populatedImageEntriesAndOtherCacheAreBothCounted() {
        val root = temporary.newFolder("cache")
        val directory = File(root, "coil3_disk_cache")
        val disk = DiskCache.Builder().directory(directory.toOkioPath()).maxSizeBytes(1_000_000).build()
        try {
            val edit = requireNotNull(disk.openEditor("fixture"))
            edit.metadata.toFile().writeBytes(ByteArray(7))
            edit.data.toFile().writeBytes(ByteArray(128))
            edit.commit()
            File(root, "camera.jpg").writeBytes(ByteArray(13))
            assertEquals(135L, disk.size)
            assertEquals(148L, cacheContentBytes(root, directory, disk.size))
        } finally { disk.shutdown() }
    }
}
