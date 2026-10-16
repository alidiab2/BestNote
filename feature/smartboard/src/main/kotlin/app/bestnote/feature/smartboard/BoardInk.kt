package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.DocumentMapper
import app.bestnote.ai.vision.requireBlocks
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.RectPt
import app.bestnote.engine.handwriting.FontComposer
import app.bestnote.engine.handwriting.HandwritingStyle
import app.bestnote.engine.handwriting.TextMeasure
import app.bestnote.engine.handwriting.TypesetPages
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.engine.layout.WritingScript
import app.bestnote.engine.handwriting.HandwrittenPages
import app.bestnote.engine.handwriting.PageComposer
import app.bestnote.engine.layout.LectureFlow
import app.bestnote.engine.layout.RuledFlow
import app.bestnote.engine.layout.RuledPaper

/** A board written out by hand: the pages of ink, the blocks they came from, and what was dropped. */
class BoardInk(
    val paper: RuledPaper,
    val pages: HandwrittenPages,
    val blocks: List<Pair<Int, PageBlock>>,
    val dropped: Int,
    val rtl: Boolean,
    /** One content-free line per dropped block, for diagnostics. */
    val dropReasons: List<String> = emptyList(),
)

/** A board typed with the handwriting fonts in a [style]: pages of text runs + pen strokes. */
class BoardText(
    val paper: RuledPaper,
    val style: StyleTemplate,
    val pages: TypesetPages,
    val dropped: Int,
    val rtl: Boolean,
    val dropReasons: List<String> = emptyList(),
)

/**
 * Smart Board, photo to handwriting: provider (expert-student prompt) → [DocumentMapper] (validate,
 * palette, LaTeX, ruler geometry) → [LectureFlow] (teaching order) → [RuledFlow] (whole ruled
 * lines, margin column, keep-with-next) measured by the [PageComposer] → handwritten strokes.
 */
class BoardToInk(private val newId: () -> String) {
    suspend fun run(provider: VisionProvider, image: VisionImage, request: BoardRequest, paper: RuledPaper, style: HandwritingStyle = HandwritingStyle()): BoardInk =
        write(provider.reconstructBoard(image, request), request.rtl, paper, style)

    /** The deterministic half (no provider), also used for tests and the offline demo. */
    fun write(doc: AiDocument, rtlHint: Boolean, paper: RuledPaper, style: HandwritingStyle = HandwritingStyle()): BoardInk {
        val mapper = DocumentMapper(newId)
        val mapped = mapper.map(doc)
        mapped.requireBlocks()
        val rtl = rtlHint || mostlyRtl(doc)
        val byKey = mapped.blocks.associateBy { it.item.key }
        val ordered = LectureFlow.order(mapped.blocks.map { it.item }, rtl).map { byKey.getValue(it.key) }
        // Built once with an empty frame, only to measure and to know each block's kind.
        val drafts = ordered.associate { it.item.key to it.build(EMPTY) }
        val composer = PageComposer(paper, style, rtl)
        val flow = RuledFlow(paper, composer.measurer { drafts[it] }, rtl) { item ->
            when (drafts[item.key]) {
                is PageBlock.Heading, is PageBlock.Diagram -> 1
                else -> 0
            }
        }
        val placed = mapper.realise(ordered, flow.place(ordered.map { it.item }).map { it.placement })
        return BoardInk(paper, composer.compose(placed), placed, mapped.dropped, rtl, mapped.dropReasons)
    }

    /** The default output: the same notes typed with the handwriting fonts, in [style]'s colours, sizes and decorations. */
    fun typeset(doc: AiDocument, rtlHint: Boolean, paper: RuledPaper, style: StyleTemplate, measure: TextMeasure, penColor: Int = FontComposer.INK): BoardText {
        val mapper = DocumentMapper(newId)
        val mapped = mapper.map(doc)
        mapped.requireBlocks()
        val rtl = rtlHint || mostlyRtl(doc)
        val byKey = mapped.blocks.associateBy { it.item.key }
        val ordered = LectureFlow.order(mapped.blocks.map { it.item }, rtl).map { byKey.getValue(it.key) }
        val drafts = ordered.associate { it.item.key to it.build(EMPTY) }
        val composer = FontComposer(paper, style, measure, rtl, penColor)
        val flow = RuledFlow(paper, composer.measurer { drafts[it] }, rtl) { item ->
            when (drafts[item.key]) {
                is PageBlock.Heading -> style.spaceBeforeHeading
                is PageBlock.Diagram -> 1
                else -> 0
            }
        }
        val placed = mapper.realise(ordered, flow.place(ordered.map { it.item }).map { it.placement })
        return BoardText(paper, style, composer.compose(placed), mapped.dropped, rtl, mapped.dropReasons)
    }

    /** Right to left when most letters are Hebrew or Arabic (any mix of scripts, not just Arabic vs Latin). */
    private fun mostlyRtl(doc: AiDocument): Boolean = WritingScript.mostlyRtl(textOf(doc))

    internal companion object {
        val EMPTY = RectPt(0f, 0f, 0f, 0f)

        fun textOf(doc: AiDocument): String = buildString {
            doc.title?.let(::append)
            doc.blocks.forEach { b -> b.text?.let(::append); b.items?.forEach(::append); b.title?.let(::append) }
        }

        /** The stroke hand has Latin and Arabic letters only; anything else must be written with fonts. */
        fun strokesCanWrite(doc: AiDocument): Boolean = textOf(doc).none { c ->
            val sc = WritingScript.of(c)
            sc != null && sc != WritingScript.Latin && sc != WritingScript.Arabic
        }
    }
}
