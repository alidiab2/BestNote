/**
 * :engine:recognition — provider-neutral contracts for understanding ink.
 *
 * Handwriting-to-text, shape beautification and full-text search are defined here as plain
 * interfaces over [StylusSample] lists so implementations (ML Kit Digital Ink, an on-device
 * model, a server) can be swapped without touching the editor. Only no-op implementations
 * live in this module.
 */
package app.bestnote.engine.recognition

import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.StylusSample

/** One stroke as recognizers see it. */
typealias InkStrokeSamples = List<StylusSample>

data class TextCandidate(val text: String, val score: Float)

interface HandwritingRecognizer {
    /** BCP-47 language tags this recognizer can read, e.g. "en-US". */
    val languages: Set<String>

    /** Ranked candidates, best first; empty when nothing was recognized. */
    suspend fun recognize(strokes: List<InkStrokeSamples>, languageTag: String): List<TextCandidate>
}

/** What a [RecognizedShape] is, for UI and tests. */
enum class ShapeKind { Line, Arrow, Circle, Ellipse, Rectangle, Triangle, Polygon }

/**
 * A clean shape, in the same coordinates as the samples it was recognized from. Closed outlines
 * list their points in drawing order (same start and direction as the hand-drawn stroke).
 */
sealed interface RecognizedShape {
    val kind: ShapeKind

    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : RecognizedShape {
        override val kind get() = ShapeKind.Line
    }

    /** A shaft from (x0, y0) to the tip (x1, y1), with a two-barbed head: barbs at [barbs] (1 or 2 points). */
    data class Arrow(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val barbs: List<Pair<Float, Float>>,
    ) : RecognizedShape {
        override val kind get() = ShapeKind.Arrow
    }

    /** [startAngleRad] is where the hand-drawn loop began; [clockwise] its direction on screen (y down). */
    data class Ellipse(
        val cx: Float,
        val cy: Float,
        val rx: Float,
        val ry: Float,
        val rotationRad: Float,
        val startAngleRad: Float = 0f,
        val clockwise: Boolean = true,
    ) : RecognizedShape {
        override val kind get() = if (rx == ry) ShapeKind.Circle else ShapeKind.Ellipse
    }

    data class Polygon(
        val points: List<Pair<Float, Float>>,
        val closed: Boolean,
        override val kind: ShapeKind = ShapeKind.Polygon,
    ) : RecognizedShape
}

/** A recognized shape and how sure the recognizer is, 0..1. */
data class ShapeMatch(val shape: RecognizedShape, val confidence: Float) {
    val kind: ShapeKind get() = shape.kind
}

interface ShapeRecognizer {
    /** A clean shape for a hand-drawn stroke, or null when it isn't confidently a shape. */
    suspend fun recognize(stroke: InkStrokeSamples): ShapeMatch?

    /**
     * An arrow made of a straight [shaft] and a separately drawn [head] stroke (a "V" at one of its
     * ends), or null when [head] isn't an arrow head for it.
     */
    suspend fun recognizeArrow(shaft: InkStrokeSamples, head: InkStrokeSamples): ShapeMatch? = null
}

data class SearchHit(val notebookId: NotebookId, val pageIndex: Int, val snippet: String)

interface SearchIndex {
    suspend fun index(notebookId: NotebookId, pageIndex: Int, text: String)
    suspend fun remove(notebookId: NotebookId)
    suspend fun search(query: String, limit: Int = 50): List<SearchHit>
}

/** Recognizes nothing; the default until a real provider is installed. */
object NoOpHandwritingRecognizer : HandwritingRecognizer {
    override val languages: Set<String> = emptySet()
    override suspend fun recognize(strokes: List<InkStrokeSamples>, languageTag: String) = emptyList<TextCandidate>()
}

object NoOpShapeRecognizer : ShapeRecognizer {
    override suspend fun recognize(stroke: InkStrokeSamples): ShapeMatch? = null
}

object NoOpSearchIndex : SearchIndex {
    override suspend fun index(notebookId: NotebookId, pageIndex: Int, text: String) = Unit
    override suspend fun remove(notebookId: NotebookId) = Unit
    override suspend fun search(query: String, limit: Int) = emptyList<SearchHit>()
}
