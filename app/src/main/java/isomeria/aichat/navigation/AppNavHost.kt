package isomeria.aichat.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import isomeria.aichat.feature.main.MainScreen
import isomeria.aichat.feature.me.ProfileScreen
import isomeria.aichat.feature.settings.LicensesScreen
import isomeria.aichat.feature.settings.LlmSettingsScreen
import isomeria.aichat.feature.settings.SettingsScreen
import isomeria.aichat.ui.theme.WeTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable data object MainRoute
/** [focusMessageId] 不为 0 时，打开后定位到这条消息并高亮（从搜索结果进来） */
@Serializable data class ChatRoute(val contactId: Long, val focusMessageId: Long = 0L)
@Serializable data class ContactDetailRoute(val contactId: Long)

/** contactId 为 -1 时是新建 */
@Serializable data class ContactEditRoute(val contactId: Long = NEW_CONTACT)
@Serializable data object SettingsRoute
@Serializable data object LlmSettingsRoute
@Serializable data object LicensesRoute
@Serializable data object ProfileRoute
@Serializable data object SearchRoute
@Serializable data class ChatInfoRoute(val contactId: Long)

/** 聊天信息页「查找聊天记录」；从全局搜索的「N条相关聊天记录」进来时带上关键词 */
@Serializable data class ChatSearchRoute(val contactId: Long, val query: String = "")
@Serializable data object ExportRoute
@Serializable data object RestoreRoute
@Serializable data class ImageViewerRoute(val path: String)

const val NEW_CONTACT = -1L

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
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenProfile = { nav.navigate(ProfileRoute) },
            )
        }
        page<ChatRoute>(nav) {
            ChatScreen(
                onBack = nav::popBackStackSafely,
                onOpenContact = { nav.navigate(ContactDetailRoute(it)) },
                onOpenChatInfo = { nav.navigate(ChatInfoRoute(it)) },
                onOpenImage = { nav.navigate(ImageViewerRoute(it)) },
                onOpenLlmSettings = { nav.navigate(LlmSettingsRoute) },
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
            )
        }
        page<ContactEditRoute>(nav) {
            ContactEditScreen(
                onBack = nav::popBackStackSafely,
                onCreated = { id ->
                    nav.navigate(ContactDetailRoute(id)) { popUpTo(MainRoute) }
                },
                onDeleted = { nav.popBackStack(MainRoute, inclusive = false) },
            )
        }
        page<SettingsRoute>(nav) {
            SettingsScreen(
                onBack = nav::popBackStackSafely,
                onOpenLlm = { nav.navigate(LlmSettingsRoute) },
                onOpenLicenses = { nav.navigate(LicensesRoute) },
                onOpenExport = { nav.navigate(ExportRoute) },
                onOpenRestore = { nav.navigate(RestoreRoute) },
            )
        }
        page<ExportRoute>(nav) { ExportScreen(onBack = nav::popBackStackSafely) }
        page<RestoreRoute>(nav) { RestoreScreen(onBack = nav::popBackStackSafely) }
        page<LlmSettingsRoute>(nav) { LlmSettingsScreen(onBack = nav::popBackStackSafely) }
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
