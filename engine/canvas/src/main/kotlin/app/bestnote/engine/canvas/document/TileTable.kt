package app.bestnote.engine.canvas.document

import kotlin.math.ceil
import kotlin.math.floor

/** Inclusive column/row range of tiles. */
internal data class TileRange(val c0: Int, val r0: Int, val c1: Int, val r1: Int) {
    val count: Int get() = (c1 - c0 + 1) * (r1 - r0 + 1)

    fun contains(col: Int, row: Int) = col in c0..c1 && row in r0..r1

    fun contains(other: TileRange) = other.c0 >= c0 && other.c1 <= c1 && other.r0 >= r0 && other.r1 <= r1
}

/** Tile geometry of the dry-ink cache (CANVAS-06): a page rendered at one scale, cut into [SIZE] px squares. */
internal object TileMath {
    const val SIZE = 512

    fun cols(widthPx: Int) = (widthPx + SIZE - 1) / SIZE

    fun rows(heightPx: Int) = (heightPx + SIZE - 1) / SIZE

    /** Tiles overlapping the pixel rect (l, t, r, b) of a page [widthPx] x [heightPx] px, or null if none. */
    fun range(l: Float, t: Float, r: Float, b: Float, widthPx: Int, heightPx: Int): TileRange? {
        if (widthPx <= 0 || heightPx <= 0 || r <= l || b <= t) return null
        val c0 = floor(l / SIZE).toInt().coerceAtLeast(0)
        val r0 = floor(t / SIZE).toInt().coerceAtLeast(0)
        val c1 = (ceil(r / SIZE).toInt() - 1).coerceAtMost(cols(widthPx) - 1)
        val r1 = (ceil(b / SIZE).toInt() - 1).coerceAtMost(rows(heightPx) - 1)
        if (c1 < c0 || r1 < r0) return null
        return TileRange(c0, r0, c1, r1)
    }
}

/**
 * What changed between two snapshots of a list whose items are compared by identity (strokes are
 * immutable, so a different instance is a different stroke). [all] means "treat everything as
 * changed" (the order of the surviving items changed). [additive] = nothing was removed.
 */
internal class IdentityDiff<T>(val all: Boolean, val removed: List<T>, val added: List<T>) {
    val isEmpty: Boolean get() = !all && removed.isEmpty() && added.isEmpty()
    val additive: Boolean get() = !all && removed.isEmpty()

    companion object {
        /**
         * Common prefix and suffix are skipped first, so appending a stroke or splicing an eraser cut
         * in place costs a pointer compare per stroke and no allocation.
         */
        fun <T> of(prev: List<T>, next: List<T>): IdentityDiff<T> {
            var start = 0
            val min = minOf(prev.size, next.size)
            while (start < min && prev[start] === next[start]) start++
            var endPrev = prev.size
            var endNext = next.size
            while (endPrev > start && endNext > start && prev[endPrev - 1] === next[endNext - 1]) {
                endPrev--
                endNext--
            }
            if (start == endPrev && start == endNext) return IdentityDiff(false, emptyList(), emptyList())
            val before = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>())
            for (i in start until endPrev) before += prev[i]
            val added = ArrayList<T>()
            val kept = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>())
            for (i in start until endNext) {
                val item = next[i]
                if (item in before) kept += item else added += item
            }
            val removed = ArrayList<T>()
            for (i in start until endPrev) if (prev[i] !in kept) removed += prev[i]
            // Same items in a different order: z-order changed somewhere, redraw it all.
            val all = removed.isEmpty() && added.isEmpty()
            return IdentityDiff(all, removed, added)
        }
    }
}

/**
 * Bookkeeping of the dry-ink tile cache, free of Android types so it can be unit tested: which tile
 * bitmaps exist for which page and scale, whether they are current, the strokes to draw over a tile
 * whose only changes since it was rendered are additions, and a byte-bounded LRU.
 *
 * Generations: every change that touches a tile bumps its [Entry.gen]; a render started for gen g is
 * installed only if the tile is still at g, so a slow render never overwrites newer content.
 */
/** How poor a stand-in tiles at [scale] are for [current]: log distance, blurrier (smaller) scales count double. */
internal fun standInCost(scale: Float, current: Float): Float {
    val r = kotlin.math.ln(scale / current)
    return if (r >= 0f) r else -2f * r
}

