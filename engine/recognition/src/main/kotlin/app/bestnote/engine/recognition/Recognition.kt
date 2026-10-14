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

sealed interface RecognizedShape {
    data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : RecognizedShape
    data class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val rotationRad: Float) : RecognizedShape
    data class Polygon(val points: List<Pair<Float, Float>>, val closed: Boolean) : RecognizedShape
}

interface ShapeRecognizer {
    /** A clean shape for a hand-drawn stroke, or null when it isn't confidently a shape. */
    suspend fun recognize(stroke: InkStrokeSamples): RecognizedShape?
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
    override suspend fun recognize(stroke: InkStrokeSamples): RecognizedShape? = null
}

object NoOpSearchIndex : SearchIndex {
    override suspend fun index(notebookId: NotebookId, pageIndex: Int, text: String) = Unit
    override suspend fun remove(notebookId: NotebookId) = Unit
    override suspend fun search(query: String, limit: Int) = emptyList<SearchHit>()
}
