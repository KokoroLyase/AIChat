package isomeria.aichat.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 系统语音合成能不能用（0.7.1）。 */
enum class SystemTtsStatus {
    /** 有引擎、有不需要联网的中文音色 */
    READY,

    /** 手机上没有语音合成引擎 */
    NO_ENGINE,

    /** 引擎不支持中文，或者中文音色都要联网（会把文字发出手机），或者还没下载 */
    NO_CHINESE,

    /** 引擎初始化失败 */
    FAILED,
}

/** 引擎提供的一个中文音色；[name] 是 [Voice.getName]，不同引擎写法不同 */
data class SystemTtsVoice(val name: String, val locale: String)

data class SystemTtsInfo(
    val status: SystemTtsStatus,
    /** 系统默认引擎的名称（例如“小米语音引擎”）；没有引擎时为 null */
    val engine: String? = null,
    /** 能用的中文音色（不需要联网、已经装好），按名称排序 */
    val voices: List<SystemTtsVoice> = emptyList(),
)

/**
 * 系统语音合成（0.7.1，「系统语音（本机）」）：用手机自带的语音合成引擎（TextToSpeech）把回复文字合成成 WAV，
 * 不需要 Key 和地址。只用不需要联网的中文音色，回复文字不离开手机；音色由引擎提供，语气和风格要求不生效。
 * JVM 测试换成假实现。
 */
interface SystemTts {
    /** 查看系统引擎和中文音色。[refresh] 为 true 时重新连接引擎（用户可能在系统设置里换了引擎） */
    suspend fun info(refresh: Boolean = false): SystemTtsInfo

    /**
     * 把 [text] 合成成 WAV，返回整个文件的内容。[voice] 是 [SystemTtsVoice.name]，为空或已经没有这个音色时用默认的中文音色。
     * 失败时抛 [IOException]
     */
    suspend fun synthesize(text: String, voice: String?): ByteArray
}

/**
 * 用 [TextToSpeech] 实现。引擎连接一次后一直留着（合成很频繁时不用反复连接），同一时间只合成一段。
 *
 * 清单里声明了 `<queries>` 的 `TTS_SERVICE`，否则 Android 11 起看不到系统引擎。
 */
@Singleton
class AndroidSystemTts @Inject constructor(@ApplicationContext private val context: Context) : SystemTts {
    private val lock = Mutex()
    private var engine: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()

    override suspend fun info(refresh: Boolean): SystemTtsInfo = lock.withLock {
        if (!hasEngine()) {
            shutdown()
            return@withLock SystemTtsInfo(SystemTtsStatus.NO_ENGINE)
        }
        if (refresh) shutdown()
        val tts = connect() ?: return@withLock SystemTtsInfo(SystemTtsStatus.FAILED)
        describe(tts)
    }

    override suspend fun synthesize(text: String, voice: String?): ByteArray = lock.withLock {
        if (!hasEngine()) throw IOException("NoEngine")
        val tts = connect() ?: throw IOException("EngineFailed")
        val info = describe(tts)
        if (info.status != SystemTtsStatus.READY) throw IOException("NoChineseVoice")
        val chosen = info.voices.firstOrNull { it.name == voice } ?: defaultVoice(tts, info)
        val target = tts.voices?.firstOrNull { it.name == chosen.name } ?: throw IOException("VoiceGone")
        if (tts.setVoice(target) != TextToSpeech.SUCCESS) throw IOException("SetVoiceFailed")
        // 引擎写到缓存目录里的临时文件，读出来就删
        val out = File(File(context.cacheDir, TEMP_DIR).apply { mkdirs() }, "${UUID.randomUUID()}.wav")
        val id = UUID.randomUUID().toString()
        val params = Bundle().apply {
            // 不用联网合成（Google 等引擎有联网音色，会把文字发出手机）
            @Suppress("DEPRECATION")
            putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
        }
        try {
            withTimeout(TIMEOUT_MILLIS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    pending[id] = cont
                    cont.invokeOnCancellation { pending.remove(id); runCatching { tts.stop() } }
                    if (tts.synthesizeToFile(text, params, out, id) != TextToSpeech.SUCCESS) {
                        pending.remove(id)
                        cont.resumeWithException(IOException("SynthesizeRejected"))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            out.delete()
            throw IOException("SynthesizeTimeout")
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        try {
            if (!out.isFile || out.length() <= WavFormat.HEADER_BYTES) throw IOException("EmptyAudio")
            out.readBytes()
        } finally {
            out.delete()
        }
    }

    private fun hasEngine(): Boolean =
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()

    /** 连接系统默认引擎；连不上时为 null */
    private suspend fun connect(): TextToSpeech? {
        engine?.let { return it }
        var created: TextToSpeech? = null
        val ok = withContext(Dispatchers.Main) {
            try {
                withTimeout(INIT_TIMEOUT_MILLIS) {
                    suspendCancellableCoroutine { cont ->
                        created = TextToSpeech(context.applicationContext) { status -> if (cont.isActive) cont.resume(status == TextToSpeech.SUCCESS) }
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                false
            }
        }
        val tts = created ?: return null
        if (!ok) {
            runCatching { tts.shutdown() }
            return null
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit

            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.takeIf { it.isActive }?.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending.remove(utteranceId)?.takeIf { it.isActive }?.resumeWithException(IOException("SynthesizeError"))
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                pending.remove(utteranceId)?.takeIf { it.isActive }?.resumeWithException(IOException("SynthesizeError$errorCode"))
            }
        })
        engine = tts
        return tts
    }

    private fun describe(tts: TextToSpeech): SystemTtsInfo {
        val label = runCatching { tts.engines.firstOrNull { it.name == tts.defaultEngine }?.label }.getOrNull()
        val voices = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { SystemTtsRules.usable(it.locale, it.isNetworkConnectionRequired, it.features.orEmpty()) }
            .map { SystemTtsVoice(it.name, it.locale.toLanguageTag()) }
            .sortedBy { it.name }
        return SystemTtsInfo(if (voices.isEmpty()) SystemTtsStatus.NO_CHINESE else SystemTtsStatus.READY, label, voices)
    }

    /** 没选音色时：引擎的默认音色是能用的中文音色就用它，否则按名称排第一个 */
    private fun defaultVoice(tts: TextToSpeech, info: SystemTtsInfo): SystemTtsVoice {
        val engineDefault = runCatching { tts.defaultVoice?.name }.getOrNull()
        return info.voices.firstOrNull { it.name == engineDefault } ?: info.voices.first()
    }

    private fun shutdown() {
        engine?.let { runCatching { it.shutdown() } }
        engine = null
    }

    private companion object {
        const val TIMEOUT_MILLIS = 60_000L
        const val INIT_TIMEOUT_MILLIS = 10_000L
        const val TEMP_DIR = "voice-temp"
    }
}

/** 哪些系统音色能用（纯函数，便于测试）。 */
object SystemTtsRules {
    /** 中文（普通话、粤语都算）、不需要联网、已经装好 */
    fun usable(locale: Locale?, network: Boolean, features: Set<String>): Boolean {
        if (locale == null || network) return false
        if (TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in features) return false
        return locale.language in CHINESE
    }

    private val CHINESE = setOf("zh", "cmn", "yue")
}
