package app.bestnote.ai.vision

import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.engine.layout.HandFont
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull

/** Which script's handwriting font a [LookPatch] changes. */
enum class FontSlot { Latin, Arabic, Hebrew }

/**
 * AI-13: changes to a Smart Board page's look on top of its template (role colours, fonts), from
 * "Ask Smart Board…" or the font picker. The notes themselves stay content-only ([NoteDocument]).
 */
data class LookPatch(val colors: Map<NoteRole, Long> = emptyMap(), val fonts: Map<FontSlot, String> = emptyMap()) {
    val isEmpty: Boolean get() = colors.isEmpty() && fonts.isEmpty()

    operator fun plus(o: LookPatch) = LookPatch(colors + o.colors, fonts + o.fonts)

    fun applyTo(look: StyleTemplate): StyleTemplate {
        if (isEmpty) return look
        val roles = look.roles.toMutableMap()
        colors.forEach { (role, color) -> roles[role] = (roles[role] ?: look.role(role)).copy(color = color) }
        return look.copy(
            roles = roles,
            latinFont = fonts[FontSlot.Latin]?.let(::HandFont) ?: look.latinFont,
            arabicFont = fonts[FontSlot.Arabic]?.let(::HandFont) ?: look.arabicFont,
            hebrewFont = fonts[FontSlot.Hebrew]?.let(::HandFont) ?: look.hebrewFont,
        )
    }
}

/** What one refine did: the new notes and look, how many ops were applied and why others were skipped. */
data class RefineResult(val doc: NoteDocument, val look: LookPatch, val applied: Int, val skipped: List<String> = emptyList())

/**
 * AI-13 "Ask Smart Board…": the current notes (element ids = their index) + the user's request → the
 * provider answers with an incremental edit, validated and applied here:
 * `{"ops":[{"op":"replace","id":2,"e":{…}}, {"op":"delete","id":4}, {"op":"insert","after":1,"e":{…}},
 * {"op":"move","id":3,"after":0}, {"op":"color","role":"Heading","color":"#D9462B"},
 * {"op":"font","script":"arabic","font":"aref-ruqaa"}, {"op":"terms","terms":[["a","b"]]}]}`
 * or, as a fallback, the whole replaced list `{"elements":[…]}`. With a selection only the selected
 * elements may change (a full list replaces just the selection).
 */
object RefineEngine {
    val SYSTEM: String = """
        You edit a student's study notes. The notes are a JSON list of elements in the compact note format
        ("e" = element kind, "t" text with \( \) inline maths, "h" head/label, "m" display LaTeX, "items", "steps",
        "rows", "terms", "cols", "parts"…). Each element has an "id" (its position). Apply ONLY what the user asks,
        keep everything else exactly as it is, never invent content that is not asked for.
        Answer with ONE JSON object: {"ops":[…]} where each op is one of
        {"op":"replace","id":N,"e":{element without id}}
        {"op":"delete","id":N}
        {"op":"insert","after":N,"e":{element}}   (after -1 = at the start)
        {"op":"move","id":N,"after":M}
        {"op":"color","role":"Title|Heading|Subheading|Definition|Theorem|Formula|Result|Example|Step|Note|Warning|Margin|Body","color":"#RRGGBB"}
        {"op":"font","script":"latin|arabic|hebrew","font":"<font id from the list>"}
        {"op":"terms","terms":[["term","translation"],…]}
        Colours and fonts are page-wide styles: use "color"/"font" ops for them, never put colours in the text.
        If the edit restructures most of the page (e.g. "split into two columns"), you may instead answer
        {"elements":[…every element…]} (a "Columns" element holds two lists in "cols").
        If "selected" ids are given, change only those elements.
    """.trimIndent()

    /** The user turn: notes with ids, selection, fonts on offer, and the request. */
    fun userTurn(doc: NoteDocument, request: String, selection: Set<Int> = emptySet(), fonts: List<String> = emptyList()): String {
        val tree = NoteWire.encodeTree(doc)
        val elements = (tree["elements"] as? JsonArray).orEmpty().mapIndexed { i, el ->
            JsonObject(mapOf("id" to JsonPrimitive(i)) + (el as? JsonObject).orEmpty())
        }
        val notes = JsonObject(tree + ("elements" to JsonArray(elements)))
        return buildString {
            append("Notes: ").append(notes.toString()).append('\n')
            if (selection.isNotEmpty()) append("Selected ids: ").append(selection.sorted().joinToString(",")).append('\n')
            if (fonts.isNotEmpty()) append("Font ids: ").append(fonts.joinToString(", ")).append('\n')
            append("Request: ").append(request.trim())
        }
    }

