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
    @Inject lateinit var proactiveControl: Provider<isomeria.aichat.data.reply.ProactiveControl>
    @Inject lateinit var proactiveEngine: Provider<isomeria.aichat.data.reply.ProactiveEngine>
    @Inject lateinit var contactDao: Provider<isomeria.aichat.data.db.ContactDao>
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
            // 主动聊天的后台任务和数据对齐：没有联系人开启时（新装、升级后都是这样）不注册，已有的也取消
            proactiveControl.get().sync()
        }
        // 周期任务可能被系统推迟或冻结：回到前台时也检查一次。没有联系人开启时什么也不做
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                    appScope.launch {
                        // 稍等一下，让正在打开的聊天页先登记「正在看」，不往用户眼前的聊天里发
                        kotlinx.coroutines.delay(FOREGROUND_CHECK_DELAY_MILLIS)
                        if (contactDao.get().proactiveCount() > 0) runCatching { proactiveEngine.get().run() }
                    }
                }
            },
        )
    }

    private companion object {
        const val FOREGROUND_CHECK_DELAY_MILLIS = 3_000L
    }
}
