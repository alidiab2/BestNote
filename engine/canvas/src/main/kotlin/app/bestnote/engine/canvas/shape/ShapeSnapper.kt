package app.bestnote.engine.canvas.shape

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.ink.strokes.Stroke
import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.StylusSample
import app.bestnote.engine.recognition.GeometricShapeRecognizer
import app.bestnote.engine.recognition.ShapeMatch
import java.util.WeakHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * INK-08: turns finished hand-drawn strokes into clean shapes. Recognition ([GeometricShapeRecognizer])
 * runs on a background thread; the swap happens on the main thread as one undo step (undo brings
 * the raw stroke back), followed by a short morph from the raw path into the clean one. A line drawn
 * right before a "V" that ends on it becomes one arrow with it.
 *
 * CANVAS-17: a stroke lifted while its hold-to-shape ghost showed becomes exactly that ghost, at once (no morph:
 * the ghost already showed it). Every clean shape is stored with its [ShapeInfo] and glues onto the page's other
 * shapes (a line's ends attach to them).
 *
 * Main thread only, except the recognizer itself.
 */
internal class ShapeSnapper(private val host: View, private val pages: Pages) {

    /** What the snapper needs from the document and its pages. */
    interface Pages {
        /** Strokes of page [index] now, or null when there is no such page. */
        fun strokes(index: Int): List<Stroke>?

        /** Swaps [replace]'s key for its value (stored as clean shape [shape]) and removes [remove] on page [index], as one undo step. */
        fun commit(index: Int, replace: Pair<Stroke, Stroke>, remove: Stroke?, shape: ShapeInfo)

        /** The clean shapes on page [index] now (what new shapes glue to). */
        fun shapes(index: Int): List<ShapeInfo>

        /** How close (page points) a shape must come to another to glue to it. */
        val glueTolerance: Float

        /** Draws [frame] in place of [target] on page [index] (both null: stop). */
        fun showMorph(index: Int, target: Stroke?, frame: Stroke?)

        /** True while a pen (or finger) is writing: nothing animates then. */
        val busy: Boolean
    }

    var morphDurationMs: Int = 0

    private val recognizer = GeometricShapeRecognizer()
    private val settler = ShapePreview(recognizer)
    private val worker: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "shape-snap").apply { isDaemon = true } }
    }

    /** Bumped when the document changes: results computed for the old one are dropped. */
    private var generation = 0

    /** The last stroke finished on any page: page, stroke, uptime. A possible arrow shaft. */
    private var last: Triple<Int, Stroke, Long>? = null
    private var morph: ValueAnimator? = null

    /** Raw stroke -> the clean stroke that replaced it, so a later arrow head finds its (already snapped) shaft. */
    private val snapped = WeakHashMap<Stroke, Stroke>()

    fun reset() {
        generation++
        last = null
        snapped.clear()
        endMorph()
    }

    /**
     * [stroke] was just added to page [page]; [snap] when it should become a clean shape if it is one. With a
     * [ghost] (the hold-to-shape preview showed when the pen lifted) it becomes exactly that, now.
     */
    fun onStrokeFinished(page: Int, stroke: Stroke, snap: Boolean, ghost: ShapePreview.Ghost? = null) {
        val now = SystemClock.uptimeMillis()
        val prev = last
        last = Triple(page, stroke, now)
        if (ghost != null) {
            val strokes = pages.strokes(page) ?: return
            if (strokes.none { it === stroke }) return
            val clean = ShapeStroke.build(ghost.shape, stroke, ShapeStroke.samplesOf(stroke))
            pages.commit(page, stroke to clean.stroke, null, ghost.info(ShapeEdits.newId()))
            snapped[stroke] = clean.stroke
            last = Triple(page, clean.stroke, now)
            return
        }
        if (!snap) return
        val strokes = pages.strokes(page) ?: return
        val samples = ShapeStroke.samplesOf(stroke)
        // The stroke drawn just before, on the same page, may be the shaft of an arrow whose head this is.
        val at = strokes.indexOfLast { it === stroke }
        val shaft = prev?.takeIf { (p, s, t) -> p == page && now - t < ARROW_PAIR_MS && at >= 1 && strokes[at - 1] === s }?.second
        val shaftSamples = shaft?.let(ShapeStroke::samplesOf)
        val gen = generation
        worker.execute {
            val arrow = shaftSamples?.let { recognizer.matchArrow(it, samples) }
            val match = arrow ?: recognizer.match(samples) ?: return@execute
            host.post {
                if (gen == generation) apply(page, stroke, samples, if (arrow != null) shaft else null, shaftSamples, match)
            }
        }
    }

    private fun apply(page: Int, raw: Stroke, samples: List<StylusSample>, rawShaft: Stroke?, shaftSamples: List<StylusSample>?, match: ShapeMatch) {
        val strokes = pages.strokes(page) ?: return
        var shaft = rawShaft
        while (shaft != null && strokes.none { it === shaft }) shaft = snapped[shaft]
        // Erased, undone or moved meanwhile: leave it as drawn.
        if (strokes.none { it === raw } || (shaft != null && strokes.none { it === shaft })) return
        val from = if (shaft != null) shaftSamples!! + samples else samples
        // Glued onto the page's other shapes, like a hold-to-shape ghost.
        val glued = settler.settle(match.shape, pages.shapes(page), pages.glueTolerance)
        val clean = ShapeStroke.build(glued.shape, shaft ?: raw, from)
        val info = glued.info(ShapeEdits.newId())
        if (shaft != null) pages.commit(page, shaft to clean.stroke, raw, info) else pages.commit(page, raw to clean.stroke, null, info)
        snapped[raw] = clean.stroke
        if (shaft != null) snapped[shaft] = clean.stroke
        if (last?.second === raw) last = Triple(page, clean.stroke, last!!.third)
        startMorph(page, clean, Polyline(FloatArray(from.size) { from[it].x }, FloatArray(from.size) { from[it].y }))
    }

    private fun startMorph(page: Int, clean: ShapeStroke, raw: Polyline) {
        endMorph()
        if (morphDurationMs <= 0 || pages.busy || raw.size < 2) return
        val start = raw.resample(clean.outline.size)
        val end = clean.outline
        morph = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = morphDurationMs.toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                pages.showMorph(page, clean.stroke, clean.withPoints(Polyline.lerp(start, end, f)))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (morph === animation) morph = null
                    pages.showMorph(-1, null, null)
                }
            })
            pages.showMorph(page, clean.stroke, clean.withPoints(start))
            start()
        }
    }

    /** Jumps any running morph to its clean end (e.g. the pen touches down: nothing moves under it). */
    fun endMorph() {
        morph?.let {
            morph = null
            it.cancel()
        }
    }

    private companion object {
        /** A "V" drawn within this long after a line can make it an arrow. */
        const val ARROW_PAIR_MS = 4000L
    }
}
