package isomeria.aichat.data.voice

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Locale

/** 0.7.1：按块读 WAV 文件头（系统语音合成的 WAV 不一定是 16 kHz、44 字节头），以及哪些系统音色能用。 */
class WavFormatTest {
    private fun wav(rate: Int, channels: Int, bits: Int, pcmBytes: Int, dataSizeField: Long = pcmBytes.toLong(), extraChunk: Boolean = false, format: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Long) = repeat(4) { out.write(((v shr (8 * it)) and 0xFF).toInt()) }
        fun short(v: Int) = repeat(2) { out.write((v shr (8 * it)) and 0xFF) }
        out.write("RIFF".toByteArray()); int(0); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); int(16); short(format); short(channels); int(rate.toLong()); int((rate * channels * bits / 8).toLong()); short(channels * bits / 8); short(bits)
        // 奇数长度的块后面要补一个字节
        if (extraChunk) { out.write("LIST".toByteArray()); int(5); out.write(byteArrayOf(1, 2, 3, 4, 5, 0)) }
        out.write("data".toByteArray()); int(dataSizeField)
        out.write(ByteArray(pcmBytes))
        return out.toByteArray()
    }

    @Test
    fun recordingHeader_isParsedAs16kMono() {
        val info = WavFormat.parse(WavFormat.wrap(ByteArray(32_000)))!!
        assertEquals(WavInfo(16_000, 1, 16, 44, 32_000), info)
        assertEquals(1_000L, info.durationMillis)
    }

    @Test
    fun otherRates_extraChunks_andOddPadding() {
        val info = WavFormat.parse(wav(24_000, 1, 16, 48_000, extraChunk = true))!!
        assertEquals(24_000, info.sampleRate)
        assertEquals("LIST 块（5 字节 + 1 字节对齐）之后", 44L + 14, info.dataOffset)
        assertEquals(1_000L, info.durationMillis)
        assertEquals(500L, WavFormat.parse(wav(22_050, 2, 16, 44_100))!!.durationMillis)
    }

    @Test
    fun dataSizeZeroOrTooBig_usesTheRestOfTheFile() {
        assertEquals(48_000L, WavFormat.parse(wav(24_000, 1, 16, 48_000, dataSizeField = 0))!!.dataBytes)
        assertEquals(48_000L, WavFormat.parse(wav(24_000, 1, 16, 48_000, dataSizeField = 0xFFFFFFFFL))!!.dataBytes)
    }

    /** 1.0.0：DeepSeek 边合成边返回，RIFF 和 data 的长度都是 0xFFFFFFFF；系统播放器不认，试听前按实际长度改好 */
    @Test
    fun fixLengths_replacesStreamingPlaceholders_keepsPcm() {
        fun u32(b: ByteArray, at: Int) = (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }
        val streamed = wav(24_000, 1, 16, 48_000, dataSizeField = 0xFFFFFFFFL).also {
            for (i in 4 until 8) it[i] = 0xFF.toByte()
        }
        val fixed = WavFormat.fixLengths(streamed)
        assertEquals(streamed.size, fixed.size)
        assertEquals("RIFF 长度 = 文件长度 - 8", streamed.size - 8L, u32(fixed, 4))
        assertEquals("data 长度 = 实际的 PCM", 48_000L, u32(fixed, 40))
        assertTrue("除了这两处，内容不变", (streamed.indices - (4 until 8) - (40 until 44)).all { streamed[it] == fixed[it] })
        assertEquals(WavFormat.parse(streamed), WavFormat.parse(fixed))
        // 有别的块时改的是 data 块自己的长度
        val withList = wav(24_000, 1, 16, 4_800, dataSizeField = 0, extraChunk = true)
        assertEquals(4_800L, u32(WavFormat.fixLengths(withList), 44 + 14 - 4))
        assertEquals(withList.size - 8L, u32(WavFormat.fixLengths(withList), 4))
    }

    @Test
    fun fixLengths_leavesCorrectOrUnknownFilesAlone() {
        val good = WavFormat.wrap(ByteArray(3_200))
        assertTrue("长度本来就对的原样返回", WavFormat.fixLengths(good) === good)
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0)
        assertTrue(WavFormat.fixLengths(mp3) === mp3)
        val float = wav(16_000, 1, 32, 100, format = 3)
        assertTrue("不是 PCM 的不动", WavFormat.fixLengths(float) === float)
    }

    @Test
    fun notPcmOrBroken_isNull() {
        assertNull("不是 PCM（3 = 浮点）", WavFormat.parse(wav(16_000, 1, 32, 100, format = 3)))
        assertNull(WavFormat.parse("RIFF0000WAVE".toByteArray()))
        assertNull(WavFormat.parse(byteArrayOf(1, 2, 3)))
        assertNull("没有 fmt 块就到 data", WavFormat.parse("RIFF0000WAVE".toByteArray() + "data".toByteArray() + ByteArray(8)))
    }

    @Test
    fun systemVoices_onlyChineseOfflineInstalled() {
        assertTrue(SystemTtsRules.usable(Locale.SIMPLIFIED_CHINESE, network = false, features = emptySet()))
        assertTrue("粤语", SystemTtsRules.usable(Locale.forLanguageTag("yue-HK"), false, emptySet()))
        assertFalse("要联网的会把文字发出手机", SystemTtsRules.usable(Locale.SIMPLIFIED_CHINESE, network = true, features = emptySet()))
        assertFalse("还没下载", SystemTtsRules.usable(Locale.SIMPLIFIED_CHINESE, false, setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
        assertFalse(SystemTtsRules.usable(Locale.US, false, emptySet()))
        assertFalse(SystemTtsRules.usable(null, false, emptySet()))
    }
}
