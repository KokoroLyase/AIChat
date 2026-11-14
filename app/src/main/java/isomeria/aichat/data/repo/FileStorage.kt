package isomeria.aichat.data.repo

import android.content.ContentValues
import android.content.Context
import androidx.core.content.edit
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

/** 复制进来的聊天文件（0.9.2）：本机路径、显示的文件名、大小和系统给的类型 */
data class StoredFile(val path: String, val name: String, val size: Long, val mime: String?)

/** 选的文件超过 [FileStorage.MAX_CHAT_FILE_BYTES]（0.9.2） */
class FileTooLargeException : Exception("FileTooLarge")

enum class MediaKind { AVATAR, IMAGE, BACKGROUND, VOICE, CALL, FAVORITE, FILE, PARSED }

/** 聊天背景文件；没设置或文件已丢失时返回 null，界面回退到默认背景。 */
fun chatBackgroundFile(path: String?): File? = path?.let(::File)?.takeIf { it.isFile }

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
    private val backgroundsDir get() = File(context.filesDir, BACKGROUND_DIR).apply { mkdirs() }
    private val cameraDir get() = File(context.cacheDir, "camera").apply { mkdirs() }
    /** 语音消息（0.7.0）：用户的录音和联系人的语音回复 */
    private val voiceDir get() = File(context.filesDir, VOICE_DIR).apply { mkdirs() }
    /** 语音通话（0.8.0）：通话里每一条话的音频 */
    private val callsDir get() = File(context.filesDir, CALLS_DIR).apply { mkdirs() }
    /** 收藏（0.9.0）：收藏语音和通话时复制一份音频到这里，删掉原消息也还能播放 */
    private val favoritesDir get() = File(context.filesDir, FAVORITES_DIR).apply { mkdirs() }
    /** 聊天里发的文件（0.9.2）：用户选的文件复制一份到这里，保留扩展名 */
    private val chatFilesDir get() = File(context.filesDir, CHAT_FILES_DIR).apply { mkdirs() }
    /** 文件和图片的解析结果（0.9.2）：MinerU 返回的 Markdown */
    private val parsedDir get() = File(context.filesDir, PARSED_DIR).apply { mkdirs() }
    /** 录音转文字（麦克风按钮）的临时录音，识别完就删；不是语音消息 */
    val voiceTempDir: File get() = File(context.cacheDir, VOICE_TEMP_DIR).apply { mkdirs() }

    /**
     * 新的语音文件，文件名随机。写库前先固定，免得清理孤立文件时被当成没人用的文件删掉；
     * 写库后调用 [commitAvatar] 解除，失败时调用 [delete]
     */
    fun newVoiceFile(extension: String): File {
        val file = File(voiceDir, "${UUID.randomUUID()}.${extension.lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }}")
        StorageOperations.pin(file)
        return file
    }

    /**
     * 通话里一条话的音频文件（0.8.0），文件名随机。和 [newVoiceFile] 一样先固定，写库后调用 [commitAvatar] 解除，失败时调用 [delete]
     */
    fun newCallFile(extension: String): File {
        val file = File(callsDir, "${UUID.randomUUID()}.${extension.lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }}")
        StorageOperations.pin(file)
        return file
    }

    /**
     * 收藏时把音频复制一份到 files/favorites（0.9.0），文件名随机。原文件不在或不在应用私有目录里时返回 null。
     * 和 [newVoiceFile] 一样先固定，写库后调用 [commitAvatar] 解除，失败时调用 [delete]
     */
    fun copyToFavorites(source: String?): String? {
        val from = source?.let(::File)?.takeIf { it.isFile && isPrivate(it) } ?: return null
        val extension = from.extension.lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        val file = File(favoritesDir, "${UUID.randomUUID()}.$extension")
        StorageOperations.pin(file)
        try {
            from.copyTo(file)
        } catch (e: Throwable) {
            file.delete()
            StorageOperations.unpin(file)
            throw e
        }
        return file.absolutePath
    }

    /**
     * 把用户选的文件复制到 files/chat_files（0.9.2），文件名随机、保留扩展名（只留字母和数字）。
     * 超过 [MAX_CHAT_FILE_BYTES] 时抛 [FileTooLargeException]，读不了时抛 IOException；复制失败时不留半个文件。
     * 和 [newVoiceFile] 一样先固定，写库后调用 [commitAvatar] 解除，失败时调用 [delete]
     */
    suspend fun importChatFile(uri: Uri): StoredFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = ""
        var declared = -1L
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0).orEmpty()
                if (!cursor.isNull(1)) declared = cursor.getLong(1)
            }
        }
        if (declared > MAX_CHAT_FILE_BYTES) throw FileTooLargeException()
        val mime = resolver.getType(uri)
        val file = File(chatFilesDir, "${UUID.randomUUID()}.${chatFileExtension(name, mime)}")
        StorageOperations.pin(file)
        try {
            val input = resolver.openInputStream(uri) ?: throw java.io.IOException("Unreadable")
            input.use { source ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_CHAT_FILE_BYTES) throw FileTooLargeException()
                        out.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Throwable) {
            file.delete()
            StorageOperations.unpin(file)
            throw e
        }
        StoredFile(file.absolutePath, displayName(name, file), file.length(), mime)
    }

    /** 解析结果（0.9.2）写到 files/parsed，返回路径。和 [newVoiceFile] 一样先固定，写库后调用 [commitAvatar] 解除 */
    fun writeParsed(text: String): String {
        val file = File(parsedDir, "${UUID.randomUUID()}.md")
        StorageOperations.pin(file)
        try {
            file.writeText(text)
        } catch (e: Throwable) {
            file.delete()
            StorageOperations.unpin(file)
            throw e
        }
        return file.absolutePath
    }

    /** 读解析结果（0.9.2）；文件不在时返回 null */
    fun readParsed(path: String): String? = runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()

    /** 打开聊天文件（0.9.2）用的 content:// 地址；文件不在时返回 null */
    fun chatFileUri(path: String?): Uri? {
        val file = path?.let(::File)?.takeIf { it.isFile && isPrivate(it) } ?: return null
        return runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }.getOrNull()
    }

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

    /**
     * 聊天背景：和聊天图片同一套压缩（长边 2048、JPEG 85%、不写 EXIF），存到 files/background。
     * 写库前先固定，免得清理孤立文件时被当成没人用的文件删掉；写库后调用 [commitAvatar] 解除。
     */
    suspend fun importBackground(uri: Uri): StoredImage = withContext(Dispatchers.IO) { saveBackground(decode(uri, MAX_IMAGE_SIDE)) }

    /** 解码后的背景写成 JPEG；单元测试里系统解码器不可用，直接从这里进 */
    internal fun saveBackground(bitmap: Bitmap): StoredImage {
        val file = File(backgroundsDir, "${UUID.randomUUID()}.jpg")
        StorageOperations.pin(file)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } catch (e: Throwable) {
            file.delete()
            StorageOperations.unpin(file)
            throw e
        }
        val result = StoredImage(file.absolutePath, bitmap.width, bitmap.height)
        bitmap.recycle()
        return result
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
    fun newMediaFile(kind: MediaKind, extension: String = "jpg"): File {
        val dir = when (kind) {
            MediaKind.AVATAR -> avatarsDir
            MediaKind.IMAGE -> imagesDir
            MediaKind.BACKGROUND -> backgroundsDir
            MediaKind.VOICE -> voiceDir
            MediaKind.CALL -> callsDir
            MediaKind.FAVORITE -> favoritesDir
            MediaKind.FILE -> chatFilesDir
            MediaKind.PARSED -> parsedDir
        }
        return File(dir, "${UUID.randomUUID()}.$extension")
    }

    /** 删除头像、图片、聊天背景、语音、通话（0.8.0）、收藏（0.9.0）、文件和解析结果（0.9.2）目录里已经没有任何记录引用的文件（恢复数据后清理） */
    fun unreferenced(referenced: Set<String>): List<File> {
        val keep = referenced.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet() + StorageOperations.pinnedPaths()
        return listOf(avatarsDir, imagesDir, backgroundsDir, voiceDir, callsDir, favoritesDir, chatFilesDir, parsedDir).flatMap { dir ->
            check(dir.isDirectory && !java.nio.file.Files.isSymbolicLink(dir.toPath()))
            (dir.listFiles() ?: throw java.io.IOException("MediaDirectoryUnavailable"))
                .filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) && isPrivate(it) && it.canonicalPath !in keep }
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
        for (name in listOf("camera", "updates", "restore", isomeria.aichat.data.logs.CrashLogs.EXPORT_DIRECTORY, VOICE_TEMP_DIR)) {
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

    companion object {
        /**
         * 0.3.1 及更早版本恢复成功后不删快照，一份完整的明文副本会一直留在手机上。
         * 只在升级后第一次启动时清理一次；之后恢复失败留下的快照不受影响。
         */
        fun clearLegacySnapshotOnce(context: Context) {
            val prefs = context.getSharedPreferences("storage-cleanup", Context.MODE_PRIVATE)
            if (prefs.getBoolean("legacySnapshotCleared", false)) return
            val backups = File(context.filesDir, "backups")
            val cleared = listOf("before-restore.zip", "before-restore.zip.tmp").map { File(backups, it) }.all { !it.exists() || it.delete() }
            if (cleared) prefs.edit(commit = true) { putBoolean("legacySnapshotCleared", true) }
        }

        /** Main-process startup only, before any restore screen or worker can start. */
        fun clearStaleRestoreCache(context: Context) {
            val root = File(context.cacheDir, "restore")
            root.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
        }
        const val BACKGROUND_DIR = "background"
        const val VOICE_DIR = "voice"
        const val CALLS_DIR = "calls"
        const val FAVORITES_DIR = "favorites"
        const val CHAT_FILES_DIR = "chat_files"
        const val PARSED_DIR = "parsed"

        /** 聊天里能发的文件最大 200 MB（MinerU 的上限） */
        const val MAX_CHAT_FILE_BYTES = 200L shl 20

        /** 文件名的扩展名（只留字母和数字，最多 5 个字）；没有时按类型猜，再没有就是 bin */
        internal fun chatFileExtension(name: String, mime: String?): String {
            val fromName = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(5)
            if (fromName.isNotEmpty() && name.contains('.')) return fromName
            val fromMime = mime?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?.lowercase()?.filter { it.isLetterOrDigit() }?.take(5)
            return fromMime?.ifEmpty { null } ?: "bin"
        }

        /** 显示的文件名：系统没给时用复制后的文件名；去掉换行这类控制字符，最多 [MAX_FILE_NAME] 个字 */
        internal fun displayName(name: String, file: File): String =
            name.filterNot { it.isISOControl() }.trim().take(MAX_FILE_NAME).ifEmpty { file.name }

        const val MAX_FILE_NAME = 120
        const val VOICE_TEMP_DIR = "voice-temp"
        private const val MAX_IMAGE_SIDE = 2048
        private const val MAX_CROP_SOURCE_SIDE = 2048
        private const val JPEG_QUALITY = 85
    }
}
