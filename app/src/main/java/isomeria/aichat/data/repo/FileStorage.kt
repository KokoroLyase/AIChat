package isomeria.aichat.data.repo

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

data class StoredImage(val path: String, val width: Int, val height: Int)

enum class MediaKind { AVATAR, IMAGE }

/**
 * 应用私有目录里的头像和聊天图片。卸载即清除。
 *
 * 聊天图片发送前压缩：长边不超过 2048 像素、JPEG 质量 85%。重新编码不写入任何 EXIF，
 * 所以原图里的定位等信息都会去掉；ImageDecoder 解码时已按 EXIF 方向旋正。
 */
@Singleton
class FileStorage @Inject constructor(@ApplicationContext private val context: Context) {
    private val imagesDir get() = File(context.filesDir, "images").apply { mkdirs() }
    private val avatarsDir get() = File(context.filesDir, "avatars").apply { mkdirs() }
    private val cameraDir get() = File(context.cacheDir, "camera").apply { mkdirs() }

    /** 恢复数据前自动备份当前数据的位置（应用私有目录） */
    val backupsDir: File get() = File(context.filesDir, "backups").apply { mkdirs() }

    /** 恢复时选中的备份文件先复制到这里再读取 */
    val restoreCacheDir: File get() = File(context.cacheDir, "restore").apply { mkdirs() }

    suspend fun importChatImage(uri: Uri): StoredImage = withContext(Dispatchers.IO) {
        val bitmap = decode(uri, MAX_IMAGE_SIDE)
        val file = File(imagesDir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        val result = StoredImage(file.absolutePath, bitmap.width, bitmap.height)
        bitmap.recycle()
        result
    }

    /** 读取用户选的图片，用于头像裁剪 */
    suspend fun decodeForCrop(uri: Uri): Bitmap = withContext(Dispatchers.IO) { decode(uri, MAX_CROP_SOURCE_SIDE) }

    suspend fun saveAvatar(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val file = File(avatarsDir, "${UUID.randomUUID()}.jpg")
        StorageOperations.pin(file)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        file.absolutePath
    }

    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        val file = File(path)
        // 只删应用私有目录里的文件
        if (isPrivate(file)) { file.delete(); StorageOperations.unpin(file) }
    }

    /** The DB/MMKV reference is now published, so the transient editor protection can end. */
    fun commitAvatar(path: String?) { path?.let { StorageOperations.unpin(File(it)) } }

    /** 是否在应用私有目录里（导出时只打包这里的文件） */
    fun isPrivate(file: File): Boolean =
        runCatching { file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator) }.getOrDefault(false)

    /** 恢复时放导入的头像或图片的新文件，文件名随机，不会和已有文件冲突 */
    fun newMediaFile(kind: MediaKind): File {
        val dir = when (kind) {
            MediaKind.AVATAR -> avatarsDir
            MediaKind.IMAGE -> imagesDir
        }
        return File(dir, "${UUID.randomUUID()}.jpg")
    }

    /** 删除头像和图片目录里已经没有任何记录引用的文件（恢复数据后清理） */
    fun unreferenced(referenced: Set<String>): List<File> {
        val keep = referenced.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet() + StorageOperations.pinnedPaths()
        return listOf(avatarsDir, imagesDir).flatMap { dir ->
            dir.listFiles().orEmpty().filter { it.isFile && isPrivate(it) && it.canonicalPath !in keep }
        }
    }

    fun deleteUnreferenced(referenced: Set<String>, reviewed: List<OrphanFile>? = null): CleanupResult {
        val reviewedByPath = reviewed?.associateBy { it.path }
        var count = 0
        var bytes = 0L
        for (file in unreferenced(referenced)) {
            val prior = reviewedByPath?.get(file.canonicalPath)
            if (reviewedByPath != null && (prior == null || prior.bytes != file.length() || prior.modified != file.lastModified())) continue
            val size = file.length()
            check(file.delete())
            count++
            bytes += size
        }
        return CleanupResult(count, bytes)
    }

    fun finishCamera() {
        for (file in cameraDir.listFiles().orEmpty()) StorageOperations.unpin(file)
    }

    fun clearRegenerableCacheDirectories(): CleanupResult {
        var count = 0
        var bytes = 0L
        val pinned = StorageOperations.pinnedPaths()
        for (name in listOf("camera", "updates", "restore")) {
            val dir = File(context.cacheDir, name)
            check(dir.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            for (file in storageFiles(dir)) {
                if (file.canonicalPath in pinned) continue
                val size = file.length()
                check(file.delete())
                count++
                bytes += size
            }
        }
        return CleanupResult(count, bytes)
    }

    /** 读成 data URL，放进请求 */
    fun readAsDataUrl(path: String): String? = runCatching {
        val bytes = File(path).readBytes()
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    /** 系统相机的输出位置 */
    fun newCameraUri(): Uri {
        cameraDir.listFiles()?.forEach { it.delete() }
        val file = File(cameraDir, "${UUID.randomUUID()}.jpg")
        StorageOperations.pin(file)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** 全屏查看时「保存到相册」。Android 10 起写入自己的图片不需要存储权限。 */
    suspend fun saveToGallery(path: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "AIChat_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AIChat")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { out -> File(path).inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = max(info.size.width, info.size.height)
            if (longSide > maxSide) {
                val scale = maxSide.toFloat() / longSide
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
        }
    }

    private companion object {
        const val MAX_IMAGE_SIDE = 2048
        const val MAX_CROP_SOURCE_SIDE = 2048
        const val JPEG_QUALITY = 85
    }
}