    suspend fun refine(
        provider: VisionProvider,
        doc: NoteDocument,
        request: String,
        selection: Set<Int> = emptySet(),
        look: LookPatch = LookPatch(),
        fonts: List<String> = emptyList(),
    ): RefineResult {
        val answer = provider.completeText(SYSTEM, listOf(ChatTurn(true, userTurn(doc, request, selection, fonts))), json = true)
        return apply(answer, doc, selection, look, fonts.toSet())
    }

    /** Validates and applies the model's [answer]; throws [VisionError.BadResponse] when nothing usable is in it. */
    fun apply(answer: String, doc: NoteDocument, selection: Set<Int> = emptySet(), look: LookPatch = LookPatch(), fonts: Set<String> = emptySet()): RefineResult {
        val notes = ArrayList<String>()
        val root = AiDocumentParser.parseRoot(answer, notes) ?: throw VisionError.BadResponse("refine answer is not JSON", raw = answer)
        val obj = when (root) {
            is JsonObject -> root
            is JsonArray -> root.firstOrNull { it is JsonObject && ("ops" in it || "elements" in it) } as? JsonObject
                ?: JsonObject(mapOf("ops" to root))
            else -> null
        } ?: throw VisionError.BadResponse("refine answer is not an object", raw = answer)
        val ops = obj["ops"] as? JsonArray
        val result = when {
            ops != null -> applyOps(ops, doc, selection, look, fonts)
            obj["elements"] != null -> replaceAll(NoteWire.parse(obj.toString()), doc, selection, look)
            else -> throw VisionError.BadResponse("refine answer has no ops", raw = answer)
        }
        if (result.applied == 0) throw VisionError.BadResponse("no usable change: ${result.skipped.joinToString("; ")}", raw = answer)
        if (result.doc.isEmpty && !doc.isEmpty) throw VisionError.BadResponse("the change would empty the page", raw = answer)
        return result
    }

    private fun replaceAll(fresh: NoteDocument, doc: NoteDocument, selection: Set<Int>, look: LookPatch): RefineResult {
        val cleaned = NoteWire.dropEmpty(fresh)
        if (selection.isEmpty()) return RefineResult(cleaned.copy(terms = cleaned.terms.ifEmpty { doc.terms }), look, cleaned.elements.size.coerceAtLeast(1))
        val first = selection.filter { it in doc.elements.indices }.minOrNull() ?: return RefineResult(doc, look, 0, listOf("selection is empty"))
        val out = ArrayList<NoteBlock>()
        doc.elements.forEachIndexed { i, b ->
            if (i == first) out += cleaned.elements
            if (i !in selection) out += b
        }
        return RefineResult(doc.copy(elements = out), look, 1)
    }

    /** [old]: the element's id in the notes the model saw (-1 for inserted ones, which carry their [anchor]). */
    private class Slot(var block: NoteBlock?, val old: Int, val anchor: Int = Int.MIN_VALUE)

