package isomeria.aichat.data.voice

import java.io.File
import java.io.RandomAccessFile

/**
 * 录音用的 WAV：16 kHz、单声道、16 位小端 PCM，44 字节文件头。MiMo 识别只收 mp3、wav，安卓不能原生编码 mp3，
 * 所以上传统一用 WAV（60 秒约 1.9 MB）。纯 JVM，便于测试。
 */
object WavFormat {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS = 16
    const val HEADER_BYTES = 44
    const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BITS / 8

    /** 文件头；[dataBytes] 是后面 PCM 的字节数 */
    fun header(dataBytes: Long): ByteArray = header(dataBytes, SAMPLE_RATE, CHANNELS)

    /** 任意采样率和声道数的 16 位 PCM 文件头（0.7.2：OpenRouter 返回的 pcm、智谱识别的分段） */
    fun header(dataBytes: Long, sampleRate: Int, channels: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(HEADER_BYTES)
        fun int(v: Long) = repeat(4) { out.write(((v shr (8 * it)) and 0xFF).toInt()) }
        fun short(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        int(36 + dataBytes)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        int(16)
        short(1) // PCM
        short(channels)
        int(sampleRate.toLong())
        int(sampleRate.toLong() * channels * BITS / 8)
        short(channels * BITS / 8)
        short(BITS)
        out.write("data".toByteArray(Charsets.US_ASCII))
        int(dataBytes)
        return out.toByteArray()
    }

    fun wrap(pcm: ByteArray): ByteArray = header(pcm.size.toLong()) + pcm

    /** 录完后补上文件头里的长度（录音时先写了长度为 0 的头） */
    fun finish(file: File) {
        RandomAccessFile(file, "rw").use { raf ->
            val data = (raf.length() - HEADER_BYTES).coerceAtLeast(0)
            raf.seek(0)
            raf.write(header(data))
        }
    }

    /** 整个 WAV 文件（含文件头）有多长 */
    fun durationMillis(totalBytes: Long): Long = ((totalBytes - HEADER_BYTES).coerceAtLeast(0) * 1000) / BYTES_PER_SECOND

    fun isWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE"

    /**
     * 读文件头里的格式（0.7.1）：系统语音合成写出的 WAV 不一定是 16 kHz 单声道、文件头也不一定是 44 字节，
     * 按块查找 `fmt ` 和 `data`。只认 PCM（格式 1 或 0xFFFE）；读不出或不是 PCM 时为 null。
     * `data` 块的长度是 0 或超出文件时按文件实际剩下的长度算（有的引擎边写边出，不回填长度）
     */
    fun parse(file: File): WavInfo? = try {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(minOf(raf.length(), PARSE_LIMIT.toLong()).toInt())
            raf.readFully(head)
            parse(head, raf.length())
        }
    } catch (_: java.io.IOException) {
        null
    }

    /** 同 [parse]，[bytes] 是文件开头（至少包含 `data` 块的块头），[totalBytes] 是整个文件的长度 */
    fun parse(bytes: ByteArray, totalBytes: Long = bytes.size.toLong()): WavInfo? {
        if (!isWav(bytes)) return null
        fun int(at: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }
        fun short(at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        var format: IntArray? = null
        var at = 12
        while (at + 8 <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = int(at + 4)
            val body = at + 8
            when (id) {
                "fmt " -> {
                    if (size < 16 || body + 16 > bytes.size) return null
                    val audioFormat = short(body)
                    if (audioFormat != 1 && audioFormat != 0xFFFE) return null
                    format = intArrayOf(short(body + 2), int(body + 4).toInt(), short(body + 14))
                }
                "data" -> {
                    val f = format ?: return null
                    val remaining = (totalBytes - body).coerceAtLeast(0)
                    val data = if (size == 0L || size > remaining) remaining else size
                    val info = WavInfo(sampleRate = f[1], channels = f[0], bits = f[2], dataOffset = body.toLong(), dataBytes = data)
                    return info.takeIf { it.sampleRate in 1..384_000 && it.channels in 1..8 && it.bits in listOf(8, 16, 24, 32) }
                }
            }
            if (size > Int.MAX_VALUE / 2) return null
            // 块按偶数字节对齐
            at = body + size.toInt() + (size % 2).toInt()
        }
        return null
    }

    private const val PARSE_LIMIT = 64 * 1024
}

/** WAV 文件的格式和 PCM 数据的位置（[WavFormat.parse]）。 */
data class WavInfo(val sampleRate: Int, val channels: Int, val bits: Int, val dataOffset: Long, val dataBytes: Long) {
    val bytesPerSecond: Int get() = sampleRate * channels * bits / 8
    val durationMillis: Long get() = if (bytesPerSecond == 0) 0 else dataBytes * 1000 / bytesPerSecond
}
