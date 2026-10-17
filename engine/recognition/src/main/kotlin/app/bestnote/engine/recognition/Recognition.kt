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
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.PageSearchHit
import app.bestnote.core.model.StylusSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One stroke as recognizers see it. */
typealias InkStrokeSamples = List<StylusSample>

/** One reading of some ink. [score] is a cost: lower is more confident, and scores are comparable across languages. */
data class TextCandidate(val text: String, val score: Float)

/**
 * What a recognizer may know about the ink besides the strokes (REC-10).
 * @property preContext the text written just before this ink (e.g. the end of the previous line), for the language model.
 */
data class InkHints(val preContext: String = "")

interface HandwritingRecognizer {
    /** BCP-47 language tags this recognizer can read, e.g. "en-US". */
    val languages: Set<String>

    /** Ranked candidates, best first; empty when nothing was recognized. */
    suspend fun recognize(strokes: List<InkStrokeSamples>, languageTag: String, hints: InkHints = InkHints()): List<TextCandidate>
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

/**
 * Full-text search over everything written or typed in the library (REC-03). Results are pages, with the
 * boxes of what matched so the editor can highlight it.
 */
interface PageSearch {
    /** Pages matching every word of [query], best first. Blank queries match nothing. */
    suspend fun search(query: String, limit: Int = 40): List<PageSearchHit>

    /** The boxes on one page ([pageId] of [notebookId]) that match [query], for highlighting after opening it. */
    suspend fun highlights(notebookId: NotebookId, pageId: String, query: String): List<PageRect>
}

/** Download state of the on-device handwriting models (REC-01). */
enum class ModelStatus { NotDownloaded, Downloading, Ready, Failed }

/** [ready] are the BCP-47 tags of the models on the device; [status] describes the download of the missing ones. */
data class ModelsState(val status: ModelStatus = ModelStatus.NotDownloaded, val ready: Set<String> = emptySet()) {
    val isReady: Boolean get() = status == ModelStatus.Ready
}

/** The on-device recognition models, which are downloaded once and then work offline. */
interface HandwritingModels {
    /** The languages the app reads, e.g. "ar" and "en-US". */
    val languages: List<String>
    val state: StateFlow<ModelsState>

    /** Starts downloading the missing models (no-op while running or when all are present). */
    fun download()
}

/** Recognizes nothing; the default until a real provider is installed. */
object NoOpHandwritingRecognizer : HandwritingRecognizer {
    override val languages: Set<String> = emptySet()
    override suspend fun recognize(strokes: List<InkStrokeSamples>, languageTag: String, hints: InkHints) = emptyList<TextCandidate>()
}

object NoOpShapeRecognizer : ShapeRecognizer {
    override suspend fun recognize(stroke: InkStrokeSamples): ShapeMatch? = null
}

object NoOpPageSearch : PageSearch {
    override suspend fun search(query: String, limit: Int) = emptyList<PageSearchHit>()
    override suspend fun highlights(notebookId: NotebookId, pageId: String, query: String) = emptyList<PageRect>()
}

object NoOpHandwritingModels : HandwritingModels {
    override val languages: List<String> = emptyList()
    override val state: StateFlow<ModelsState> = MutableStateFlow(ModelsState())
    override fun download() = Unit
}
