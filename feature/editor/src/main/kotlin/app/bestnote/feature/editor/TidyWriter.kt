package app.bestnote.feature.editor

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.DocumentMapper
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.PageRect
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.model.RectPt
import app.bestnote.engine.handwriting.HandwritingStyle
import app.bestnote.engine.handwriting.PageComposer
import app.bestnote.engine.handwriting.PenChoice
import app.bestnote.engine.layout.LectureFlow
import app.bestnote.engine.layout.RuledFlow
import app.bestnote.engine.layout.RuledPaper
import kotlin.math.ceil
import kotlin.math.max

/**
 * AI-08 "Tidy": writes the tidied content of a lasso selection back in place as neat handwriting.
 * The writing starts on the page's own ruled line at the top of the selection, keeps the
 * selection's left and right edges (wider when the selection is very narrow), uses the selection's
 * ink colour for body text, and runs right to left for Arabic. Same pipeline as Smart Board:
 * [DocumentMapper] → [LectureFlow] → [RuledFlow] measured by the [PageComposer].
 */
internal object TidyWriter {
    fun write(doc: AiDocument, box: PageRect, template: PaperTemplate, page: PageSize, colorArgb: Int, newId: () -> String): List<InkStroke> {
        val mapper = DocumentMapper(newId)
        val mapped = mapper.map(doc)
        if (mapped.blocks.isEmpty()) return emptyList()
        val rtl = mostlyArabic(doc)
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
        left = left.coerceIn(0f, page.widthPt - minWidth)
        right = right.coerceIn(left + minWidth, max(page.widthPt, left + minWidth))
        // A sheet as tall as needed: tidied notes that need more lines than the selection keep going down.
        val paper = RuledPaper(
            width = max(page.widthPt, right + gap),
            height = page.heightPt * 4,
            firstLine = sheet.firstLine + first * gap,
            lineGap = gap,
            marginX = left - gap * 0.35f,
            edge = max(page.widthPt, right + gap) - right,
            template = sheet.template,
        )
        val byKey = mapped.blocks.associateBy { it.item.key }
        val ordered = LectureFlow.order(mapped.blocks.map { it.item }, rtl).map { byKey.getValue(it.key) }
        val drafts = ordered.associate { it.item.key to it.build(RectPt(0f, 0f, 0f, 0f)) }
        val composer = PageComposer(paper, HandwritingStyle(pen = PenChoice(colorArgb = colorArgb)), rtl)
        val flow = RuledFlow(paper, composer.measurer { drafts[it] }, rtl) { item -> if (drafts[item.key] is PageBlock.Heading) 1 else 0 }
        val placed = mapper.realise(ordered, flow.place(ordered.map { it.item }).map { it.placement })
        return composer.compose(placed).pages.flatten().map { it.stroke }
    }

    private fun mostlyArabic(doc: AiDocument): Boolean {
        val text = buildString {
            doc.title?.let(::append)
            doc.blocks.forEach { b -> b.text?.let(::append); b.items?.forEach(::append) }
        }
        return text.count { it in '؀'..'ۿ' } > text.count { it in 'a'..'z' || it in 'A'..'Z' }
    }
}
