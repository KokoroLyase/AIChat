package isomeria.aichat.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import isomeria.aichat.data.repo.ChatRepository
import isomeria.aichat.data.voice.AndroidVoicePlayer
import isomeria.aichat.data.voice.ServicePlaybackHost
import isomeria.aichat.data.voice.VoicePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** 语音消息的播放（0.7.0）：整个 App 一个播放器，在主线程上运行 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {
    @Provides
    @Singleton
    fun voicePlayback(player: AndroidVoicePlayer, chats: ChatRepository, host: ServicePlaybackHost): VoicePlayback =
        VoicePlayback(player, chats, host, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
}
