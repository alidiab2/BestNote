package app.bestnote.engine.templates

import app.bestnote.engine.layout.DetailLevel
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.TermsStyle
import app.bestnote.engine.layout.RuledPaper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One message of the "Design with AI" conversation. */
data class DesignTurn(val fromUser: Boolean, val text: String)

class DesignParseException(message: String) : Exception(message)

/**
 * What the AI changed: the new [template] (clamped), a short [message] for the user, [changes] to show, and
 * the [adjusted] keys the app had to pull back into range.
 */
data class DesignOutcome(
    val template: NoteTemplate,
    val message: String,
    val changes: List<TemplateChangeItem>,
    val adjusted: List<String>,
)

/**
 * "Design with AI" (TPL-02): the user describes a template in words, the AI answers with the template as
 * JSON in the strict shape of [TemplateJson], the app validates and clamps it ([TemplateClamp]) and the
 * preview updates. Text only (no image): [system] is the system prompt, [userMessage] wraps the user's
 * words with the template as it is now so "bigger headings" works.
 */
object DesignWithAi {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** What each role is, so "blue headings, red formulas" maps to the right keys. */
    private val roleHelp = mapOf(
        NoteRole.Title to "page title", NoteRole.Heading to "section headings", NoteRole.Subheading to "sub-headings",
        NoteRole.Definition to "definitions", NoteRole.Theorem to "theorems and rules", NoteRole.Formula to "ordinary formulas",
        NoteRole.Result to "key results and final answers (usually boxed)", NoteRole.Example to "examples", NoteRole.Step to "solution steps",
        NoteRole.Note to "notes and summaries", NoteRole.Warning to "warnings and common mistakes", NoteRole.Margin to "margin remarks",
        NoteRole.Body to "body text",
    )

    private val elementHelp = mapOf(
        NoteElement.Title to "page title", NoteElement.Heading to "section heading", NoteElement.Paragraph to "plain text", NoteElement.Bullets to "bullet list",
        NoteElement.Definition to "term + meaning", NoteElement.Theorem to "named theorem", NoteElement.Formula to "display formula", NoteElement.KeyResult to "boxed key result",
        NoteElement.Example to "worked example", NoteElement.StepSolution to "numbered steps with reasons", NoteElement.Note to "side note", NoteElement.Warning to "warning",
        NoteElement.MarginRemark to "remark in the margin", NoteElement.Comparison to "two-column comparison", NoteElement.Table to "table", NoteElement.Summary to "summary box",
        NoteElement.Glossary to "terms list", NoteElement.Diagram to "simple diagram", NoteElement.Columns to "two columns side by side", NoteElement.NumberedFormula to "boxed formula with a circled number",
        NoteElement.VariableLegend to "symbol - meaning - unit lines", NoteElement.Derivation to "equations aligned at =", NoteElement.WorkedExample to "problem, givens, sub-questions, boxed results",
        NoteElement.StarNote to "star remark",
    )

