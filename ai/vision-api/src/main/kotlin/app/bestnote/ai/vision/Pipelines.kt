package app.bestnote.ai.vision

import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.RectPt
import app.bestnote.engine.layout.BlockFlow
import app.bestnote.engine.layout.BlockMeasurer
import app.bestnote.engine.layout.FlowItem
import app.bestnote.engine.layout.LectureFlow
import app.bestnote.engine.layout.Placement

/** Pages of blocks ready to insert; index 0 is the first new page. */
data class Reconstruction(val pages: List<List<PageBlock>>, val dropped: Int)

/**
 * Smart Board: photo → expert-student transcription → validated blocks → logical lecture order
 * → template layout.
 */
class BoardReconstructor(
    private val provider: VisionProvider,
    private val measurer: BlockMeasurer,
    private val newId: () -> String,
) {
    suspend fun run(image: VisionImage, request: BoardRequest, size: PageSize): Reconstruction {
        val mapper = DocumentMapper(newId)
        val mapped = mapper.map(provider.reconstructBoard(image, request))
        mapped.requireBlocks()

        val byKey = mapped.blocks.associateBy { it.item.key }
        val ordered = LectureFlow.order(mapped.blocks.map { it.item }, request.rtl).map { byKey.getValue(it.key) }

        val placements = BlockFlow(request.template, size, measurer, rtl = request.rtl).place(ordered.map { it.item })
        val placed = mapper.realise(ordered, placements)
        val pages = placed.groupBy({ it.first }, { it.second }).toSortedMap().values.toList()
        return Reconstruction(pages, mapped.dropped)
    }
}

/**
 * Beautifier: lasso selection (rasterised by the canvas) → clean blocks stacked inside the
 * selection's bounds on the page. The editor commits them as one undo step.
 */
class NoteBeautifier(
    private val provider: VisionProvider,
    private val measurer: BlockMeasurer,
    private val newId: () -> String,
) {
    suspend fun run(selection: VisionImage, bounds: RectPt, spacing: Float = 8f): List<PageBlock> {
        val mapper = DocumentMapper(newId)
        val mapped = mapper.map(provider.beautify(selection, bounds.width, bounds.height))
        mapped.requireBlocks()
        val ordered = LectureFlow.order(mapped.blocks.map { it.item }).let { items ->
            val byKey = mapped.blocks.associateBy { it.item.key }
            items.map { byKey.getValue(it.key) }
        }
        var y = bounds.top
        val placements = ordered.map { p ->
            val h = measurer.heightFor(p.item, bounds.width)
            Placement(p.item.key, 0, RectPt(bounds.left, y, bounds.right, y + h)).also { y += h + spacing }
        }
        return mapper.realise(ordered, placements).map { it.second }
    }
}

/**
 * Records [DocumentMapper.Result.dropReasons] in [VisionDiagnostics] and fails with them when no
 * block survived, so "nothing could be read" always comes with the reasons.
 */
fun DocumentMapper.Result.requireBlocks() {
    VisionDiagnostics.recordOutcome(dropReasons = dropReasons)
    if (blocks.isEmpty()) {
        val why = if (dropReasons.isEmpty()) "the model returned no blocks" else dropReasons.take(5).joinToString("; ")
        throw VisionError.BadResponse("no usable blocks ($why)")
    }
}

/** Fixed-height fallback measurer for tests and previews; the canvas supplies a real one. */
object RoughMeasurer : BlockMeasurer {
    override fun heightFor(item: FlowItem, width: Float) = 48f
}

/** A [VisionProvider.transcribe] answer as the lines to type, in reading order. */
/**
 * The LaTeX of a [VisionProvider.readEquation] answer: the math blocks in order, several joined as lines
 * (`\\`); a block with only `text` counts too (some models put the LaTeX there). Empty = no maths seen.
 */
fun AiDocument.equationLatex(): String {
    val ordered = blocks.withIndex().sortedWith(compareBy({ it.value.order ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    val parts = ordered.mapNotNull { b ->
        b.latex?.takeIf { it.isNotBlank() }
            ?: b.steps?.mapNotNull { s -> s.latex?.takeIf(String::isNotBlank) }?.takeIf { it.isNotEmpty() }?.joinToString(" \\\\ ")
            ?: b.text?.takeIf { it.isNotBlank() }
    }.map { it.trim() }
    return parts.joinToString(" \\\\ ")
}

fun AiDocument.transcriptLines(): List<String> {
    val ordered = blocks.withIndex().sortedWith(compareBy({ it.value.order ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    return ordered.flatMap { b ->
        val words = b.text?.takeIf { it.isNotBlank() }
            ?: b.items?.joinToString("\n")
            ?: b.latex
            ?: b.steps?.joinToString("\n") { it.text?.takeIf(String::isNotBlank) ?: it.latex.orEmpty() }
            ?: b.title
        words.orEmpty().lines()
    }.map { it.trim() }.filter { it.isNotEmpty() }
}
