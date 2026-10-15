package app.bestnote.engine.pdf

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import app.bestnote.core.model.Page
import app.bestnote.engine.canvas.document.PageBackgrounds
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * Renders the PDF pages of an open notebook for the page canvas (PDF-01/02): a single background
 * thread rasterizes the pages being looked at (newest request first, so scrolling shows the page
 * you stopped on first), results go into a byte-bounded LRU cache, and the slot is told to redraw.
 * While a sharp bitmap renders, the best cached one for the same page (any width) is drawn scaled.
 *
 * [sources] maps a PDF asset path to its document. Closing this closes them.
 */
class PdfPageBackgrounds(
    private val sources: Map<String, PdfDocumentSource>,
    cacheBytes: Long = defaultCacheBytes(),
    private val main: Handler = Handler(Looper.getMainLooper()),
) : PageBackgrounds, Closeable {
    private data class Key(val asset: String, val page: Int, val width: Int)

    private class Job(val key: Key, val heightPx: Int) {
        val listeners = LinkedHashSet<Runnable>()
    }

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val cache = SizedLru<Key, Bitmap>(cacheBytes) { it.allocationByteCount.toLong() }

    /** Requests not started yet; iteration order = oldest first. */
    private val pending = LinkedHashMap<Key, Job>()
    private val failed = HashSet<Key>()
    private var closed = false
    private val worker = Thread(::run, "pdf-render").apply { isDaemon = true; start() }

    override fun background(page: Page, widthPx: Int, onReady: Runnable): Bitmap? {
        val ref = page.pdf ?: return null
        if (widthPx <= 0) return null
        val aspect = page.heightPt / page.widthPt
        val width = bucket(widthPx, aspect)
        val key = Key(ref.asset, ref.pageIndex, width)
        lock.withLock {
            cache[key]?.let { return it }
            if (!closed && key !in failed && ref.asset in sources) {
                val job = pending.remove(key) ?: Job(key, (width * aspect).toInt().coerceAtLeast(1))
                job.listeners += onReady
                pending[key] = job // newest last
                while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
                wake.signalAll()
            }
            return cache.entries()
                .filter { it.key.asset == key.asset && it.key.page == key.page }
                .maxByOrNull { it.key.width }?.value
        }
    }

    /** Renders [page] now, [widthPx] wide, bypassing the cache (thumbnails). Call off the main thread. */
    override fun thumbnail(page: Page, widthPx: Int): Bitmap? = renderNow(page, widthPx)

    fun renderNow(page: Page, widthPx: Int): Bitmap? {
        val ref = page.pdf ?: return null
        return sources[ref.asset]?.takeIf { ref.pageIndex < it.pageCount }?.renderPage(ref.pageIndex, widthPx)
    }

    private fun run() {
        while (true) {
            val job = lock.withLock {
                while (!closed && pending.isEmpty()) wake.awaitUninterruptibly()
                if (closed) return
                val newest = pending.keys.last()
                pending.remove(newest)!!
            }
            val bitmap = try {
                sources.getValue(job.key.asset).renderPage(job.key.page, job.key.width)
            } catch (_: Exception) { // damaged page or a source closed under us: leave the page white
                null
            }
            val listeners = lock.withLock {
                if (closed) return
                if (bitmap != null) cache.put(job.key, bitmap) else failed += job.key
                job.listeners.toList()
            }
            if (bitmap != null) main.post { listeners.forEach(Runnable::run) }
        }
    }

    override fun close() {
        lock.withLock {
            closed = true
            pending.clear()
            wake.signalAll()
        }
        worker.interrupt()
        sources.values.forEach { runCatching { it.close() } }
        lock.withLock { cache.clear() }
    }

    companion object {
        private const val MAX_PENDING = 12
        private const val STEP = 64
        private const val MAX_WIDTH = 2400
        private const val MAX_PIXELS = 6_000_000.0 // 24 MB per bitmap

        /** Width to render for a slot [widthPx] wide: rounded up to a step (fewer distinct bitmaps) and bounded in memory. */
        internal fun bucket(widthPx: Int, aspect: Float): Int {
            val byMemory = sqrt(MAX_PIXELS / aspect.coerceAtLeast(0.01f)).toInt()
            val limit = minOf(MAX_WIDTH, byMemory).coerceAtLeast(STEP)
            return ((widthPx + STEP - 1) / STEP * STEP).coerceIn(STEP, limit)
        }

        fun defaultCacheBytes(): Long = (Runtime.getRuntime().maxMemory() / 8).coerceIn(32L shl 20, 128L shl 20)
    }
}
