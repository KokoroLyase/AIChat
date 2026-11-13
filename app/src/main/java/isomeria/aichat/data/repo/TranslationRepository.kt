package isomeria.aichat.data.repo

import isomeria.aichat.core.TokenEstimator
import isomeria.aichat.core.TranslateDirection
import isomeria.aichat.core.TranslateTarget
import isomeria.aichat.data.db.AppDatabase
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageStatus
import isomeria.aichat.data.db.MessageType
import isomeria.aichat.data.db.UsagePurpose
import isomeria.aichat.data.llm.ChatEvent
import isomeria.aichat.data.llm.ChatMessage
import isomeria.aichat.data.llm.ChatRequest
import isomeria.aichat.data.llm.ChatRole
import isomeria.aichat.data.llm.LlmException
import javax.inject.Inject
import javax.inject.Singleton

/** 翻译提示（0.9.0），从 strings.xml 读取后传入 */
data class TranslateLabels(val toEnglish: String, val toChinese: String)

/**
 * 翻译（0.9.0）：长按文字或有转写的语音“翻译”。
 *
 * - 方向按 [TranslateDirection]：汉字超过一半翻成英语，否则翻成中文
 * - 用这个联系人的服务商和聊天模型，模型能关思考就关，温度等其余参数按联系人的设置（[ProviderRepository.snapshot]）；
 *   请求只有两条：翻译提示（系统）和要翻译的文字（用户），不带聊天记录、记忆和人设
 * - 译文存在消息上（[Message.translation]），再点“翻译”直接显示，不再请求；不进上下文和记忆，聊天请求不变
 * - 用量记为 [UsagePurpose.TRANSLATE]，不算主动聊天
 */
@Singleton
class TranslationRepository @Inject constructor(
    private val db: AppDatabase,
    private val providers: ProviderRepository,
    private val usage: UsageRepository,
    private val labels: TranslateLabels,
) {
    /** 显示这条消息的译文：已经翻译过的直接显示，没有时请求模型。成功（或消息已经不在、不能翻译）时为 null，失败时是原因 */
    suspend fun translate(messageId: Long): FailReason? {
        val message = db.messageDao().get(messageId) ?: return null
        message.translation?.let {
            db.messageDao().setTranslation(messageId, it, shown = true)
            return null
        }
        if (!canTranslate(message)) return null
        val target = TranslateDirection.of(message.content) ?: return null
        val text = try {
            request(message.contactId, target, message.content)
        } catch (e: LlmException) {
            return FailReason.of(e)
        } catch (e: EmptyTranslation) {
            return FailReason.EMPTY_REPLY
        } catch (e: NoApiKey) {
            return FailReason.NO_API_KEY
        }
        db.messageDao().setTranslation(messageId, text, shown = true)
        return null
    }

    /** “隐藏翻译”：译文保留，下次点“翻译”直接显示 */
    suspend fun hide(messageId: Long) = db.messageDao().setTranslationShown(messageId, false)

    /** 发给服务商的请求（单独拿出来，便于核对请求内容） */
    fun buildMessages(target: TranslateTarget, text: String): List<ChatMessage> = listOf(
        ChatMessage(ChatRole.SYSTEM, if (target == TranslateTarget.ENGLISH) labels.toEnglish else labels.toChinese),
        ChatMessage(ChatRole.USER, text),
    )

    private suspend fun request(contactId: Long, target: TranslateTarget, text: String): String {
        val messages = buildMessages(target, text)
        // 和聊天一样：取配置时就没有 Key（拿不到地址）算“没有 Key”，请求时服务商拒绝的才是 Key 无效
        val snapshot = try {
            providers.snapshot(contactId, memory = false, thinkingOff = true)
        } catch (e: LlmException) {
            throw if (e.error == isomeria.aichat.data.llm.LlmError.AUTH) NoApiKey() else e
        }
        val request: ChatRequest = snapshot.request(messages)
        val content = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0
        providers.provider(snapshot.config).streamChat(snapshot.endpoint, request).collect { event ->
            when (event) {
                is ChatEvent.Content -> content.append(event.delta)
                is ChatEvent.Usage -> {
                    promptTokens = event.promptTokens
                    completionTokens = event.completionTokens
                }
                is ChatEvent.Reasoning, is ChatEvent.Finished -> Unit
            }
        }
        val result = content.toString().trim()
        val estimator = TokenEstimator.forProtocol(snapshot.config.provider)
        usage.record(
            contactId = contactId,
            purpose = UsagePurpose.TRANSLATE,
            promptTokens = promptTokens.takeIf { it > 0 } ?: estimator.request(messages),
            completionTokens = completionTokens.takeIf { it > 0 } ?: estimator.text(result),
        )
        if (result.isEmpty()) throw EmptyTranslation()
        return result
    }

    private class EmptyTranslation : Exception()
    private class NoApiKey : Exception()

    companion object {
        /** 能翻译的：文字、有转写的语音（双方的都可以），里面要有字（只有表情、数字、标点的不行）；待投递的看不到，不算 */
        fun canTranslate(message: Message): Boolean =
            message.status != MessageStatus.PENDING &&
                (message.type == MessageType.TEXT || message.type == MessageType.VOICE) &&
                TranslateDirection.of(message.content) != null
    }
}
