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

    private fun parseRoot(text: String, notes: MutableList<String>): JsonElement? {
        val candidate = extractJson(text)
        if (candidate != text.trim()) notes += "stripped text around the JSON"
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

    private val NEWLINE_COMMAND = Regex("\n(abla|eq|u|ot|otin|e|ewline|leq|geq|ewcommand|i|exists|subset|mid|parallel|cong|sim|rightarrow|leftarrow|Rightarrow|Leftarrow)(?![a-z])")
    private const val VALID_ESCAPES = "\"\\/bfnrtu"

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
            title = s("title", "term", "heading"),
            text = s("text", "content", "body", "value", "caption"),
            items = strings(o["items"] ?: o["bullets"] ?: o["points_list"]),
            tone = s("tone"),
            latex = restoreLatex(s("latex", "equation", "formula", "math")),
            boxed = bool(o["boxed"]),
            steps = (o["steps"] as? JsonArray)?.mapNotNull(::step),
            annotates = s("annotates", "anchor", "refers_to"),
            semantic = s("semantic"),
            inkColor = s("ink_color", "inkColor", "color", "colour"),
            diagram = (o["diagram"] as? JsonObject)?.let(::diagram),
            sourceBox = floats(o["source_box"] ?: o["sourceBox"] ?: o["bbox"] ?: o["box"]),
        )
    }

    private fun step(el: JsonElement): AiProofStep? = when (el) {
        is JsonPrimitive -> el.takeIf { it.isString }?.content?.let { AiProofStep(text = it) }
        is JsonObject -> AiProofStep(
            latex = restoreLatex(string(el["latex"] ?: el["equation"] ?: el["math"])),
            text = string(el["text"] ?: el["content"]),
            because = string(el["because"] ?: el["reason"] ?: el["justification"]),
        )
        else -> null
    }

    private fun diagram(o: JsonObject): AiDiagram {
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

    private fun string(el: JsonElement?): String? = when (el) {
        null, JsonNull -> null
        is JsonPrimitive -> el.content
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content }.takeIf { it.isNotEmpty() }?.joinToString("\n")
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
