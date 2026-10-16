package app.bestnote.engine.handwriting

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.FlowItem
import app.bestnote.engine.layout.LectureFlow
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteNormalizer
import app.bestnote.engine.layout.NoteSamples
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.PackedFlow
import app.bestnote.engine.layout.PackedNotes
import app.bestnote.engine.layout.PageLayout
import app.bestnote.engine.layout.RegionRole
import app.bestnote.engine.layout.RuledFlow
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.WritingScript

/** A [NoteDocument] written out: pages of text runs + pen strokes, and where each element went. */
class NotePages(
    val paper: RuledPaper,
    val template: NoteTemplate,
    val pages: TypesetPages,
    val placed: List<Pair<Int, NoteComposer.Placed>>,
    val rtl: Boolean,
    val doc: NoteDocument,
)

/**
 * Templates first: the AI's [NoteDocument] (already normalised by [NoteNormalizer]) → ruled-line
 * layout ([RuledFlow]: whole lines, margin column for remarks, keep-with-next) → the element
 * library ([NoteComposer]) in the template's look. Deterministic and fast, so changing only the look
 * re-renders a cached document without a new AI call.
 */
object NoteLayout {
    /**
     * @param reserved lines the user already wrote on (page index relative to the first page, line index):
     *   blocks never cover them (follow-up photos on a page that has the user's own ink).
     */
    fun typeset(
        doc: NoteDocument,
        template: NoteTemplate,
        paper: RuledPaper,
        measure: TextMeasure,
        rtlHint: Boolean = false,
        penColor: Int = FontComposer.INK,
        reserved: (page: Int, line: Int) -> Boolean = { _, _ -> false },
    ): NotePages {
        val rtl = rtlHint || isRtl(doc)
        if (template.look.layout == PageLayout.Packed && paper.template != PaperTemplate.Cornell) return packed(doc, template, paper, measure, rtl, penColor, reserved)
        val composer = NoteComposer(paper, template.look, measure, rtl, penColor)
        val keyed = doc.elements.mapIndexed { i, b -> "e$i" to b }
        val byKey = keyed.toMap()
        val items = keyed.mapIndexed { i, (key, b) ->
            val margin = b.element == NoteElement.MarginRemark && b.target.let { it != null && it in doc.elements.indices && it != i }
            FlowItem(
                key = key,
                role = if (margin) RegionRole.Margin else RegionRole.Main,
                keepWithNext = b.element in KEEP_WITH_NEXT,
                anchorKey = if (margin) "e${b.target}" else null,
                order = i,
            )
        }
        // Lecture order is the model's order; margin remarks move to just after their target.
        val ordered = LectureFlow.order(items, rtl)
        val flow = RuledFlow(paper, composer.measurer { byKey[it] }, rtl, spaceBefore = { item ->
            when (byKey[item.key]?.element) {
                NoteElement.Heading -> template.look.spaceBeforeHeading
                NoteElement.Diagram, NoteElement.Columns -> 1
                else -> 0
            }
        }, reserved = reserved)
        val slots = flow.place(ordered)
        val placed = slots.map { s ->
            val key = s.placement.key
            val b = byKey.getValue(key)
            s.placement.pageIndex to NoteComposer.Placed(key, b, s.placement.frame, ordered.first { it.key == key }.anchorKey)
        }
        return NotePages(paper, template, composer.compose(placed), placed, rtl, doc)
    }

