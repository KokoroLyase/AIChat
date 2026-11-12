package isomeria.aichat.data.voice

import isomeria.aichat.data.llm.LlmError
import isomeria.aichat.data.llm.LlmException

/**
 * 长录音分段（0.7.2，智谱识别单次最长 30 秒，App 最长录 60 秒）。
 *
 * 超过 [MAX_PIECE_MILLIS] 的录音平均分成几段，每个切点在预定位置前后 [SEARCH_MILLIS] 内找最安静的 [FRAME_MILLIS]，
 * 尽量不把一个字切开；每段重新包成同样格式的 WAV。这样每段最长 [MAX_PIECE_MILLIS] + 2 × [SEARCH_MILLIS] = 29 秒。
 * 纯 JVM，便于测试。
 */
object AsrSplitter {
    const val MAX_PIECE_MILLIS = 25_000L
    const val SEARCH_MILLIS = 2_000L
    const val FRAME_MILLIS = 20L

    /** 不用分段或读不出格式（不是 16 位 PCM 的 WAV）时原样返回一段 */
    fun split(wav: ByteArray): List<ByteArray> {
        val info = WavFormat.parse(wav)?.takeIf { it.bits == 16 } ?: return listOf(wav)
        if (info.durationMillis <= MAX_PIECE_MILLIS) return listOf(wav)
        val frame = info.channels * 2
        val start = info.dataOffset.toInt()
        val end = (start + info.dataBytes).toInt().coerceAtMost(wav.size)
        val frames = (end - start) / frame
        val count = ((info.durationMillis + MAX_PIECE_MILLIS - 1) / MAX_PIECE_MILLIS).toInt()
        val perMilli = info.sampleRate / 1000.0
        val cuts = (1 until count).map { i ->
            val target = frames.toLong() * i / count
            quietest(wav, start, frame, frames, target, (SEARCH_MILLIS * perMilli).toLong(), (FRAME_MILLIS * perMilli).toInt().coerceAtLeast(1))
        }
        val bounds = listOf(0L) + cuts + frames.toLong()
        return bounds.zipWithNext().map { (from, to) ->
            val pcm = wav.copyOfRange(start + (from * frame).toInt(), start + (to * frame).toInt())
            WavFormat.header(pcm.size.toLong(), info.sampleRate, info.channels) + pcm
        }
    }

    /** [target] 前后 [window] 帧内，能量最小的 [size] 帧窗口的起点（帧序号） */
    private fun quietest(wav: ByteArray, start: Int, frame: Int, frames: Int, target: Long, window: Long, size: Int): Long {
        val from = (target - window).coerceAtLeast(0)
        val to = (target + window).coerceAtMost((frames - size).toLong())
        var best = target
        var bestEnergy = Long.MAX_VALUE
        var at = from
        while (at <= to) {
            var energy = 0L
            for (f in at until at + size) {
                val i = start + (f * frame).toInt()
                val sample = ((wav[i + 1].toInt() shl 8) or (wav[i].toInt() and 0xFF)).toLong()
                energy += sample * sample
            }
            if (energy < bestEnergy) {
                bestEnergy = energy
                best = at + size / 2
            }
            at += size
        }
        return best
    }

    /** 接上一段识别出的文字：两边都是英文字母或数字时中间加空格，中文直接接 */
    fun join(previous: String, next: String): String = when {
        next.isEmpty() -> previous
        previous.isEmpty() -> next
        previous.last().isLetterOrDigit() && previous.last().code < 128 && next.first().isLetterOrDigit() && next.first().code < 128 -> "$previous $next"
        else -> previous + next
    }
}

/**
 * OpenRouter 返回的 pcm（0.7.2）：16 位小端，采样率和声道数在 Content-Type 的参数里（如 `audio/pcm;rate=24000;channels=1`），
 * 没写时按 24 kHz 单声道（OpenAI、Gemini 都是）；包成 WAV 后和系统语音一样压成 AAC。
 */
object PcmAudio {
    const val DEFAULT_RATE = 24_000

    /**
     * 返回头说是 pcm，或者要的是 pcm 而内容认不出是常见的音频格式时，包成 WAV；否则原样返回。
     * 数据是奇数字节时按坏数据处理
     */
    fun wrapIfPcm(bytes: ByteArray, contentType: String, requestedPcm: Boolean): ByteArray {
        val type = contentType.lowercase()
        val pcm = type.contains("pcm") || type.contains("l16") || (requestedPcm && !AudioFormats.recognized(bytes))
        if (!pcm || bytes.isEmpty()) return bytes
        val params = type.split(';').drop(1).associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
        val rate = params["rate"]?.toIntOrNull()?.takeIf { it in 8_000..192_000 } ?: DEFAULT_RATE
        val channels = params["channels"]?.toIntOrNull()?.takeIf { it in 1..2 } ?: 1
        if (bytes.size % (channels * 2) != 0) throw LlmException(LlmError.UNKNOWN, message = "odd pcm length")
        return WavFormat.header(bytes.size.toLong(), rate, channels) + bytes
    }
}
