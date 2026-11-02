package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme
import isomeria.aichat.update.UpdateFailure
import isomeria.aichat.update.UpdateInstallState
import java.util.Locale

/** 正在下载更新：进度条和「取消」。点外面和返回键不关闭，避免误触中断下载。 */
@Composable
fun UpdateProgressDialog(state: UpdateInstallState.Downloading, onCancel: () -> Unit) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Column(
            Modifier
                .padding(horizontal = 40.dp)
                .widthIn(max = dimens.dialogMaxWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.dialogCorner))
                .background(colors.dialogBackground),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    stringResource(R.string.update_downloading, state.release.version),
                    style = WeTheme.typography.dialogTitle,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.brand,
                        trackColor = colors.switchTrackOff,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                    Text(
                        stringResource(R.string.update_download_progress, megabytes(state.done), megabytes(state.total)),
                        style = WeTheme.typography.small,
                        color = colors.textTertiary,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.brand,
                        trackColor = colors.switchTrackOff,
                        strokeCap = StrokeCap.Round,
                        gapSize = 0.dp,
                    )
                }
            }
            UpdateButton(stringResource(R.string.action_cancel), colors.textPrimary, onCancel)
        }
    }
}

/** 下载或校验失败：说明原因，提供「在浏览器中打开」。 */
@Composable
fun UpdateFailedDialog(failure: UpdateFailure, onOpenBrowser: () -> Unit, onDismiss: () -> Unit) {
    WeDialog(
        title = stringResource(R.string.update_download_failed_title),
        message = stringResource(
            when (failure) {
                UpdateFailure.MISSING_ASSET -> R.string.update_failure_missing
                UpdateFailure.NETWORK -> R.string.update_failure_network
                UpdateFailure.CHECKSUM -> R.string.update_failure_checksum
                UpdateFailure.INVALID_APK -> R.string.update_failure_invalid
            },
        ),
        confirmText = stringResource(R.string.update_open_browser),
        onDismiss = onDismiss,
        onConfirm = onOpenBrowser,
    )
}

/** 系统还没允许智信安装应用：说明后跳到系统设置。 */
@Composable
fun UpdateInstallPermissionDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    WeDialog(
        title = stringResource(R.string.update_permission_title),
        message = stringResource(R.string.update_permission_message),
        confirmText = stringResource(R.string.action_go_settings),
        onDismiss = onDismiss,
        onConfirm = onOpenSettings,
    )
}

private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1_048_576.0)
