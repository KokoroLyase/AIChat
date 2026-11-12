package isomeria.aichat.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.R
import isomeria.aichat.data.db.Message
import isomeria.aichat.data.db.MessageType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 聊天列表预览和通知里一条消息显示成什么：文字消息是正文，图片是「[图片]」，语音（0.7.0）是「[语音]」
 * （和微信一样不在预览里露出转写文字），通话（0.8.0）是「[通话]」，挂断后加上时长。
 */
fun interface MessagePreviewer {
    fun preview(message: Message): String

    companion object {
        /** 单元测试用：不读资源，图片和语音按类型名占位 */
        val PLAIN = MessagePreviewer { message ->
            when (message.type) {
                MessageType.TEXT -> message.content
                MessageType.IMAGE -> "[IMAGE]"
                MessageType.VOICE -> "[VOICE]"
                MessageType.CALL -> if (message.callOngoing) "[CALL]" else "[CALL] " + isomeria.aichat.core.CallDurations.format(message.voiceMillis)
            }
        }
    }
}

@Singleton
class ResourceMessagePreviewer @Inject constructor(@ApplicationContext private val context: Context) : MessagePreviewer {
    override fun preview(message: Message): String = when (message.type) {
        MessageType.TEXT -> message.content
        MessageType.IMAGE -> context.getString(R.string.preview_image)
        MessageType.VOICE -> context.getString(R.string.preview_voice)
        MessageType.CALL -> callPreview(context, message)
    }
}

/** 通话消息的预览（0.8.0）：进行中是「[通话]」，挂断后是「[通话] 03:21」 */
fun callPreview(context: Context, message: Message): String =
    if (message.callOngoing) {
        context.getString(R.string.preview_call)
    } else {
        context.getString(R.string.preview_call_duration, isomeria.aichat.core.CallDurations.format(message.voiceMillis))
    }
