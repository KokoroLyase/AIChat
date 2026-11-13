package isomeria.aichat.testtools

import java.io.File

/**
 * 测试工具“import_call_audio”（0.9.0）：把预置音频从 App 的外部私有目录（adb push 就能放进去，perf 包不能 run-as）
 * 复制进 files/（通话用 test-call-input.wav，按住说话用 test-voice-input.wav），代替麦克风。只在 debug、perf 包里有；只接受 16 kHz 单声道 16 位 PCM 的 WAV。
 */
object CallAudioFixture {
    /** 文件头是不是 16 kHz 单声道 16 位 PCM 的 WAV（标准 44 字节头） */
    fun isSupported(header: ByteArray): Boolean {
        if (header.size < 44) return false
        fun ascii(at: Int) = String(header, at, 4, Charsets.US_ASCII)
        fun u16(at: Int) = (header[at].toInt() and 0xFF) or ((header[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        return ascii(0) == "RIFF" && ascii(8) == "WAVE" && ascii(12) == "fmt " &&
            u16(20) == 1 && u16(22) == 1 && u32(24) == 16_000L && u16(34) == 16
    }

    /**
     * 复制 [source] 到 [target]（先写临时文件再改名）。[remove] 为 true 时删掉 [target]，回到用麦克风。
     * 返回结果说明（给测试工具显示，不含任何个人数据）
     */
    fun import(source: File, target: File, remove: Boolean): String {
        if (remove) return "removed=${!target.exists() || target.delete()}"
        if (!source.isFile) return "missing=${source.name}"
        val header = source.inputStream().use { input -> ByteArray(44).also { val n = input.read(it); if (n < 44) return "tooShort" } }
        if (!isSupported(header)) return "unsupported (need 16 kHz mono 16-bit PCM WAV)"
        val temp = File(target.parentFile, target.name + ".tmp")
        source.copyTo(temp, overwrite = true)
        if (!temp.renameTo(target)) {
            target.delete()
            if (!temp.renameTo(target)) { temp.delete(); return "renameFailed" }
        }
        return "imported=${target.length()} bytes"
    }
}
