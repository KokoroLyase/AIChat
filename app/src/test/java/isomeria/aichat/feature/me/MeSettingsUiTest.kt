package isomeria.aichat.feature.me

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import isomeria.aichat.R
import isomeria.aichat.feature.settings.SettingsActions
import isomeria.aichat.feature.settings.SettingsNavigation
import isomeria.aichat.feature.settings.SettingsSection
import isomeria.aichat.feature.settings.SettingsSectionContent
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

/**
 * 0.4.2「我」页：个人卡片下是「模型服务」「通用设置」「数据管理」「关于 APP」四个入口，点进去是各自的设置页；
 * 0.4.1 设置页的 20 项全部分到这四页里，点击走到原来的去处；不再有「设置」入口。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class MeSettingsUiTest {
    @get:Rule val compose = createComposeRule()
    private val app = RuntimeEnvironment.getApplication()
    private val calls = mutableListOf<String>()
    private fun text(id: Int) = app.getString(id)

    private val items = mapOf(
        SettingsSection.MODELS to listOf(R.string.settings_llm, R.string.usage_title, R.string.settings_tts, R.string.settings_asr),
        SettingsSection.GENERAL to listOf(
            R.string.settings_dark_mode, R.string.settings_font_size, R.string.settings_notify, R.string.settings_notify_preview,
            R.string.settings_notify_permission_off, R.string.settings_battery,
        ),
        SettingsSection.DATA to listOf(R.string.storage_title, R.string.settings_export, R.string.settings_restore, R.string.settings_clear_all),
        SettingsSection.ABOUT to listOf(
            R.string.crash_title, R.string.settings_version, R.string.settings_check_update, R.string.settings_auto_update,
            R.string.settings_licenses, R.string.settings_github,
        ),
    )

    private fun section(section: SettingsSection, state: SettingsUiState = SettingsUiState(hasKey = true, canNotify = false)) = compose.setContent {
        AIChatTheme(darkTheme = false) {
            SettingsSectionContent(
                section, state, onBack = { calls += "back" },
                navigation = SettingsNavigation(
                    onOpenLlm = { calls += "llm" }, onOpenUsage = { calls += "usage" }, onOpenLicenses = { calls += "licenses" },
                    onOpenExport = { calls += "export" }, onOpenRestore = { calls += "restore" },
                    onOpenCrashLogs = { calls += "crash" }, onOpenStorage = { calls += "storage" },
                ),
                actions = SettingsActions(
                    onTheme = { calls += "theme" }, onFont = { calls += "font" }, onNotifyEnabled = { calls += "notify=$it" },
                    onNotifyPreview = { calls += "preview=$it" }, onNotifyPermission = { calls += "permission" },
                    onBattery = { calls += "battery" }, onClearAll = { calls += "clearAll" }, onCheckUpdate = { calls += "check" },
                    onAutoCheckUpdate = { calls += "auto=$it" }, onGithub = { calls += "github" },
                ),
            )
        }
    }

    @Test
    fun mePageHasFourEntriesInOrder_andOpensEachSection() {
        val opened = mutableListOf<SettingsSection>()
        compose.setContent { AIChatTheme(darkTheme = false) { MeContent("Isomeria", null, { calls += "profile" }, { opened += it }) } }
        assertEquals(listOf("模型服务", "通用设置", "数据管理", "关于 APP"), SettingsSection.entries.map { text(it.title) })
        val tops = SettingsSection.entries.map { compose.onNodeWithText(text(it.title)).getUnclippedBoundsInRoot().top }
        assertTrue(tops.zipWithNext().all { (a, b) -> a < b })
        SettingsSection.entries.forEach { compose.onNodeWithText(text(it.title)).performClick() }
        assertEquals(SettingsSection.entries, opened)
        // 设置项本身不在「我」页上，也没有单独的「设置」入口
        compose.onNodeWithText(text(R.string.settings_dark_mode)).assertDoesNotExist()
        compose.onNodeWithText("设置").assertDoesNotExist()
        compose.onNodeWithText("Isomeria").performClick()
        assertEquals(listOf("profile"), calls)
    }

    @Test
    fun allTwentySettingsAreSplitAcrossTheFourPages() {
        assertEquals(20, items.values.sumOf { it.size })
        assertEquals(SettingsSection.entries.toSet(), items.keys)
    }

    private fun checkSection(section: SettingsSection, taps: List<Pair<Int, String>>) {
        section(section)
        compose.onNodeWithText(text(section.title)).assertExists() // 顶栏标题
        val rows = items.getValue(section)
        for ((a, b) in rows.zipWithNext()) {
            compose.onNodeWithText(text(b)).performScrollTo()
            assertTrue("${text(a)} 应在 ${text(b)} 上方",
                compose.onNodeWithText(text(a)).getUnclippedBoundsInRoot().top < compose.onNodeWithText(text(b)).getUnclippedBoundsInRoot().top)
        }
        // 别的栏的项不在这一页
        items.filterKeys { it != section }.values.flatten().forEach { compose.onNodeWithText(text(it)).assertDoesNotExist() }
        for ((id, expected) in taps) {
            calls.clear()
            compose.onNodeWithText(text(id)).performScrollTo().performClick()
            assertEquals(text(id), listOf(expected), calls)
        }
    }

    @Test fun modelsPage() = checkSection(SettingsSection.MODELS, listOf(R.string.settings_llm to "llm", R.string.usage_title to "usage"))

    @Test fun generalPage() = checkSection(SettingsSection.GENERAL, listOf(
        R.string.settings_dark_mode to "theme", R.string.settings_font_size to "font",
        R.string.settings_notify_permission_off to "permission", R.string.settings_battery to "battery",
    ))

    @Test fun dataPage() = checkSection(SettingsSection.DATA, listOf(
        R.string.storage_title to "storage", R.string.settings_export to "export", R.string.settings_restore to "restore",
        R.string.settings_clear_all to "clearAll",
    ))

    @Test fun aboutPage() = checkSection(SettingsSection.ABOUT, listOf(
        R.string.crash_title to "crash", R.string.settings_check_update to "check", R.string.settings_licenses to "licenses",
        R.string.settings_github to "github",
    ))

    @Test
    fun voiceRowsAreInert() {
        section(SettingsSection.MODELS)
        compose.onNodeWithText(text(R.string.settings_tts)).performClick()
        assertTrue(calls.isEmpty())
    }

    @Test
    fun permissionHintOnlyWhenNotificationsOnButBlocked() {
        section(SettingsSection.GENERAL, SettingsUiState(notifyEnabled = true, canNotify = true))
        compose.onNodeWithText(text(R.string.settings_notify_permission_off)).assertDoesNotExist()
    }

    @Test
    fun newerReleaseShowsOnCheckUpdateRow() {
        section(SettingsSection.ABOUT, SettingsUiState(newerVersion = "9.9.9"))
        compose.onNodeWithText(app.getString(R.string.settings_new_version, "9.9.9")).assertExists()
    }
}
