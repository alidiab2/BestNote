package app.bestnote.ai.vision

import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bridges the old block format ([AiDocument]) and the element format ([NoteDocument]): an answer in
 * the old shape still becomes elements, and the synthetic-stroke writer (which reads blocks) can
 * write element notes.
 */
object LegacyNotes {
    /** Old blocks → elements, in the blocks' teaching order; margin notes keep pointing at their block. */
    fun fromAiDocument(doc: AiDocument): NoteDocument {
        val ordered = doc.blocks.withIndex().sortedWith(compareBy({ it.value.order ?: Int.MAX_VALUE }, { it.index })).map { it.value }
        val out = ArrayList<NoteBlock>()
        doc.title?.takeIf { it.isNotBlank() }?.let { out += NoteBlock(NoteElement.Title, text = it.trim()) }
        val indexById = HashMap<String, Int>()
        val pendingTargets = ArrayList<Pair<Int, String>>()
        val mapper = DocumentMapper({ "" })
        for (b in ordered) {
            val words = b.text?.trim()?.takeIf { it.isNotEmpty() }
            val block: NoteBlock? = when (normalType(b)) {
                "heading" -> (words ?: b.title)?.let { NoteBlock(if (b.level == 1) NoteElement.Title else NoteElement.Heading, text = it) }
                "bullets" -> (b.items ?: words?.let(AiDocumentParser::splitLines))?.takeIf { it.isNotEmpty() }?.let { NoteBlock(NoteElement.Bullets, items = it) }
                "definition" -> NoteBlock(NoteElement.Definition, head = b.title, text = words ?: b.latex?.let { "\\( $it \\)" })
                "callout" -> {
                    val e = when (b.tone?.lowercase()) {
                        "theorem" -> NoteElement.Theorem
                        "warning" -> NoteElement.Warning
                        "example" -> NoteElement.Example
                        "summary" -> NoteElement.Summary
                        "key_result" -> if (b.latex != null) NoteElement.KeyResult else NoteElement.Note
                        "definition" -> NoteElement.Definition
                        else -> NoteElement.Note
                    }
                    NoteBlock(e, head = b.title, text = words ?: b.items?.joinToString("\n"), math = b.latex)
                }
                "math" -> (b.latex ?: words)?.let { NoteBlock(if (b.boxed == true || b.semantic == "essential") NoteElement.KeyResult else NoteElement.Formula, math = it) }
                "proof" -> NoteBlock(
                    NoteElement.StepSolution,
                    head = b.title,
                    steps = b.steps?.map { app.bestnote.engine.layout.NoteStep(it.text, it.latex, it.because) }
                        ?: listOf(app.bestnote.engine.layout.NoteStep(words, b.latex)),
                )
                "margin_note" -> (words ?: b.title)?.let { t ->
                    b.annotates?.let { pendingTargets += out.size to it }
                    NoteBlock(NoteElement.MarginRemark, text = t)
                }
                "diagram" -> b.diagram?.let(mapper::mapDiagram)?.let { NoteBlock(NoteElement.Diagram, head = words, diagram = it) }
                    ?: words?.let { NoteBlock(NoteElement.Paragraph, text = it) }
                else -> (words ?: b.items?.joinToString(" ") ?: b.title)?.let { NoteBlock(NoteElement.Paragraph, text = it) }
            }
            if (block != null) {
                b.id?.let { indexById[it] = out.size }
                out += block
            }
        }
        for ((at, id) in pendingTargets) indexById[id]?.let { out[at] = out[at].copy(target = it) }
        return NoteDocument(out)
    }

    private fun normalType(b: AiBlock): String = when (val t = b.type.trim().lowercase().replace(' ', '_').replace('-', '_')) {
        "title", "header", "section", "subheading", "h1", "h2", "h3" -> "heading"
        "list", "bullet", "bullet_list", "numbered_list" -> "bullets"
        "equation", "formula", "latex", "display_math" -> "math"
        "derivation", "steps", "solution" -> "proof"
        "margin", "aside", "remark", "side_note", "annotation" -> "margin_note"
        "theorem", "law", "rule", "example", "warning", "key_result", "box" -> "callout"
        "figure", "drawing", "graph", "sketch" -> "diagram"
        else -> t
    }

