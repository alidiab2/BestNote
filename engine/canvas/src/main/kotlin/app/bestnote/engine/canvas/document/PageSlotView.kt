package app.bestnote.engine.canvas.document

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.core.graphics.withTranslation
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import app.bestnote.engine.layout.document.PxRect
import java.util.WeakHashMap

/**
 * One recyclable page sheet (CANVAS-07): soft shadow, paper, template and the page's ink, in a
 * View sized to the page plus room for the shadow. While bound, it only redraws when its page's
 * ink or paper changes; scrolling moves its RenderNode without re-recording anything.
 */
@SuppressLint("ViewConstructor")
internal class PageSlotView(context: Context, private val style: SheetStyle) : View(context) {
    /** Bound page index, or -1 while pooled. */
    var index: Int = -1
        private set

    private var page: DocumentPage? = null

    /** Source of PDF page rasters (PDF-01); null for plain notebooks. */
    var backgrounds: PageBackgrounds? = null
    private val backgroundReady = Runnable { if (index >= 0) invalidate() }
    private var scale = 1f

    /** Source of the pictures of image elements (MEDIA-01). */
    var images: ElementImages? = null
    private val imageReady = Runnable { if (index >= 0) invalidate() }
    private val density = resources.displayMetrics.density
    private val sheet = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tinted = WeakHashMap<Stroke, Stroke>()
    private val drawList = ArrayList<Stroke>()
    private var wet: InProgressStroke? = null
    private var wetColor = 0

    /** INK-08 snap morph: [morphFrame] is drawn in place of [morphTarget] while the shape settles. */
    private var morphTarget: Stroke? = null
    private var morphFrame: Stroke? = null

    /** Strokes the selection layer is dragging (CANVAS-09): drawn there, so left out here. */
    private var hidden: Set<Stroke> = emptySet()

