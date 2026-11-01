package isomeria.aichat.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import isomeria.aichat.feature.chat.ChatScreen
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
@Serializable data class ChatRoute(val contactId: Long)
@Serializable data class ContactDetailRoute(val contactId: Long)

/** contactId 为 -1 时是新建 */
@Serializable data class ContactEditRoute(val contactId: Long = NEW_CONTACT)
@Serializable data object SettingsRoute
@Serializable data object LlmSettingsRoute
@Serializable data object LicensesRoute
@Serializable data object ProfileRoute
@Serializable data object SearchRoute
@Serializable data class ImageViewerRoute(val path: String)

const val NEW_CONTACT = -1L

private const val DURATION = 300

/**
 * 单 Activity + Navigation Compose。二级页面从右侧推入，返回时滑出；
 * 预测性返回手势（Android 14 起）会驱动同一套动画。
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
        enterTransition = { slideInHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { it } },
        exitTransition = { slideOutHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { -it / 4 } },
        popEnterTransition = { slideInHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { -it / 4 } },
        popExitTransition = { slideOutHorizontally(tween(DURATION, easing = FastOutSlowInEasing)) { it } },
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
        composable<ChatRoute> {
            ChatScreen(
                onBack = nav::popBackStackSafely,
                onOpenContact = { nav.navigate(ContactDetailRoute(it)) },
                onOpenImage = { nav.navigate(ImageViewerRoute(it)) },
                onOpenLlmSettings = { nav.navigate(LlmSettingsRoute) },
            )
        }
        composable<ContactDetailRoute> { entry ->
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
        composable<ContactEditRoute> {
            ContactEditScreen(
                onBack = nav::popBackStackSafely,
                onCreated = { id ->
                    nav.navigate(ContactDetailRoute(id)) { popUpTo(MainRoute) }
                },
                onDeleted = { nav.popBackStack(MainRoute, inclusive = false) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = nav::popBackStackSafely,
                onOpenLlm = { nav.navigate(LlmSettingsRoute) },
                onOpenLicenses = { nav.navigate(LicensesRoute) },
            )
        }
        composable<LlmSettingsRoute> { LlmSettingsScreen(onBack = nav::popBackStackSafely) }
        composable<LicensesRoute> { LicensesScreen(onBack = nav::popBackStackSafely) }
        composable<ProfileRoute> { ProfileScreen(onBack = nav::popBackStackSafely) }
        composable<SearchRoute> {
            SearchScreen(
                onBack = nav::popBackStackSafely,
                onOpenChat = { nav.navigate(ChatRoute(it)) { popUpTo(MainRoute) } },
            )
        }
        composable<ImageViewerRoute> { entry ->
            ImageViewerScreen(path = entry.toRoute<ImageViewerRoute>().path, onBack = nav::popBackStackSafely)
        }
    }
}

/** 连点返回时不要把首页也弹掉 */
private fun NavHostController.popBackStackSafely() {
    if (previousBackStackEntry != null) popBackStack()
}
