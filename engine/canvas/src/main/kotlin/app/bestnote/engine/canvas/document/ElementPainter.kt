package app.bestnote.engine.canvas.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.MetricAffectingSpan
import android.util.LruCache
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.ListStyle
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.ParagraphStyle
import app.bestnote.core.model.Paragraphs
import app.bestnote.core.model.RunStyle
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement

/**
 * Draws text boxes and images (TEXT-01/02, MEDIA-01) in page points. Text is laid out by Android's text
 * engine with the first strong character deciding the paragraph direction, so Arabic is shaped, runs
 * right to left and aligns [TextAlignment.Start] on the right, while English stays left.
 */
class ElementPainter {
    /** Typefaces for [TextElement.font] ids (the app registers the bundled handwriting fonts at start). */
    object Fonts {
        @Volatile var resolve: (String) -> Typeface? = { null }

        /** [id]'s typeface: the monospaced system font for [TextElement.FONT_MONO], else what [resolve] gives. */
        fun face(id: String?): Typeface? = when (id) {
            null -> null
            TextElement.FONT_MONO -> Typeface.MONOSPACE
            else -> resolve(id)
        }
    }

    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val placeholder = Paint().apply { color = PLACEHOLDER; style = Paint.Style.FILL }

    /**
     * Draws [elements] in order onto [canvas], whose units are page points ([pxPerPt] says how many pixels
     * one point is, to pick image sizes). [colorOf] maps a text color to what is readable on this paper;
     * elements in [skip] are left out (the selection layer is drawing them).
     */
    fun draw(
        canvas: Canvas,
        elements: List<PageElement>,
        pxPerPt: Float,
        images: ElementImages?,
        onImageReady: Runnable,
        skip: Set<String> = emptySet(),
        colorOf: (Int) -> Int = { it },
    ) {
        for (e in elements) {
            if (e.id in skip) continue
            drawOne(canvas, e, pxPerPt, images, onImageReady, colorOf)
        }
    }

    fun drawOne(canvas: Canvas, e: PageElement, pxPerPt: Float, images: ElementImages?, onImageReady: Runnable, colorOf: (Int) -> Int = { it }) {
        canvas.save()
        canvas.translate(e.cx, e.cy)
        canvas.rotate(Math.toDegrees(e.rotation.toDouble()).toFloat())
        when (e) {
            is ImageElement -> drawImage(canvas, e, pxPerPt, images, onImageReady)
            is TextElement -> {
                canvas.translate(-e.width / 2f, -e.height / 2f)
                val layout = layoutOf(e, colorOf(e.colorArgb), colorOf)
                canvas.scale(1f / SCALE, 1f / SCALE)
                layout.draw(canvas)
            }
        }
        canvas.restore()
    }

    private fun drawImage(canvas: Canvas, e: ImageElement, pxPerPt: Float, images: ElementImages?, onImageReady: Runnable) {
        dst.set(-e.width / 2f, -e.height / 2f, e.width / 2f, e.height / 2f)
        val widthPx = (e.width * pxPerPt / (e.crop.right - e.crop.left)).toInt().coerceAtLeast(1)
        val bmp: Bitmap? = images?.bitmap(e.asset, widthPx, onImageReady)
        if (bmp == null) {
            canvas.drawRect(dst, placeholder)
            return
        }
        val bw = bmp.width
        val bh = bmp.height
        src.set((e.crop.left * bw).toInt(), (e.crop.top * bh).toInt(), (e.crop.right * bw).toInt().coerceAtLeast(1), (e.crop.bottom * bh).toInt().coerceAtLeast(1))
        canvas.drawBitmap(bmp, src, dst, imagePaint)
    }

    private val layouts = object : LruCache<LayoutKey, StaticLayout>(LAYOUT_CACHE) {}

    /** Everything that changes how a box looks, and nothing that only moves it. */
    private data class LayoutKey(val look: TextElement, val color: Int, val runColors: List<Int?>, val widthUnits: Int)