    init {
        // Never hit-tested: the document view routes all input.
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(index: Int, page: DocumentPage, rect: PxRect, scale: Float) {
        // Rebinding the same page (a relayout after a zoom) keeps its content snapshot and tint list.
        if (lastPage !== page) forgetContent()
        lastPage = page
        this.index = index
        this.page = page
        this.scale = scale
        contentDirty = true
        val pad = style.shadowPad
        val l = rect.left - pad
        val t = rect.top - pad
        val r = rect.right + pad
        val b = rect.bottom + pad
        if (l != left || t != top || r != right || b != bottom) {
            measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY))
            layout(l, t, r, b)
        }
        visibility = VISIBLE
        invalidate()
    }

    /**
     * A highlighter stroke in progress on this page (in page points, started with an opaque brush),
     * drawn inside its highlight layer under the ink; [colorArgb] is its real color. Null clears it.
     */
    fun setWet(stroke: InProgressStroke?, colorArgb: Int) {
        if (stroke == null && wet == null) return
        wet = stroke
        wetColor = colorArgb
        invalidate()
    }

    /** Elements the selection layer is dragging: drawn there, so left out here. */
    private var hiddenElements: Set<String> = emptySet()

    fun setHiddenElements(ids: Set<String>) {
        if (ids == hiddenElements) return
        hiddenElements = ids
        contentDirty = true
        invalidate()
    }

    /** Strokes drawn faded: ink written after the audio playhead (MEDIA-05). */
    private var dimmed: Set<Stroke> = emptySet()

    fun setDimmed(strokes: Set<Stroke>) {
        if (strokes == dimmed) return
        dimmed = strokes
        contentDirty = true
        invalidate()
    }

    fun setHidden(strokes: Set<Stroke>) {
        if (strokes === hidden) return
        hidden = strokes
        contentDirty = true
        invalidate()
    }

    fun setMorph(target: Stroke?, frame: Stroke?) {
        if (target == null && morphTarget == null) return
        morphTarget = target
        morphFrame = frame
        contentDirty = true
        invalidate()
    }

    fun unbind() {
        index = -1
        page = null
        wet = null
        morphTarget = null
        morphFrame = null
        if (hidden.isNotEmpty() || hiddenElements.isNotEmpty()) forgetContent()
        hidden = emptySet()
        hiddenElements = emptySet()
        recorded.setEmpty()
        visibility = INVISIBLE
    }

    /** The page last bound: its content snapshot survives an unbind/rebind of the same page. */
    private var lastPage: DocumentPage? = null

    private fun forgetContent() {
        drawList.clear()
        readSrc.clear()
        readKey = null
        content = null
        contentDirty = true
        pictures.clear()
        raster = null
    }

    /** Shared dry-ink tile cache (CANVAS-06); set once by the pages layer. */
    var tiles: DryInkTiles? = null

    /** Writes the part of the pages layer that can be on screen, in the layer's content coordinates. */
    var viewport: ((Rect) -> Unit)? = null

    private var content: PageContent? = null
    private var contentVersion = -1
    private var contentElements: List<app.bestnote.core.model.PageElement>? = null
    private var contentPaper: app.bestnote.core.model.Page? = null
    private var contentDirty = true
    private val pictures = HashMap<String, android.graphics.Bitmap>()
    private var raster: android.graphics.Bitmap? = null
    private val visible = Rect()
    private val coverage = Rect()
    private val recorded = Rect()
    private val scratch = Rect()
    private val pageBounds = Rect()

    /** Nanoseconds the last [onDraw] took (the main-thread redraw cost), for the debug STATE dump and benchmarks. */
    var lastDrawNanos = 0L
        private set

    /**
     * Called when the viewport moved: re-records only if the area that can now be seen reaches past
     * what the last recording covered (tiles are recorded with a margin, so most scrolling is free).
     */
    fun onViewportMoved() {
        if (index < 0) return
        visibleRect(visible)
        if (!visible.isEmpty && !recorded.contains(visible)) invalidate()
    }

    /** Page pixels of this slot that can be on screen into [out] (empty if none). */
    private fun visibleRect(out: Rect) {
        val pad = style.shadowPad
        val w = width - 2 * pad
        val h = height - 2 * pad
        val source = viewport
        if (source == null) {
            out.set(0, 0, w, h)
            return
        }
        source(scratch)
        scratch.offset(-(left + pad), -(top + pad))
        if (!out.setIntersect(scratch, pageBounds.apply { set(0, 0, w, h) })) out.setEmpty()
    }

    override fun onDraw(canvas: Canvas) = traced("BN.slotDraw") { drawSlot(canvas) }

    private fun drawSlot(canvas: Canvas) {
        val start = System.nanoTime()
        val p = page ?: return
        val pad = style.shadowPad.toFloat()
        val w = width - 2 * style.shadowPad
        val h = height - 2 * style.shadowPad
        if (w <= 0 || h <= 0) return
        val colors = PaperColors.of(p.page.paperColor)
        sheet.color = colors.page
        sheet.setShadowLayer(style.shadowRadius, 0f, style.shadowDy, style.shadowColor)
        canvas.drawRect(pad, pad, pad + w, pad + h, sheet)

        val s = scale
        val cache = tiles
        canvas.withTranslation(pad, pad) {
            clipRect(0, 0, w, h)
            val c = contentFor(p, colors, s, w)
            if (cache == null) {
                drawUncached(this, c ?: placeholderFor(p, colors), s, w, h)
            } else {
                visibleRect(visible)
                // Record a margin around what is visible, so scrolling within it never re-records.
                // One tile sideways, a tile and a half up and down: enough for a fling's first frames,
                // and bounded at 800 % (about 50 tiles on a 2560 x 1600 screen).
                val mx = TileMath.SIZE
                val my = TileMath.SIZE * 3 / 2
                coverage.set(visible.left - mx, visible.top - my, visible.right + mx, visible.bottom + my)
                if (visible.isEmpty) {
                    // An overscan slot: record the edge nearest the viewport so it is ready when it scrolls in.
                    viewport?.invoke(scratch)
                    scratch.offset(-(left + style.shadowPad), -(top + style.shadowPad))
                    scratch.inset(-mx, -my)
                    coverage.set(scratch)
                }
                if (!coverage.intersect(0, 0, w, h)) coverage.setEmpty()
                recorded.set(coverage)
                if (coverage.isEmpty) {
                    Unit
                } else if (c != null) {
                    cache.draw(this, c, s, w, h, visible, coverage, wet, PaperInk.colorOn(wetColor, colors))
                } else {
                    // Strokes still being measured off the main thread: paper and any tiles we have.
                    recorded.setEmpty()
                    cache.drawPending(this, placeholderFor(p, colors), s, w, h, visible, coverage)
                }
            }
        }
        lastDrawNanos = System.nanoTime() - start
        recent[recentCount++ % recent.size] = lastDrawNanos
    }

    private val recent = LongArray(64)
    private var recentCount = 0

    /** Median and worst of the last (up to 64) redraws, in ns. */
    fun recentDrawNanos(): Pair<Long, Long> {
        val n = minOf(recentCount, recent.size)
        if (n == 0) return 0L to 0L
        val sorted = recent.copyOf(n).also { it.sort() }
        return sorted[n / 2] to sorted[n - 1]
    }

    private var fallback: PagePainter? = null

    private fun drawUncached(canvas: Canvas, c: PageContent, s: Float, w: Int, h: Int) {
        val painter = fallback ?: PagePainter(density).also { fallback = it }
        painter.draw(canvas, c, s, w, h, android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat()), null, wet, PaperInk.colorOn(wetColor, c.colors))
    }

    /**
     * The page's content as drawn now; rebuilt (and handed to the tile cache, which diffs it) only
     * when strokes, elements, paper, tint/dim/hidden/morph state or a background picture changed.
     */
    private fun contentFor(p: DocumentPage, colors: PaperColors, s: Float, w: Int): PageContent? = traced("BN.contentFor") { contentForNow(p, colors, s, w) }

    private var placeholder: PageContent? = null

    /** The page without strokes (paper, template, elements): drawn while its strokes are measured. */
    private fun placeholderFor(p: DocumentPage, colors: PaperColors): PageContent {
        val cached = placeholder
        if (cached != null && cached.pageId == p.id && cached.page == p.page && cached.colors == colors && cached.elements === p.elements) return cached
        return PageContent(p.id, p.page, colors, emptyList(), FloatArray(0), BooleanArray(0), p.elements, emptyMap(), raster).also { placeholder = it }
    }

    private val wanted = HashMap<String, android.graphics.Bitmap>()

    private fun contentForNow(p: DocumentPage, colors: PaperColors, s: Float, w: Int): PageContent? {
        var changed = contentDirty || p.version != contentVersion || p.elements !== contentElements || p.page != contentPaper
        val newRaster = if (p.page.pdf != null) backgrounds?.background(p.page, w, backgroundReady) else null
        if (newRaster !== raster) changed = true
        var picturesChanged = false
        wanted.clear()
        forEachImage(p.elements) { e ->
            if (e.id in hiddenElements) return@forEachImage
            val widthPx = (e.width * s / (e.crop.right - e.crop.left)).toInt().coerceAtLeast(1)
            this.images?.bitmap(e.asset, widthPx, imageReady)?.let { wanted[e.asset] = it }
        }
        if (wanted.size != pictures.size || wanted.any { (k, v) -> pictures[k] !== v }) picturesChanged = true
        val prev = content
        if (!changed && !picturesChanged && prev != null && prev.pageId == p.id) return prev
        val elements = if (hiddenElements.isEmpty()) p.elements else p.elements.filter { it.id !in hiddenElements }
        val strokes = readable(p.strokes, colors)
        val cache = tiles
        val next = if (cache != null) {
            // Null: the page's strokes are being measured off the main thread; try again when it's done.
            cache.content(p.id, p.page, colors, strokes, elements, HashMap(wanted), newRaster) ?: return null
        } else {
            PageContent(p.id, p.page, colors, ArrayList(strokes), boxesOf(strokes), BooleanArray(strokes.size), elements, HashMap(wanted), newRaster)
        }
        raster = newRaster
        pictures.clear()
        pictures.putAll(wanted)
        contentVersion = p.version
        contentElements = p.elements
        contentPaper = p.page
        contentDirty = false
        cache?.update(next)
        content = next
        return next
    }

    private fun boxesOf(strokes: List<Stroke>): FloatArray {
        val out = FloatArray(strokes.size * 4)
        strokes.forEachIndexed { i, st ->
            val b = st.shape.computeBoundingBox()
            if (b != null) floatArrayOf(b.xMin, b.yMin, b.xMax, b.yMax).copyInto(out, i * 4)
        }
        return out
    }

    private val readSrc = ArrayList<Stroke>()
    private var readKey: List<Any?>? = null

    /**
     * [strokes] with every ink color made readable on [paper] ([PaperInk]); unchanged strokes are reused.
     * Incremental: while paper, dimming and morph stay the same and nothing is hidden, only the strokes
     * between the unchanged head and tail of the list are mapped again (one stroke after a pen-up).
     */
    private fun readable(strokes: List<Stroke>, paper: PaperColors): List<Stroke> {
        val key = listOf(paper, dimmed, morphTarget, morphFrame)
        val reuse = hidden.isEmpty() && readKey == key && readSrc.size == drawList.size
        readKey = key
        if (!reuse) {
            drawList.clear()
            for (stroke in strokes) if (stroke !in hidden) drawList += readable(stroke, paper)
        } else {
            val n = strokes.size
            val max = minOf(readSrc.size, n)
            var head = 0
            while (head < max && readSrc[head] === strokes[head]) head++
            var tail = 0
            while (tail < max - head && readSrc[readSrc.size - 1 - tail] === strokes[n - 1 - tail]) tail++
            if (head + tail != n || readSrc.size != n) {
                val tailOut = ArrayList(drawList.subList(drawList.size - tail, drawList.size))
                while (drawList.size > head) drawList.removeAt(drawList.size - 1)
                for (i in head until n - tail) drawList += readable(strokes[i], paper)
                drawList += tailOut
            }
        }
        readSrc.clear()
        if (hidden.isEmpty()) readSrc.addAll(strokes)
        return drawList
    }

    private fun readable(stroke: Stroke, paper: PaperColors): Stroke {
        val s = if (stroke === morphTarget) morphFrame ?: stroke else stroke
        val color = s.brush.colorIntArgb
        var want = PaperInk.colorOn(color, paper)
        if (s in dimmed) want = (want and 0x00FFFFFF) or ((want ushr 24) * DIM_ALPHA / 255 shl 24)
        if (want == color) return s
        val cached = tinted[s]
        return if (cached != null && cached.brush.colorIntArgb == want) {
            cached
        } else if (s === morphFrame) {
            s.copy(s.brush.copyWithColorIntArgb(want)) // a frame is drawn once: don't cache it
        } else {
            s.copy(s.brush.copyWithColorIntArgb(want)).also { tinted[s] = it }
        }
    }
}

/** Ink written after the audio playhead keeps this share of its opacity. */
private const val DIM_ALPHA = 56

/** Sheet chrome shared by all slots, in pixels. */
internal data class SheetStyle(val shadowRadius: Float, val shadowDy: Float, val shadowColor: Int) {
    val shadowPad: Int get() = (shadowRadius + shadowDy).toInt() + 1
}
