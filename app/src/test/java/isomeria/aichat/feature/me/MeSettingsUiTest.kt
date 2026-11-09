package isomeria.aichat.feature.me

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import isomeria.aichat.R
import isomeria.aichat.feature.settings.SettingsActions
import isomeria.aichat.feature.settings.SettingsList
import isomeria.aichat.feature.settings.SettingsNavigation
import isomeria.aichat.feature.settings.SettingsUiState
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 0.4.2「我」页：0.4.1 设置页的每一项都在，分四栏，没有单独的「设置」入口，点击走到原来的去处。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class MeSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()

    private fun page(state: SettingsUiState = SettingsUiState(hasKey = true, canNotify = false)) = compose.setContent {
        AIChatTheme(darkTheme = false) {
            MeContent("Isomeria", null, onOpenProfile = { calls += "profile" }) {
                SettingsList(
                    state,
                    SettingsNavigation(
                        onOpenLlm = { calls += "llm" }, onOpenUsage = { calls += "usage" }, onOpenLicenses = { calls += "licenses" },
                        onOpenExport = { calls += "export" }, onOpenRestore = { calls += "restore" },
                        onOpenCrashLogs = { calls += "crash" }, onOpenStorage = { calls += "storage" },
                    ),
                    SettingsActions(
                        onTheme = { calls += "theme" }, onFont = { calls += "font" }, onNotifyEnabled = { calls += "notify=$it" },
                        onNotifyPreview = { calls += "preview=$it" }, onNotifyPermission = { calls += "permission" },
                        onBattery = { calls += "battery" }, onClearAll = { calls += "clearAll" }, onCheckUpdate = { calls += "check" },
                        onAutoCheckUpdate = { calls += "auto=$it" }, onGithub = { calls += "github" },
                    ),
                )
            }
        }
    }

    private fun text(id: Int) = app.getString(id)

    @Test
    fun everySettingIsOnMePageInFourSections() {
        page()
        val sections = listOf(
            R.string.settings_group_models to listOf(R.string.settings_llm, R.string.usage_title, R.string.settings_tts, R.string.settings_asr),
            R.string.settings_group_general to listOf(
                R.string.settings_dark_mode, R.string.settings_font_size, R.string.settings_notify, R.string.settings_notify_preview,
                R.string.settings_notify_permission_off, R.string.settings_battery,
            ),
            R.string.settings_group_data to listOf(R.string.storage_title, R.string.settings_export, R.string.settings_restore, R.string.settings_clear_all),
            R.string.settings_group_about to listOf(
                R.string.crash_title, R.string.settings_version, R.string.settings_check_update, R.string.settings_auto_update,
                R.string.settings_licenses, R.string.settings_github,
            ),
        )
        assertEquals(listOf("模型服务", "通用设置", "数据管理", "关于 APP"), sections.map { text(it.first) })
        assertEquals(20, sections.sumOf { it.second.size })
        // 每一项都要在上一项下面（含分栏标题）
        for ((a, b) in sections.flatMap { (header, rows) -> listOf(header) + rows }.zipWithNext()) {
            compose.onNodeWithText(text(b)).performScrollTo()
            val top = { id: Int -> compose.onNodeWithText(text(id)).getUnclippedBoundsInRoot().top }
            assertTrue("${text(a)} 应在 ${text(b)} 上方", top(a) < top(b))
        }
        compose.onNodeWithText("设置").assertDoesNotExist()
    }

    @Test
    fun rowsKeepTheirTargets() {
        page()
        val taps = listOf(
            R.string.settings_llm to "llm", R.string.usage_title to "usage", R.string.settings_dark_mode to "theme",
            R.string.settings_font_size to "font", R.string.settings_notify_permission_off to "permission",
            R.string.settings_battery to "battery", R.string.storage_title to "storage", R.string.settings_export to "export",
            R.string.settings_restore to "restore", R.string.settings_clear_all to "clearAll", R.string.crash_title to "crash",
            R.string.settings_check_update to "check", R.string.settings_licenses to "licenses", R.string.settings_github to "github",
        )
        for ((id, expected) in taps) {
            calls.clear()
            compose.onNodeWithText(text(id)).performScrollTo().performClick()
            assertEquals(text(id), listOf(expected), calls)
        }
        calls.clear()
        compose.onNodeWithText("Isomeria").performScrollTo().performClick()
        assertEquals(listOf("profile"), calls)
        // 语音两项还没做，点了没反应
        calls.clear()
        compose.onNodeWithText(text(R.string.settings_tts)).performScrollTo().performClick()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun permissionHintOnlyWhenNotificationsOnButBlocked() {
        page(SettingsUiState(notifyEnabled = true, canNotify = true))
        compose.onNodeWithText(text(R.string.settings_notify_permission_off)).assertDoesNotExist()
    }

    @Test
    fun newerReleaseShowsOnCheckUpdateRow() {
        page(SettingsUiState(newerVersion = "9.9.9"))
        compose.onNodeWithText(app.getString(R.string.settings_new_version, "9.9.9")).performScrollTo().assertExists()
    }
}
