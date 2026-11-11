package isomeria.aichat.data.reply

import android.util.Log
import androidx.room.withTransaction
import isomeria.aichat.BuildConfig
import isomeria.aichat.core.Clock
import isomeria.aichat.core.ReplyPreferencesSource
import isomeria.aichat.core.ReplyTypeRule
import isomeria.aichat.core.VoiceChunks
import isomeria.aichat.core.VoiceSettingsSource
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageOrigin
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.ProviderKind
import isomeria.aichat.data.db.Role
import isomeria.aichat.data.llm.LlmException
import isomeria.aichat.data.repo.FailReason
import isomeria.aichat.data.repo.FileStorage
import isomeria.aichat.data.repo.VoiceRepository
import isomeria.aichat.data.repo.VoiceUnavailableException
import isomeria.aichat.data.voice.AudioCodec
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * 语音消息在回复流程里的两步（0.7.0），回复任务（[ReplyEngine]）和主动聊天（[ProactiveEngine]）共用：
 *
 * - **识别**：用户发的语音先识别出文字（存进 content），WAV 再压成 AAC 省空间；识别失败时这一轮等待回复的消息都标为失败、
 *   不请求模型，点击重试会重新识别
 * - **合成**：联系人这一轮回语音时，先把文字以“等合成”的语音消息写库（PENDING、没有语音文件，不会送达），
 *   再逐条合成；成功的填上文件和时长排进投递，部分失败的那几条改成文字，全部失败时按回复偏好重新拆成文字消息。
 *   都不再请求模型
 */
