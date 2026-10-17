package app.bestnote.feature.editor.math

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Strokes written on the "rewrite by hand" pad (MATH-01), in pad pixels. */
internal class PadInk {
    val strokes: SnapshotStateList<List<Offset>> = mutableStateListOf()

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun clear() = strokes.clear()

    /** The ink as black on white PNG, cropped to what was written with a margin (what vision models read best). */
    fun png(strokeWidthPx: Float): ByteArray? {
        val pts = strokes.flatten()
        if (pts.isEmpty()) return null
        val left = pts.minOf { it.x }
        val top = pts.minOf { it.y }
        val w = max(1f, pts.maxOf { it.x } - left)
        val h = max(1f, pts.maxOf { it.y } - top)
        val scale = min(MAX_SIDE / max(w, h), max(1f, MIN_SIDE / max(w, h)))
        val bmp = Bitmap.createBitmap((w * scale + 2 * MARGIN).toInt(), (h * scale + 2 * MARGIN).toInt(), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = (strokeWidthPx * scale).coerceIn(MIN_PEN, MAX_PEN)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        strokes.forEach { s ->
            val path = android.graphics.Path()
            s.forEachIndexed { i, p ->
                val x = (p.x - left) * scale + MARGIN
                val y = (p.y - top) * scale + MARGIN
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (s.size == 1) path.lineTo((s[0].x - left) * scale + MARGIN + 0.1f, (s[0].y - top) * scale + MARGIN)
            c.drawPath(path, paint)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private companion object {
        const val MAX_SIDE = 1200f
        const val MIN_SIDE = 480f
        const val MARGIN = 24f
        const val MIN_PEN = 2f
        const val MAX_PEN = 12f
        const val PNG_QUALITY = 100
    }
}

/** A small writing area (pen or finger) for writing part of an equation again. */
@Composable
internal fun HandwritingPad(ink: PadInk, modifier: Modifier = Modifier) {
    val inkColor = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(12.dp)
    Canvas(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(ink) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val pts = mutableStateListOf(down.position)
                    ink.strokes += pts
                    val index = ink.strokes.lastIndex
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) {
                            change.consume()
                            pts += change.position
                            ink.strokes[index] = pts.toList()
                        }
                    }
                }
            },
    ) {
        val stroke = Stroke(width = PEN_DP.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        ink.strokes.forEach { s ->
            if (s.isEmpty()) return@forEach
            val path = Path().apply {
                moveTo(s[0].x, s[0].y)
                if (s.size == 1) lineTo(s[0].x + 0.1f, s[0].y)
                s.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, inkColor, style = stroke)
        }
    }
}

internal const val PEN_DP = 3f
