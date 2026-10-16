package app.bestnote.feature.editor.audio

import app.bestnote.core.model.AudioSync
import app.bestnote.core.model.AudioTimeline
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The strokes of a notebook that were written while a recording ran (MEDIA-05), kept as plain
 * polylines in page points so tapping and dimming need no ink geometry. [ref] is the canvas stroke
 * the entry stands for.
 */
internal class InkAudioIndex(val entries: List<Entry>) {
    class Entry(
        val page: Int,
        val sync: AudioSync,
        val xs: FloatArray,
        val ys: FloatArray,
        /** Half the stroke width in page points. */
        val radius: Float,
        val ref: Any,
    )

    private val offsetsByRecording: Map<String, LongArray> =
        entries.groupBy { it.sync.recordingId }.mapValues { (_, list) -> list.map { it.sync.offsetMillis }.sorted().toLongArray() }

    /** The synced stroke nearest to ([x], [y]) on [page], if one is within its width plus [slop] points. */
    fun hit(page: Int, x: Float, y: Float, slop: Float): Entry? {
        var best: Entry? = null
        var bestGap = Float.MAX_VALUE
        for (e in entries) {
            if (e.page != page) continue
            val gap = distanceToPolyline(e.xs, e.ys, x, y) - e.radius
            if (gap <= slop && gap < bestGap) {
                best = e
                bestGap = gap
            }
        }
        return best
    }

    /** Entries of [recordingId] written after [playheadMillis]: the ink to dim. */
    fun laterThan(recordingId: String, playheadMillis: Long): List<Entry> =
        entries.filter { AudioTimeline.isLater(it.sync, recordingId, playheadMillis) }

    /** A value that changes exactly when the dimmed set for [recordingId] changes, so callers redraw only then. */
    fun dimSignature(recordingId: String, playheadMillis: Long): Int =
        AudioTimeline.writtenCount(offsetsByRecording[recordingId] ?: LongArray(0), playheadMillis)

    companion object {
        fun distanceToPolyline(xs: FloatArray, ys: FloatArray, px: Float, py: Float): Float {
            if (xs.isEmpty()) return Float.MAX_VALUE
            if (xs.size == 1) return hypot(px - xs[0], py - ys[0])
            var best = Float.MAX_VALUE
            for (i in 0 until xs.size - 1) best = min(best, distanceToSegment(xs[i], ys[i], xs[i + 1], ys[i + 1], px, py))
            return best
        }

        private fun distanceToSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Float {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else max(0f, min(1f, ((px - ax) * dx + (py - ay) * dy) / len2))
            return hypot(px - (ax + t * dx), py - (ay + t * dy))
        }
    }
}
