package isomeria.aichat.testtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 0.9.0 测试工具的预置音频导入：只收 16 kHz 单声道 16 位 WAV，复制到私有目录，也能删掉回到麦克风 */
class CallAudioFixtureTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun wav(rate: Int = 16_000, channels: Int = 1, bits: Int = 16, dataBytes: Int = 320): ByteArray {
        val buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        buffer.put("data".toByteArray()).putInt(dataBytes)
        return buffer.array()
    }

    @Test
    fun onlySixteenKhzMonoSixteenBit() {
        assertTrue(CallAudioFixture.isSupported(wav()))
        assertFalse(CallAudioFixture.isSupported(wav(rate = 44_100)))
        assertFalse(CallAudioFixture.isSupported(wav(channels = 2)))
        assertFalse(CallAudioFixture.isSupported(wav(bits = 8)))
        assertFalse(CallAudioFixture.isSupported(ByteArray(10)))
        assertFalse(CallAudioFixture.isSupported(ByteArray(44)))
    }

    @Test
    fun import_copies_rejectsBadFiles_removeDeletes() {
        val external = temporary.newFolder("external")
        val files = temporary.newFolder("files")
        val target = File(files, "test-voice-input.wav")
        val source = File(external, "test-voice-input.wav")
        assertEquals("missing=test-voice-input.wav", CallAudioFixture.import(source, target, remove = false))
        source.writeBytes(wav(rate = 8_000))
        assertTrue(CallAudioFixture.import(source, target, remove = false).startsWith("unsupported"))
        assertFalse(target.exists())
        source.writeBytes(wav())
        assertEquals("imported=364 bytes", CallAudioFixture.import(source, target, remove = false))
        assertTrue(wav().contentEquals(target.readBytes()))
        assertFalse(File(files, "test-voice-input.wav.tmp").exists())
        assertEquals("removed=true", CallAudioFixture.import(source, target, remove = true))
        assertFalse(target.exists())
    }
}
