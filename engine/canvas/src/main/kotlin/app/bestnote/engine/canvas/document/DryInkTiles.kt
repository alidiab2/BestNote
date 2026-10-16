package app.bestnote.engine.canvas.document

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.ink.geometry.BoxAccumulator
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.PageElement
import app.bestnote.engine.ink.BrushCatalog
import java.util.WeakHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Dry-ink tile cache (CANVAS-06): finished strokes, paper, PDF page and elements of a page are
 * rasterized off the main thread into [TileMath.SIZE] px tiles, one set per page and zoom scale.
 * A page slot then redraws by blitting a few bitmaps instead of replaying thousands of strokes.
 *
 * - Precise invalidation: [update] diffs the new [PageContent] against the last one (strokes by
 *   identity, elements by value, paper, PDF raster, pictures) and marks only the tiles under what
 *   changed. A tile whose only change is new ink keeps its old bitmap and draws the new strokes
 *   over it until it is re-rendered, so a finished stroke never flickers. Other changes draw the
 *   tile live (vector, culled to the tile) until its new bitmap is ready.
 * - Zoom: a scale with no tiles yet shows the tiles of the previous scale stretched (one frame of
 *   softness, no stall) while sharp tiles render at the exact new scale.
 * - Memory: a byte-bounded LRU; tiles drawn in the last second are never evicted.
 *
 * Main thread only, except the workers' rendering.
 */
