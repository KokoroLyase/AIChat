package isomeria.aichat.data.voice

import android.app.Application
import isomeria.aichat.R
import isomeria.aichat.data.db.ProviderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 语音合成、语音识别列表页底部的说明列出了全部预置，要和预置列表一致、顺序相同。
 * 0.9.2 加 DeepSeek 时漏改了这两句（真机验证时发现）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VoicePresetHintTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun providerListHints_nameEveryPreset_inOrder() {
        for ((kind, hint) in listOf(ProviderKind.TTS to R.string.voice_providers_tts_hint, ProviderKind.ASR to R.string.voice_providers_asr_hint)) {
            val text = context.getString(hint)
            val names = VoicePresets.forKind(kind).map { context.getString(it.nameRes) }
            val positions = names.map { text.indexOf(it) }
            assertTrue("$kind 的说明漏了预置：${names.filterIndexed { i, _ -> positions[i] < 0 }}", positions.all { it >= 0 })
            assertEquals("$kind 的说明里预置的顺序和列表一致", positions.sorted(), positions)
        }
    }
}
