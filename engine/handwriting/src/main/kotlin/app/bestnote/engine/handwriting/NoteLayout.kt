package app.bestnote.engine.handwriting

import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.BlockMeasurer
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
        val margins = doc.elements.indices.filter { i ->
            val b = doc.elements[i]
            b.element == NoteElement.MarginRemark && b.target.let { it != null && it in doc.elements.indices && it != i }
        }.toSet()
        val mainWidth = RuledFlow(paper, composer.measurer { null }, rtl).columns(margins.isNotEmpty()).let { it.mainRight - it.mainLeft }
        // A block taller than a page goes on in pieces of whole rows, page after page (never off the page's foot).
        val pieces = doc.elements.withIndex().flatMap { (i, b) ->
            val chunks = if (i in margins) listOf(null) else pageChunks(composer.rows(b, mainWidth), paper.lineCount)
            chunks.mapIndexed { k, rows -> Chunk(if (k == 0) "e$i" else "e$i~$k", i, b, rows) }
        }
        val byKey = pieces.associateBy { it.key }
        val items = pieces.mapIndexed { n, c ->
            val margin = c.index in margins
            FlowItem(
                key = c.key,
                role = if (margin) RegionRole.Margin else RegionRole.Main,
                keepWithNext = c.block.element in KEEP_WITH_NEXT,
                anchorKey = if (margin) "e${c.block.target}" else null,
                order = n,
            )
        }
        val measurer = BlockMeasurer { item, width ->
            val c = byKey[item.key] ?: return@BlockMeasurer paper.lineGap
            val range = c.rows ?: return@BlockMeasurer composer.linesFor(c.block, width) * paper.lineGap
            composer.rows(c.block, width).subList(range.first, range.last + 1).sumOf { it.lines }.coerceAtLeast(1) * paper.lineGap
        }
        // Lecture order is the model's order; margin remarks move to just after their target.
        val ordered = LectureFlow.order(items, rtl)
        val flow = RuledFlow(paper, measurer, rtl, spaceBefore = { item ->
            when (byKey[item.key]?.takeIf { it.rows == null || it.rows.first == 0 }?.block?.element) {
                NoteElement.Heading -> template.look.spaceBeforeHeading
                NoteElement.Diagram, NoteElement.Columns -> 1
                else -> 0
            }
        }, reserved = reserved, clearance = { prev, next, width -> composer.clearance(byKey.getValue(prev.key).block, byKey.getValue(next.key).block, width) })
        val slots = flow.place(ordered)
        val placed = slots.map { s ->
            val key = s.placement.key
            val c = byKey.getValue(key)
            s.placement.pageIndex to NoteComposer.Placed(key, c.block, s.placement.frame, ordered.first { it.key == key }.anchorKey, rows = c.rows)
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
        // A column holds at least MIN_COLUMN_EMS body letters' heights across, or the page gets one column.
        val flow = PackedFlow(paper, rtl, look.density, onSheet, minColumnWidth = MIN_COLUMN_EMS * paper.lineGap * style.bodyScale)
        val result = flow.place(blocks)
        val byKey = keyed.toMap()
        val placed = result.slots.map { s ->
            s.page to NoteComposer.Placed(s.key, byKey.getValue(s.key), s.frame, rows = s.rowFrom until s.rowTo)
        }
        return NotePages(paper, template, composer.compose(placed, result.rules), placed, rtl, tidy)
    }

    /** One piece of an element in the flow layout: all of it ([rows] null) or rows [rows] of a block taller than a page. */
    private class Chunk(val key: String, val index: Int, val block: NoteBlock, val rows: IntRange?)

    /** [rows] in pieces of at most [lines] lines, never between rows glued under one pen box; one piece (null) when they fit. */
    private fun pageChunks(rows: List<FontComposer.Row>, lines: Int): List<IntRange?> {
        if (rows.sumOf { it.lines } <= lines) return listOf(null)
        val out = ArrayList<IntRange?>()
        var from = 0
        var used = 0
        var cut = -1 // the last row after which a page may end
        for ((i, r) in rows.withIndex()) {
            if (used + r.lines > lines && cut >= from) {
                out += from..cut
                used = rows.subList(cut + 1, i).sumOf { it.lines }
                from = cut + 1
            }
            used += r.lines
            if (!r.glue) cut = i
        }
        out += from..rows.lastIndex
        return out
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

    /** Narrowest packed column, in body text sizes (≈ 20 letters: shorter lines break most words). */
    private const val MIN_COLUMN_EMS = 11f

    private val KEEP_WITH_NEXT = setOf(NoteElement.Title, NoteElement.Heading, NoteElement.Definition)
}
