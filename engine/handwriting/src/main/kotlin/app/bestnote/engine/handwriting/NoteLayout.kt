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
