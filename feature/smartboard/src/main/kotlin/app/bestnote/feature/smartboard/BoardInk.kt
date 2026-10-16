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
import app.bestnote.engine.handwriting.StrokeHand
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
        return BoardInk(paper, composer.compose(placed), placed, mapped.dropped, rtl, mapped.dropReasons + unknownLatex(doc))
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
        return BoardText(paper, style, composer.compose(placed), mapped.dropped, rtl, mapped.dropReasons + unknownLatex(doc))
    }

    /** Right to left when most letters are Hebrew or Arabic (any mix of scripts, not just Arabic vs Latin). */
    private fun mostlyRtl(doc: AiDocument): Boolean = WritingScript.mostlyRtl(textOf(doc))

    internal companion object {
        val EMPTY = RectPt(0f, 0f, 0f, 0f)

        fun textOf(doc: AiDocument): String = buildString {
            doc.title?.let(::append)
            doc.blocks.forEach { b -> b.text?.let(::append); b.items?.forEach(::append); b.title?.let(::append) }
        }

        /** Every piece of prose and every formula of [doc]. */
        private fun pieces(doc: AiDocument): Pair<List<String>, List<String>> {
            val prose = ArrayList<String>()
            val tex = ArrayList<String>()
            doc.title?.let(prose::add)
            for (b in doc.blocks) {
                listOfNotNull(b.text, b.title).forEach(prose::add)
                b.items?.let(prose::addAll)
                b.latex?.let(tex::add)
                b.steps?.forEach { s -> listOfNotNull(s.text, s.because).forEach(prose::add); s.latex?.let(tex::add) }
                b.diagram?.shapes?.forEach { sh -> sh.text?.let { if (sh.latex == true) tex += it else prose += it } }
            }
            return prose to tex
        }

        /**
         * The stroke hand has Latin, Arabic and a set of maths symbols; a page with anything else
         * (Hebrew, Cyrillic, a rare symbol) is written with the fonts, so nothing comes out as "?".
         */
        fun strokesCanWrite(doc: AiDocument): Boolean {
            val (prose, tex) = pieces(doc)
            return prose.all(StrokeHand::canWriteProse) && tex.all(StrokeHand::canWriteLatex)
        }

        /** One diagnostics line per LaTeX command written as its bare name ("\\dotsm" → "dotsm"). */
        fun unknownLatex(doc: AiDocument): List<String> {
            val (prose, tex) = pieces(doc)
            val names = LinkedHashSet<String>()
            prose.forEach { names += StrokeHand.unknownCommandsInProse(it) }
            tex.forEach { names += StrokeHand.unknownCommands(it) }
            return names.map { "unknown LaTeX command \\$it written as \"$it\"" }
        }
    }
}
