package app.bestnote.feature.editor

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.bestnote.core.model.CropRect
import app.bestnote.core.model.ImageElement

/**
 * Crops a picture (MEDIA-02): the stored picture with a frame over the part to keep, and four edges to slide.
 * [bytes] is the stored file; the picture itself is never changed, only which part of it shows.
 */
@Composable
internal fun CropDialog(
    element: ImageElement,
    bytes: ByteArray?,
    onConfirm: (CropRect) -> Unit,
    onDismiss: () -> Unit,
) {
    val preview = remember(bytes) {
        bytes?.let {
            val opts = BitmapFactory.Options().apply { inSampleSize = PREVIEW_SAMPLE }
            runCatching { BitmapFactory.decodeByteArray(it, 0, it.size, opts) }.getOrNull()
        }
    }
    var left by remember { mutableFloatStateOf(element.crop.left) }
    var top by remember { mutableFloatStateOf(element.crop.top) }
    var right by remember { mutableFloatStateOf(element.crop.right) }
    var bottom by remember { mutableFloatStateOf(element.crop.bottom) }
    val edge = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_crop)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(element.srcWidth.toFloat() / element.srcHeight)) {
                    if (preview != null) Image(preview.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
                    Canvas(Modifier.matchParentSize()) {
                        val shade = Color.Black.copy(alpha = 0.45f)
                        val l = left * size.width
                        val t = top * size.height
                        val r = right * size.width
                        val b = bottom * size.height
                        drawRect(shade, Offset.Zero, Size(size.width, t))
                        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                        drawRect(shade, Offset(0f, t), Size(l, b - t))
                        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                        drawRect(edge, Offset(l, t), Size(r - l, b - t), style = Stroke(3.dp.toPx()))
                    }
                }
                EdgeSlider(stringResource(R.string.crop_left), left, 0f, right - CropRect.MIN_SPAN) { left = it }
                EdgeSlider(stringResource(R.string.crop_right), right, left + CropRect.MIN_SPAN, 1f) { right = it }
                EdgeSlider(stringResource(R.string.crop_top), top, 0f, bottom - CropRect.MIN_SPAN) { top = it }
                EdgeSlider(stringResource(R.string.crop_bottom), bottom, top + CropRect.MIN_SPAN, 1f) { bottom = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(CropRect.sanitized(left, top, right, bottom)) }) { Text(stringResource(R.string.dialog_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
    )
}

@Composable
private fun EdgeSlider(label: String, value: Float, min: Float, max: Float, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Slider(value.coerceIn(min, max), onChange, valueRange = min..max.coerceAtLeast(min + 0.001f))
    }
}

private const val PREVIEW_SAMPLE = 4