    private fun layoutOf(e: TextElement, color: Int, colorOf: (Int) -> Int = { it }): StaticLayout {
        val look = e.copy(id = "", cx = 0f, cy = 0f, height = 1f, rotation = 0f, locked = false)
        val key = LayoutKey(look, color, e.runs.map { r -> r.style.colorArgb?.let(colorOf) }, (e.width * SCALE).toInt().coerceAtLeast(1))
        layouts.get(key)?.let { return it }
        val base = Typeface.create(Fonts.face(e.font) ?: Typeface.DEFAULT, style(e))
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = e.sizePt * SCALE
            typeface = base
            isUnderlineText = e.underline
            isStrikeThruText = e.strike
        }
        val built = build(e, paint, key.widthUnits, key.runColors)
        layouts.put(key, built)
        return built
    }

    private fun style(e: TextElement) = faceStyle(e.bold, e.italic)

    /** One styled part of a box: adds bold / italic / underline / strike / color / font / size to the base paint. */
    private class RunSpan(val e: TextElement, val s: RunStyle, val color: Int?) : MetricAffectingSpan() {
        override fun updateMeasureState(p: TextPaint) = apply(p)
        override fun updateDrawState(p: TextPaint) = apply(p)
        private fun apply(p: TextPaint) {
            // The paint may already carry a paragraph style (heading bold, code mono): add to it, never drop it.
            val current = p.typeface
            val bold = e.bold || s.bold || current?.isBold == true
            val italic = e.italic || s.italic || current?.isItalic == true
            if (s.font != null || s.bold || s.italic) {
                val face = Fonts.face(s.font) ?: current ?: Fonts.face(e.font) ?: Typeface.DEFAULT
                p.typeface = Typeface.create(face, faceStyle(bold, italic))
            }
            s.sizePt?.let { p.textSize = it * SCALE }
            color?.let { p.color = it }
            if (s.underline) p.isUnderlineText = true
            if (s.strike) p.isStrikeThruText = true
        }
    }

    /** A paragraph style (TEXT-04): its size relative to the box, heading weight, quote slant, code font. */
    private class ParaSpan(val e: TextElement, val style: ParagraphStyle) : MetricAffectingSpan() {
        override fun updateMeasureState(p: TextPaint) = apply(p)
        override fun updateDrawState(p: TextPaint) = apply(p)
        private fun apply(p: TextPaint) {
            p.textSize = e.sizePt * SCALE * style.sizeScale
            val face = if (style.mono) Typeface.MONOSPACE else Fonts.face(e.font) ?: Typeface.DEFAULT
            p.typeface = Typeface.create(face, faceStyle(e.bold || style.bold, e.italic || style.italic))
        }
    }

    /** Tints the lines of a Code paragraph. */
    private class CodeBackground(val color: Int) : LineBackgroundSpan {
        override fun drawBackground(c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int) {
            val old = p.color
            p.color = color
            c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), p)
            p.color = old
        }
    }

    /**
     * The leading margin of one paragraph: its indent, plus room for a list marker and a quote bar, drawn on the
     * left of left-to-right paragraphs and on the right of right-to-left ones.
     */
    private class MarginSpan(val marker: String?, val indent: Int, val markerRoom: Int, val quoteRoom: Int, val paintOf: TextPaint, val barColor: Int) : LeadingMarginSpan {
        override fun getLeadingMargin(first: Boolean) = indent + markerRoom + quoteRoom
        override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout) {
            // dir is +1 when x is the left edge of the text, -1 when it is the right edge.
            if (quoteRoom > 0) {
                val bar = quoteRoom * QUOTE_BAR
                val at = x + dir * (indent + bar)
                val old = p.color
                val oldStyle = p.style
                p.color = barColor
                p.style = Paint.Style.FILL
                c.drawRect(minOf(at, at + dir * bar).toFloat(), top.toFloat(), maxOf(at, at + dir * bar).toFloat(), bottom.toFloat(), p)
                p.color = old
                p.style = oldStyle
            }
            if (!first || marker == null) return
            val mp = TextPaint(paintOf).apply { isUnderlineText = false; isStrikeThruText = false }
            val w = mp.measureText(marker)
            val pad = markerRoom * 0.2f
            val inner = indent + quoteRoom + markerRoom
            c.drawText(marker, if (dir >= 0) x + inner - pad - w else x - inner + pad, baseline.toFloat(), mp)
        }
    }

    private fun build(e: TextElement, paint: TextPaint, width: Int, runColors: List<Int?>): StaticLayout {
        val text = SpannableStringBuilder(e.text)
        val markers = Paragraphs.markers(e.text, e.list, e.paragraphs, e.numerals)
        val em = e.sizePt * SCALE
        var from = 0
        e.text.split('\n').forEachIndexed { i, p ->
            val f = Paragraphs.formatOf(e.paragraphs, i)
            val m = markers[i]
            // A paragraph includes its trailing line break so the span covers whole paragraphs.
            val to = (from + p.length + 1).coerceAtMost(text.length)
            if (from < to) {
                if (f.style != ParagraphStyle.Body) text.setSpan(ParaSpan(e, f.style), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                val listed = (f.list ?: e.list) != ListStyle.None
                val indent = (f.indent * em * LIST_INDENT_EM).toInt()
                val markerRoom = if (listed) (em * LIST_INDENT_EM).toInt() else 0
                val quoteRoom = if (f.style == ParagraphStyle.Quote) (em * QUOTE_INDENT_EM).toInt() else 0
                if (indent + markerRoom + quoteRoom > 0) {
                    text.setSpan(MarginSpan(m, indent, markerRoom, quoteRoom, paint, withAlpha(paint.color, QUOTE_ALPHA)), from, to, Spanned.SPAN_PARAGRAPH or Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                if (f.style == ParagraphStyle.Code) text.setSpan(CodeBackground(withAlpha(paint.color, CODE_ALPHA)), from, to, Spanned.SPAN_PARAGRAPH or Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
            from += p.length + 1
        }
        // Paragraph styles go first so style runs (added after) refine them.
        e.runs.forEachIndexed { i, r ->
            val end = r.end.coerceAtMost(text.length)
            if (r.start < end) text.setSpan(RunSpan(e, r.style, runColors[i]), r.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(
                when (e.align) {
                    TextAlignment.Start, TextAlignment.Justify -> Layout.Alignment.ALIGN_NORMAL
                    TextAlignment.Center -> Layout.Alignment.ALIGN_CENTER
                    TextAlignment.End -> Layout.Alignment.ALIGN_OPPOSITE
                },
            )
            .setJustificationMode(if (e.align == TextAlignment.Justify) LineBreaker.JUSTIFICATION_MODE_INTER_WORD else LineBreaker.JUSTIFICATION_MODE_NONE)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .setLineSpacing(0f, e.lineSpacing.coerceIn(TextElement.MIN_LINE_SPACING, TextElement.MAX_LINE_SPACING))
            .build()
    }

    /** [e] with its height set to exactly what its text needs at its width (the box grows and shrinks with its text). */
    fun fitted(e: TextElement): TextElement {
        val h = layoutOf(e, e.colorArgb).height / SCALE
        val height = h.coerceAtLeast(e.sizePt * MIN_LINES)
        // Keep the top edge where it was: the box grows downward from the top like a text field.
        val cos = Math.cos(e.rotation.toDouble()).toFloat()
        val sin = Math.sin(e.rotation.toDouble()).toFloat()
        val d = (height - e.height) / 2f
        return e.copy(height = height, cx = e.cx - sin * d, cy = e.cy + cos * d)
    }

    companion object {
        /** Text is laid out at this many times its point size and drawn scaled down, for precise wrapping. */
        private const val SCALE = 4f
        private const val LIST_INDENT_EM = 1.4f
        private const val QUOTE_INDENT_EM = 0.9f
        private const val QUOTE_BAR = 0.22f
        private const val QUOTE_ALPHA = 0.35f
        private const val CODE_ALPHA = 0.08f

        private fun faceStyle(bold: Boolean, italic: Boolean) = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

        private fun withAlpha(color: Int, alpha: Float) = Color.argb((Color.alpha(color) * alpha).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        private const val MIN_LINES = 1.3f
        private const val LAYOUT_CACHE = 96
        private val PLACEHOLDER = Color.argb(40, 120, 120, 120)
    }
}