    /**
     * The packed notebook page ([PageLayout.Packed], [PackedFlow]): topics in side-by-side columns
     * with dashed dividers, compact maths, short equations sharing a line; margin remarks become ★
     * notes in place. On grid and dot paper (Normal/Dense) every grid line is a writing line.
     */
    private fun packed(
        doc: NoteDocument,
        template: NoteTemplate,
        sheet: RuledPaper,
        measure: TextMeasure,
        rtl: Boolean,
        penColor: Int,
        reserved: (page: Int, line: Int) -> Boolean,
    ): NotePages {
        val look = template.look
        val paper = PackedFlow.paperFor(sheet, look.density)
        val style = look.copy(bodyScale = look.bodyScale * PackedFlow.bodyFactor(sheet, paper, look.density))
        val composer = NoteComposer(paper, style, measure, rtl, penColor)
        composer.compact(look.density, fine = paper.lineGap < sheet.lineGap)
        // The app packs the columns itself: a Columns element's contents join the flow (first column,
        // then the second), margin remarks become ★ notes in place.
        val tidy = PackedNotes.tidy(doc).let { d ->
            d.copy(
                elements = d.elements.flatMap { if (it.element == NoteElement.Columns) it.columns.flatten() else listOf(it) }
                    .map { if (it.element == NoteElement.MarginRemark) it.copy(element = NoteElement.StarNote, target = null) else it },
            )
        }
        val keyed = tidy.elements.mapIndexed { i, b -> "e$i" to b }
        val blocks = keyed.mapIndexed { i, (key, b) ->
            PackedFlow.PackBlock(
                key = key,
                rows = { w -> composer.rowShape(b, w) },
                topic = b.element == NoteElement.Heading,
                keepWithNext = b.element == NoteElement.Title || b.element == NoteElement.Heading,
                // A heading right before a full-width element goes across with it.
                span = spans(b) || (b.element == NoteElement.Heading && keyed.getOrNull(i + 1)?.second?.let(::spans) == true),
                keepWhole = b.element in KEEP_WHOLE,
            )
        }
        // The user's own lines, in this paper's line numbers (a grid line pitch may be half the sheet's).
        val k = sheet.lineGap / paper.lineGap
        val onSheet: (Int, Int) -> Boolean = if (k == 1f) reserved else { page, line ->
            val y = paper.lineY(line)
            reserved(page, kotlin.math.ceil((y - sheet.firstLine) / sheet.lineGap - 0.01f).toInt())
        }
        val flow = PackedFlow(paper, rtl, look.density, onSheet)
        val result = flow.place(blocks)
        val byKey = keyed.toMap()
        val placed = result.slots.map { s ->
            s.page to NoteComposer.Placed(s.key, byKey.getValue(s.key), s.frame, rows = s.rowFrom until s.rowTo)
        }
        return NotePages(paper, template, composer.compose(placed, result.rules), placed, rtl, tidy)
    }

    /** Runs across all columns: the title, a table of 3+ columns. */
    private fun spans(b: NoteBlock): Boolean = when (b.element) {
        NoteElement.Title -> true
        NoteElement.Table, NoteElement.Comparison -> (b.rows.maxOfOrNull { it.size } ?: 0) >= 3
        else -> false
    }

    private val KEEP_WHOLE = setOf(
        NoteElement.Formula, NoteElement.KeyResult, NoteElement.NumberedFormula, NoteElement.Definition, NoteElement.Theorem,
        NoteElement.Summary, NoteElement.Note, NoteElement.Warning, NoteElement.StarNote, NoteElement.Diagram, NoteElement.Derivation,
    )

    /**
     * The first page of [sampleDoc] written with [template] on its own paper (or [fallbackPaper] at
     * [size] when the look follows the notebook): what the template picker and the template editor show.
     */
    fun previewPage(
        template: NoteTemplate,
        measure: TextMeasure,
        sampleDoc: NoteDocument = NoteSamples.forTemplate(template),
        size: PageSize = PageSize(595f, 842f),
        fallbackPaper: PaperTemplate = PaperTemplate.Ruled,
    ): Pair<RuledPaper, TypesetPage> {
        val paper = RuledPaper.of(template.look.paper ?: fallbackPaper, size)
        val doc = NoteNormalizer.normalize(sampleDoc, template)
        val pages = typeset(doc, template, paper, measure)
        return paper to (pages.pages.pages.firstOrNull() ?: TypesetPage(emptyList(), emptyList()))
    }

    /** Right to left when most of the notes' letters are Hebrew or Arabic. */
    fun isRtl(doc: NoteDocument): Boolean = WritingScript.mostlyRtl(InlineMath.flatten(doc.flatten().joinToString(" ") { it.words() }))

    private val KEEP_WITH_NEXT = setOf(NoteElement.Title, NoteElement.Heading, NoteElement.Definition)
}
