package app.bestnote.core.model

@JvmInline
value class BlockId(val value: String)

/**
 * What the lecturer *meant* by an ink colour, independent of the colour itself. Board colours are
 * read into a semantic and rendered with the notebook palette, so a red marker and red chalk both
 * become the same "essential" accent.
 */
enum class InkSemantic {
    /** Ordinary explanation (black/blue marker, white chalk). */
    Regular,

    /** Must-know formula or result (usually red). Rendered boxed / accented. */
    Essential,

    /** Side remark or annotation (usually green). Rendered as a margin note. */
    Annotation,

    /** Highlight for structure: titles, underlined keywords (yellow chalk, purple). */
    Emphasis,
}

/** Typeset content placed on a page; ink lives beside blocks, not inside them. */
sealed interface PageBlock {
    val id: BlockId
    val frame: RectPt
    val semantic: InkSemantic get() = InkSemantic.Regular

    data class Heading(
        override val id: BlockId, override val frame: RectPt, val level: Int, val text: String,
        override val semantic: InkSemantic = InkSemantic.Emphasis,
    ) : PageBlock

    data class Paragraph(
        override val id: BlockId, override val frame: RectPt, val text: String,
        override val semantic: InkSemantic = InkSemantic.Regular,
    ) : PageBlock

    data class Bullets(
        override val id: BlockId, override val frame: RectPt, val items: List<String>, val ordered: Boolean = false,
        override val semantic: InkSemantic = InkSemantic.Regular,
    ) : PageBlock

    data class Callout(
        override val id: BlockId, override val frame: RectPt, val tone: CalloutTone, val title: String?, val body: String,
        override val semantic: InkSemantic = InkSemantic.Regular,
    ) : PageBlock

    /** A display formula. [boxed] draws it in a frame, the way a student boxes a key result. */
    data class Math(
        override val id: BlockId, override val frame: RectPt, val latex: String, val display: Boolean = true,
        val boxed: Boolean = false,
        override val semantic: InkSemantic = InkSemantic.Regular,
    ) : PageBlock

    /** A derivation or proof kept visually separate from the explanation around it. */
    data class Proof(
        override val id: BlockId, override val frame: RectPt, val title: String?, val steps: List<ProofStep>,
        override val semantic: InkSemantic = InkSemantic.Regular,
    ) : PageBlock

    /** A side remark placed in the margin next to the block it comments on ([anchor]). */
    data class MarginNote(
        override val id: BlockId, override val frame: RectPt, val text: String, val anchor: BlockId?,
        override val semantic: InkSemantic = InkSemantic.Annotation,
    ) : PageBlock

    data class Diagram(override val id: BlockId, override val frame: RectPt, val diagram: VectorDiagram) : PageBlock
}

/** One line of a proof: a formula and/or words, with an optional reason ("by Pythagoras"). */
data class ProofStep(val latex: String?, val text: String?, val justification: String?)

enum class CalloutTone { Info, Definition, Theorem, KeyResult, Warning, Example, Summary }

/**
 * Resolution-independent drawing in its own unit box (viewBox), scaled into the block frame at
 * render time. This is what makes reconstructed diagrams crisp at any zoom.
 */
data class VectorDiagram(val viewBox: RectPt, val shapes: List<VectorShape>)

data class StrokeStyle(val colorArgb: Long = 0xFF1F1F1FL, val width: Float = 1.5f, val dashed: Boolean = false)

sealed interface VectorShape {
    val style: StrokeStyle

    data class Line(val from: Vec2, val to: Vec2, override val style: StrokeStyle = StrokeStyle()) : VectorShape

    data class Arrow(val from: Vec2, val to: Vec2, val headSize: Float = 8f, override val style: StrokeStyle = StrokeStyle()) : VectorShape

    data class Rect(val rect: RectPt, val rotationDeg: Float = 0f, val fillArgb: Long? = null, override val style: StrokeStyle = StrokeStyle()) : VectorShape

    data class Ellipse(val bounds: RectPt, override val style: StrokeStyle = StrokeStyle()) : VectorShape

    data class Polyline(val points: List<Vec2>, val closed: Boolean = false, override val style: StrokeStyle = StrokeStyle()) : VectorShape

    /** SVG path data (M/L/C/Q/Z subset) for curves the primitives cannot express. */
    data class Path(val d: String, override val style: StrokeStyle = StrokeStyle()) : VectorShape

    data class Label(val at: Vec2, val text: String, val latex: Boolean = false, override val style: StrokeStyle = StrokeStyle()) : VectorShape
}
