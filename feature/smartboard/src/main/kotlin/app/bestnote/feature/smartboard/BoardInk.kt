package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.DocumentMapper
import app.bestnote.ai.vision.requireBlocks
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.RectPt
import app.bestnote.engine.handwriting.HandwritingStyle
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
        val rtl = rtlHint || mostlyArabic(doc)
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

    private fun mostlyArabic(doc: AiDocument): Boolean {
        val text = buildString {
            doc.title?.let(::append)
            doc.blocks.forEach { b -> b.text?.let(::append); b.items?.forEach(::append) }
        }
        val arabic = text.count { it in '؀'..'ۿ' }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return arabic > latin
    }

    private companion object {
        val EMPTY = RectPt(0f, 0f, 0f, 0f)
    }
}