    private fun applyOps(ops: JsonArray, doc: NoteDocument, selection: Set<Int>, start: LookPatch, fonts: Set<String>): RefineResult {
        val skipped = ArrayList<String>()
        var look = start
        var terms = doc.terms
        var applied = 0
        // Slots keep the original index so ids in later ops still mean the elements the model saw.
        val slots = doc.elements.mapIndexed { i, b -> Slot(b, i) }.toMutableList()
        val n = doc.elements.size
        fun inScope(id: Int) = selection.isEmpty() || id in selection
        fun indexOf(id: Int) = slots.indexOfFirst { it.old == id }
        ops.forEachIndexed { k, raw ->
            val op = raw as? JsonObject ?: return@forEachIndexed run { skipped += "op $k: not an object" }
            val kind = op.str("op")?.lowercase()
            val id = op.int("id")
            val reason: String? = when (kind) {
                "replace", "update", "edit" -> {
                    val e = element(op["e"] ?: op["element"])
                    when {
                        id == null || id !in 0 until n -> "replace: bad id $id"
                        !inScope(id) -> "replace: $id not selected"
                        e == null -> "replace: unusable element"
                        else -> { slots[indexOf(id)].block = e; null }
                    }
                }
                "delete", "remove" -> when {
                    id == null || id !in 0 until n -> "delete: bad id $id"
                    !inScope(id) -> "delete: $id not selected"
                    else -> { slots[indexOf(id)].block = null; null }
                }
                "insert", "add" -> {
                    val after = op.int("after") ?: -1
                    val e = element(op["e"] ?: op["element"])
                    when {
                        e == null -> "insert: unusable element"
                        after < -1 || after >= n -> "insert: bad position $after"
                        selection.isNotEmpty() && after !in selection -> "insert: $after not selected"
                        else -> {
                            // After the original element (and anything already inserted after it).
                            var at = if (after == -1) 0 else indexOf(after) + 1
                            while (at < slots.size && slots[at].old == -1 && slots[at].anchor == after) at++
                            slots.add(at, Slot(e, -1, after))
                            null
                        }
                    }
                }
                "move" -> {
                    val after = op.int("after") ?: -1
                    when {
                        id == null || id !in 0 until n || after < -1 || after >= n || id == after -> "move: bad ids"
                        !inScope(id) -> "move: $id not selected"
                        else -> {
                            val slot = slots.removeAt(indexOf(id))
                            slots.add(if (after == -1) 0 else indexOf(after) + 1, slot)
                            null
                        }
                    }
                }
                "color", "colour", "style" -> {
                    val roles = roles(op.str("role"))
                    val color = color(op.str("color") ?: op.str("colour"))
                    if (roles.isEmpty() || color == null) "color: bad role or colour" else {
                        look += LookPatch(colors = roles.associateWith { color }); null
                    }
                }
                "font" -> {
                    val slot = FontSlot.entries.firstOrNull { it.name.equals(op.str("script"), true) } ?: FontSlot.Latin
                    val font = op.str("font")
                    if (font.isNullOrBlank() || (fonts.isNotEmpty() && font !in fonts)) "font: unknown font" else {
                        look += LookPatch(fonts = mapOf(slot to font)); null
                    }
                }
                "terms", "glossary" -> {
                    val parsed = NoteWire.parse(JsonObject(mapOf("elements" to JsonArray(emptyList()), "terms" to (op["terms"] ?: JsonArray(emptyList())))).toString()).terms
                    if (parsed.isEmpty()) "terms: empty" else { terms = parsed; null }
                }
                else -> "op $k: unknown \"$kind\""
            }
            if (reason == null) applied++ else skipped += reason
        }
        // Margin remarks point at element indexes: re-point them at the same element after the edit.
        val kept = slots.filter { it.block != null }
        val newIndex = HashMap<Int, Int>()
        kept.forEachIndexed { i, s -> if (s.old >= 0) newIndex[s.old] = i }
        val elements = kept.map { s ->
            val b = s.block!!
            val t = b.target ?: return@map b
            b.copy(target = newIndex[t])
        }
        return RefineResult(NoteWire.dropEmpty(NoteDocument(elements, terms)), look, applied, skipped)
    }

    private fun element(el: JsonElement?): NoteBlock? {
        val o = el as? JsonObject ?: return null
        val one = JsonObject(mapOf("elements" to buildJsonArray { add(JsonObject(o - "id")) }))
        return runCatching { NoteWire.dropEmpty(NoteWire.parse(one.toString())).elements.firstOrNull() }.getOrNull()
    }

    /** Role names as the model or a user writes them; "headings" covers both heading levels. */
    fun roles(name: String?): List<NoteRole> {
        val k = name?.lowercase()?.trim()?.removeSuffix("s") ?: return emptyList()
        return when (k) {
            "heading", "header", "headline" -> listOf(NoteRole.Heading, NoteRole.Subheading)
            "all", "text", "everything" -> NoteRole.entries.toList()
            "formula", "equation", "math" -> listOf(NoteRole.Formula)
            else -> NoteRole.entries.filter { it.name.equals(k, true) }
        }
    }

