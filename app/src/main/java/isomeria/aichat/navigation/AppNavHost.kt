package isomeria.aichat.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import isomeria.aichat.feature.backup.ExportScreen
import isomeria.aichat.feature.backup.RestoreScreen
import isomeria.aichat.feature.chat.ChatScreen
import isomeria.aichat.feature.chatinfo.ChatInfoScreen
import isomeria.aichat.feature.chatinfo.ChatSearchScreen
import isomeria.aichat.feature.chat.ImageViewerScreen
import isomeria.aichat.feature.chatlist.SearchScreen
import isomeria.aichat.feature.contactedit.ContactDetailScreen
import isomeria.aichat.feature.contactedit.ContactEditScreen
import isomeria.aichat.feature.contactedit.ContactPromptScreen
import isomeria.aichat.feature.globalmemory.GlobalMemoryCategoryScreen
import isomeria.aichat.feature.globalmemory.GlobalMemoryItemScreen
import isomeria.aichat.feature.globalmemory.GlobalMemoryScreen
import isomeria.aichat.feature.globalmemory.GlobalMemoryTopicScreen
import isomeria.aichat.feature.main.MainScreen
import isomeria.aichat.feature.replyprefs.ReplyPreferencesScreen
import isomeria.aichat.feature.me.ProfileScreen
import isomeria.aichat.feature.memory.MemoryEditScreen
import isomeria.aichat.feature.memory.MemoryScreen
import isomeria.aichat.feature.settings.LicensesScreen
import isomeria.aichat.feature.settings.ModelEditScreen
import isomeria.aichat.feature.settings.ProviderEditScreen
import isomeria.aichat.feature.settings.ProviderListScreen
import isomeria.aichat.feature.settings.SettingsNavigation
import isomeria.aichat.feature.settings.SettingsSection
import isomeria.aichat.feature.settings.SettingsSectionScreen
import isomeria.aichat.feature.settings.CrashLogsScreen
import isomeria.aichat.feature.settings.StorageScreen
import isomeria.aichat.feature.usage.UsageDetailScreen
import isomeria.aichat.feature.usage.UsageScreen
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable data object MainRoute
/** [focusMessageId] 不为 0 时，打开后定位到这条消息并高亮（从搜索结果进来） */
@Serializable data class ChatRoute(val contactId: Long, val focusMessageId: Long = 0L)
@Serializable data class ContactDetailRoute(val contactId: Long)

/** contactId 为 -1 时是新建 */
@Serializable data class ContactEditRoute(val contactId: Long = NEW_CONTACT)

/** 编辑联系人里的「系统提示词」单独一页，和编辑页共用 ViewModel（草稿） */
@Serializable data object ContactPromptRoute
@Serializable data object CrashLogsRoute
@Serializable data object StorageRoute
@Serializable data object ProviderListRoute

/** providerId 为 -1 时是添加新服务商 */
@Serializable data class ProviderEditRoute(val providerId: Long = NEW_PROVIDER)
@Serializable data class ModelEditRoute(val providerId: Long, val modelId: String)
@Serializable data class MemoryRoute(val contactId: Long)
@Serializable data class MemoryEditRoute(val memoryId: Long)
@Serializable data object UsageRoute

/** 某一天各联系人的用量，或某个联系人每天的用量；不用的条件是 [NO_USAGE_FILTER] */
@Serializable data class UsageDetailRoute(val day: Long = NO_USAGE_FILTER, val contactId: Long = NO_USAGE_FILTER)
@Serializable data object LicensesRoute
/** 「我」页的一栏设置；[section] 是 [SettingsSection] 的名字 */
@Serializable data class SettingsSectionRoute(val section: String)
@Serializable data object ProfileRoute
@Serializable data object GlobalMemoryRoute
@Serializable data class GlobalMemoryCategoryRoute(val categoryId: Long)
@Serializable data class GlobalMemoryTopicRoute(val topicId: Long)

/** [itemId] 为 [NEW_GLOBAL_ITEM] 时是在 [topicId] 下新加一条 */
@Serializable data class GlobalMemoryItemRoute(val topicId: Long, val itemId: Long = NEW_GLOBAL_ITEM)
@Serializable data object ReplyPreferencesRoute
@Serializable data object ProactiveRoute
@Serializable data object VoiceSettingsRoute
/** [kind] 是 ProviderKind 的名字：TTS 或 ASR */
@Serializable data class VoiceProviderListRoute(val kind: String)
@Serializable data class VoiceProviderEditRoute(val kind: String, val providerId: Long = NEW_PROVIDER)
/** 0.7.1：系统语音（本机）、离线识别（本机） */
@Serializable data object SystemTtsRoute
@Serializable data object OfflineAsrRoute
@Serializable data object SearchRoute
@Serializable data class ChatInfoRoute(val contactId: Long)

