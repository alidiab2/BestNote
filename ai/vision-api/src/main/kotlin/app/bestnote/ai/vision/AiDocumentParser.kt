package app.bestnote.ai.vision

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The model's answer text → [AiDocument], for every provider. Deliberately forgiving, because a
 * real model answer is rarely exactly the schema:
 * - JSON inside ``` fences or after a sentence of prose;
 * - output cut off by the token limit (closed back up at the last complete value);
 * - a bare array of blocks, or a single block, instead of `{title, blocks}`;
 * - alias keys (`content`/`body` for `text`, `equation` for `latex`, `inkColor`, `bbox`…);
 * - numbers as strings, `1.0` for an integer, items as one newline-separated string, nested points.
 *
 * Validation is [DocumentMapper]'s job; this only recovers the shape.
 */
object AiDocumentParser {
    /** [notes] says what had to be repaired; it goes to diagnostics, never to the user. */
    class Parsed(val document: AiDocument, val notes: List<String>)

    private val json = Json { isLenient = true }

    /**
     * What every provider calls on the model's answer text: parse, record the repairs in
     * [VisionDiagnostics], and turn a cut-off answer with nothing complete into [VisionError.Truncated].
     */
    fun answer(text: String, truncated: Boolean = false): AiDocument {
        val parsed = try {
            parse(text)
        } catch (e: VisionError.BadResponse) {
            if (truncated) throw VisionError.Truncated(text)
            throw e
        }
        val notes = if (truncated) parsed.notes + "answer cut off by the output limit" else parsed.notes
        VisionDiagnostics.recordOutcome(parseNotes = notes)
        if (truncated && parsed.document.blocks.isEmpty() && parsed.document.title == null) throw VisionError.Truncated(text)
        return parsed.document
    }

    fun parse(text: String): Parsed {
        val notes = ArrayList<String>()
        val root = parseRoot(text, notes) ?: throw VisionError.BadResponse("the notes are not JSON", raw = text)
        val (title, blocksEl) = when (root) {
            is JsonArray -> null to root.also { notes += "root is an array" }
            is JsonObject -> {
                val list = BLOCK_LIST_KEYS.firstNotNullOfOrNull { root[it] as? JsonArray }
                when {
                    list != null -> string(root["title"] ?: root["topic"]) to list
                    root["type"] != null -> null to JsonArray(listOf(root)).also { notes += "root is a single block" }
                    else -> string(root["title"]) to JsonArray(emptyList())
                }
            }
            else -> throw VisionError.BadResponse("the notes are not a JSON object", raw = text)
        }
        val blocks = blocksEl.mapIndexedNotNull { i, el ->
            block(el).also { if (it == null) notes += "block $i: not an object" }
        }
        return Parsed(AiDocument(title = title?.trim()?.takeIf { it.isNotEmpty() }, blocks = blocks), notes)
    }

    internal fun parseRoot(text: String, notes: MutableList<String>): JsonElement? {
        // AI-12a (Claude, real device): a small junk object, a blank line, then the real answer. Every
        // complete top-level value is read and the richest one wins (the later one on a tie).
        val values = topLevelValues(stripFences(text))
        if (values.size > 1) {
            val parsed = values.mapNotNull { v -> parseOne(v, ArrayList())?.let { it to v } }
            if (parsed.size > 1) {
                notes += "picked the fullest of ${values.size} JSON values"
                return parsed.withIndex().maxWith(compareBy({ richness(it.value.first) }, { it.index })).value.first
            }
        }
        return parseOne(text, notes)
    }

    private fun parseOne(text: String, notes: MutableList<String>): JsonElement? {
        val extracted = extractJson(text)
        if (extracted != text.trim()) notes += "stripped text around the JSON"
        // "\frac" written with one backslash is valid JSON (form feed + "rac"): protect LaTeX first.
        val candidate = protectLatex(extracted)
        if (candidate != extracted) notes += "kept LaTeX backslashes"
        runCatching { return json.parseToJsonElement(candidate) }
        // LaTeX written with single backslashes ("\," "\s") is not valid JSON.
        val escaped = fixEscapes(candidate)
        if (escaped != candidate) {
            notes += "fixed invalid escapes"
            runCatching { return json.parseToJsonElement(escaped) }
        }
        val repaired = repairTruncated(escaped) ?: return null
        notes += "closed truncated JSON"
        return runCatching { json.parseToJsonElement(repaired) }.getOrNull()
    }

    /**
     * Inside JSON strings, turns a single backslash that starts a LaTeX command into a literal one
     * BEFORE parsing, so `"\frac{a}{b}"` (one backslash, as models often write it) keeps its `\f`:
     * - `\f` or `\b` followed by a letter: always LaTeX (`\frac`, `\beta`, `\bar`…); a form feed or
     *   backspace is never meant in notes;
     * - `\n`, `\t`, `\r` followed by letters: LaTeX only when the whole word is a known command
     *   (`\nabla`, `\theta`, `\times`, `\rho`…), so a real newline before "Then" stays a newline;
     * - `\u` not followed by four hex digits (`\underline`, `\uparrow`): LaTeX.
     */
    internal fun protectLatex(text: String): String {
        if (text.indexOf('\\') < 0) return text
        val out = StringBuilder(text.length + 16)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!inString) {
                if (c == '"') inString = true
                out.append(c)
                i++
                continue
            }
            if (c == '"') {
                inString = false
                out.append(c)
                i++
                continue
            }
            if (c != '\\') {
                out.append(c)
                i++
                continue
            }
            val next = text.getOrNull(i + 1)
            if (next == '\\' || next == '"' || next == '/') {
                out.append(c).append(next)
                i += 2
                continue
            }
            var j = i + 1
            while (j < text.length && text[j].isLetter() && text[j].code < 128) j++
            val word = text.substring(i + 1, j)
            val latex = when (next) {
                'f', 'b' -> word.length >= 2
                'n', 't', 'r' -> word in LATEX_NTR
                'u' -> text.substring(i + 2, minOf(i + 6, text.length)).let { hex -> hex.length < 4 || !hex.all { h -> h.isDigit() || h.lowercaseChar() in 'a'..'f' } }
                else -> false
            }
            if (latex) out.append("\\\\") else out.append(c)
            i++
        }
        return out.toString()
    }

    /** LaTeX commands that start with n, t or r (the JSON escapes \n \t \r). */
    private val LATEX_NTR = setOf(
        "nabla", "neq", "ne", "neg", "nu", "not", "notin", "ni", "nleq", "ngeq", "nmid", "nparallel", "newline", "nolimits", "nsubseteq",
        "theta", "Theta", "tau", "times", "tan", "tanh", "text", "textbf", "textit", "textrm", "tfrac", "tilde", "to", "top", "triangle",
        "triangleq", "therefore", "tag", "thinspace", "textstyle", "tt",
        "rho", "right", "rightarrow", "Rightarrow", "rangle", "rceil", "rfloor", "rbrace", "rvert", "Re", "rm", "rightleftharpoons", "real",
    )

    /** Doubles every backslash that does not start a valid JSON escape. */
    internal fun fixEscapes(text: String): String {
        val out = StringBuilder(text.length + 16)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (!inString) {
                if (c == '"') inString = true
                out.append(c)
            } else if (c == '\\') {
                val next = text.getOrNull(i + 1)
                if (next != null && next in VALID_ESCAPES) {
                    out.append(c).append(next)
                    i++
                } else {
                    out.append("\\\\")
                }
            } else {
                if (c == '"') inString = false
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /**
     * A model that writes `\frac` with one backslash produces valid JSON that decodes to a form feed
     * + "rac". In LaTeX those control characters are never meant, so they are turned back into the
     * command they came from.
     */
    internal fun restoreLatex(s: String?): String? = s
        ?.replace("\u000C", "\\f")?.replace("\b", "\\b")?.replace("\t", "\\t")?.replace("\r", "\\r")
        ?.replace(NEWLINE_COMMAND) { "\\n" + it.groupValues[1] }
        ?.let(::arrowScripts)

    /** Text fields: control characters left by single-backslash LaTeX (`\(\frac…\)` in prose) are restored the same way. */
    internal fun restoreText(s: String?): String? = s
        ?.replace("\u000C", "\\f")?.replace("\b", "\\b")
        ?.replace(TAB_COMMAND) { "\\t" + it.groupValues[1] }
        ?.replace(NEWLINE_COMMAND) { "\\n" + it.groupValues[1] }
        ?.let(AsciiMath::inline)

    /** "x↑30" or "e↑(x+1)" (a superscript written as an arrow) → "x^{30}", "e^{(x+1)}". */
    internal fun arrowScripts(s: String): String = if ('↑' !in s) s else ARROW_SCRIPT.replace(s) { "^{" + it.groupValues[1] + "}" }

    private val ARROW_SCRIPT = Regex("↑\\s*(\\([^)]*\\)|\\{[^}]*\\}|[A-Za-z0-9.]+)")
    private val TAB_COMMAND = Regex("\t(heta|imes|au|ext|extbf|frac|ilde|o|op|riangle|herefore|an)(?![a-z])")

    private val NEWLINE_COMMAND = Regex("\n(abla|eq|u|ot|otin|e|ewline|leq|geq|ewcommand|i|exists|subset|mid|parallel|cong|sim|rightarrow|leftarrow|Rightarrow|Leftarrow)(?![a-z])")
    private const val VALID_ESCAPES = "\"\\/bfnrtu"

    /** [text] with every ``` fence line removed (the JSON inside is kept). */
    private fun stripFences(text: String): String = text.lines().filterNot { it.trim().startsWith("```") }.joinToString("\n")

    /**
     * The complete top-level JSON objects/arrays in [text], in order (strings and escapes respected;
     * prose between them skipped). An unfinished last value is not returned.
     */
    internal fun topLevelValues(text: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for ((i, c) in text.withIndex()) {
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> if (depth > 0) inString = true
                '{', '[' -> { if (depth == 0) start = i; depth++ }
                '}', ']' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) { out += text.substring(start, i + 1); start = -1 }
                }
            }
        }
        return out
    }

    /** How much content a parsed answer carries: the number of string characters in it. */
    private fun richness(e: JsonElement): Int = when (e) {
        is JsonObject -> e.values.sumOf(::richness)
        is JsonArray -> e.sumOf(::richness)
        is JsonPrimitive -> if (e.isString) e.content.length else 0
        else -> 0
    }

    /** The JSON part of a model answer: inside a ``` fence if there is one, else from the first `{`/`[`. */
    internal fun extractJson(text: String): String {
        val t = text.trim()
        FENCE.find(t)?.let { return it.groupValues[1].trim() }
        if (t.startsWith("```")) return t.substringAfter('\n').trim() // opening fence, never closed (truncated)
        val start = t.indexOfFirst { it == '{' || it == '[' }
        if (start <= 0) return t
        return t.substring(start)
    }

    /**
     * Closes JSON that stops mid-way (MAX_TOKENS). Cuts back to just after the last complete
     * object or array and appends the closers still open at that point, so every block that was
     * fully written survives. Null when nothing complete was written.
     */
    internal fun repairTruncated(text: String): String? {
        val stack = StringBuilder()
        var inString = false
        var escape = false
        var cut = -1
        var cutStack = ""
        for ((i, c) in text.withIndex()) {
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.append(c)
                '}', ']' -> {
                    if (stack.isEmpty()) return null
                    stack.setLength(stack.length - 1)
                    cut = i + 1
                    cutStack = stack.toString()
                }
            }
        }
        if (cut < 0 || (cutStack.isEmpty() && cut == text.trimEnd().length)) return null
        val closers = cutStack.reversed().map { if (it == '{') '}' else ']' }.joinToString("")
        return text.substring(0, cut) + closers
    }

    private fun block(el: JsonElement): AiBlock? = when (el) {
        is JsonPrimitive -> el.takeIf { it.isString }?.content?.let { AiBlock(type = "paragraph", text = it) }
        is JsonObject -> blockOf(el)
        else -> null
    }

    private fun blockOf(o: JsonObject): AiBlock {
        fun s(vararg keys: String) = keys.firstNotNullOfOrNull { string(o[it]) }
        return AiBlock(
            type = s("type", "kind", "block_type", "blockType")?.trim().orEmpty(),
            id = s("id"),
            order = int(o["order"] ?: o["index"]),
            role = s("role"),
            level = int(o["level"]),
            title = restoreText(s("title", "term", "heading")),
            text = restoreText(s("text", "content", "body", "value", "caption")),
            items = strings(o["items"] ?: o["bullets"] ?: o["points_list"])?.map { restoreText(it)!! },
            tone = s("tone"),
            latex = restoreLatex(s("latex", "equation", "formula", "math")),
            boxed = bool(o["boxed"]),
            steps = (o["steps"] as? JsonArray)?.mapNotNull(::step),
            annotates = s("annotates", "anchor", "refers_to"),
            semantic = s("semantic"),
            inkColor = s("ink_color", "inkColor", "color", "colour"),
            diagram = (o["diagram"] as? JsonObject)?.let(::diagram),
            sourceBox = floats(o["source_box"] ?: o["sourceBox"] ?: o["bbox"] ?: o["box"]),
            lang = s("lang", "language", "locale"),
        )
    }

    private fun step(el: JsonElement): AiProofStep? = when (el) {
        is JsonPrimitive -> el.takeIf { it.isString }?.content?.let { AiProofStep(text = it) }
        is JsonObject -> AiProofStep(
            latex = restoreLatex(string(el["latex"] ?: el["equation"] ?: el["math"])),
            text = restoreText(string(el["text"] ?: el["content"])),
            because = restoreText(string(el["because"] ?: el["reason"] ?: el["justification"])),
        )
        else -> null
    }

    internal fun diagram(o: JsonObject): AiDiagram {
        val shapes = (o["shapes"] as? JsonArray).orEmpty().mapNotNull { el ->
            val s = el as? JsonObject ?: return@mapNotNull null
            AiShape(
                kind = string(s["kind"] ?: s["type"] ?: s["shape"]).orEmpty(),
                points = floats(s["points"] ?: s["coords"]).orEmpty(),
                rotation = float(s["rotation"]),
                d = string(s["d"] ?: s["path"]),
                text = string(s["text"] ?: s["label"]),
                latex = bool(s["latex"]),
                dashed = bool(s["dashed"]),
                color = string(s["color"] ?: s["colour"]),
            )
        }
        // A missing or zero size is inferred from the points, so the drawing is not lost.
        val size = floats(o["size"])?.takeIf { it.size >= 2 && it[0] > 0f && it[1] > 0f }
            ?: shapes.flatMap { it.points }.chunked(2).filter { it.size == 2 }.let { pts ->
                val w = pts.maxOfOrNull { it[0] } ?: 0f
                val h = pts.maxOfOrNull { it[1] } ?: 0f
                if (w > 0f && h > 0f) listOf(w, h) else emptyList()
            }
        return AiDiagram(size = size, shapes = shapes)
    }

    /**
     * A string field. A boolean is never content: `"math": true` (a flag some models add) must not
     * become the formula "true", and `"text": false` is no text.
     */
    private fun string(el: JsonElement?): String? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> if (!el.isString && el.booleanOrNull != null) null else el.content
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> !p.isString && p.booleanOrNull != null }?.content }.takeIf { it.isNotEmpty() }?.joinToString("\n")
        is JsonObject -> null
    }

    private fun int(el: JsonElement?): Int? = (el as? JsonPrimitive)?.let { p ->
        p.content.trim().toIntOrNull() ?: p.doubleOrNull?.takeIf { it.isFinite() }?.toInt()
    }

    private fun float(el: JsonElement?): Float? = (el as? JsonPrimitive)?.let { p ->
        p.content.trim().toFloatOrNull()?.takeIf { it.isFinite() }
    }

    private fun bool(el: JsonElement?): Boolean? = (el as? JsonPrimitive)?.let { p ->
        p.booleanOrNull ?: when (p.content.trim().lowercase()) {
            "yes", "1" -> true
            "no", "0" -> false
            else -> null
        }
    }

    /** Flat or nested number lists ([[x,y],[x,y]] → [x,y,x,y]); numbers given as strings are accepted. */
    private fun floats(el: JsonElement?): List<Float>? {
        val arr = el as? JsonArray ?: return null
        val out = ArrayList<Float>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonArray -> e.forEach(::walk)
                is JsonPrimitive -> float(e)?.let(out::add)
                else -> Unit
            }
        }
        walk(arr)
        return out
    }

    /** A list of strings (or of objects with `text`), or one string split into lines/bullets. */
    private fun strings(el: JsonElement?): List<String>? = when (el) {
        is JsonArray -> el.mapNotNull { e ->
            when (e) {
                is JsonPrimitive -> e.content
                is JsonObject -> string(e["text"] ?: e["content"])
                else -> null
            }
        }
        is JsonPrimitive -> if (el.isString) splitLines(el.content) else null
        else -> null
    }

    /** "a\n- b\n• c" → [a, b, c]. */
    fun splitLines(text: String): List<String> =
        text.split('\n').map { it.trim().removePrefix("-").removePrefix("•").removePrefix("*").trim() }.filter { it.isNotEmpty() }

    private val BLOCK_LIST_KEYS = listOf("blocks", "notes", "sections", "content", "items")
    private val FENCE = Regex("```[a-zA-Z]*\\s*\\n?([\\s\\S]*?)```")
}