    /** Elements → old blocks, for the synthetic-stroke writer; tables, legends and glossaries become bullets. */
    fun toAiDocument(doc: NoteDocument): AiDocument {
        val blocks = ArrayList<AiBlock>()
        fun add(b: NoteBlock) {
            val i = blocks.size
            val id = "b$i"
            fun rowsAsItems() = b.rows.map { it.filter(String::isNotBlank).joinToString(" — ") }
            blocks += when (b.element) {
                NoteElement.Title -> AiBlock("heading", id, i, level = 1, text = b.text ?: b.head)
                NoteElement.Heading -> AiBlock("heading", id, i, level = 2, text = b.text ?: b.head)
                NoteElement.Paragraph, NoteElement.StarNote -> AiBlock("paragraph", id, i, text = b.text ?: b.head)
                NoteElement.Bullets -> AiBlock("bullets", id, i, items = b.items)
                NoteElement.Definition -> AiBlock("definition", id, i, title = b.head, text = b.text)
                NoteElement.Theorem -> AiBlock("callout", id, i, tone = "theorem", title = b.head, text = b.text)
                NoteElement.Example -> AiBlock("callout", id, i, tone = "example", title = b.head, text = b.text)
                NoteElement.Note -> AiBlock("callout", id, i, tone = "info", title = b.head, text = b.text)
                NoteElement.Warning -> AiBlock("callout", id, i, tone = "warning", title = b.head, text = b.text)
                NoteElement.Summary -> AiBlock("callout", id, i, tone = "summary", title = b.head, text = (listOfNotNull(b.text) + b.items).joinToString("\n"))
                NoteElement.Formula -> AiBlock("math", id, i, latex = b.math)
                NoteElement.KeyResult, NoteElement.NumberedFormula -> AiBlock("math", id, i, latex = b.math, boxed = true)
                NoteElement.StepSolution, NoteElement.Derivation -> AiBlock("proof", id, i, title = b.head, steps = b.steps.map { AiProofStep(it.math, it.text, it.why) })
                NoteElement.MarginRemark -> AiBlock("margin_note", id, i, text = b.text, annotates = b.target?.let { "b$it" })
                NoteElement.Comparison, NoteElement.Table, NoteElement.VariableLegend -> AiBlock("bullets", id, i, items = rowsAsItems())
                NoteElement.Glossary -> AiBlock("bullets", id, i, items = b.terms.map { "${it.term} — ${it.translation}" })
                NoteElement.Diagram -> AiBlock("diagram", id, i, text = b.head, diagram = b.diagram?.let(::aiDiagram))
                NoteElement.WorkedExample -> AiBlock(
                    "proof", id, i, title = b.head, text = b.text,
                    steps = b.parts.flatMap { p -> listOfNotNull(p.question?.let { AiProofStep(text = it) }) + p.steps.map { AiProofStep(latex = it) } + listOfNotNull(p.result?.let { AiProofStep(latex = it) }) },
                )
                NoteElement.Columns -> {
                    b.columns.flatten().forEach(::add)
                    return
                }
            }
        }
        doc.elements.forEach(::add)
        return AiDocument(blocks = blocks)
    }

    private fun aiDiagram(d: VectorDiagram): AiDiagram = AiDiagram(
        size = listOf(d.viewBox.width, d.viewBox.height),
        shapes = d.shapes.mapNotNull { s ->
            val ox = d.viewBox.left
            val oy = d.viewBox.top
            when (s) {
                is VectorShape.Line -> AiShape("line", listOf(s.from.x - ox, s.from.y - oy, s.to.x - ox, s.to.y - oy), dashed = s.style.dashed, color = hex(s.style.colorArgb))
                is VectorShape.Arrow -> AiShape("arrow", listOf(s.from.x - ox, s.from.y - oy, s.to.x - ox, s.to.y - oy), color = hex(s.style.colorArgb))
                is VectorShape.Rect -> AiShape("rect", listOf(s.rect.left - ox, s.rect.top - oy, s.rect.right - ox, s.rect.bottom - oy), rotation = s.rotationDeg)
                is VectorShape.Ellipse -> AiShape("ellipse", listOf(s.bounds.left - ox, s.bounds.top - oy, s.bounds.right - ox, s.bounds.bottom - oy))
                is VectorShape.Polyline -> AiShape(if (s.closed) "polygon" else "polyline", s.points.flatMap { listOf(it.x - ox, it.y - oy) })
                is VectorShape.Path -> AiShape("path", d = s.d)
                is VectorShape.Label -> AiShape("label", listOf(s.at.x - ox, s.at.y - oy), text = s.text, latex = s.latex)
            }
        },
    )

    /** A colour other than the default ink, as "#rrggbb" (mapped back through the notebook palette when read). */
    private fun hex(argb: Long): String? = if (argb == app.bestnote.engine.layout.NotebookPalette.INK || argb == 0xFF1F1F1FL) null else "#%06x".format(argb and 0xFFFFFF)

    /** A drawing as `d` JSON (the shapes format), for storing a page's notes. */
    fun diagramJson(d: VectorDiagram): JsonObject {
        val a = aiDiagram(d)
        return buildJsonObject {
            put("size", JsonArray(a.size.map(::JsonPrimitive)))
            put(
                "shapes",
                JsonArray(
                    a.shapes.map { s ->
                        buildJsonObject {
                            put("kind", s.kind)
                            if (s.points.isNotEmpty()) put("points", JsonArray(s.points.map { JsonPrimitive(Math.round(it * 10) / 10f) }))
                            s.rotation?.takeIf { it != 0f }?.let { put("rotation", it) }
                            s.d?.let { put("d", it) }
                            s.text?.let { put("text", it) }
                            if (s.latex == true) put("latex", true)
                            if (s.dashed == true) put("dashed", true)
                            s.color?.let { put("color", it) }
                        }
                    },
                ),
            )
        }
    }
}
