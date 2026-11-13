package isomeria.aichat.data.backup

import isomeria.aichat.data.backup.BackupModule.CHATS
import isomeria.aichat.data.backup.BackupModule.CONTACTS
import isomeria.aichat.data.backup.BackupModule.FAVORITES
import isomeria.aichat.data.backup.BackupModule.GLOBAL_MEMORY
import isomeria.aichat.data.backup.BackupModule.MEMORIES
import isomeria.aichat.data.backup.BackupModule.PROACTIVE
import isomeria.aichat.data.backup.BackupModule.PROFILE
import isomeria.aichat.data.backup.BackupModule.PROVIDERS
import isomeria.aichat.data.backup.BackupModule.REPLY_PREFERENCES
import isomeria.aichat.data.backup.BackupModule.USAGE
import isomeria.aichat.data.backup.BackupModule.VOICE_SETTINGS

/**
 * 只留下 [modules] 里的内容（0.7.2）。导出时用它决定写进文件的内容，恢复时用它去掉用户没选的内容。
 *
 * - 不选聊天记录时，会话只留置顶、免打扰、标为未读这些和联系人一起的设置；未读数、草稿、最后一条消息、聊天背景清空
 * - 聊天记录和记忆没有都选时，自动记忆的进度清空，恢复后重新确定起点
 * - 不选模型配置时，服务商换成只有格式和地址的引用（[BackupData.providerRefs]），给联系人和语音来源找本机的同一个服务商
 * - 设置里没选的部分为 null：恢复时保留本机的
 */
fun BackupData.restrictTo(modules: Set<BackupModule>): BackupData {
    val selected = BackupModule.normalize(modules)
    val contacts = CONTACTS in selected
    val chats = CHATS in selected
    val memories = MEMORIES in selected
    val providers = PROVIDERS in selected
    val refs = if (providers) emptyList() else (providerRefs + this.providers.map { it.toRef() }).distinctBy { it.id }
    val profile = PROFILE in selected
    return copy(
        contacts = if (contacts) this.contacts else emptyList(),
        conversations = if (!contacts) emptyList() else conversations.map { conversation ->
            var result = conversation
            if (!chats) {
                result = result.copy(unreadCount = 0, draft = "", lastMessageTime = 0, lastMessagePreview = "", background = null)
            }
            if (!chats || !memories) result = result.copy(memoryCursor = null, memoryStart = 0)
            result
        },
        messages = if (chats) messages else emptyList(),
        memories = if (memories) this.memories else emptyList(),
        usage = if (USAGE in selected) usage else emptyList(),
        providers = if (providers) this.providers else emptyList(),
        providerRefs = if (!providers && (contacts || VOICE_SETTINGS in selected)) refs else emptyList(),
        globalCategories = if (GLOBAL_MEMORY in selected) globalCategories else emptyList(),
        globalTopics = if (GLOBAL_MEMORY in selected) globalTopics else emptyList(),
        globalItems = if (GLOBAL_MEMORY in selected) globalItems else emptyList(),
        favorites = if (FAVORITES in selected) favorites else emptyList(),
        settings = SettingsDto(
            themeMode = settings.themeMode.takeIf { profile },
            fontSize = settings.fontSize.takeIf { profile },
            nickname = settings.nickname.takeIf { profile },
            avatar = settings.avatar.takeIf { profile },
            notifyEnabled = settings.notifyEnabled.takeIf { profile },
            notifyPreview = settings.notifyPreview.takeIf { profile },
            autoCheckUpdate = settings.autoCheckUpdate.takeIf { profile },
            replyPreferences = settings.replyPreferences.takeIf { REPLY_PREFERENCES in selected },
            proactive = settings.proactive.takeIf { PROACTIVE in selected },
            voice = settings.voice.takeIf { VOICE_SETTINGS in selected },
        ),
    )
}

/** 数据里用到的 zip 内文件（头像、图片、聊天背景、语音、通话的音频、收藏的音频） */
fun BackupData.mediaNames(): Set<String> = buildSet {
    contacts.mapNotNullTo(this) { it.avatar }
    messages.mapNotNullTo(this) { it.image }
    messages.mapNotNullTo(this) { it.voice }
    messages.forEach { message -> message.call.mapNotNullTo(this) { it.audio } }
    conversations.mapNotNullTo(this) { it.background }
    settings.avatar?.let(::add)
    favorites.mapNotNullTo(this) { it.audio }
    favorites.forEach { favorite -> favorite.call.mapNotNullTo(this) { it.audio } }
}

fun ProviderDto.toRef() = ProviderRefDto(id = id, kind = kind, provider = provider, baseUrl = baseUrl)
