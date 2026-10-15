package app.bestnote.engine.ink.lab

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.Tool
import app.bestnote.engine.ink.BrushCatalog
import app.bestnote.engine.ink.InkPalette
import app.bestnote.engine.ink.InkRenderer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Debug-only brush lab: every pen side by side, each drawn with a pressure ramp (light -> full ->
 * light), plus a highlighter test where highlights are added *after* ink and cross each other.
 * Every tuning PR includes a before/after screenshot of this screen.
 */
class BrushLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(BrushLabView(this))
    }
}

private class BrushLabView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val renderer = InkRenderer()
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = InkPalette.GRAPHITE; textSize = 14 * density }
    private var strokes: List<Stroke> = emptyList()
    private val labels = ArrayList<Triple<String, Float, Float>>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        strokes = build(w.toFloat())
    }

    private fun build(width: Float): List<Stroke> {
        labels.clear()
        val out = ArrayList<Stroke>()
        val left = 120 * density
        val rowH = 70 * density
        val size = BrushCatalog.DEFAULT_SIZE * 1.5f
        val pens = listOf(
            "Fountain" to (Tool.Pen to PenStyle.Fountain),
            "Ballpoint" to (Tool.Pen to PenStyle.Ball),
            "Brush" to (Tool.Pen to PenStyle.Brush),
            "Pencil" to (Tool.Pencil to PenStyle.Fountain),
        )
        val colWidth = (width - left - 16 * density) / InkPalette.inks.size
        var y = 48 * density
        for ((name, pen) in pens) {
            labels += Triple(name, 16 * density, y + 5 * density)
            InkPalette.inks.forEachIndexed { i, color ->
                val brush = BrushCatalog.brush(pen.first, pen.second, color, size) ?: return@forEachIndexed
                val x0 = left + i * colWidth
                out += Stroke(brush, wave(x0, y, colWidth - 24 * density, 14 * density))
            }
            y += rowH
        }

        // Highlighter: "text" lines of ink, then highlights drawn afterwards that overlap each other.
        labels += Triple("Highlighter", 16 * density, y + 5 * density)
        val ink = BrushCatalog.brush(Tool.Pen, PenStyle.Ball, InkPalette.BLACK, size)!!
        val hi = BrushCatalog.brush(Tool.Highlighter, PenStyle.Fountain, InkPalette.HIGHLIGHTER_YELLOW, size * 1.6f)!!
        val textRight = width - 16 * density
        for (line in 0 until 2) {
            val ly = y + line * 36 * density
            out += Stroke(ink, wave(left, ly, textRight - left, 6 * density, cycles = 18f))
        }
        out += Stroke(hi, line(left, y, left + (textRight - left) * 0.65f, y))
        out += Stroke(hi, line(left + (textRight - left) * 0.35f, y + 4 * density, textRight, y + 4 * density))
        out += Stroke(hi, line(left + (textRight - left) * 0.2f, y - 20 * density, left + (textRight - left) * 0.45f, y + 56 * density))
        return out
    }

    /** A sine wave whose pressure ramps 0.1 -> 1 -> 0.1 along its length. */
    private fun wave(x0: Float, cy: Float, length: Float, amp: Float, cycles: Float = 2f): MutableStrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val n = 120
        for (i in 0..n) {
            val t = i / n.toFloat()
            batch.add(
                type = InputToolType.STYLUS,
                x = x0 + t * length,
                y = cy + amp * sin(t * cycles * 2 * PI).toFloat(),
                elapsedTimeMillis = (i * 8).toLong(),
                pressure = 0.1f + 0.9f * sin(t * PI).toFloat(),
                tiltRadians = 0f,
                orientationRadians = 0f,
            )
        }
        return batch
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float): MutableStrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val n = 40
        for (i in 0..n) {
            val t = i / n.toFloat()
            batch.add(InputToolType.STYLUS, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, (i * 8).toLong())
        }
        return batch
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFFFFFDF7.toInt())
        renderer.draw(canvas, strokes)
        labels.forEach { (text, x, y) -> canvas.drawText(text, x, y, label) }
    }
}
