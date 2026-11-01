package isomeria.aichat.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 键盘弹出时，返回键只收起键盘，不退出页面。
 *
 * 开启预测性返回后，部分输入法（如 HyperOS 上的输入法）不会先处理返回键，
 * 返回直接交给页面导航，带着键盘退出了页面。放在页面里其他 BackHandler 之后调用，优先级最高。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImeBackHandler() {
    val imeVisible = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = imeVisible) {
        keyboard?.hide()
        focusManager.clearFocus()
    }
}
