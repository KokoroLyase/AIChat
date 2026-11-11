package isomeria.aichat.data.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * 用 AudioRecord 录 16 kHz 单声道 PCM，边录边写进 WAV 文件；停止时补上文件头里的长度。
 *
 * 测试包（debug、perf）里，应用私有目录有 `files/test-voice-input.wav` 时，用这个文件按实际时长“播放”进来代替麦克风，
 * 真机测试默认用预置音频、不录手机周围的声音（release 不含这个开关）。
 */
@Singleton
class AndroidVoiceRecorder @Inject constructor(@ApplicationContext private val context: Context) : VoiceRecorder {
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var currentLevel = 0f
    @Volatile private var bytesWritten = 0L
    private var file: File? = null
    override var pcmListener: ((ShortArray, Int) -> Unit)? = null

    @SuppressLint("MissingPermission") // 调用方先检查并申请 RECORD_AUDIO
    override fun start(file: File) {
        check(!running) { "already recording" }
        this.file = file
        bytesWritten = 0
        currentLevel = 0f
        FileOutputStream(file).use { it.write(WavFormat.header(0)) }
        val injected = File(context.filesDir, TEST_INPUT).takeIf { BuildConfig.TEST_TOOLS && it.isFile }
        val source: PcmSource = if (injected != null) FilePcmSource(injected) else MicPcmSource()
        source.open()
        running = true
        thread = Thread({ loop(source, file) }, "voice-recorder").apply { start() }
    }

    private fun loop(source: PcmSource, file: File) {
        val buffer = ShortArray(WavFormat.SAMPLE_RATE / 10)
        val bytes = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        try {
            FileOutputStream(file, true).use { out ->
                while (running) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    bytes.clear()
                    var sum = 0.0
                    for (i in 0 until count) {
                        bytes.putShort(buffer[i])
                        sum += buffer[i].toDouble() * buffer[i]
                    }
                    out.write(bytes.array(), 0, count * 2)
                    bytesWritten += count * 2L
                    currentLevel = (sqrt(sum / count) / 6000.0).coerceIn(0.0, 1.0).toFloat()
                    pcmListener?.invoke(buffer, count)
                }
            }
        } finally {
            source.close()
            currentLevel = 0f
        }
    }

    override fun level(): Float = currentLevel

    override fun elapsedMillis(): Long = bytesWritten * 1000 / WavFormat.BYTES_PER_SECOND

    override fun stop(): Long {
        val f = file ?: return 0
        halt()
        runCatching { WavFormat.finish(f) }
        return WavFormat.durationMillis(f.length())
    }

    override fun cancel() {
        val f = file
        halt()
        f?.delete()
    }

    private fun halt() {
        running = false
        thread?.join(2_000)
        thread = null
        file = null
    }

    private interface PcmSource {
        fun open()
        fun read(buffer: ShortArray): Int
        fun close()
    }

    @SuppressLint("MissingPermission")
    private class MicPcmSource : PcmSource {
        private lateinit var record: AudioRecord
        override fun open() {
            val min = AudioRecord.getMinBufferSize(WavFormat.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WavFormat.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, WavFormat.BYTES_PER_SECOND / 2),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("microphone unavailable")
            }
            record.startRecording()
        }

        override fun read(buffer: ShortArray): Int = record.read(buffer, 0, buffer.size)

        override fun close() {
            runCatching { record.stop() }
            record.release()
        }
    }

    /** 测试包用：从 WAV 文件按实际时长读 PCM，读完后补静音（像一直在录，直到用户松手） */
    private class FilePcmSource(private val file: File) : PcmSource {
        private lateinit var input: RandomAccessFile
        private var startedAt = 0L
        private var delivered = 0L
        override fun open() {
            input = RandomAccessFile(file, "r")
            input.seek(WavFormat.HEADER_BYTES.toLong())
            startedAt = System.nanoTime()
        }

        override fun read(buffer: ShortArray): Int {
            // 按实时速度给数据
            val due = (System.nanoTime() - startedAt) / 1_000_000 * WavFormat.SAMPLE_RATE / 1000
            if (delivered + buffer.size > due) Thread.sleep(((delivered + buffer.size - due) * 1000 / WavFormat.SAMPLE_RATE).coerceAtLeast(1))
            val raw = ByteArray(buffer.size * 2)
            val read = input.read(raw).coerceAtLeast(0)
            val wrapped = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            for (i in buffer.indices) buffer[i] = if (i * 2 + 1 < read) wrapped.getShort(i * 2) else 0
            delivered += buffer.size
            return buffer.size
        }

        override fun close() = input.close()
    }

    companion object {
        /** 测试包里代替麦克风的预置音频（16 kHz 单声道 16 位 WAV） */
        const val TEST_INPUT = "test-voice-input.wav"
    }
}

/** WAV → AAC（m4a）用 MediaCodec + MediaMuxer；时长用 MediaMetadataRetriever。 */
@Singleton
class AndroidAudioCodec @Inject constructor() : AudioCodec {
    override fun encodeAac(wav: File, out: File): Boolean = try {
        encode(wav, out)
        out.length() > 0
    } catch (_: Exception) {
        out.delete()
        false
    }

    private fun encode(wav: File, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, WavFormat.SAMPLE_RATE, WavFormat.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            RandomAccessFile(wav, "r").use { input ->
                input.seek(WavFormat.HEADER_BYTES.toLong())
                val info = MediaCodec.BufferInfo()
                var track = -1
                var inputDone = false
                var presentedBytes = 0L
                while (true) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val chunk = ByteArray(buffer.remaining().coerceAtMost(8_192))
                            val read = input.read(chunk)
                            val time = presentedBytes * 1_000_000 / WavFormat.BYTES_PER_SECOND
                            if (read <= 0) {
                                codec.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buffer.put(chunk, 0, read)
                                codec.queueInputBuffer(index, 0, read, time, 0)
                                presentedBytes += read
                            }
                        }
                    }
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                        }
                        outIndex >= 0 -> {
                            val encoded = codec.getOutputBuffer(outIndex)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0 && track >= 0) muxer.writeSampleData(track, encoded, info)
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    override fun durationMillis(file: File): Long {
        if (!file.isFile) return 0
        if (file.extension.equals("wav", ignoreCase = true)) return WavFormat.durationMillis(file.length())
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }
}
