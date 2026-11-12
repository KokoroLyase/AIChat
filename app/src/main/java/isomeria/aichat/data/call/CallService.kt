package isomeria.aichat.data.call

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
 * 通话期间的前台服务（0.8.0，类型 microphone 和 mediaPlayback）：回到桌面、锁屏时接着听和放。通知写“正在和 X 通话”和「挂断」，
 * 点通知回到通话页。麦克风受“使用期间”限制，App 在后台时系统不让启动这种服务，所以只在通话页还在前台、发起通话时启动；
 * 挂断时停掉。通话本身由 [CallEngine] 负责。
 */
@AndroidEntryPoint
class CallService : Service() {
    @Inject lateinit var engine: CallEngine
    @Inject lateinit var notifier: ReplyNotifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            engine.hangUp()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_CONTACT_NAME).orEmpty()
        val notification = notifier.callNotification(name, hangUpIntent(this))
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } catch (e: Exception) {
            // 系统不让（比如没有麦克风权限）：通话照常在前台进行，只是回到桌面后可能被停掉
            if (BuildConfig.DEBUG) Log.d(TAG, "foreground not started: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val ACTION_HANG_UP = "isomeria.aichat.action.HANG_UP"
        const val EXTRA_CONTACT_ID = "contact_id"
        const val EXTRA_CONTACT_NAME = "contact_name"
        const val NOTIFICATION_ID = 4
        private const val TAG = "CallService"

        fun hangUpIntent(context: Context): android.app.PendingIntent = android.app.PendingIntent.getService(
            context,
            0,
            Intent(context, CallService::class.java).setAction(ACTION_HANG_UP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 启动、停止 [CallService]；发起通话时通话页在前台，系统允许 */
@Singleton
class ServiceCallHost @Inject constructor(@ApplicationContext private val context: Context) : CallHost {
    override fun start(contactId: Long, contactName: String) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java).putExtra(CallService.EXTRA_CONTACT_ID, contactId).putExtra(CallService.EXTRA_CONTACT_NAME, contactName),
            )
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.d("CallService", "foreground service not started: ${e.javaClass.simpleName}")
        }
    }

    override fun stop() {
        context.stopService(Intent(context, CallService::class.java))
    }
}