internal class TileTable<B : Any, S : Any>(
    var budgetBytes: Long,
    private val sizeOf: (B) -> Long,
    /** A bitmap left the table (replaced, evicted, dropped): free it now rather than waiting for GC. */
    private val onDrop: (B) -> Unit = {},
) {
    data class Key(val page: String, val scale: Float, val col: Int, val row: Int)

    class Entry<B, S>(val key: Key) {
        var bitmap: B? = null
        var bitmapGen = -1
        var gen = 0

        /** Gen a render was last requested for (avoids queueing the same work twice). */
        var requestedGen = -1

        /** Strokes added since [bitmapGen]; drawn over the bitmap until it is re-rendered. */
        var overlay: ArrayList<S>? = null

        /** A non-additive change happened since [bitmapGen]: the bitmap can't be shown at all. */
        var broken = false

        /** The bitmap's background was a placeholder (a PDF page still rendering). */
        var placeholder = false
        var lastUse = 0L

        val fresh: Boolean get() = bitmap != null && bitmapGen == gen
        val showable: Boolean get() = bitmap != null && !broken
        val needsRender: Boolean get() = !fresh && requestedGen != gen
    }

    private val map = LinkedHashMap<Key, Entry<B, S>>(64, 0.75f, true)
    var bytes = 0L
        private set
    val size: Int get() = map.size
    /** Current time in ms (set by the caller before drawing); tiles used within [protectMs] of it are never evicted. */
    var now = 0L
    var protectMs = 1000L

    fun peek(key: Key): Entry<B, S>? = map[key]

    /** The entry for [key], created empty if needed, marked as used now. */
    fun obtain(key: Key): Entry<B, S> = (map[key] ?: Entry<B, S>(key).also { map[key] = it }).also { it.lastUse = now }

    /** Puts a finished render in place; false (and nothing changes) if the tile moved on or was evicted. */
    fun install(key: Key, gen: Int, bitmap: B, placeholder: Boolean): Boolean {
        val e = map[key] ?: return false
        if (e.gen != gen) return false
        e.bitmap?.let { drop(it) }
        e.bitmap = bitmap
        bytes += sizeOf(bitmap)
        e.bitmapGen = gen
        e.overlay = null
        e.broken = false
        e.placeholder = placeholder
        evict()
        return true
    }

    /**
     * Marks the tiles of [page] that overlap the page-point rect (l, t, r, b), padded by [padPx] device
     * pixels at each tile's scale. [added] non-null = the change only added these strokes (they can be
     * drawn over the old bitmap meanwhile); null = the old bitmap is wrong there.
     */
    fun invalidate(page: String, l: Float, t: Float, r: Float, b: Float, padPx: Float, added: S?) {
        for (e in map.values) {
            val k = e.key
            if (k.page != page) continue
            val size = TileMath.SIZE / k.scale
            val pad = padPx / k.scale
            val tl = k.col * size
            val tt = k.row * size
            if (r + pad < tl || l - pad > tl + size || b + pad < tt || t - pad > tt + size) continue
            touch(e, added)
        }
    }

    /** Every tile of [page] is out of date ([onlyPlaceholders]: just those rendered over a placeholder background). */
    fun invalidatePage(page: String, onlyPlaceholders: Boolean = false) {
        for (e in map.values) if (e.key.page == page && (!onlyPlaceholders || e.placeholder)) touch(e, null)
    }

    private fun touch(e: Entry<B, S>, added: S?) {
        e.gen++
        // No bitmap yet: nothing on screen is wrong, the pending render just restarts.
        if (e.bitmap == null) return
        if (added != null && !e.broken) {
            (e.overlay ?: ArrayList<S>().also { e.overlay = it }) += added
        } else {
            e.broken = true
            e.overlay = null
        }
    }

    fun pages(): Set<String> = map.keys.mapTo(HashSet()) { it.page }

    /**
     * Other scales of [page] (not [current]) that have at least one showable tile, best stand-in
     * first, into [out]; returns how many. Best = closest to [current], a sharper (larger) scale
     * preferred over a blurrier one the same distance away. No allocation.
     */
    fun scalesOf(page: String, current: Float, out: FloatArray): Int {
        var n = 0
        for (e in map.values) {
            val k = e.key
            if (k.page != page || k.scale == current || !e.showable) continue
            var dup = false
            for (i in 0 until n) if (out[i] == k.scale) dup = true
            if (dup) continue
            val cost = standInCost(k.scale, current)
            if (n == out.size && cost >= standInCost(out[n - 1], current)) continue
            // Insertion sort by cost.
            var i = if (n == out.size) n - 1 else n++
            while (i > 0 && standInCost(out[i - 1], current) > cost) {
                out[i] = out[i - 1]
                i--
            }
            out[i] = k.scale
        }
        return n
    }

    /** Drops every tile of the pages not in [keep]. */
    fun retain(keep: Set<String>) {
        val it = map.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.page !in keep) {
                e.bitmap?.let { b -> drop(b) }
                it.remove()
            }
        }
    }

    fun clear() {
        for (e in map.values) e.bitmap?.let(onDrop)
        map.clear()
        bytes = 0
    }

    private fun drop(b: B) {
        bytes -= sizeOf(b)
        onDrop(b)
    }

    /** Least recently used tiles go first; tiles drawn within [protectMs] stay. */
    fun evict() {
        if (bytes <= budgetBytes) return
        val it = map.values.iterator()
        while (bytes > budgetBytes && it.hasNext()) {
            val e = it.next()
            if (e.lastUse > now - protectMs) continue
            e.bitmap?.let { b -> drop(b) }
            it.remove()
        }
    }
}
