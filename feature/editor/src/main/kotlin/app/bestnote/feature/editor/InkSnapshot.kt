package app.bestnote.feature.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.ink.strokes.Stroke
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Draws selected handwriting as black ink on white, for "Convert with AI". A plain polyline per
 * stroke at the pen's width is what a vision model reads best; colours and textures would only
 * add noise. Lives here (not in :engine:canvas) because it is a one-off export, not rendering.
 */
internal object InkSnapshot {
    private const val MAX_SIDE = 1600
    private const val MIN_SIDE = 480
    private const val MARGIN = 24f

    fun png(strokes: List<Stroke>): ByteArray? {
        val lines = strokes.map { s -> InkConversion.samplesOf(s) to InkConversion.brushFromInk(s.brush).size }.filter { it.first.isNotEmpty() }
        if (lines.isEmpty()) return null
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        lines.forEach { (pts, _) ->
            pts.forEach { p ->
                left = min(left, p.x); top = min(top, p.y); right = max(right, p.x); bottom = max(bottom, p.y)
            }
        }
        val w = (right - left).coerceAtLeast(1f)
        val h = (bottom - top).coerceAtLeast(1f)
        val long = max(w, h)
        val scale = when {
            long > MAX_SIDE -> MAX_SIDE / long
            long < MIN_SIDE -> MIN_SIDE / long
            else -> 1f
        }
        val bw = (w * scale + 2 * MARGIN).toInt()
        val bh = (h * scale + 2 * MARGIN).toInt()
        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        lines.forEach { (pts, size) ->
            paint.strokeWidth = (size * scale).coerceIn(2f, 14f)
            val path = Path()
            pts.forEachIndexed { i, p ->
                val x = (p.x - left) * scale + MARGIN
                val y = (p.y - top) * scale + MARGIN
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (pts.size == 1) canvas.drawPoint((pts[0].x - left) * scale + MARGIN, (pts[0].y - top) * scale + MARGIN, paint) else canvas.drawPath(path, paint)
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
