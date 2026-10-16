package app.bestnote.engine.canvas.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import app.bestnote.engine.ink.InkRenderer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * An immutable picture of what one page shows (CANVAS-06): paper, PDF raster, elements and the
 * finished strokes exactly as drawn (dark-paper tint, audio dimming, hidden and morphing strokes
 * already applied). Built on the main thread; tile workers read it from theirs.
 *
 * [boxes] holds each stroke's bounds in page points (left, top, right, bottom), so a tile only
 * draws the strokes that reach it.
 */
internal class PageContent(
    val pageId: String,
    val page: Page,
    val colors: PaperColors,
    val strokes: List<Stroke>,
    val boxes: FloatArray,
    val highlighter: BooleanArray,
    val elements: List<PageElement>,
    val images: Map<String, Bitmap>,
    val raster: Bitmap?,
) {
    /** Pictures resolved on the main thread, so workers never call the (main-thread) image cache. */
    val imageSource = object : ElementImages {
        override fun bitmap(asset: String, widthPx: Int, onReady: Runnable): Bitmap? = images[asset]
    }

    fun intersects(i: Int, l: Float, t: Float, r: Float, b: Float): Boolean {
        val o = i * 4
        return boxes[o] <= r && boxes[o + 2] >= l && boxes[o + 1] <= b && boxes[o + 3] >= t
    }

    companion object {
        /** Axis-aligned bounds of [e] in page points (rotated boxes included), into [out]. */
        fun elementBounds(e: PageElement, out: RectF) {
            val c = abs(cos(e.rotation))
            val s = abs(sin(e.rotation))
            val hw = (e.width * c + e.height * s) / 2f
            val hh = (e.width * s + e.height * c) / 2f
            out.set(e.cx - hw - ELEMENT_PAD, e.cy - hh - ELEMENT_PAD, e.cx + hw + ELEMENT_PAD, e.cy + hh + ELEMENT_PAD)
        }

        /** Text may draw a little outside its box (italic overhang, anti-aliasing). */
        const val ELEMENT_PAD = 4f
    }
}

/**
 * Draws a [PageContent] into a canvas in page pixels (one per thread: the renderers cache per
 * instance). Used by the tile workers and, on the main thread, for regions no tile covers yet.
 */
internal class PagePainter(private val density: Float) {
    private val paper = PaperRenderer()
    private val ink = InkRenderer()
    private val elementPainter = ElementPainter()
    private val matrix = Matrix()
    private val src = Rect()
    private val dst = Rect()
    private val dstF = RectF()
    private val box = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val list = ArrayList<Stroke>()
    private val els = ArrayList<PageElement>()
    private val noop = Runnable {}

    /**
     * Paints [c] at [scale] px/pt onto [canvas] (page pixels, origin at the page's top-left, already
     * clipped), drawing only what reaches the pixel rect [region]. [regionBackground] is a PDF piece
     * rendered exactly for [region] (PDF-02); otherwise the whole-page raster is scaled.
     */
    fun draw(
        canvas: Canvas,
        c: PageContent,
        scale: Float,
        widthPx: Int,
        heightPx: Int,
        region: RectF,
        regionBackground: Bitmap? = null,
        wet: InProgressStroke? = null,
        wetColorArgb: Int = 0,
    ) {
        if (c.page.pdf != null) {
            canvas.drawColor(c.colors.page)
            if (regionBackground != null) {
                canvas.drawBitmap(regionBackground, region.left, region.top, null)
            } else if (c.raster != null) {
                src.set(0, 0, c.raster.width, c.raster.height)
                dst.set(0, 0, widthPx, heightPx)
                canvas.drawBitmap(c.raster, src, dst, bitmapPaint)
            }
        } else {
            paper.draw(canvas, c.page, scale, widthPx, heightPx, density)
        }
        val pad = PAD_PX / scale
        val l = region.left / scale - pad
        val t = region.top / scale - pad
        val r = region.right / scale + pad
        val b = region.bottom / scale + pad
        val save = canvas.save()
        canvas.scale(scale, scale)
        if (c.elements.isNotEmpty()) {
            els.clear()
            for (e in c.elements) {
                PageContent.elementBounds(e, box)
                if (box.left <= r && box.right >= l && box.top <= b && box.bottom >= t) els += e
            }
            if (els.isNotEmpty()) elementPainter.draw(canvas, els, scale, c.imageSource, noop) { PaperInk.colorOn(it, c.colors) }
        }
        list.clear()
        for (i in c.strokes.indices) if (c.intersects(i, l, t, r, b)) list += c.strokes[i]
        matrix.setScale(scale, scale)
        ink.draw(canvas, list, matrix, wet, wetColorArgb)
        list.clear()
        canvas.restoreToCount(save)
    }

    /** Draws [strokes] (non-highlighters, page points) over what is there, at [scale]. */
    fun drawInk(canvas: Canvas, strokes: List<Stroke>, scale: Float) {
        val save = canvas.save()
        canvas.scale(scale, scale)
        matrix.setScale(scale, scale)
        ink.drawInk(canvas, strokes, matrix)
        canvas.restoreToCount(save)
    }

    /** Draws [bitmap] (a tile of another scale) stretched over [dst] px, filtered. */
    fun drawScaled(canvas: Canvas, bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float) {
        dstF.set(left, top, right, bottom)
        canvas.drawBitmap(bitmap, null, dstF, bitmapPaint)
    }

    private companion object {
        /** Anti-aliasing reaches about a pixel past a stroke's geometry. */
        const val PAD_PX = 2f
    }
}

/** Image elements of [elements] that are drawn, for resolving their pictures. */
internal inline fun forEachImage(elements: List<PageElement>, block: (ImageElement) -> Unit) {
    for (e in elements) if (e is ImageElement) block(e)
}
