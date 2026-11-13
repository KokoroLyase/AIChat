package isomeria.aichat.navigation

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import isomeria.aichat.ui.theme.AIChatTheme
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Serializable internal data object TestHomeRoute
@Serializable internal data object TestDetailRoute

/**
 * 0.9.1 预测性返回：二级页面不再拦截返回，侧边手势拖动时页面跟着手指走；
 * 放弃手势滑回原位，松手完成返回；页面里自己的 BackHandler 仍然优先。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp-xxhdpi", application = Application::class)
class PredictiveBackTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private lateinit var dispatcher: OnBackPressedDispatcher
    private val innerHandlerOn = mutableStateOf(false)
    private val innerHandled = mutableIntStateOf(0)

    private fun setUp() {
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                nav = rememberNavController()
                dispatcher = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                NavHost(
                    navController = nav,
                    startDestination = TestHomeRoute,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = pushEnter,
                    exitTransition = pushExit,
                    popEnterTransition = popEnter,
                    popExitTransition = popExit,
                ) {
                    composable<TestHomeRoute> { Box(Modifier.fillMaxSize().background(Color.White).testTag("home")) }
                    composable<TestDetailRoute> {
                        PageFrame(transition) {
                            // 页面里自己的返回（比如收起面板）：开着时先处理它
                            BackHandler(enabled = innerHandlerOn.value) { innerHandled.intValue++ }
                            Box(Modifier.fillMaxSize().background(Color.Gray).testTag("detail"))
                        }
                    }
                }
            }
        }
        compose.runOnUiThread { nav.navigate(TestDetailRoute) }
        compose.waitForIdle()
        assertEquals(0.dp, compose.onNodeWithTag("detail").getBoundsInRoot().left)
    }

    private fun event(progress: Float) = BackEventCompat(touchX = 400f * progress, touchY = 400f, progress = progress, swipeEdge = BackEventCompat.EDGE_LEFT)
    private fun onDetail() = nav.currentBackStackEntry?.destination?.route?.contains("TestDetailRoute") == true

    @Test fun pageFollowsFingerWhileDragging() {
        setUp()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.dispatchOnBackProgressed(event(0.5f))
        }
        compose.waitForIdle()
        val half = compose.onNodeWithTag("detail").getBoundsInRoot().left
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(0.8f)) }
        compose.waitForIdle()
        val further = compose.onNodeWithTag("detail").getBoundsInRoot().left
        assertTrue("page should move right while dragging: $half", half > 0.dp && half < 400.dp)
        assertTrue("page should keep following: $half -> $further", further > half)
        assertTrue(onDetail())
    }

    @Test fun cancelledGestureSlidesBack() {
        setUp()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.dispatchOnBackProgressed(event(0.6f))
        }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertTrue(onDetail())
        assertEquals(0.dp, compose.onNodeWithTag("detail").getBoundsInRoot().left)
    }

    @Test fun releasedGestureGoesBack() {
        setUp()
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(event(0f))
            dispatcher.dispatchOnBackProgressed(event(0.6f))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertTrue(!onDetail())
        compose.onNodeWithTag("detail").assertDoesNotExist()
    }

    @Test fun pageOwnBackHandlerStillWins() {
        setUp()
        innerHandlerOn.value = true
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, innerHandled.intValue)
        assertTrue(onDetail())
        // 关掉以后返回才回到上一页
        innerHandlerOn.value = false
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(!onDetail())
    }
}