/** 聊天信息页「查找聊天记录」；从全局搜索的「N条相关聊天记录」进来时带上关键词 */
@Serializable data class ChatSearchRoute(val contactId: Long, val query: String = "")
@Serializable data object ExportRoute
@Serializable data object RestoreRoute
@Serializable data class ImageViewerRoute(val path: String)

const val NEW_CONTACT = -1L
const val NEW_PROVIDER = -1L
const val NO_USAGE_FILTER = -1L
const val NEW_GLOBAL_ITEM = -1L

/**
 * 单 Activity + Navigation Compose。二级页面从右侧推入；返回按微信的动画滑出，
 * 侧边返回手势和点返回箭头效果相同（见 PageTransitions.kt）。
 */
@Composable
fun AppNavHost(openChatRequest: StateFlow<Long?>, onOpenChatHandled: () -> Unit) {
    val nav = rememberNavController()
    val pendingChat by openChatRequest.collectAsStateWithLifecycle()
    LaunchedEffect(pendingChat) {
        val id = pendingChat ?: return@LaunchedEffect
        nav.navigate(ChatRoute(id)) {
            popUpTo(MainRoute)
            launchSingleTop = true
        }
        onOpenChatHandled()
    }

    NavHost(
        navController = nav,
        startDestination = MainRoute,
        modifier = Modifier.fillMaxSize().background(WeTheme.colors.pageBackground),
        enterTransition = pushEnter,
        exitTransition = pushExit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
    ) {
        composable<MainRoute> {
            MainScreen(
                onOpenChat = { nav.navigate(ChatRoute(it)) },
                onOpenContact = { nav.navigate(ContactDetailRoute(it)) },
                onNewContact = { nav.navigate(ContactEditRoute()) },
                onSearch = { nav.navigate(SearchRoute) },
                onOpenProfile = { nav.navigate(ProfileRoute) },
                onOpenSettings = { nav.navigate(SettingsSectionRoute(it.name)) },
                onOpenGlobalMemory = { nav.navigate(GlobalMemoryRoute) },
                onOpenReplyPreferences = { nav.navigate(ReplyPreferencesRoute) },
                onOpenProactive = { nav.navigate(ProactiveRoute) },
                onOpenVoice = { nav.navigate(VoiceSettingsRoute) },
            )
        }
        page<GlobalMemoryRoute>(nav) {
            GlobalMemoryScreen(onBack = nav::popBackStackSafely, onOpenCategory = { nav.navigate(GlobalMemoryCategoryRoute(it)) })
        }
        page<GlobalMemoryCategoryRoute>(nav) { entry ->
            GlobalMemoryCategoryScreen(
                categoryId = entry.toRoute<GlobalMemoryCategoryRoute>().categoryId,
                onBack = nav::popBackStackSafely,
                onOpenTopic = { nav.navigate(GlobalMemoryTopicRoute(it)) },
            )
        }
        page<GlobalMemoryTopicRoute>(nav) { entry ->
            GlobalMemoryTopicScreen(
                topicId = entry.toRoute<GlobalMemoryTopicRoute>().topicId,
                onBack = nav::popBackStackSafely,
                onOpenItem = { topic, item -> nav.navigate(GlobalMemoryItemRoute(topic, item)) },
            )
        }
        page<GlobalMemoryItemRoute>(nav) { GlobalMemoryItemScreen(onBack = nav::popBackStackSafely) }
        page<ReplyPreferencesRoute>(nav) { ReplyPreferencesScreen(onBack = nav::popBackStackSafely) }
        page<ProactiveRoute>(nav) {
            isomeria.aichat.feature.proactive.ProactiveScreen(
                onBack = nav::popBackStackSafely,
                onOpenContact = { nav.navigate(ContactDetailRoute(it)) },
            )
        }
        page<VoiceSettingsRoute>(nav) {
            isomeria.aichat.feature.voice.VoiceSettingsScreen(
                onBack = nav::popBackStackSafely,
                onOpenProviders = { nav.navigate(VoiceProviderListRoute(it.name)) },
            )
        }
        page<VoiceProviderListRoute>(nav) {
            isomeria.aichat.feature.voice.VoiceProviderListScreen(
                onBack = nav::popBackStackSafely,
                onOpen = { kind, id -> nav.navigate(VoiceProviderEditRoute(kind.name, id)) },
                onAdd = { nav.navigate(VoiceProviderEditRoute(it.name)) },
                onOpenLocal = { nav.navigate(if (it == isomeria.aichat.data.db.ProviderKind.TTS) SystemTtsRoute else OfflineAsrRoute) },
            )
        }
        page<VoiceProviderEditRoute>(nav) { isomeria.aichat.feature.voice.VoiceProviderEditScreen(onBack = nav::popBackStackSafely) }
        page<SystemTtsRoute>(nav) { isomeria.aichat.feature.voice.SystemTtsScreen(onBack = nav::popBackStackSafely) }
        page<OfflineAsrRoute>(nav) { isomeria.aichat.feature.voice.OfflineAsrScreen(onBack = nav::popBackStackSafely) }
        page<SettingsSectionRoute>(nav) { entry ->
            SettingsSectionScreen(
                section = SettingsSection.valueOf(entry.toRoute<SettingsSectionRoute>().section),
                onBack = nav::popBackStackSafely,
                navigation = SettingsNavigation(
                    onOpenLlm = { nav.navigate(ProviderListRoute) },
                    onOpenUsage = { nav.navigate(UsageRoute) },
                    onOpenLicenses = { nav.navigate(LicensesRoute) },
                    onOpenExport = { nav.navigate(ExportRoute) },
                    onOpenRestore = { nav.navigate(RestoreRoute) },
                    onOpenCrashLogs = { nav.navigate(CrashLogsRoute) },
                    onOpenStorage = { nav.navigate(StorageRoute) },
                    onOpenTts = { nav.navigate(VoiceProviderListRoute(isomeria.aichat.data.db.ProviderKind.TTS.name)) },
                    onOpenAsr = { nav.navigate(VoiceProviderListRoute(isomeria.aichat.data.db.ProviderKind.ASR.name)) },
                ),
            )
        }
        page<ChatRoute>(nav) {
            ChatScreen(
                onBack = nav::popBackStackSafely,
                onOpenContact = { nav.navigate(ContactDetailRoute(it)) },
                onOpenChatInfo = { nav.navigate(ChatInfoRoute(it)) },
                onOpenImage = { nav.navigate(ImageViewerRoute(it)) },
                onOpenProvider = { nav.navigate(ProviderEditRoute(it)) },
                onEditContact = { nav.navigate(ContactEditRoute(it)) },
                onOpenModel = { id, model -> nav.navigate(ModelEditRoute(id, model)) },
                onOpenVoiceSettings = { nav.navigate(VoiceProviderListRoute(isomeria.aichat.data.db.ProviderKind.ASR.name)) },
            )
        }
        page<ChatInfoRoute>(nav) {
            ChatInfoScreen(
                onBack = nav::popBackStackSafely,
                onOpenContact = { nav.navigate(ContactDetailRoute(it)) },
                onSearchHistory = { nav.navigate(ChatSearchRoute(it)) },
            )
        }
        page<ChatSearchRoute>(nav) {
            ChatSearchScreen(
                onBack = nav::popBackStackSafely,
                onOpenMessage = nav::openMessage,
            )
        }
        page<ContactDetailRoute>(nav) { entry ->
            val route = entry.toRoute<ContactDetailRoute>()
            ContactDetailScreen(
                onBack = nav::popBackStackSafely,
                onSendMessage = {
                    // 已经是从这个聊天进来的就直接返回，否则打开聊天
                    val previous = nav.previousBackStackEntry
                    if (previous?.destination?.hasRoute<ChatRoute>() == true &&
                        previous.toRoute<ChatRoute>().contactId == route.contactId
                    ) {
                        nav.popBackStackSafely()
                    } else {
                        nav.navigate(ChatRoute(route.contactId)) { popUpTo(MainRoute) }
                    }
                },
                onEdit = { nav.navigate(ContactEditRoute(route.contactId)) },
                onOpenMemory = { nav.navigate(MemoryRoute(route.contactId)) },
                onOpenModel = { id, model -> nav.navigate(ModelEditRoute(id, model)) },
            )
        }
        page<ModelEditRoute>(nav) { ModelEditScreen(onBack = nav::popBackStackSafely) }
        page<MemoryRoute>(nav) {
            MemoryScreen(onBack = nav::popBackStackSafely, onOpen = { nav.navigate(MemoryEditRoute(it)) }, onOpenProvider = { nav.navigate(ProviderEditRoute(it)) },
                onOpenModel = { id, model -> nav.navigate(ModelEditRoute(id, model)) })
        }
        page<MemoryEditRoute>(nav) { MemoryEditScreen(onBack = nav::popBackStackSafely) }
        page<ContactEditRoute>(nav) {
            ContactEditScreen(
                onBack = nav::popBackStackSafely,
                onCreated = { id ->
                    nav.navigate(ContactDetailRoute(id)) { popUpTo(MainRoute) }
                },
                onDeleted = { nav.popBackStack(MainRoute, inclusive = false) },
                onOpenProvider = { nav.navigate(ProviderEditRoute(it)) },
                onOpenModel = { id, model -> nav.navigate(ModelEditRoute(id, model)) },
                onOpenPrompt = { nav.navigate(ContactPromptRoute) },
            )
        }
        page<ContactPromptRoute>(nav) { entry ->
            val editEntry = remember(entry) { nav.getBackStackEntry<ContactEditRoute>() }
            ContactPromptScreen(onBack = nav::popBackStackSafely, viewModel = hiltViewModel(editEntry))
        }
        page<ExportRoute>(nav) { ExportScreen(onBack = nav::popBackStackSafely) }
        page<CrashLogsRoute>(nav) { CrashLogsScreen(onBack = nav::popBackStackSafely) }
        page<StorageRoute>(nav) { StorageScreen(onBack = nav::popBackStackSafely) }
        page<RestoreRoute>(nav) { RestoreScreen(onBack = nav::popBackStackSafely) }
        page<ProviderListRoute>(nav) {
            ProviderListScreen(
                onBack = nav::popBackStackSafely,
                onOpen = { nav.navigate(ProviderEditRoute(it)) },
                onAdd = { nav.navigate(ProviderEditRoute()) },
            )
        }
        page<ProviderEditRoute>(nav) { ProviderEditScreen(onBack = nav::popBackStackSafely, onOpenModel = { id, model -> nav.navigate(ModelEditRoute(id, model)) }) }
        page<UsageRoute>(nav) {
            UsageScreen(
                onBack = nav::popBackStackSafely,
                onOpenDay = { nav.navigate(UsageDetailRoute(day = it)) },
                onOpenContact = { nav.navigate(UsageDetailRoute(contactId = it)) },
            )
        }
        page<UsageDetailRoute>(nav) { UsageDetailScreen(onBack = nav::popBackStackSafely) }
        page<LicensesRoute>(nav) { LicensesScreen(onBack = nav::popBackStackSafely) }
        page<ProfileRoute>(nav) { ProfileScreen(onBack = nav::popBackStackSafely) }
        page<SearchRoute>(nav) {
            SearchScreen(
                onBack = nav::popBackStackSafely,
                onOpenChat = { nav.navigate(ChatRoute(it)) { popUpTo(MainRoute) } },
                onOpenMessage = nav::openMessage,
                onOpenChatSearch = { contactId, query -> nav.navigate(ChatSearchRoute(contactId, query)) },
            )
        }
        page<ImageViewerRoute>(nav) { entry ->
            ImageViewerScreen(path = entry.toRoute<ImageViewerRoute>().path, onBack = nav::popBackStackSafely)
        }
    }
}

/** 二级页面：套上 PageFrame（返回接管 + 左边缘阴影） */
private inline fun <reified T : Any> NavGraphBuilder.page(
    nav: NavHostController,
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        PageFrame(transition, onBack = nav::popBackStackSafely) { content(entry) }
    }
}

/** 打开聊天并定位到某条消息（搜索结果）。返回时直接回到首页，和微信一样 */
private fun NavHostController.openMessage(contactId: Long, messageId: Long) {
    navigate(ChatRoute(contactId, focusMessageId = messageId)) { popUpTo(MainRoute) }
}

/** 连点返回时不要把首页也弹掉 */
private fun NavHostController.popBackStackSafely() {
    if (previousBackStackEntry != null) popBackStack()
}
