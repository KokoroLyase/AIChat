package isomeria.aichat

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import isomeria.aichat.data.reply.ReplyNotifier
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class AIChatApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: ReplyNotifier
    @Inject lateinit var chatRepository: ChatRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        // 上次等待回复时 App 被系统杀掉：把这些请求标记为失败，用户可以点击重试
        appScope.launch { chatRepository.markInterruptedRequests() }
    }
}
