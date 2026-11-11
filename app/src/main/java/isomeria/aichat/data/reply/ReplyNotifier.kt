package isomeria.aichat.data.reply

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.work.ForegroundInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.MainActivity
import isomeria.aichat.R
import isomeria.aichat.data.db.Contact
import isomeria.aichat.data.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton

/** 后台任务转为前台时用的「正在接收消息」通知；单独成接口，投递任务的测试不必构造 MMKV。 */
fun interface ReceivingForeground {
    fun receivingForegroundInfo(): ForegroundInfo
}

/**
 * 通知：回复到达提醒（标题为联系人名称，内容为回复预览），以及等待较久时的「正在接收消息」常驻通知。
 */
@Singleton
class ReplyNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
) : ReplyNotifications, ReceivingForeground {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = context.getString(R.string.notification_channel_messages_desc) }
        val receiving = NotificationChannel(
            CHANNEL_RECEIVING,
            context.getString(R.string.notification_channel_receiving),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_receiving_desc)
            setShowBadge(false)
        }
        // 0.7.0：后台播放语音时的常驻通知
        val playback = NotificationChannel(
            CHANNEL_PLAYBACK,
            context.getString(R.string.notification_channel_playback),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_playback_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(messages, receiving, playback))
    }

    fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled()

    /** 回复到达。[parts] 是分条后的各条内容。 */
    override fun notifyReply(contact: Contact, parts: List<String>, alert: Boolean) {
        if (!settings.notifyEnabled.value || !canNotify()) return
        val text = when {
            !settings.notifyPreview.value -> context.getString(R.string.notification_hidden_content)
            parts.size > 1 -> context.getString(R.string.notification_multi, parts.size, parts.last())
            else -> parts.firstOrNull().orEmpty()
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setLargeIcon(avatarBitmap(contact))
            .setContentTitle(contact.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // 同一次回复的后几条只更新内容：通知还在时不再响
            .setOnlyAlertOnce(!alert)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(openChatIntent(contact.id))
            .build()
        try {
            manager.notify(messageNotificationId(contact.id), notification)
        } catch (_: SecurityException) {
            // 用户在通知发出前撤销了权限，不影响聊天
        }
    }

    override fun cancelReply(contactId: Long) {
        manager.cancel(messageNotificationId(contactId))
    }

    /** 等待较久时转为前台任务。系统会延迟约 10 秒才显示这条通知，回复很快时用户看不到它。 */
    override fun receivingForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_RECEIVING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setContentTitle(context.getString(R.string.notification_receiving))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .build()
        return ForegroundInfo(RECEIVING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** 后台播放语音时的常驻通知（0.7.0）：不含联系人和消息内容，带「停止」 */
    fun playbackNotification(stop: PendingIntent): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_PLAYBACK)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(BRAND_COLOR)
            .setContentTitle(context.getString(R.string.notification_playback))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, context.getString(R.string.voice_stop), stop)
            .build()

    private fun openChatIntent(contactId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CHAT
            putExtra(EXTRA_CONTACT_ID, contactId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            contactId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /** 通知里的大图标：联系人头像，没有头像时画「名称首字 + 色块」 */
    private fun avatarBitmap(contact: Contact): Bitmap? = runCatching {
        val size = (48 * context.resources.displayMetrics.density).toInt()
        contact.avatarPath?.let { path ->
            BitmapFactory.decodeFile(path)?.let { return@runCatching it.scale(size, size) }
        }
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val radius = size * 0.1f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = contact.avatarColor }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        val letter = contact.name.trim().take(1).uppercase()
        paint.color = android.graphics.Color.WHITE
        paint.textSize = size * 0.45f
        paint.textAlign = Paint.Align.CENTER
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, y, paint)
        bitmap
    }.getOrNull()

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_RECEIVING = "receiving"
        const val CHANNEL_PLAYBACK = "voice_playback"
        const val ACTION_OPEN_CHAT = "isomeria.aichat.action.OPEN_CHAT"
        const val EXTRA_CONTACT_ID = "contact_id"
        private const val RECEIVING_ID = 1
        private const val MESSAGE_ID_BASE = 1000
        private const val BRAND_COLOR = 0xFF07C160.toInt()

        fun messageNotificationId(contactId: Long): Int = MESSAGE_ID_BASE + contactId.toInt()
    }
}