    private val NAMED = mapOf(
        "red" to 0xFFD9462BL, "blue" to 0xFF2A4BD7L, "green" to 0xFF2F7D57L, "black" to 0xFF1E2433L, "orange" to 0xFFE07B00L,
        "purple" to 0xFF7B3FB5L, "teal" to 0xFF00796BL, "brown" to 0xFF8D5524L, "grey" to 0xFF5F6670L, "gray" to 0xFF5F6670L,
        "navy" to 0xFF1E2A5AL, "pink" to 0xFFD63384L,
    )

    fun color(raw: String?): Long? {
        val s = raw?.trim()?.lowercase() ?: return null
        NAMED[s]?.let { return it }
        val hex = s.removePrefix("#").takeIf { it.length == 6 || it.length == 8 } ?: return null
        val v = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) 0xFF000000L or v else v
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.content.trim().toDoubleOrNull()?.toInt() }
}

/** A request "Ask Smart Board…" handles on the device, without an AI call. */
sealed interface LocalRefine {
    data class Template(val id: String) : LocalRefine
    data class Font(val slot: FontSlot, val id: String) : LocalRefine
    data class Color(val roles: List<NoteRole>, val color: Long) : LocalRefine
    data object Undo : LocalRefine
}

/**
 * Recognises short local commands ("template cornell", "font caveat", "headings red", "undo", and the
 * Arabic «القالب …», «الخط …», «تراجع», «العناوين باللون الأحمر»). Anything else goes to the AI.
 * [templates] and [fonts] are (id, display name) pairs.
 */
object LocalRefineCommands {
    private val TEMPLATE_WORDS = listOf("template", "style", "قالب", "القالب", "تصميم")
    private val FONT_WORDS = listOf("font", "خط", "الخط")
    private val UNDO = setOf("undo", "تراجع", "ارجع", "إلغاء", "الغاء")
    private val AR_ROLES = mapOf("العناوين" to "heading", "عناوين" to "heading", "العنوان" to "title", "التعريفات" to "definition", "الأمثلة" to "example", "النص" to "body", "الكل" to "all")
    private val AR_COLORS = mapOf("أحمر" to "red", "الأحمر" to "red", "أزرق" to "blue", "الأزرق" to "blue", "أخضر" to "green", "الأخضر" to "green", "أسود" to "black", "الأسود" to "black", "برتقالي" to "orange", "بنفسجي" to "purple")
    private const val MAX_WORDS = 6

    fun parse(text: String, templates: List<Pair<String, String>>, fonts: List<Pair<String, String>>, slot: FontSlot = FontSlot.Latin): LocalRefine? {
        val t = text.trim().lowercase().trimEnd('.', '!', '؟', '?')
        if (t.isEmpty()) return null
        if (t in UNDO) return LocalRefine.Undo
        val words = t.split(Regex("\\s+"))
        if (words.size > MAX_WORDS) return null
        fun names(list: List<Pair<String, String>>) = list.sortedByDescending { it.second.length }.firstOrNull { (id, name) ->
            t.contains(name.lowercase()) || t.contains(id.lowercase())
        }?.first
        if (TEMPLATE_WORDS.any { w -> words.any { it == w || it.startsWith(w) } }) names(templates)?.let { return LocalRefine.Template(it) }
        if (FONT_WORDS.any { it in words }) names(fonts)?.let { return LocalRefine.Font(slot, it) }
        val norm = words.map { w -> AR_ROLES[w] ?: AR_COLORS[w] ?: AR_COLORS[w.removePrefix("ب").removePrefix("ال")] ?: AR_COLORS["ال" + w.removePrefix("بال")] ?: w }
        val color = norm.firstNotNullOfOrNull { RefineEngine.color(it.takeIf { c -> c.startsWith("#") || c.all(Char::isLetter) }) }
        val roles = norm.firstNotNullOfOrNull { w -> RefineEngine.roles(w).takeIf { it.isNotEmpty() } }
        if (color != null && roles != null) return LocalRefine.Color(roles, color)
        return null
    }
}
