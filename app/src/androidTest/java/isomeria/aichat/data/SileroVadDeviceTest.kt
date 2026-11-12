package isomeria.aichat.data

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import isomeria.aichat.data.voice.SileroSpeechActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.8.0：Silero VAD 的模型随安装包放在 assets 里，在 64 位 ARM 的手机上用 sherpa-onnx 加载得起来；
 * 静音不算说话（真正的人声要用预置音频在通话里验证）。
 */
@RunWith(AndroidJUnit4::class)
class SileroVadDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun modelIsBundled() {
        val bytes = context.assets.open(SileroSpeechActivity.MODEL_ASSET).use { it.readBytes() }
        assertEquals(643_854, bytes.size)
    }

    @Test
    fun loadsOnArm64_silenceIsNotSpeech() {
        assumeTrue("arm64-v8a" in Build.SUPPORTED_ABIS)
        val vad = SileroSpeechActivity(context.assets)
        try {
            val silence = FloatArray(vad.windowSamples)
            repeat(20) { assertTrue(vad.probability(silence) < 0.3f) }
            vad.reset()
        } finally {
            vad.release()
        }
    }
}