@Singleton
class VoicePipeline @Inject constructor(
    private val db: AppDatabase,
    private val voices: VoiceRepository,
    private val codec: AudioCodec,
    private val files: FileStorage,
    private val settings: VoiceSettingsSource,
    private val preferences: ReplyPreferencesSource,
    private val clock: Clock,
    private val random: Random,
) {
    private val messages get() = db.messageDao()

    /** 识别这次等待回复的语音；都成功（或没有要识别的）时返回 null，否则返回失败原因 */
    suspend fun transcribeWaiting(contactId: Long): FailReason? {
        for (message in messages.untranscribedVoice(contactId)) {
            val wav = message.voicePath?.let(::File)?.takeIf { it.isFile } ?: return FailReason.ASR_FAILED
            val text = try {
                voices.transcribe(contactId, wav.readBytes()).text
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceUnavailableException) {
                return FailReason.ASR_UNAVAILABLE
            } catch (e: LlmException) {
                debugLog("transcribe failed: ${e.error} http=${e.httpCode}")
                return FailReason.ASR_FAILED
            } catch (e: Exception) {
                debugLog("transcribe failed unexpectedly: ${e.javaClass.simpleName}")
                return FailReason.ASR_FAILED
            }
            // 识别成功后把 WAV 压成 AAC 省空间；写库后才删 WAV（压缩失败时继续用 WAV）
            val compressed = compress(wav)
            messages.setTranscript(message.id, text, compressed)
            if (compressed != null) files.delete(wav.absolutePath)
        }
        return null
    }

    /** WAV 压成 AAC，返回新文件；压缩失败时为 null */
    private fun compress(wav: File): String? {
        if (!wav.extension.equals("wav", ignoreCase = true)) return null
        val out = files.newVoiceFile("m4a")
        return try {
            if (codec.encodeAac(wav, out) && out.length() > 0) out.absolutePath else null.also { out.delete() }
        } finally {
            files.commitAvatar(out.absolutePath)
        }
    }

    /** 这一轮回语音吗：见 [ReplyTypeRule]；没有可用的语音合成时回文字 */
    suspend fun replyAsVoice(contact: Contact, lastWaitingIsVoice: Boolean?): Boolean {
        val voice = settings.voiceSettings()
        // 文字模式时不用查语音服务（省一次数据库读取，请求和 0.6.0 一样）
        if ((contact.replyType ?: voice.replyType) == isomeria.aichat.data.db.ReplyType.TEXT) return false
        return ReplyTypeRule.voiceReply(contact.replyType, voice.replyType, lastWaitingIsVoice, voices.available(ProviderKind.TTS))
    }

    /** 回语音时要写库的几条：等合成的语音消息，内容按 [VoiceChunks] 分段；思考和用量只记在第一条 */
    fun heldRows(
        contact: Contact,
        text: String,
        reasoning: String,
        reasoningMillis: Long,
        promptTokens: Int,
        completionTokens: Int,
        at: Long,
        origin: MessageOrigin = MessageOrigin.NORMAL,
    ): List<Message> {
        val group = UUID.randomUUID().toString()
        return VoiceChunks.split(text).mapIndexed { index, part ->
            Message(
                contactId = contact.id,
                role = Role.ASSISTANT,
                type = MessageType.VOICE,
                content = part,
                reasoning = if (index == 0) reasoning.trim().ifEmpty { null } else null,
                reasoningMillis = if (index == 0) reasoningMillis else 0L,
                status = MessageStatus.PENDING,
                model = contact.model,
                promptTokens = if (index == 0) promptTokens else 0,
                completionTokens = if (index == 0) completionTokens else 0,
                replyGroup = group,
                createdAt = at + index,
                origin = origin,
            )
        }
    }

    /**
     * 合成这个联系人等着合成的语音回复。返回有没有要投递的（调用方再排投递任务）。
     * 合成途中被取消时，没合成的留着，下次回复任务开始时接着合成；App 被杀时启动恢复按文字送达
     */
    suspend fun synthesizeHeld(contactId: Long): Boolean {
        val held = messages.awaitingSynthesis(contactId)
        if (held.isEmpty()) return false
        val contact = db.contactDao().get(contactId)
        if (contact == null) {
            messages.degradeToText(held.map { it.id })
            return true
        }
        val failed = mutableListOf<Message>()
        var time = clock.now()
        for (message in held) {
            val ok = try {
                synthesizeOne(contact, message, time)?.let { time = it + GAP_MILLIS } != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: VoiceUnavailableException) {
                false
            } catch (e: LlmException) {
                debugLog("synthesize failed: ${e.error} http=${e.httpCode}")
                false
            } catch (e: Exception) {
                debugLog("synthesize failed unexpectedly: ${e.javaClass.simpleName}")
                false
            }
            if (!ok) failed += message
        }
        if (failed.isEmpty()) return true
        if (failed.size == held.size) degradeAll(contact, held) else messages.degradeToText(failed.map { it.id })
        return true
    }

    /** 合成一条：写文件、量时长、填进消息；返回这条的送达时间。消息在这期间被删了时删掉文件、返回 null */
    private suspend fun synthesizeOne(contact: Contact, message: Message, notBefore: Long): Long? {
        val audio = voices.synthesize(contact, message.content)
        val file = files.newVoiceFile(audio.extension)
        try {
            file.writeBytes(audio.bytes)
            val millis = codec.durationMillis(file).takeIf { it > 0 } ?: estimateMillis(message.content)
            val at = maxOf(clock.now(), notBefore)
            if (messages.finishSynthesis(message.id, file.absolutePath, millis, at) == 0) {
                files.delete(file.absolutePath)
                return null
            }
            voices.recordSynthesis(contact.id, message.content.length, millis)
            return at
        } catch (e: Throwable) {
            files.delete(file.absolutePath)
            throw e
        } finally {
            files.commitAvatar(file.absolutePath)
        }
    }

    /** 全部合成失败：去掉这几条，按联系人的设置（回复偏好或分条显示）重新拆成文字消息，照常投递 */
    private suspend fun degradeAll(contact: Contact, held: List<Message>) {
        val first = held.first()
        val now = clock.now()
        val text = held.joinToString("\n") { it.content }
        val rows = ReplyComposer.rows(
            contact = contact,
            text = text,
            reasoning = first.reasoning.orEmpty(),
            promptTokens = first.promptTokens,
            completionTokens = first.completionTokens,
            startedAt = now,
            firstContentAt = 0,
            finishedAt = now,
            preferences = if (contact.useReplyPreferences) preferences.replyPreferences() else null,
            random = random,
            origin = first.origin,
        ).mapIndexed { index, row -> if (index == 0) row.copy(reasoningMillis = first.reasoningMillis) else row }
        db.withTransaction {
            val still = messages.awaitingSynthesis(contact.id).map { it.id }.toSet()
            val ids = held.map { it.id }.filter { it in still }
            if (ids.isEmpty()) return@withTransaction
            ids.forEach { messages.delete(it) }
            messages.insertAll(rows)
        }
    }

    /** 读不出时长时按字数估（约每字 0.25 秒） */
    private fun estimateMillis(text: String): Long = (text.length * 250L).coerceIn(1_000, 60_000)

    private fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    private companion object {
        const val TAG = "VoicePipeline"
        /** 几条语音之间隔多久送达 */
        const val GAP_MILLIS = 1_500L
    }
}
