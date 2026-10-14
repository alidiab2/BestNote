package app.bestnote.ai.vision

import app.bestnote.ai.math.LatexResult
import app.bestnote.ai.math.LatexSanitizer
import app.bestnote.core.model.BlockId
import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.InkSemantic
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.ProofStep
import app.bestnote.core.model.RectPt
import app.bestnote.core.model.StrokeStyle
import app.bestnote.core.model.Vec2
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.ColorSemantics
import app.bestnote.engine.layout.FlowItem
import app.bestnote.engine.layout.LectureFlow
import app.bestnote.engine.layout.NotebookPalette
import app.bestnote.engine.layout.Placement
import app.bestnote.engine.layout.RegionRole
import app.bestnote.engine.layout.RulerGeometry

/** A validated block with no frame yet; the layout engine assigns one. */
data class PendingBlock(val item: FlowItem, val build: (frame: RectPt) -> PageBlock)

/**
 * Validates model output and turns it into page blocks the way the expert student would write
 * them: colours resolved to the notebook palette, LaTeX standardised, diagrams redrawn with
 * [RulerGeometry], margin notes tied to the block they comment on. Anything malformed is dropped
 * (and counted in [Result.dropped]) rather than failing the whole reconstruction.
 */
class DocumentMapper(
    private val newId: () -> String,
    private val ruler: RulerGeometry = RulerGeometry(),
) {
    data class Result(val blocks: List<PendingBlock>, val dropped: Int)

    fun map(doc: AiDocument): Result {
        var dropped = 0
        val out = ArrayList<PendingBlock>()
        doc.title?.takeIf { it.isNotBlank() }?.let { t ->
            out += pending(RegionRole.Title, order = LectureFlow.FIRST, keepWithNext = true) { id, f ->
                PageBlock.Heading(id, f, 1, t.trim())
            }
        }
        // Block ids are assigned up front so margin notes can point at blocks that come later.
        val ids = doc.blocks.map { BlockId(newId()) }
        val idByModelId = doc.blocks.indices.mapNotNull { i -> doc.blocks[i].id?.let { it to ids[i] } }.toMap()
        for ((i, b) in doc.blocks.withIndex()) {
            val block = mapBlock(b, ids[i], idByModelId)
            if (block == null) dropped++ else out += block
        }
        return Result(out, dropped)
    }

    /** Pair layout placements back with their pending blocks. */
    fun realise(pending: List<PendingBlock>, placements: List<Placement>): List<Pair<Int, PageBlock>> {
        val byKey = placements.associateBy { it.key }
        return pending.mapNotNull { p -> byKey[p.item.key]?.let { it.pageIndex to p.build(it.frame) } }
    }

    private fun mapBlock(b: AiBlock, id: BlockId, ids: Map<String, BlockId>): PendingBlock? {
        val role = roleOf(b.role)
        val semantic = ColorSemantics.resolve(b.semantic, b.inkColor)
        fun make(r: RegionRole = role, keep: Boolean = false, anchor: String? = null, build: (BlockId, RectPt) -> PageBlock) =
            PendingBlock(FlowItem(id.value, r, keep, anchor, b.order, sourceBox(b.sourceBox))) { f -> build(id, f) }

        return when (b.type.lowercase()) {
            "heading" -> b.text.clean()?.let { t ->
                val level = (b.level ?: 2).coerceIn(1, 3)
                val r = if (level == 1 && b.role == null) RegionRole.Title else role
                make(r, keep = true) { i, f -> PageBlock.Heading(i, f, level, t, semantic.orEmphasis()) }
            }
            "paragraph" -> b.text.clean()?.let { t -> make { i, f -> PageBlock.Paragraph(i, f, t, semantic) } }
            "bullets" -> b.items?.mapNotNull { it.clean() }?.takeIf { it.isNotEmpty() }
                ?.let { items -> make { i, f -> PageBlock.Bullets(i, f, items, semantic = semantic) } }
            // A definition stays with what follows it (usually its formula or example).
            "definition" -> b.text.clean()?.let { t ->
                make(keep = true) { i, f -> PageBlock.Callout(i, f, CalloutTone.Definition, b.title.clean(), t, semantic) }
            }
            "callout" -> b.text.clean()?.let { t ->
                val tone = toneOf(b.tone) ?: NotebookPalette.defaultTone(semantic)
                make { i, f -> PageBlock.Callout(i, f, tone, b.title.clean(), t, semantic) }
            }
            "math" -> latex(b.latex)?.let { tex ->
                val boxed = b.boxed ?: (semantic == InkSemantic.Essential)
                make { i, f -> PageBlock.Math(i, f, tex, boxed = boxed, semantic = semantic) }
            }
            "proof" -> b.steps?.mapNotNull(::step)?.takeIf { it.isNotEmpty() }?.let { steps ->
                make { i, f -> PageBlock.Proof(i, f, b.title.clean(), steps, semantic) }
            }
            "margin_note" -> b.text.clean()?.let { t ->
                val anchor = b.annotates?.let(ids::get)
                make(RegionRole.Margin, anchor = anchor?.value) { i, f ->
                    PageBlock.MarginNote(i, f, t, anchor, ColorSemantics.parseSemantic(b.semantic) ?: InkSemantic.Annotation)
                }
            }
            "diagram" -> b.diagram?.let(::mapDiagram)?.let { d -> make { i, f -> PageBlock.Diagram(i, f, d) } }
            else -> null
        }
    }

    private fun step(s: AiProofStep): ProofStep? {
        val tex = latex(s.latex)
        val text = s.text.clean()
        if (tex == null && text == null) return null
        return ProofStep(tex, text, s.because.clean())
    }

    internal fun mapDiagram(d: AiDiagram): VectorDiagram? {
        val w = d.size.getOrNull(0) ?: return null
        val h = d.size.getOrNull(1) ?: return null
        if (w <= 0f || h <= 0f) return null
        val box = RectPt(0f, 0f, w, h)
        val shapes = d.shapes.mapNotNull { mapShape(it, box) }
        return if (shapes.isEmpty()) null else ruler.straighten(VectorDiagram(box, shapes))
    }

    private fun mapShape(s: AiShape, box: RectPt): VectorShape? {
        val pts = s.points.chunked(2).filter { it.size == 2 && it.all(Float::isFinite) }
            .map { Vec2(it[0].coerceIn(box.left, box.right), it[1].coerceIn(box.top, box.bottom)) }
        val style = StrokeStyle(colorArgb = NotebookPalette.ink(ColorSemantics.fromBoardColor(s.color)), dashed = s.dashed == true)
        return when (s.kind.lowercase()) {
            "line" -> if (pts.size >= 2) VectorShape.Line(pts[0], pts[1], style) else null
            "arrow" -> if (pts.size >= 2) VectorShape.Arrow(pts[0], pts[1], style = style) else null
            "rect" -> if (pts.size >= 2) VectorShape.Rect(rectOf(pts[0], pts[1]), s.rotation ?: 0f, style = style) else null
            "ellipse", "circle" -> if (pts.size >= 2) VectorShape.Ellipse(rectOf(pts[0], pts[1]), style) else null
            "polyline", "polygon" -> if (pts.size >= 2) VectorShape.Polyline(pts, closed = s.kind == "polygon", style = style) else null
            "path" -> s.d?.takeIf { SVG_PATH.matches(it) }?.let { VectorShape.Path(it, style) }
            "label" -> if (pts.isNotEmpty() && !s.text.isNullOrBlank()) VectorShape.Label(pts[0], s.text, s.latex == true, style) else null
            else -> null
        }
    }

    private fun pending(role: RegionRole, order: Int?, keepWithNext: Boolean, make: (BlockId, RectPt) -> PageBlock): PendingBlock {
        val id = BlockId(newId())
        return PendingBlock(FlowItem(id.value, role, keepWithNext, order = order)) { frame -> make(id, frame) }
    }

    private fun rectOf(a: Vec2, b: Vec2) = RectPt(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))

    private companion object {
        val SVG_PATH = Regex("""^[MmLlHhVvCcSsQqTtAaZz0-9eE.,\s+-]+$""")

        fun String?.clean() = this?.trim()?.takeIf { it.isNotEmpty() }

        fun latex(raw: String?) = (raw?.let(LatexSanitizer::sanitize) as? LatexResult.Ok)?.latex

        fun InkSemantic.orEmphasis() = if (this == InkSemantic.Regular) InkSemantic.Emphasis else this

        fun sourceBox(v: List<Float>?): RectPt? {
            if (v == null || v.size != 4 || !v.all(Float::isFinite)) return null
            val r = RectPt(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
            return r.takeUnless { it.isEmpty }
        }

        fun roleOf(r: String?) = when (r?.lowercase()) {
            "title" -> RegionRole.Title
            "cues" -> RegionRole.Cues
            "summary" -> RegionRole.Summary
            "margin" -> RegionRole.Margin
            else -> RegionRole.Main
        }

        fun toneOf(t: String?) = when (t?.lowercase()) {
            "info" -> CalloutTone.Info
            "definition" -> CalloutTone.Definition
            "theorem", "law", "rule" -> CalloutTone.Theorem
            "key_result", "key", "result" -> CalloutTone.KeyResult
            "warning" -> CalloutTone.Warning
            "example" -> CalloutTone.Example
            "summary" -> CalloutTone.Summary
            else -> null
        }
    }
}
