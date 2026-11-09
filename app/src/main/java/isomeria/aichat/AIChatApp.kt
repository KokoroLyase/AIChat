package isomeria.aichat

import android.app.Application
import android.content.Context
import isomeria.aichat.data.logs.CrashLogs
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

@HiltAndroidApp
class AIChatApp : Application(), Configuration.Provider {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        CrashLogs.install(base)
    }
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: Provider<ReplyNotifier>
    @Inject lateinit var chatRepository: Provider<ChatRepository>
    @Inject lateinit var delivery: Provider<isomeria.aichat.data.reply.DeliveryEngine>
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        appScope.launch(kotlinx.coroutines.Dispatchers.IO) { CrashLogs.collectExits(this@AIChatApp) }
        // The launcher shortcut runs in an independent process and must never open Room/MMKV.
        if (getProcessName() == "$packageName:diagnostics") return
        isomeria.aichat.data.repo.FileStorage.clearStaleRestoreCache(this)
        isomeria.aichat.data.repo.FileStorage.clearLegacySnapshotOnce(this)
        notifier.get().createChannels()
        // 上次等待回复时 App 被系统杀掉：把这些请求标记为失败，用户可以点击重试；
        // 已经生成、还没送达的回复：到期的直接送达，没到期的重新排队（不会丢，也不会重复）
        appScope.launch {
            chatRepository.get().markInterruptedRequests()
            delivery.get().recover()
        }
    }
}
