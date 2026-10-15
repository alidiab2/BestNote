package app.bestnote.engine.canvas

import androidx.ink.strokes.Stroke

/** The page's finished strokes, as edited by [CanvasEdit]s. */
interface StrokeTarget {
    fun add(strokes: Collection<Stroke>)
    fun remove(strokes: Collection<Stroke>)
}

/** A reversible change to the page's finished strokes, kept in the page's [CommandStack]. */
sealed interface CanvasEdit : Command<StrokeTarget> {
    data class Added(val strokes: List<Stroke>) : CanvasEdit {
        override val retainedBytes: Long = estimateBytes(strokes)
        override fun apply(target: StrokeTarget) = target.add(strokes)
        override fun revert(target: StrokeTarget) = target.remove(strokes)
    }

    data class Erased(val strokes: List<Stroke>) : CanvasEdit {
        override val retainedBytes: Long = estimateBytes(strokes)
        override fun apply(target: StrokeTarget) = target.remove(strokes)
        override fun revert(target: StrokeTarget) = target.add(strokes)
    }

    /** [removed] strokes became [added] (transforms, restyles, pixel-eraser splits). */
    data class Replaced(val removed: List<Stroke>, val added: List<Stroke>) : CanvasEdit {
        override val retainedBytes: Long = estimateBytes(removed) + estimateBytes(added)
        override fun apply(target: StrokeTarget) {
            target.remove(removed)
            target.add(added)
        }

        override fun revert(target: StrokeTarget) {
            target.remove(added)
            target.add(removed)
        }
    }

    companion object {
        /** Inputs + mesh per input sample, rounded up; good enough to bound history memory. */
        private const val BYTES_PER_INPUT = 96L
        private const val BYTES_PER_STROKE = 512L

        internal fun estimateBytes(strokes: List<Stroke>): Long =
            strokes.sumOf { BYTES_PER_STROKE + it.inputs.size * BYTES_PER_INPUT }
    }
}
