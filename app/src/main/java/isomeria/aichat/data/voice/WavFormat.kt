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
    fun header(dataBytes: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(HEADER_BYTES)
        fun int(v: Long) = repeat(4) { out.write(((v shr (8 * it)) and 0xFF).toInt()) }
        fun short(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        int(36 + dataBytes)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        int(16)
        short(1) // PCM
        short(CHANNELS)
        int(SAMPLE_RATE.toLong())
        int(BYTES_PER_SECOND.toLong())
        short(CHANNELS * BITS / 8)
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
}
