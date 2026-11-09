package isomeria.aichat.feature.backup

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import isomeria.aichat.data.backup.BackupData
import isomeria.aichat.data.backup.BackupManifest
import isomeria.aichat.data.backup.BackupPreview
import isomeria.aichat.data.backup.GlobalCategoryDto
import isomeria.aichat.data.backup.GlobalMemoryCount
import isomeria.aichat.data.backup.RestoreMode
import isomeria.aichat.ui.theme.AIChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 覆盖恢复前问是否保留本机全局记忆：本机有才问；选了以后才做最后确认，确认框写明选了什么；
 * 取消或点空白就放弃覆盖。页面的回调接的是和 ViewModel 相同的状态函数。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class RestoreGlobalMemoryChoiceTest {
    @get:Rule val compose = createComposeRule()

    private val local = GlobalMemoryCount(3, 12)

    private fun preview(withGlobalMemory: Boolean) = BackupPreview(
        File("fixture.zip"),
        BackupManifest(appVersion = "0.5.0", appVersionCode = 500, formatVersion = 5, exportedAt = 1793664000000, contactCount = 2, messageCount = 10),
        BackupData(globalCategories = if (withGlobalMemory) listOf(GlobalCategoryDto(1, "个人信息", 0, 1, 1), GlobalCategoryDto(2, "工作", 1, 1, 1)) else emptyList()),
    )

    private var state by mutableStateOf(RestoreUiState())
    private val restoredWith = mutableListOf<Boolean?>()

    /** 点「开始恢复」后的状态：查到本机的数量 [count] */
    private fun show(withGlobalMemory: Boolean, count: GlobalMemoryCount = local) {
        state = RestoreUiState(preview = preview(withGlobalMemory), mode = RestoreMode.OVERWRITE).askingOverwrite(count)
        compose.setContent {
            AIChatTheme(darkTheme = false) {
                RestoreContent(
                    state, {}, {}, {}, {}, {}, onRestore = { restoredWith += state.keepGlobalMemory }, onDismissError = {},
                    onAskOverwrite = {},
                    onChooseGlobalMemory = { state = state.choseGlobalMemory(it) },
                    onCancelOverwrite = { state = state.cancelledOverwrite() },
                    onDismissGlobalChoice = { state = state.globalChoiceDismissed() },
                )
            }
        }
    }

    @Test
    fun oldBackupWithoutGlobalMemory_keepLocal_thenConfirmSaysKept() {
        show(withGlobalMemory = false)
        compose.onNodeWithText("本机有全局记忆（3 个类别，12 条），这个备份里没有（0.4.2 及更早的备份不含全局记忆）。覆盖恢复时要保留本机的吗？").assertExists()
        compose.onNodeWithText("清空本机的全局记忆").assertExists()

        compose.onNodeWithText("保留本机的全局记忆").performClick()
        compose.waitForIdle()
        // 菜单点完选项也会关闭，不能把刚选的当成取消
        assertTrue(state.confirmOverwrite)
        assertEquals(true, state.keepGlobalMemory)
        compose.onNodeWithText("全局记忆：保留本机的。", substring = true).assertExists()

        compose.onNode(hasText("覆盖") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf<Boolean?>(true), restoredWith)
    }

    @Test
    fun oldBackupWithoutGlobalMemory_clear_thenConfirmSaysCleared() {
        show(withGlobalMemory = false)
        compose.onNodeWithText("清空本机的全局记忆").performClick()
        compose.waitForIdle()
        assertEquals(false, state.keepGlobalMemory)
        compose.onNodeWithText("全局记忆：清空。", substring = true).assertExists()
    }

    @Test
    fun backupWithGlobalMemory_offersUseBackup_thenConfirmSaysReplaced() {
        show(withGlobalMemory = true)
        compose.onNodeWithText("本机有全局记忆（3 个类别，12 条），备份里也有（2 个类别，0 条）。覆盖恢复时用哪一份？").assertExists()
        compose.onNodeWithText("用备份里的全局记忆").performClick()
        compose.waitForIdle()
        assertEquals(false, state.keepGlobalMemory)
        compose.onNodeWithText("全局记忆：换成备份里的。", substring = true).assertExists()
    }

    @Test
    fun cancelInTheMenu_abandonsOverwrite() {
        show(withGlobalMemory = false)
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertNull(state.globalChoice)
        assertFalse(state.confirmOverwrite)
        assertNull(state.keepGlobalMemory)
        assertTrue(restoredWith.isEmpty())
    }

    @Test
    fun noLocalGlobalMemory_skipsTheQuestion_confirmHasNoGlobalNote() {
        show(withGlobalMemory = true, count = GlobalMemoryCount(0, 0))
        assertNull(state.globalChoice)
        assertTrue(state.confirmOverwrite)
        compose.onNodeWithText("全局记忆：", substring = true).assertDoesNotExist()
        compose.onNode(hasText("覆盖") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf<Boolean?>(null), restoredWith)
    }
}
