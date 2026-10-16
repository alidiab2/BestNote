package app.bestnote.feature.editor

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.DocumentMapper
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.RectPt
import app.bestnote.engine.handwriting.FontComposer
import app.bestnote.engine.handwriting.TextMeasure
import app.bestnote.engine.layout.LectureFlow
import app.bestnote.engine.layout.RuledFlow
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.engine.layout.StyleTemplates
import app.bestnote.engine.layout.WritingScript
import kotlin.math.ceil
import kotlin.math.max

/**
 * AI-08 "Tidy": writes the tidied content of a lasso selection back in place, neatly, with the
 * handwriting fonts (Smart Board's [FontComposer]): text starts on the page's own ruled line at the
 * top of the selection, keeps the selection's left and right edges (wider when it is very narrow),
 * is written in the selection's ink colour, and runs right to left for Hebrew and Arabic.
 */
internal object TidyWriter {
    /** Text boxes plus pen strokes (underlines, boxes, fraction bars, drawings). */
    class Written(val elements: List<PageElement>, val strokes: List<InkStroke>)

    /** The user's own colour for everything; structure stays visible through size and decoration. */
    private val TIDY: StyleTemplate = StyleTemplates.Classic.copy(
        id = "tidy",
        roles = StyleTemplates.Classic.roles.mapValues { (_, r) -> r.copy(color = null) },
        marginArrows = true,
    )

    fun write(
        doc: AiDocument,
        box: PageRect,
        template: PaperTemplate,
        page: PageSize,
        colorArgb: Int,
        measure: TextMeasure,
        newId: () -> String,
    ): Written {
        val mapper = DocumentMapper(newId)
        val mapped = mapper.map(doc)
        if (mapped.blocks.isEmpty()) return Written(emptyList(), emptyList())
        val rtl = WritingScript.mostlyRtl(textOf(doc))
        val sheet = RuledPaper.of(template.takeIf { it in RuledPaper.writable } ?: PaperTemplate.Ruled, page)
        val gap = sheet.lineGap
        // First writing line: the page's ruled line whose band holds the top of the selection.
        val first = ceil((box.top + 0.7f * gap - sheet.firstLine) / gap).toInt().coerceAtLeast(0)
        val minWidth = 6 * gap
        var left = box.left
        var right = box.right
        if (right - left < minWidth) {
            if (rtl) left = right - minWidth else right = left + minWidth
        }
        left = left.coerceIn(0f, max(0f, page.widthPt - minWidth))
        right = right.coerceIn(left + minWidth, max(page.widthPt, left + minWidth))
        val width = max(page.widthPt, right + gap)
        // A sheet as tall as needed: tidied notes that need more lines than the selection keep going down.
        val paper = RuledPaper(
            width = width,
            height = page.heightPt * 4,
            firstLine = sheet.firstLine + first * gap,
            lineGap = gap,
            marginX = left - gap * 0.35f,
            edge = width - right,
            template = sheet.template,
        )
        val byKey = mapped.blocks.associateBy { it.item.key }
        val ordered = LectureFlow.order(mapped.blocks.map { it.item }, rtl).map { byKey.getValue(it.key) }
        val drafts = ordered.associate { it.item.key to it.build(RectPt(0f, 0f, 0f, 0f)) }
        val composer = FontComposer(paper, TIDY, measure, rtl, colorArgb)
        val flow = RuledFlow(paper, composer.measurer { drafts[it] }, rtl) { item -> if (drafts[item.key] is PageBlock.Heading) 1 else 0 }
        val placed = mapper.realise(ordered, flow.place(ordered.map { it.item }).map { it.placement })
        val pages = composer.compose(placed).pages
        return Written(pages.flatMap { TypesetElements.elements(it, measure, newId) }, pages.flatMap { it.ink })
    }

    private fun textOf(doc: AiDocument) = buildString {
        doc.title?.let(::append)
        doc.blocks.forEach { b -> b.text?.let(::append); b.items?.forEach(::append) }
    }
}
