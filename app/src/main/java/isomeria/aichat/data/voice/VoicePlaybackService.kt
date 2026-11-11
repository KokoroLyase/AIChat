package isomeria.aichat.data.voice

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import isomeria.aichat.BuildConfig
import isomeria.aichat.data.reply.ReplyNotifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 后台播放语音时的前台服务（0.7.0，类型 mediaPlayback）：只在「后台播放」期间运行，放完或点通知里的「停止」就结束。
 * 播放本身由 [VoicePlayback] 负责，这个服务只是让 App 回到桌面后系统不停掉它。
 */
@AndroidEntryPoint
class VoicePlaybackService : Service() {
    @Inject lateinit var playback: VoicePlayback
    @Inject lateinit var notifier: ReplyNotifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            playback.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, VoicePlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notifier.playbackNotification(stop), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_STOP = "isomeria.aichat.action.STOP_VOICE"
        private const val NOTIFICATION_ID = 2
    }
}

/** 启动、停止 [VoicePlaybackService]。只在 App 前台（用户在长按菜单里点了「后台播放」）时启动，系统允许 */
@Singleton
class ServicePlaybackHost @Inject constructor(@ApplicationContext private val context: Context) : BackgroundPlaybackHost {
    override fun start() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, VoicePlaybackService::class.java))
        } catch (e: Exception) {
            // 系统不让启动时照常播放，只是回到桌面后可能被停掉
            if (BuildConfig.DEBUG) Log.d("VoicePlayback", "foreground service not started: ${e.javaClass.simpleName}")
        }
    }

    override fun stop() {
        context.stopService(Intent(context, VoicePlaybackService::class.java))
    }
}
