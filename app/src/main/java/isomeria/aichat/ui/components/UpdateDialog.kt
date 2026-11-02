package isomeria.aichat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import isomeria.aichat.R
import isomeria.aichat.update.ReleaseInfo
import isomeria.aichat.ui.theme.WeTheme

/**
 * 发现新版本：标题、版本号、可滚动的更新说明，下面竖排三个按钮「立即更新」「稍后」「忽略此版本」。
 * 点对话框外面等于「稍后」。
 */
@Composable
fun UpdateDialog(
    release: ReleaseInfo,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
) {
    val colors = WeTheme.colors
    val dimens = WeTheme.dimens
    Dialog(onDismissRequest = onLater, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 40.dp)
                .widthIn(max = dimens.dialogMaxWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(dimens.dialogCorner))
                .background(colors.dialogBackground),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.update_title),
                    style = WeTheme.typography.dialogTitle,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.update_version, release.version),
                    style = WeTheme.typography.small,
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
            if (release.notes.isNotBlank()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                ) {
                    Text(release.notes, style = WeTheme.typography.small, color = colors.textPrimary)
                }
            }
            UpdateButton(stringResource(R.string.update_now), colors.brand, onUpdate, bold = true)
            UpdateButton(stringResource(R.string.update_later), colors.textPrimary, onLater)
            UpdateButton(stringResource(R.string.update_ignore), colors.textTertiary, onIgnore)
        }
    }
}

@Composable
private fun UpdateButton(text: String, color: Color, onClick: () -> Unit, bold: Boolean = false) {
    WeDivider(Modifier.background(WeTheme.colors.dialogBackground))
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = WeTheme.typography.listTitle.copy(fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal),
            color = color,
        )
    }
}
