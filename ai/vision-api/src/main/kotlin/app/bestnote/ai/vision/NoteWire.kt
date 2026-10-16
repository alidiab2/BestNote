package app.bestnote.ai.vision

import app.bestnote.engine.layout.DiagramKinds
import app.bestnote.engine.layout.GlossaryTerm
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NotePart
import app.bestnote.engine.layout.NoteStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * The compact AI wire format (TPL-01): the model returns only which built-in element and what goes
 * in it, never coordinates, colours, sizes, source boxes or ids:
 *
 * ```
 * {"elements":[{"e":"Definition","h":"term","t":"words with \\(x\\) inline"},
 *              {"e":"KeyResult","m":"E=mc^2"},
 *              {"e":"StepSolution","steps":[{"s":"…","why":"…"}]},
 *              {"e":"Table","rows":[["a","b"],["1","2"]]},
 *              {"e":"MarginRemark","t":"!","target":1}],
 *  "terms":[["ar term","he term"]]}
 * ```
 * Fields: `e` element, `t` text, `h` head/label, `m` display LaTeX, `items`, `steps` (`s`, `m`, `why`),
 * `rows`, `terms`, `target`, `n` (formula number), `cols` (Columns: two lists of elements), `parts`
 * (WorkedExample: `q`, `s` solution lines, `r` result), and drawings as data: `fbd` (forces
 * `[["N",90],…]`), `flow` (`nodes`, `edges` `[[0,1,"yes"]]`), `axes` (`x`, `y`, `pts`) or `d` (shapes).
 *
 * [parse] is forgiving like [AiDocumentParser] (fences, prose, truncation, single backslashes,
 * aliases) and still reads the OLD block format (`{"title","blocks":[…]}`) through [LegacyNotes], so an
 * older prompt or a model that ignores the schema never breaks a page. [encode] writes the same
 * format back (stored with every Smart Board page and sent with follow-up photos).
 */
object NoteWire {
    /** The model's answer text → notes; records repairs in [VisionDiagnostics]. */
    fun answer(text: String, truncated: Boolean = false): NoteDocument {
        val notes = ArrayList<String>()
        val doc = try {
            dropEmpty(parse(text, notes), notes)
        } catch (e: VisionError.BadResponse) {
            if (truncated) throw VisionError.Truncated(text)
            throw e
        }
        if (truncated) notes += "answer cut off by the output limit"
        VisionDiagnostics.recordOutcome(parseNotes = notes)
        if (doc.isEmpty) {
            if (truncated) throw VisionError.Truncated(text)
            throw VisionError.BadResponse("no usable elements", raw = text)
        }
        return doc
    }

    /**
     * Removes elements with nothing in them (models sometimes return an empty Summary or Glossary) and
     * re-points margin remarks' [NoteBlock.target] at the same element afterwards.
     */
    fun dropEmpty(doc: NoteDocument, notes: MutableList<String> = ArrayList()): NoteDocument {
        val keep = doc.elements.map { !it.isEmpty() }
        if (keep.all { it }) return doc
        val newIndex = IntArray(keep.size)
        var n = 0
        keep.forEachIndexed { i, k -> newIndex[i] = if (k) n++ else -1 }
        val kept = doc.elements.filterIndexed { i, _ -> keep[i] }.map { b ->
            val t = b.target ?: return@map b
            b.copy(target = newIndex.getOrNull(t)?.takeIf { it >= 0 })
        }
        notes += "dropped ${keep.count { !it }} empty element(s)"
        return doc.copy(elements = kept)
    }

    private fun NoteBlock.isEmpty(): Boolean =
        text.isNullOrBlank() && head.isNullOrBlank() && math.isNullOrBlank() && items.isEmpty() && steps.isEmpty() &&
            rows.isEmpty() && terms.isEmpty() && columns.all { it.isEmpty() } && parts.isEmpty() && diagram == null

    fun parse(text: String, notes: MutableList<String> = ArrayList()): NoteDocument {
        val root = AiDocumentParser.parseRoot(text, notes) ?: throw VisionError.BadResponse("the notes are not JSON", raw = text)
        val list = when (root) {
            is JsonObject -> root["elements"] as? JsonArray ?: (root["e"]?.let { JsonArray(listOf(root)) })
            is JsonArray -> root.takeIf { arr -> arr.any { (it as? JsonObject)?.containsKey("e") == true } }
            else -> null
        }
        if (list == null) {
            // The old block format (or something close to it).
            notes += "old block format"
            return LegacyNotes.fromAiDocument(AiDocumentParser.parse(text).document)
        }
        val terms = ((root as? JsonObject)?.get("terms") as? JsonArray)?.let(::terms).orEmpty()
        return NoteDocument(list.mapIndexedNotNull { i, el -> (el as? JsonObject)?.let(::element).also { if (it == null) notes += "element $i: unusable" } }, terms)
    }

