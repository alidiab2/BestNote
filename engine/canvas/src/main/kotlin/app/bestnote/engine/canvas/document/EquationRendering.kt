package app.bestnote.engine.canvas.document

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextPaint
import android.util.LruCache
import android.util.SparseArray
import app.bestnote.core.model.EquationElement
import app.bestnote.engine.mathtype.GlyphPath
import app.bestnote.engine.mathtype.MathFont
import app.bestnote.engine.mathtype.MathLayout
import app.bestnote.engine.mathtype.MathOp
import app.bestnote.engine.mathtype.MathTypesetter
import app.bestnote.engine.mathtype.TextMeasurer
import app.bestnote.engine.mathtype.Tone
import kotlin.math.min

/**
 * Draws typeset equations (MATH-01) as vector outlines: every glyph is a [Path] from STIX Two Math, every bar
 * a rectangle, so equations stay sharp at any zoom, on the tiles and in PDF export. Layouts and glyph paths are
 * cached; everything here is safe to call from the tile threads.
 */
object EquationRendering {
    /** Words the math font lacks (Arabic, Hebrew in `\text{}`) are measured and drawn with the device font. */
    private object DeviceText : TextMeasurer {
        override fun measure(text: String, sizePt: Float, bold: Boolean, italic: Boolean): FloatArray {
            val p = textPaint(sizePt * MEASURE_SCALE, bold, italic)
            val fm = p.fontMetrics
            return floatArrayOf(p.measureText(text) / MEASURE_SCALE, -fm.ascent / MEASURE_SCALE, fm.descent / MEASURE_SCALE)
        }
    }

    val typesetter: MathTypesetter by lazy { MathTypesetter(MathFont.stixTwo, DeviceText) }

    private data class Key(val latex: String, val sizePt: Float, val display: Boolean, val placeholders: Boolean)

    private val layouts = LruCache<Key, MathLayout>(LAYOUT_CACHE)
    private val paths = SparseArray<Path>()

    /** [latex] typeset at [sizePt] (cached). Never throws: errors are in [MathLayout.errors]. */
    fun layout(latex: String, sizePt: Float, display: Boolean = true, placeholders: Boolean = false): MathLayout {
        val key = Key(latex, sizePt, display, placeholders)
        layouts.get(key)?.let { return it }
        return typesetter.typeset(latex, sizePt, display, placeholders).also { layouts.put(key, it) }
    }

    /** The frame (width, height in points) an equation needs: its formula plus [EquationElement.MARGIN_EM] all round. */
    fun frameSize(latex: String, sizePt: Float, display: Boolean = true): Pair<Float, Float> {
        val l = layout(latex, sizePt, display)
        val m = sizePt * EquationElement.MARGIN_EM
        return (l.width + 2 * m).coerceAtLeast(sizePt) to (l.height + 2 * m).coerceAtLeast(sizePt)
    }

    /** [e] with its frame re-measured for its current LaTeX and size, keeping its center. */
    fun fitted(e: EquationElement): EquationElement {
        val (w, h) = frameSize(e.latex, e.sizePt, e.display)
        return e.copy(width = w, height = h)
    }

    /**
     * Draws [e] centered on the canvas origin (the element's center), scaled to fit its frame, in [color].
     * Parts with LaTeX errors use [errorColor].
     */
    fun drawElement(canvas: Canvas, e: EquationElement, color: Int, errorColor: Int = ERROR_COLOR) {
        val l = layout(e.latex, e.sizePt, e.display)
        val m = e.sizePt * EquationElement.MARGIN_EM
        val w = (l.width + 2 * m).coerceAtLeast(e.sizePt)
        val h = (l.height + 2 * m).coerceAtLeast(e.sizePt)
        val s = min(e.width / w, e.height / h)
        canvas.save()
        canvas.scale(s, s)
        draw(canvas, l, -l.width / 2f, -(l.height) / 2f + l.ascent, color, errorColor)
        canvas.restore()
    }

    /** Draws [layout] with its left baseline point at ([x], [baseline]). */
    fun draw(canvas: Canvas, layout: MathLayout, x: Float, baseline: Float, color: Int, errorColor: Int = ERROR_COLOR) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (op in layout.ops) {
            fill.color = when (op.tone) {
                Tone.Normal -> color
                Tone.Error -> errorColor
                Tone.Placeholder -> Color.argb((Color.alpha(color) * PLACEHOLDER_ALPHA).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            }
            when (op) {
                is MathOp.GlyphOp -> {
                    canvas.save()
                    canvas.translate(x + op.x, baseline + op.y)
                    canvas.scale(op.scale, -op.scale)
                    canvas.drawPath(pathOf(op.glyph.id, op.glyph.path), fill)
                    canvas.restore()
                }
                is MathOp.RuleOp -> canvas.drawRect(x + op.x, baseline + op.top, x + op.x + op.width, baseline + op.top + op.height, fill)
                is MathOp.TextOp -> {
                    // Laid out at a larger size and scaled down, as text elements are, for precise glyph positions.
                    val p = textPaint(op.sizePt * MEASURE_SCALE, op.bold, op.italic).apply { this.color = fill.color }
                    canvas.save()
                    canvas.translate(x + op.x, baseline + op.y)
                    canvas.scale(1f / MEASURE_SCALE, 1f / MEASURE_SCALE)
                    canvas.drawText(op.text, 0f, 0f, p)
                    canvas.restore()
                }
            }
        }
    }

    private fun pathOf(id: Int, g: GlyphPath): Path = synchronized(paths) {
        paths.get(id) ?: build(g).also { paths.put(id, it) }
    }

    private fun build(g: GlyphPath): Path {
        val p = Path()
        val c = g.coords
        var k = 0
        for (op in g.ops) {
            when (op) {
                GlyphPath.MOVE -> { p.moveTo(c[k].toFloat(), c[k + 1].toFloat()); k += 2 }
                GlyphPath.LINE -> { p.lineTo(c[k].toFloat(), c[k + 1].toFloat()); k += 2 }
                GlyphPath.QUAD -> { p.quadTo(c[k].toFloat(), c[k + 1].toFloat(), c[k + 2].toFloat(), c[k + 3].toFloat()); k += 4 }
                GlyphPath.CUBIC -> {
                    p.cubicTo(c[k].toFloat(), c[k + 1].toFloat(), c[k + 2].toFloat(), c[k + 3].toFloat(), c[k + 4].toFloat(), c[k + 5].toFloat())
                    k += 6
                }
                else -> p.close()
            }
        }
        return p
    }

    private fun textPaint(size: Float, bold: Boolean, italic: Boolean) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(
            Typeface.SERIF,
            when {
                bold && italic -> Typeface.BOLD_ITALIC
                bold -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            },
        )
    }

    /** Parses the font off the main thread so the first equation on a page draws without a pause. */
    fun warmUp() {
        Thread({ runCatching { typesetter.typeset("x", 1f) } }, "bestnote-math-font").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private const val LAYOUT_CACHE = 128
    private const val MEASURE_SCALE = 8f
    private const val PLACEHOLDER_ALPHA = 0.35f
    private val ERROR_COLOR = Color.rgb(0xD3, 0x2F, 0x2F)
}
