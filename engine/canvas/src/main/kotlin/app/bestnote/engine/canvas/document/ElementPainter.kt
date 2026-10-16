package app.bestnote.engine.canvas.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.LruCache
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.PageElement
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
                val layout = layoutOf(e, colorOf(e.colorArgb))
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

    private data class LayoutKey(
        val text: String, val sizePt: Float, val color: Int, val bold: Boolean, val italic: Boolean,
        val align: TextAlignment, val widthUnits: Int, val font: String?,
    )

    private fun layoutOf(e: TextElement, color: Int): StaticLayout {
        val key = LayoutKey(e.text, e.sizePt, color, e.bold, e.italic, e.align, (e.width * SCALE).toInt().coerceAtLeast(1), e.font)
        layouts.get(key)?.let { return it }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = e.sizePt * SCALE
            typeface = Typeface.create(e.font?.let(Fonts.resolve) ?: Typeface.DEFAULT, style(e))
        }
        val built = build(e.text, paint, key.widthUnits, e.align)
        layouts.put(key, built)
        return built
    }

    private fun style(e: TextElement) = when {
        e.bold && e.italic -> Typeface.BOLD_ITALIC
        e.bold -> Typeface.BOLD
        e.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

    private fun build(text: String, paint: TextPaint, width: Int, align: TextAlignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(
                when (align) {
                    TextAlignment.Start -> Layout.Alignment.ALIGN_NORMAL
                    TextAlignment.Center -> Layout.Alignment.ALIGN_CENTER
                    TextAlignment.End -> Layout.Alignment.ALIGN_OPPOSITE
                },
            )
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(false)
            .setLineSpacing(0f, LINE_SPACING)
            .build()

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
        private const val LINE_SPACING = 1.12f
        private const val MIN_LINES = 1.3f
        private const val LAYOUT_CACHE = 96
        private val PLACEHOLDER = Color.argb(40, 120, 120, 120)
    }
}