    /** Compact JSON of [doc] (empty fields left out). */
    fun encode(doc: NoteDocument): String = encodeTree(doc).toString()

    fun encodeTree(doc: NoteDocument): JsonObject = buildJsonObject {
        put("elements", JsonArray(doc.elements.map(::encode)))
        if (doc.terms.isNotEmpty()) put("terms", JsonArray(doc.terms.map { pair(it.term, it.translation) }))
    }

    // ------------------------------------------------------------------ decode

    private fun element(o: JsonObject): NoteBlock? {
        val type = elementOf(str(o["e"] ?: o["element"] ?: o["type"]))
        val text = text(o["t"] ?: o["text"])
        val head = text(o["h"] ?: o["head"] ?: o["title"] ?: o["term"])
        val math = latex(o["m"] ?: o["math"] ?: o["latex"])
        val e = type ?: when {
            math != null -> NoteElement.Formula
            text != null || head != null -> NoteElement.Paragraph
            else -> return null
        }
        val diagram = when {
            o["fbd"] is JsonArray -> DiagramKinds.freeBody(
                (o["fbd"] as JsonArray).mapNotNull { f ->
                    val a = f as? JsonArray ?: return@mapNotNull null
                    val label = str(a.getOrNull(0)) ?: return@mapNotNull null
                    DiagramKinds.Force(label, num(a.getOrNull(1)) ?: 0f)
                },
            )
            o["flow"] is JsonObject -> (o["flow"] as JsonObject).let { f ->
                DiagramKinds.flow(
                    strings(f["nodes"]),
                    (f["edges"] as? JsonArray).orEmpty().mapNotNull { ed ->
                        val a = ed as? JsonArray ?: return@mapNotNull null
                        val from = num(a.getOrNull(0))?.toInt() ?: return@mapNotNull null
                        val to = num(a.getOrNull(1))?.toInt() ?: return@mapNotNull null
                        DiagramKinds.Edge(from, to, str(a.getOrNull(2)))
                    },
                )
            }
            o["axes"] is JsonObject -> (o["axes"] as JsonObject).let { a ->
                DiagramKinds.axes(
                    str(a["x"]) ?: "x",
                    str(a["y"]) ?: "y",
                    (a["pts"] as? JsonArray).orEmpty().mapNotNull { p -> (p as? JsonArray)?.let { q -> num(q.getOrNull(0))?.let { x -> num(q.getOrNull(1))?.let { y -> x to y } } } },
                )
            }
            o["d"] is JsonObject -> DocumentMapper({ "" }).mapDiagram(AiDocumentParser.diagram(o["d"] as JsonObject))
            o["diagram"] is JsonObject -> DocumentMapper({ "" }).mapDiagram(AiDocumentParser.diagram(o["diagram"] as JsonObject))
            else -> null
        }
        return NoteBlock(
            element = e,
            text = text,
            head = head,
            math = math,
            items = strings(o["items"]).map { AiDocumentParser.restoreText(it)!! },
            steps = (o["steps"] as? JsonArray).orEmpty().mapNotNull(::step),
            // Cells hold symbols and units as written ("m/s^2"): control characters restored, no ASCII-maths rewriting.
            rows = (o["rows"] as? JsonArray).orEmpty().mapNotNull { r -> (r as? JsonArray)?.map { c -> str(c)?.let { AiDocumentParser.restoreLatex(it) }.orEmpty() } },
            terms = (o["terms"] as? JsonArray)?.let(::terms).orEmpty(),
            target = num(o["target"])?.toInt(),
            number = num(o["n"])?.toInt(),
            columns = (o["cols"] as? JsonArray).orEmpty().mapNotNull { c -> (c as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::element) } }.filter { it.isNotEmpty() },
            parts = (o["parts"] as? JsonArray).orEmpty().mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                NotePart(text(po["q"]), strings(po["s"]).mapNotNull { AiDocumentParser.restoreLatex(it) }, latex(po["r"]))
            },
            diagram = diagram,
        )
    }

    private fun step(el: JsonElement): NoteStep? = when (el) {
        is JsonPrimitive -> str(el)?.let { NoteStep(text = AiDocumentParser.restoreText(it)) }
        is JsonObject -> {
            val s = text(el["s"] ?: el["text"])
            val m = latex(el["m"] ?: el["math"] ?: el["latex"])
            val why = text(el["why"] ?: el["because"] ?: el["reason"])
            if (s == null && m == null) null else NoteStep(s, m, why)
        }
        else -> null
    }

    private fun terms(arr: JsonArray): List<GlossaryTerm> = arr.mapNotNull { t ->
        when (t) {
            is JsonArray -> {
                val a = str(t.getOrNull(0))?.trim()
                val b = str(t.getOrNull(1))?.trim()
                if (a.isNullOrEmpty() || b.isNullOrEmpty()) null else GlossaryTerm(a, b)
            }
            is JsonObject -> {
                val a = str(t["term"])?.trim()
                val b = str(t["translation"] ?: t["tr"])?.trim()
                if (a.isNullOrEmpty() || b.isNullOrEmpty()) null else GlossaryTerm(a, b)
            }
            else -> null
        }
    }

    private val BY_NAME: Map<String, NoteElement> = NoteElement.entries.associateBy { norm(it.name) } + mapOf(
        "h1" to NoteElement.Title, "heading1" to NoteElement.Title, "h2" to NoteElement.Heading, "section" to NoteElement.Heading, "subheading" to NoteElement.Heading,
        "text" to NoteElement.Paragraph, "list" to NoteElement.Bullets, "bullet" to NoteElement.Bullets,
        "rule" to NoteElement.Theorem, "law" to NoteElement.Theorem, "equation" to NoteElement.Formula, "math" to NoteElement.Formula,
        "result" to NoteElement.KeyResult, "boxed" to NoteElement.KeyResult, "steps" to NoteElement.StepSolution, "solution" to NoteElement.StepSolution,
        "proof" to NoteElement.StepSolution, "remark" to NoteElement.MarginRemark, "margin" to NoteElement.MarginRemark, "marginnote" to NoteElement.MarginRemark,
        "caution" to NoteElement.Warning, "compare" to NoteElement.Comparison, "terms" to NoteElement.Glossary, "figure" to NoteElement.Diagram,
        "legend" to NoteElement.VariableLegend, "variables" to NoteElement.VariableLegend, "star" to NoteElement.StarNote, "column" to NoteElement.Columns,
        "workedexample" to NoteElement.WorkedExample, "exercise" to NoteElement.WorkedExample, "chain" to NoteElement.Derivation, "numbered" to NoteElement.NumberedFormula,
    )

    private fun norm(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    internal fun elementOf(raw: String?): NoteElement? = raw?.let { BY_NAME[norm(it)] }

    private fun str(el: JsonElement?): String? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> if (!el.isString && el.booleanOrNull != null) null else el.content
        else -> null
    }

    private fun text(el: JsonElement?): String? = str(el)?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in NOT_CONTENT }?.let { AiDocumentParser.restoreText(it) }

    private fun latex(el: JsonElement?): String? = str(el)?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in NOT_CONTENT }?.let { AiDocumentParser.restoreLatex(it) }

    private fun num(el: JsonElement?): Float? = (el as? JsonPrimitive)?.let { it.content.trim().toFloatOrNull() ?: it.doubleOrNull?.toFloat() }?.takeIf { it.isFinite() }

    private fun strings(el: JsonElement?): List<String> = when (el) {
        is JsonArray -> el.mapNotNull { str(it)?.trim()?.takeIf(String::isNotEmpty) }
        is JsonPrimitive -> str(el)?.let(AiDocumentParser::splitLines).orEmpty()
        else -> emptyList()
    }

    private val NOT_CONTENT = setOf("true", "false", "null", "undefined", "none")

    // ------------------------------------------------------------------ encode

    private fun pair(a: String, b: String) = buildJsonArray { add(JsonPrimitive(a)); add(JsonPrimitive(b)) }

    private fun encode(b: NoteBlock): JsonObject = buildJsonObject {
        put("e", b.element.name)
        b.text?.let { put("t", it) }
        b.head?.let { put("h", it) }
        b.math?.let { put("m", it) }
        if (b.items.isNotEmpty()) put("items", JsonArray(b.items.map(::JsonPrimitive)))
        if (b.steps.isNotEmpty()) put(
            "steps",
            JsonArray(
                b.steps.map { s ->
                    buildJsonObject {
                        s.text?.let { put("s", it) }
                        s.math?.let { put("m", it) }
                        s.why?.let { put("why", it) }
                    }
                },
            ),
        )
        if (b.rows.isNotEmpty()) put("rows", JsonArray(b.rows.map { r -> JsonArray(r.map(::JsonPrimitive)) }))
        if (b.terms.isNotEmpty()) put("terms", JsonArray(b.terms.map { pair(it.term, it.translation) }))
        b.target?.let { put("target", it) }
        b.number?.let { put("n", it) }
        if (b.columns.isNotEmpty()) put("cols", JsonArray(b.columns.map { c -> JsonArray(c.map(::encode)) }))
        if (b.parts.isNotEmpty()) put(
            "parts",
            JsonArray(
                b.parts.map { p ->
                    buildJsonObject {
                        p.question?.let { put("q", it) }
                        if (p.steps.isNotEmpty()) put("s", JsonArray(p.steps.map(::JsonPrimitive)))
                        p.result?.let { put("r", it) }
                    }
                },
            ),
        )
        b.diagram?.let { put("d", LegacyNotes.diagramJson(it)) }
    }

    /** Parses compact JSON this app wrote ([encode]); null when it is not readable. */
    fun decodeStored(text: String): NoteDocument? = runCatching { parse(text) }.getOrNull()
}
