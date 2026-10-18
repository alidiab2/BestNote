package app.bestnote.ai.vision

import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.BoardFigure
import app.bestnote.engine.layout.DiagramKinds
import app.bestnote.engine.layout.FigureBox
import app.bestnote.engine.layout.FigureGraph
import app.bestnote.engine.layout.FigureKind
import app.bestnote.engine.layout.FigureLabel
import app.bestnote.engine.layout.FigureRaster
import app.bestnote.engine.layout.FigureShape
import app.bestnote.engine.layout.FigureSource
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
            rows.isEmpty() && terms.isEmpty() && columns.all { it.isEmpty() } && parts.isEmpty() && diagram == null &&
            figure.let { f -> f == null || (f.box == null && f.graph == null && f.shape == null && f.raster == null && f.ref == null) }

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

    /**
     * AI-12a (Claude without its schema, real device): every element came wrapped one level deeper,
     * `{"e":{"e":"Title","t":"…"}}`. The inner object's fields (and its own `e`) are lifted up.
     */
    private fun unwrap(o: JsonObject): JsonObject {
        var cur = o
        repeat(MAX_NESTING) {
            val key = listOf("e", "element").firstOrNull { cur[it] is JsonObject } ?: return cur
            val inner = cur[key] as JsonObject
            cur = JsonObject(cur.filterKeys { it != key } + inner)
        }
        return cur
    }

    private fun element(raw: JsonObject): NoteBlock? {
        val o = unwrap(raw)
        val type = elementOf(str(o["e"] ?: o["element"] ?: o["type"]))
        val text = text(o["t"] ?: o["text"])
        val head = text(o["h"] ?: o["head"] ?: o["title"] ?: o["term"])
        val math = latex(o["m"] ?: o["math"] ?: o["latex"])
        val figure = figure(o)
        val e = type ?: when {
            figure != null -> NoteElement.Diagram
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
            figure = figure,
        )
    }

    /**
     * AI-12 figure fields: `k` kind, `box` [left, top, right, bottom] 0–1000 in the photo, `lbl` labels
     * (`[["N", x, y], …]` or plain strings), simple data `graph` / `shape`, `ref` (a kept figure on a
     * follow-up), and what the app stored after drawing it: `src`, `img` [asset, w, h]. Null when the
     * element has none of them; a bad box is dropped (the figure then needs data, or is only a caption).
     */
    private fun figure(o: JsonObject): BoardFigure? {
        val boxRaw = o["box"] ?: o["bbox"]
        val kindRaw = str(o["k"] ?: o["kind"])
        if (boxRaw == null && kindRaw == null && o["graph"] !is JsonObject && o["shape"] !is JsonObject && o["ref"] == null) return null
        val box = (boxRaw as? JsonArray)?.mapNotNull { num(it) }?.let(FigureBox::of)
        val labels = (o["lbl"] ?: o["labels"]).let { it as? JsonArray }.orEmpty().mapNotNull { l ->
            when (l) {
                is JsonArray -> str(l.getOrNull(0))?.trim()?.takeIf(String::isNotEmpty)?.let { t ->
                    val x = num(l.getOrNull(1))
                    val y = num(l.getOrNull(2))
                    FigureLabel(AiDocumentParser.restoreLatex(t) ?: t, point(x, y))
                }
                is JsonPrimitive -> str(l)?.trim()?.takeIf(String::isNotEmpty)?.let { FigureLabel(AiDocumentParser.restoreLatex(it) ?: it) }
                else -> null
            }
        }.take(MAX_LABELS)
        val img = (o["img"] as? JsonArray)?.let { a ->
            val asset = str(a.getOrNull(0)) ?: return@let null
            val w = num(a.getOrNull(1))?.toInt() ?: return@let null
            val h = num(a.getOrNull(2))?.toInt() ?: return@let null
            if (w > 0 && h > 0) FigureRaster(asset, w, h) else null
        }
        val source = str(o["src"])?.let { s -> FigureSource.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } } ?: FigureSource.Pending
        return BoardFigure(
            kind = FigureKind.of(kindRaw),
            box = box,
            labels = labels,
            graph = (o["graph"] as? JsonObject)?.let(::graph),
            shape = (o["shape"] as? JsonObject)?.let(::shape),
            raster = img,
            source = if (source == FigureSource.Picture && img == null) FigureSource.Pending else source,
            ref = num(o["ref"])?.toInt(),
        )
    }

    /** A wire point (0–1000, or fractions) → photo fractions; null when missing or negative. */
    private fun point(x: Float?, y: Float?): Vec2? {
        if (x == null || y == null || x < 0f || y < 0f) return null
        val k = if (maxOf(x, y) <= 1.0001f) 1f else 1000f
        return Vec2((x / k).coerceIn(0f, 1f), (y / k).coerceIn(0f, 1f))
    }

    private fun range(el: JsonElement?): Pair<Float, Float>? = (el as? JsonArray)?.let { a ->
        val lo = num(a.getOrNull(0)) ?: return null
        val hi = num(a.getOrNull(1)) ?: return null
        if (lo == hi) null else minOf(lo, hi) to maxOf(lo, hi)
    }

    private fun graph(g: JsonObject): FigureGraph? {
        val x = range(g["x"])
        val y = range(g["y"])
        val expr = str(g["f"] ?: g["expr"])?.trim()?.takeIf { it.isNotEmpty() }?.let { AiDocumentParser.restoreLatex(it) ?: it }
        val pts = (g["pts"] as? JsonArray).orEmpty().mapNotNull { p -> (p as? JsonArray)?.let { q -> num(q.getOrNull(0))?.let { a -> num(q.getOrNull(1))?.let { b -> Vec2(a, b) } } } }
        if (expr == null && pts.size < 2) return null
        return FigureGraph(
            xMin = x?.first ?: pts.minOfOrNull { it.x } ?: -5f,
            xMax = x?.second ?: pts.maxOfOrNull { it.x } ?: 5f,
            yMin = y?.first,
            yMax = y?.second,
            xLabel = str(g["xl"])?.takeIf { it.isNotBlank() } ?: "x",
            yLabel = str(g["yl"])?.takeIf { it.isNotBlank() } ?: "y",
            expr = expr,
            points = pts,
        )
    }

    private fun shape(o: JsonObject): FigureShape? {
        val raw = str(o["type"] ?: o["t"])?.lowercase()?.filter(Char::isLetter) ?: return null
        val type = when (raw) {
            "line", "segment" -> FigureShape.Type.Line
            "arrow", "vector" -> FigureShape.Type.Arrow
            "rect", "rectangle", "square", "box" -> FigureShape.Type.Rect
            "circle" -> FigureShape.Type.Circle
            "ellipse", "oval" -> FigureShape.Type.Ellipse
            "triangle" -> FigureShape.Type.Triangle
            "polygon", "poly" -> FigureShape.Type.Polygon
            else -> return null
        }
        val pts = (o["pts"] as? JsonArray).orEmpty().mapNotNull { p ->
            (p as? JsonArray)?.let { q ->
                val x = num(q.getOrNull(0)) ?: return@let null
                val y = num(q.getOrNull(1)) ?: return@let null
                point(x, y)
            }
        }
        return if (pts.isEmpty()) null else FigureShape(type, pts)
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
    private const val MAX_NESTING = 3
    private const val MAX_LABELS = 24

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
        b.figure?.let { encodeFigure(it, forAi = false) }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.encodeFigure(f: BoardFigure, forAi: Boolean) {
        put("k", f.kind.name.lowercase())
        if (!forAi) {
            f.box?.let { b -> put("box", JsonArray(listOf(b.left, b.top, b.right, b.bottom).map { JsonPrimitive(Math.round(it * 1000)) })) }
            if (f.source != FigureSource.Pending) put("src", f.source.name.lowercase())
            f.raster?.let { r -> put("img", buildJsonArray { add(JsonPrimitive(r.asset)); add(JsonPrimitive(r.width)); add(JsonPrimitive(r.height)) }) }
        }
        if (f.labels.isNotEmpty()) put(
            "lbl",
            JsonArray(
                f.labels.map { l ->
                    buildJsonArray {
                        add(JsonPrimitive(l.text))
                        if (!forAi) l.at?.let { add(JsonPrimitive(Math.round(it.x * 1000))); add(JsonPrimitive(Math.round(it.y * 1000))) }
                    }
                },
            ),
        )
    }

    /**
     * The notes as sent back to the AI with a follow-up photo: drawings are left out (they are big and
     * belong to the old photo); each already drawn figure is a short `{"e":"Diagram","k",…,"ref":i}`
     * the model keeps where it belongs, and [reattach] puts the drawing back.
     */
    fun encodeForAi(doc: NoteDocument): String {
        var n = 0
        fun strip(b: NoteBlock): JsonObject {
            val f = b.figure
            if (b.element != NoteElement.Diagram || (f == null && b.diagram == null)) {
                return if (b.columns.isEmpty()) encode(b) else JsonObject(encode(b) + ("cols" to JsonArray(b.columns.map { c -> JsonArray(c.map(::strip)) })))
            }
            val ref = n++
            return buildJsonObject {
                put("e", NoteElement.Diagram.name)
                b.head?.let { put("h", it) }
                if (f != null) encodeFigure(f, forAi = true) else put("k", "diagram")
                put("ref", ref)
            }
        }
        return buildJsonObject {
            put("elements", JsonArray(doc.elements.map(::strip)))
            if (doc.terms.isNotEmpty()) put("terms", JsonArray(doc.terms.map { pair(it.term, it.translation) }))
        }.toString()
    }

    /** Puts the drawings of [previous] back into the Diagram elements of [merged] that kept a `ref` (see [encodeForAi]). */
    fun reattach(merged: NoteDocument, previous: NoteDocument?): NoteDocument {
        if (previous == null) return merged
        val drawn = previous.flatten().filter { it.element == NoteElement.Diagram && (it.figure != null || it.diagram != null) }
        if (drawn.isEmpty()) return merged
        fun fix(b: NoteBlock): NoteBlock {
            val ref = b.figure?.ref
            val old = ref?.let { drawn.getOrNull(it) }
            val base = if (old != null && b.figure?.box == null) old.copy(head = b.head ?: old.head) else b
            return if (base.columns.isEmpty()) base else base.copy(columns = base.columns.map { c -> c.map(::fix) })
        }
        return merged.copy(elements = merged.elements.map(::fix))
    }

    /** Parses compact JSON this app wrote ([encode]); null when it is not readable. */
    fun decodeStored(text: String): NoteDocument? = runCatching { parse(text) }.getOrNull()
}
