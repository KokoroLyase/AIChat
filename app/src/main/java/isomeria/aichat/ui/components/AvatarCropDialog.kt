package isomeria.aichat.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.scale
import isomeria.aichat.R
import isomeria.aichat.ui.theme.WeTheme
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 头像裁剪：图片铺满中间的正方形取景框，双指缩放、拖动选择范围，完成后输出 512×512 的正方形。
 */
@Composable
fun AvatarCropDialog(source: Bitmap, onDismiss: () -> Unit, onCropped: (Bitmap) -> Unit) {
    val image: ImageBitmap = remember(source) { source.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var frameSize by remember { mutableFloatStateOf(0f) }

    WeDialogWindow(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val close = LocalDialogClose.current
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Text(
                stringResource(R.string.crop_title),
                color = Color.White,
                style = WeTheme.typography.topBarTitle,
                modifier = Modifier.statusBarsPadding().padding(16.dp).align(Alignment.CenterHorizontally),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val density = LocalDensity.current
                val frame = with(density) { (minOf(maxWidth, maxHeight) - 32.dp).toPx() }
                frameSize = frame
                val baseScale = max(frame / source.width, frame / source.height)

                fun clamp(o: Offset, z: Float): Offset {
                    val s = baseScale * z
                    val maxX = ((source.width * s - frame) / 2).coerceAtLeast(0f)
                    val maxY = ((source.height * s - frame) / 2).coerceAtLeast(0f)
                    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(source) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                offset = clamp(offset + pan, zoom)
                            }
                        },
                ) {
                    val s = baseScale * zoom
                    val center = Offset(size.width / 2, size.height / 2)
                    withTransform({
                        translate(center.x + offset.x - source.width * s / 2, center.y + offset.y - source.height * s / 2)
                        scale(s, s, pivot = Offset.Zero)
                    }) {
                        drawImage(image, IntOffset.Zero, IntSize(source.width, source.height))
                    }
                    // 取景框外压暗
                    val left = center.x - frame / 2
                    val top = center.y - frame / 2
                    val dim = Color.Black.copy(alpha = 0.55f)
                    drawRect(dim, Offset.Zero, Size(size.width, top))
                    drawRect(dim, Offset(0f, top + frame), Size(size.width, size.height - top - frame))
                    drawRect(dim, Offset(0f, top), Size(left, frame))
                    drawRect(dim, Offset(left + frame, top), Size(size.width - left - frame, frame))
                    drawRect(Color.White, Offset(left, top), Size(frame, frame), style = Stroke(1.dp.toPx()))
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.action_cancel),
                    color = Color.White,
                    style = WeTheme.typography.listTitle,
                    modifier = Modifier.clickable { close(onDismiss) }.padding(16.dp),
                )
                Spacer(Modifier.weight(1f))
                WeButton(
                    text = stringResource(R.string.action_done),
                    onClick = {
                        val frame = frameSize
                        if (frame > 0f) {
                            val cropped = crop(source, frame, zoom, offset)
                            close { onCropped(cropped) }
                        }
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

private fun crop(source: Bitmap, frame: Float, zoom: Float, offset: Offset): Bitmap {
    val s = max(frame / source.width, frame / source.height) * zoom
    val sizeInSource = (frame / s).roundToInt().coerceIn(1, minOf(source.width, source.height))
    val left = (source.width / 2f - (frame / 2 + offset.x) / s).roundToInt().coerceIn(0, source.width - sizeInSource)
    val top = (source.height / 2f - (frame / 2 + offset.y) / s).roundToInt().coerceIn(0, source.height - sizeInSource)
    val square = Bitmap.createBitmap(source, left, top, sizeInSource, sizeInSource)
    return square.scale(AVATAR_SIZE, AVATAR_SIZE)
}

private const val AVATAR_SIZE = 512