    fun system(fonts: FontOptions): String = buildString {
        appendLine("You design note-taking templates for a handwriting notes app. A template = a LOOK (colour per role, font per script, sizes, decorations, spacing), AI INSTRUCTIONS for the note-writing AI, and the ALLOWED ELEMENTS.")
        appendLine("Reply with ONE JSON object and nothing else: {\"reply\": \"<one or two short sentences to the user, in the user's language, saying what you changed>\", \"template\": <the full template>}.")
        appendLine("The template JSON shape:")
        appendLine(SHAPE)
        appendLine("Rules:")
        appendLine("- Always return the FULL template, starting from the CURRENT template the user message gives. Change only what the user asked for; keep everything else exactly.")
        appendLine("- Colours are \"#RRGGBB\" dark enough to read on white paper, or null (= the pen colour). Never white or near-white.")
        appendLine("- scale 0.7..2.2 (1 = body size; titles ~1.4, headings ~1.2). bodyScale 0.6..1.3. spaceBeforeHeading 0..3. decoration: none, underline, doubleUnderline, highlight, box.")
        appendLine("- Roles: " + NoteRole.entries.joinToString("; ") { "${TemplateJson.roleKeys.getValue(it)} = ${roleHelp[it]}" } + ".")
        appendLine("- Fonts, by script, by id ONLY from these lists: " + ScriptKey.entries.joinToString("; ") { s -> "${s.name.lowercase()}: " + fonts.ids(s).joinToString(", ") { "${it} (${fonts.nameOf(it)})" } } + ". Use \"system\" only if asked for a plain font.")
        appendLine("- paper: null (the notebook's own paper, the default) or one of " + RuledPaper.writable.joinToString(", ") { "\"${it.name}\"" } + ". Leave it null unless the user asks for paper.")
        appendLine("- ai.notesLanguage: null = same language as the board, else a code such as \"ar\", \"he\", \"en\". ai.termsLanguage: null or a code (technical terms also given in this language). termsStyle: ${TermsStyle.entries.joinToString("|") { it.name.replaceFirstChar(Char::lowercase) }}. detail: ${DetailLevel.entries.joinToString("|") { it.name.replaceFirstChar(Char::lowercase) }} (cheatSheet = very short). stepByStep, glossary: booleans.")
        appendLine("- ai.note: the user's own instruction to the note-writing AI, in plain words, written in English or the user's language (max 400 characters); put wishes about CONTENT there (e.g. \"exam cheat sheet, only formulas and traps\").")
        appendLine("- elements: names from this list only: " + NoteElement.entries.joinToString(", ") { "${it.name} (${elementHelp[it]})" } + ". Keep Title and Heading. A cheat sheet drops Paragraph and Example; a lecture keeps everything.")
        appendLine("- If the request is unclear, make your best guess and say so in reply. Never add keys that are not in the shape.")
    }.trim()

    /** The user's words, with the template as it is now. */
    fun userMessage(words: String, current: NoteTemplate): String =
        "CURRENT TEMPLATE:\n" + TemplateJson.encodeToString(current, includeId = false) + "\n\nREQUEST:\n" + words.trim()

    /**
     * Turns the model's answer into a [DesignOutcome]: finds the JSON (tolerates code fences and prose),
     * layers it on [current], keeps the template's id, clamps. Throws [DesignParseException] when there is no usable JSON.
     */
    fun parse(answer: String, current: NoteTemplate, fonts: FontOptions): DesignOutcome {
        val root = extractObject(answer) ?: throw DesignParseException("no JSON object in the answer")
        val body = (root["template"] as? JsonObject) ?: root.takeIf { "look" in it || "ai" in it || "elements" in it }
            ?: throw DesignParseException("no template in the answer")
        val message = (root["reply"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val decoded = TemplateJson.decode(JsonObject(body - "id"), current)
        val clamped = TemplateClamp.clamp(decoded.copy(id = current.id, builtIn = false, look = decoded.look.copy(id = current.id)), fonts)
        return DesignOutcome(clamped.template, message, TemplateDiff.between(current, clamped.template), clamped.adjusted)
    }

    /** The first balanced `{…}` in [text] that parses as a JSON object. */
    fun extractObject(text: String): JsonObject? {
        val t = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        var start = t.indexOf('{')
        while (start >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until t.length) {
                val c = t[i]
                if (inString) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                } else when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            (runCatching { json.parseToJsonElement(t.substring(start, i + 1)) }.getOrNull() as? JsonObject)?.let { return it }
                            break
                        }
                    }
                }
            }
            start = t.indexOf('{', start + 1)
        }
        return null
    }

    private val SHAPE = """
{"name":"...","look":{"fonts":{"latin":"<id>","arabic":"<id>","hebrew":"<id>"},"bodyScale":0.9,"spaceBeforeHeading":1,"marginArrows":true,"highlight":"#FFE066","paper":null,
"roles":{"title":{"color":"#1E2A5A","scale":1.45,"decoration":"doubleUnderline"},"heading":{...},"subheading":{...},"definition":{...},"theorem":{...},"formula":{...},"result":{...},"example":{...},"step":{...},"note":{...},"warning":{...},"margin":{...},"body":{"color":null,"scale":1,"decoration":"none"}}},
"ai":{"notesLanguage":null,"termsLanguage":null,"termsStyle":"inParentheses","detail":"full","stepByStep":true,"glossary":true,"note":""},
"elements":["Title","Heading","Paragraph"]}
""".trim()
}