internal class DryInkTiles(
    private val density: Float,
    budgetBytes: Long = defaultBudget(),
    private val workerCount: Int = 2,
) {
    // Freed explicitly: tiles churn quickly while writing and the GC doesn't see native pixel memory
    // soon enough (the app grew by ~60 MB/s on the emulator without this). A display list that still
    // shows a dropped tile holds its own reference to the pixels, so this never blanks the screen.
    val table = TileTable<Bitmap, Stroke>(budgetBytes, { it.allocationByteCount.toLong() }, { it.recycle() })

    /** False draws every page live, like before the cache (benchmarks compare the two). */
    var enabled = true

    /** Sharp PDF pieces at high zoom (PDF-02); the whole-page raster is used up to its resolution. */
    var backgrounds: PageBackgrounds? = null

    /** A tile of page [pageId] became ready: its slot should redraw. */
    var onTilesReady: ((pageId: String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val contents = HashMap<String, PageContent>()
    private val lastScale = HashMap<String, Float>()
    private val previousScale = HashMap<String, Float>()
    private val bounds = WeakHashMap<Stroke, FloatArray>()
    private val livePainter = PagePainter(density)
    private val region = RectF()
    private val wetBox = BoxAccumulator()

    // ---------------------------------------------------------------- content snapshots

    /** Bounds of [stroke] in page points (cached; strokes are immutable). */
    fun boxOf(stroke: Stroke): FloatArray = bounds.getOrPut(stroke) {
        val b = stroke.shape.computeBoundingBox()
        if (b == null) floatArrayOf(0f, 0f, -1f, -1f) else floatArrayOf(b.xMin, b.yMin, b.xMax, b.yMax)
    }

    /** A [PageContent] for [strokes] (already in drawing order and color). */
    fun content(
        pageId: String,
        page: app.bestnote.core.model.Page,
        colors: PaperColors,
        strokes: List<Stroke>,
        elements: List<PageElement>,
        images: Map<String, Bitmap>,
        raster: Bitmap?,
    ): PageContent {
        val copy = ArrayList(strokes)
        val n = copy.size
        val boxes = FloatArray(n * 4)
        val highlighter = BooleanArray(n)
        // Reuse what the last snapshot computed for the unchanged head and tail (a new stroke is
        // appended, an eraser cut is spliced in place), so a redraw after an edit stays O(changes).
        val prev = contents[pageId]
        var head = 0
        var tail = 0
        if (prev != null) {
            val ps = prev.strokes
            val max = minOf(ps.size, n)
            while (head < max && ps[head] === copy[head]) head++
            while (tail < max - head && ps[ps.size - 1 - tail] === copy[n - 1 - tail]) tail++
            prev.boxes.copyInto(boxes, 0, 0, head * 4)
            prev.highlighter.copyInto(highlighter, 0, 0, head)
            if (tail > 0) {
                prev.boxes.copyInto(boxes, (n - tail) * 4, (ps.size - tail) * 4, ps.size * 4)
                prev.highlighter.copyInto(highlighter, n - tail, ps.size - tail, ps.size)
            }
        }
        for (i in head until n - tail) {
            boxOf(copy[i]).copyInto(boxes, i * 4)
            highlighter[i] = BrushCatalog.isHighlighter(copy[i].brush)
        }
        return PageContent(pageId, page, colors, copy, boxes, highlighter, elements, images, raster)
    }

    /** Makes [next] the page's content; marks the tiles under whatever differs from the last one. */
    fun update(next: PageContent) {
        val id = next.pageId
        val prev = contents.put(id, next)
        if (prev == null) {
            table.invalidatePage(id)
            return
        }
        if (prev === next) return
        if (prev.page != next.page) return table.invalidatePage(id)
        if (next.page.pdf != null && prev.raster !== next.raster) table.invalidatePage(id, onlyPlaceholders = true)
        if (prev.elements !== next.elements || prev.images != next.images) diffElements(id, prev, next)
        val d = IdentityDiff.of(prev.strokes, next.strokes)
        if (d.isEmpty) return
        if (d.all || d.removed.size + d.added.size > MAX_PRECISE) return table.invalidatePage(id)
        for (s in d.removed) mark(id, s, null)
        for (s in d.added) mark(id, s, if (BrushCatalog.isHighlighter(s.brush)) null else s)
    }

    private fun mark(id: String, s: Stroke, added: Stroke?) {
        val b = boxOf(s)
        if (b[2] < b[0]) return
        table.invalidate(id, b[0], b[1], b[2], b[3], PAD_PX, added)
    }

    private fun diffElements(id: String, prev: PageContent, next: PageContent) {
        val before = prev.elements.associateBy { it.id }
        val after = next.elements.associateBy { it.id }
        val box = RectF()
        fun dirty(e: PageElement) {
            PageContent.elementBounds(e, box)
            table.invalidate(id, box.left, box.top, box.right, box.bottom, PAD_PX, null)
        }
        for ((key, e) in before) {
            val now = after[key]
            if (now != e) dirty(e)
            if (now != null && now != e) dirty(now)
        }
        for ((key, e) in after) {
            if (key !in before) dirty(e)
            if (e is ImageElement && prev.images[e.asset] !== next.images[e.asset]) dirty(e)
        }
    }

    /** Forgets pages that are no longer in the document. */
    fun retain(pageIds: Set<String>) {
        table.retain(pageIds)
        contents.keys.retainAll(pageIds)
        lastScale.keys.retainAll(pageIds)
        previousScale.keys.retainAll(pageIds)
        lock.withLock { queue.removeAll { it.key.page !in pageIds } }
    }

    fun clear() {
        table.clear()
        contents.clear()
        lastScale.clear()
        previousScale.clear()
        lock.withLock { queue.clear() }
    }

    // ---------------------------------------------------------------- drawing (main thread)

    /** Tiles drawn by the last [draw] call, by kind, for benchmarks and the debug STATE dump. */
    var lastFresh = 0
        private set
    var lastStale = 0
        private set
    var lastLive = 0
        private set

    /**
     * Draws page [c] at [scale] onto [canvas] (page pixels, clipped to the page) for the pixel rect
     * [coverage] (what may become visible before the next redraw); [visible] renders first.
     * [wet] is a highlighter in progress, drawn under the ink of the tiles it reaches.
     */
    fun draw(
        canvas: Canvas,
        c: PageContent,
        scale: Float,
        widthPx: Int,
        heightPx: Int,
        visible: Rect,
        coverage: Rect,
        wet: InProgressStroke?,
        wetColorArgb: Int,
    ) {
        lastFresh = 0
        lastStale = 0
        lastLive = 0
        if (!enabled) {
            region.set(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
            livePainter.draw(canvas, c, scale, widthPx, heightPx, region, null, wet, wetColorArgb)
            return
        }
        val id = c.pageId
        val now = SystemClock.uptimeMillis()
        table.now = now
        if (lastScale[id] != scale) {
            lastScale[id]?.let { previousScale[id] = it }
            lastScale[id] = scale
        }
        val cov = TileMath.range(coverage.left.toFloat(), coverage.top.toFloat(), coverage.right.toFloat(), coverage.bottom.toFloat(), widthPx, heightPx) ?: return
        val vis = TileMath.range(visible.left.toFloat(), visible.top.toFloat(), visible.right.toFloat(), visible.bottom.toFloat(), widthPx, heightPx)
        val wetRange = wet?.let { wetRange(it, scale, widthPx, heightPx) }
        val live = ArrayList<TileTable.Entry<Bitmap, Stroke>>()
        val drawn = ArrayList<TileTable.Entry<Bitmap, Stroke>>(cov.count)
        // Visible tiles are requested first, then the margin.
        for (pass in 0..1) {
            for (row in cov.r0..cov.r1) for (col in cov.c0..cov.c1) {
                val inVis = vis?.contains(col, row) == true
                if ((pass == 0) != inVis) continue
                val e = table.obtain(TileTable.Key(id, scale, col, row))
                if (e.needsRender) request(e, c, scale, widthPx, heightPx, if (inVis) 0 else 1)
                if (wetRange?.contains(col, row) == true || !e.showable) live += e else drawn += e
            }
        }
        if (live.isNotEmpty()) drawLive(canvas, c, scale, widthPx, heightPx, live, wet, wetColorArgb)
        for (e in drawn) {
            val x = (e.key.col * TileMath.SIZE).toFloat()
            val y = (e.key.row * TileMath.SIZE).toFloat()
            canvas.drawBitmap(e.bitmap!!, x, y, null)
            val overlay = e.overlay
            if (!e.fresh && !overlay.isNullOrEmpty()) {
                val save = canvas.save()
                canvas.clipRect(x, y, x + TileMath.SIZE, y + TileMath.SIZE)
                livePainter.drawInk(canvas, overlay, scale)
                canvas.restoreToCount(save)
            }
            if (e.fresh) lastFresh++ else lastStale++
        }
        table.evict()
    }

    private fun wetRange(wet: InProgressStroke, scale: Float, w: Int, h: Int): TileRange? {
        wetBox.reset()
        for (coat in 0 until wet.getBrushCoatCount()) wet.populateMeshBounds(coat, wetBox)
        val b = wetBox.box ?: return null
        return TileMath.range(b.xMin * scale - PAD_PX, b.yMin * scale - PAD_PX, b.xMax * scale + PAD_PX, b.yMax * scale + PAD_PX, w, h)
    }

    /** Tiles without a usable bitmap: the previous zoom's tiles stretched if they cover it, else vector ink. */
    private fun drawLive(
        canvas: Canvas,
        c: PageContent,
        scale: Float,
        w: Int,
        h: Int,
        tiles: List<TileTable.Entry<Bitmap, Stroke>>,
        wet: InProgressStroke?,
        wetColor: Int,
    ) {
        val vector = ArrayList<TileTable.Entry<Bitmap, Stroke>>(tiles.size)
        val old = previousScale[c.pageId]
        for (e in tiles) {
            // The old zoom's tiles may stand in only while they still show the current content (fresh).
            if (wet == null && old != null && drawFromScale(canvas, c.pageId, e.key, scale, old, w, h)) {
                lastStale++
            } else {
                vector += e
            }
        }
        if (vector.isEmpty()) return
        region.setEmpty()
        val r = RectF()
        for (e in vector) {
            tileRect(e.key, w, h, r)
            region.union(r)
        }
        if (livePainter.countIn(c, scale, region) > LIVE_STROKES) {
            // Too much ink to draw now without stalling the main thread (it grows with the page):
            // show each tile's last bitmap (briefly out of date) or bare paper until the workers finish.
            for (e in vector) {
                tileRect(e.key, w, h, r)
                val save = canvas.save()
                canvas.clipRect(r)
                val b = e.bitmap
                if (b != null) canvas.drawBitmap(b, r.left, r.top, null) else livePainter.draw(canvas, c, scale, w, h, r, null, withInk = false)
                if (wet != null) livePainter.drawWet(canvas, wet, wetColor, scale)
                canvas.restoreToCount(save)
                lastStale++
            }
            return
        }
        lastLive += vector.size
        if (vector.size <= PER_TILE_LIVE) {
            for (e in vector) {
                tileRect(e.key, w, h, region)
                val save = canvas.save()
                canvas.clipRect(region)
                livePainter.draw(canvas, c, scale, w, h, region, null, wet, wetColor)
                canvas.restoreToCount(save)
            }
        } else {
            val save = canvas.save()
            canvas.clipRect(region)
            livePainter.draw(canvas, c, scale, w, h, region, null, wet, wetColor)
            canvas.restoreToCount(save)
        }
    }

    /** Draws tile [key] from the fresh tiles of scale [old], stretched; false if they don't cover it. */
    private fun drawFromScale(canvas: Canvas, page: String, key: TileTable.Key, scale: Float, old: Float, w: Int, h: Int): Boolean {
        tileRect(key, w, h, region)
        val k = old / scale
        val ow = (w * k).toInt()
        val oh = (h * k).toInt()
        val range = TileMath.range(region.left * k, region.top * k, region.right * k, region.bottom * k, ow, oh) ?: return false
        val olds = ArrayList<Bitmap>(range.count)
        for (row in range.r0..range.r1) for (col in range.c0..range.c1) {
            val e = table.peek(TileTable.Key(page, old, col, row)) ?: return false
            if (!e.fresh) return false
            olds += e.bitmap!!
        }
        val save = canvas.save()
        canvas.clipRect(region)
        var i = 0
        for (row in range.r0..range.r1) for (col in range.c0..range.c1) {
            val b = olds[i++]
            val l = col * TileMath.SIZE / k
            val t = row * TileMath.SIZE / k
            livePainter.drawScaled(canvas, b, l, t, l + b.width / k, t + b.height / k)
        }
        canvas.restoreToCount(save)
        return true
    }

    private fun tileRect(key: TileTable.Key, w: Int, h: Int, out: RectF) {
        val x = key.col * TileMath.SIZE
        val y = key.row * TileMath.SIZE
        out.set(x.toFloat(), y.toFloat(), minOf(x + TileMath.SIZE, w).toFloat(), minOf(y + TileMath.SIZE, h).toFloat())
    }

    // ---------------------------------------------------------------- workers

    private class Job(
        val key: TileTable.Key,
        val gen: Int,
        val content: PageContent,
        val widthPx: Int,
        val heightPx: Int,
        val priority: Int,
        val seq: Long,
        val epoch: Int,
    )

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val queue = ArrayList<Job>()
    private var seq = 0L
    private var running = 0
    private var closed = false
    private val workers = ArrayList<Thread>()

    /** Bumped by [close]: workers of an older epoch exit, so the cache can start again after it. */
    private var epoch = 0

    /** Jobs queued or rendering, for tests that wait for the cache to settle. */
    val busy: Boolean get() = lock.withLock { queue.isNotEmpty() || running > 0 }

    private fun request(e: TileTable.Entry<Bitmap, Stroke>, c: PageContent, scale: Float, w: Int, h: Int, priority: Int) {
        e.requestedGen = e.gen
        val job = Job(e.key, e.gen, c, w, h, priority, seq++, epoch)
        lock.withLock {
            queue.removeAll { it.key == e.key }
            queue += job
            if (queue.size > MAX_QUEUE) {
                // Drop the oldest margin work first; its tiles are requested again when they are drawn.
                val drop = queue.filter { it.priority > 0 }.minByOrNull { it.seq } ?: queue.minBy { it.seq }
                queue.remove(drop)
                table.peek(drop.key)?.let { if (it.requestedGen == drop.gen) it.requestedGen = -1 }
            }
            closed = false
            if (workers.isEmpty()) startWorkers()
            wake.signal()
        }
    }

    private fun startWorkers() {
        val mine = epoch
        repeat(workerCount) { i ->
            workers += Thread({ work(mine) }, "dry-ink-tiles-$i").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
                start()
            }
        }
    }

    private fun work(mine: Int) {
        val painter = PagePainter(density)
        val region = RectF()
        while (true) {
            val job = lock.withLock {
                while (epoch == mine && queue.isEmpty()) wake.awaitUninterruptibly()
                if (epoch != mine) return
                // Visible tiles first, newest request first (the page you stopped on).
                val best = queue.minWith(compareBy<Job> { it.priority }.thenByDescending { it.seq })
                queue.remove(best)
                running++
                best
            }
            var result: Bitmap? = null
            var placeholder = false
            try {
                val k = job.key
                val x = k.col * TileMath.SIZE
                val y = k.row * TileMath.SIZE
                val bw = minOf(TileMath.SIZE, job.widthPx - x)
                val bh = minOf(TileMath.SIZE, job.heightPx - y)
                if (bw > 0 && bh > 0) {
                    val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                    region.set(x.toFloat(), y.toFloat(), (x + bw).toFloat(), (y + bh).toFloat())
                    val c = job.content
                    var piece: Bitmap? = null
                    if (c.page.pdf != null) {
                        val raster = c.raster
                        val sharp = raster != null && raster.width >= job.widthPx * SHARP_ENOUGH
                        if (!sharp) piece = backgrounds?.region(c.page, job.widthPx, job.heightPx, Rect(x, y, x + bw, y + bh))
                        placeholder = !sharp && piece == null
                    }
                    val canvas = Canvas(bitmap)
                    canvas.translate(-x.toFloat(), -y.toFloat())
                    canvas.clipRect(region)
                    painter.draw(canvas, c, k.scale, job.widthPx, job.heightPx, region, piece)
                    piece?.recycle()
                    result = bitmap
                }
            } catch (_: OutOfMemoryError) {
                main.post { table.budgetBytes = (table.budgetBytes / 2).coerceAtLeast(MIN_BUDGET); table.evict() }
            } catch (_: RuntimeException) {
                // A stroke or PDF that can't be drawn: the tile stays live (vector) instead.
            } finally {
                lock.withLock { running-- }
            }
            val bitmap = result ?: continue
            main.post { installed(job, bitmap, placeholder) }
        }
    }

    private fun installed(job: Job, bitmap: Bitmap, placeholder: Boolean) {
        if (closed || job.epoch != epoch || !table.install(job.key, job.gen, bitmap, placeholder)) {
            bitmap.recycle()
            return
        }
        val page = job.key.page
        pendingReady += page
        // Coalesce: one redraw when this page's visible work is done, or every READY_MS meanwhile.
        val more = lock.withLock { queue.any { it.key.page == page && it.priority == 0 } || running > 0 }
        if (!more) flushReady() else if (!readyScheduled) {
            readyScheduled = true
            main.postDelayed(flush, READY_MS)
        }
    }

    private val pendingReady = HashSet<String>()
    private var readyScheduled = false
    private val flush = Runnable { flushReady() }

    private fun flushReady() {
        readyScheduled = false
        main.removeCallbacks(flush)
        val pages = pendingReady.toList()
        pendingReady.clear()
        pages.forEach { onTilesReady?.invoke(it) }
    }

    /** Stops the workers and drops every tile; drawing again starts new workers. */
    fun close() {
        lock.withLock {
            closed = true
            epoch++
            workers.clear()
            queue.clear()
            wake.signalAll()
        }
        main.removeCallbacks(flush)
        pendingReady.clear()
        clear()
    }

    /** Renders every queued tile on the calling thread's behalf and waits (benchmarks and tests only). */
    fun awaitIdle(timeoutMs: Long = 30_000) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (busy && SystemClock.uptimeMillis() < end) Thread.sleep(2)
    }

    companion object {
        private const val PAD_PX = 2f
        private const val MAX_PRECISE = 400
        private const val MAX_QUEUE = 96
        private const val PER_TILE_LIVE = 4

        /** Most strokes drawn as vectors in one redraw (a few ms on a phone); past it tiles wait for the workers. */
        private const val LIVE_STROKES = 600
        private const val READY_MS = 120L
        private const val SHARP_ENOUGH = 0.98f
        private const val MIN_BUDGET = 16L shl 20

        /**
         * A quarter of the app's heap limit, between 32 and 96 MB: a full screen of tiles plus the margin
         * is ~30 MB on a 2560 x 1600 tablet. (Tile bitmaps live in native memory; this is a policy.)
         */
        fun defaultBudget(): Long = (Runtime.getRuntime().maxMemory() / 4).coerceIn(32L shl 20, 96L shl 20)
    }
}
