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
 * （和微信一样不在预览里露出转写文字）。
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
    }
}
