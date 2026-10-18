package app.bestnote.ai.vision

import app.bestnote.engine.layout.AiInstructions
import app.bestnote.engine.layout.DetailLevel
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.TermsStyle
import java.util.Locale

/** What a provider needs to write a board into a template's elements. [current]: the page's notes, for a follow-up photo. */
data class NotesRequest(
    val template: NoteTemplate,
    val rtl: Boolean = false,
    val current: NoteDocument? = null,
) {
    /** The old-format request, for providers that only implement [VisionProvider.reconstructBoard]. */
    fun legacy(): BoardRequest = BoardRequest(
        app.bestnote.core.model.LayoutTemplate.LectureSummary,
        rtl = rtl,
        language = NotePrompts.languageName(template.ai.notesLanguage),
    )
}

/**
 * Prompts for the template-first element format. The template comes FIRST: the model is told which
 * built-in elements it may use and what each one holds, the template's [AiInstructions], and the
 * user's own note verbatim (highest priority). The expert-student persona ([PERSONA], the core of
 * [Prompts.EXPERT_STUDENT]) stays; everything about coordinates, colours, ids and source boxes is
 * gone because the app lays the elements out itself.
 */
object NotePrompts {
    /** The expert student, without the old format's layout and colour instructions. */
    const val PERSONA = """You are an elite, meticulous student copying the board into your own notebook — not an OCR engine. Copy what the lecturer MEANT, in the order it was taught (follow numbering, arrows, "⇒", a definition before the example that uses it; panels left to right, right to left on Hebrew/Arabic boards), complete and correct for the exam. Fix slips and spelling, never change the meaning, never invent content, never lose content. Every drawing is kept; every symbol keeps its unit. Read the WHOLE board, every panel, edge to edge."""

    /** One line per element: what goes in which field. */
    private val GUIDE: Map<NoteElement, String> = linkedMapOf(
        NoteElement.Title to "Title: t = the lecture's topic (once, first)",
        NoteElement.Heading to "Heading: t = a section",
        NoteElement.Paragraph to "Paragraph: t",
        NoteElement.Bullets to "Bullets: items",
        NoteElement.Definition to "Definition: h = the term, t = its meaning",
        NoteElement.Theorem to "Theorem: h = name, t = statement (law, rule)",
        NoteElement.Formula to "Formula: m = display LaTeX",
        NoteElement.KeyResult to "KeyResult: m = the stressed formula (boxed), h = optional label",
        NoteElement.NumberedFormula to "NumberedFormula: m = a key formula, boxed and numbered ①②… in order; later text refers to it as \"(from ②)\"",
        NoteElement.Derivation to "Derivation: steps[].m = equations of one chain, each one line, ending in the result (boxed, numbered)",
        NoteElement.VariableLegend to "VariableLegend: rows = [symbol LaTeX, meaning, unit] right after the formula it explains; each symbol defined once",
        NoteElement.Example to "Example: h, t = the example, steps optional",
        NoteElement.StepSolution to "StepSolution: steps = [{s: words, m: LaTeX, why: short reason}]",
        NoteElement.WorkedExample to "WorkedExample: t = problem, rows = given [symbol, value, unit], parts = [{q: sub-question, s: [formula, substitution] LaTeX, r: result LaTeX WITH unit}]",
        NoteElement.Note to "Note: t",
        NoteElement.StarNote to "StarNote: t = a ★ remark",
        NoteElement.Warning to "Warning: t = a common mistake",
        NoteElement.MarginRemark to "MarginRemark: t = a short side remark, target = index of the element it belongs to",
        NoteElement.Comparison to "Comparison: rows = [[left header, right header], [left, right], …]",
        NoteElement.Table to "Table: rows (row 0 = headers)",
        NoteElement.Summary to "Summary: items = 2–4 key points",
        NoteElement.Glossary to "Glossary: terms = [[term, translation]]",
        // AI-12: the app redraws every drawing from the photo; the model only locates it and reads its labels.
        NoteElement.Diagram to "Diagram: EVERY drawing (graph, diagram, table grid, shape, sketch) in teaching order: " +
            "box = [left, top, right, bottom] around it and its labels, 0–1000 (not pixels) from the photo's top-left; k = graph|diagram|table|shape|arrow|drawing; " +
            "lbl = [[LaTeX, x, y]] for every label, letter, number and angle on it (ticks' 0 too), at its centre. " +
            "The app redraws it from the photo: never describe it in words; a boxed formula is a KeyResult. Only if very simple also: shape = {type: line|arrow|rect|circle|triangle|polygon, pts [[x, y]]}, " +
            "graph = {x: [min, max], y: [min, max], f: y(x)}, rows (a table's cells)",
        NoteElement.Columns to "Columns: cols = [[elements], [elements]] — theory/formulas in the first, the worked example beside it",
    )

    /** System instruction for writing a board into [request]'s template. */
    fun system(request: NotesRequest): String = buildString {
        val t = request.template
        append(PERSONA)
        append("\n\n## Output\nReturn ONLY {\"elements\":[{\"e\":<element>, …}]} in teaching order")
        if (t.ai.glossary) append(" plus \"terms\"")
        append(". ONE JSON object, nothing before or after it. Each element is one flat object whose \"e\" is the element's name, e.g. {\"e\":\"Heading\",\"t\":\"…\"}.")
        append(" No coordinates, colours, sizes or ids: the app lays out and styles everything.\nElements you may use:\n")
        for ((e, line) in GUIDE) if (e in t.elements) append("- ").append(line).append('\n')
        append("Maths: inline in t as \\( … \\); display maths in m without delimiters; double every backslash in JSON (\"\\\\frac\").\n")
        append("In Hebrew or Arabic sentences only single symbols stay inline (x, e^x, 30!); put any fraction, limit, sum, root or multi-term expression in its own Formula element after the sentence.\n")
        append("Units in parentheses: 10\\,(m/s).\n")
        // AI-12 (owner): crossed-out terms and braces were lost; the question was restated as an extra Note.
        append("Keep \\cancel{…} (crossed out) and \\underbrace{…}_{…} (braces). Write only what is on the board: never restate the question; text on a drawing goes only in its lbl.\n")
        // Live tests (owner's DeepSeek key, thinking off): the same computation came back as both a
        // Derivation and a StepSolution, and Summary/Glossary came back empty.
        val both = NoteElement.Derivation in t.elements && NoteElement.StepSolution in t.elements
        append("Each piece of content appears ONCE")
        append(if (both) ": one computation is one Derivation OR one StepSolution, never both" else "")
        append("; do not restate a result as a Note.\n")
        append("Never return an empty element: a Summary has 2–4 items, a Glossary has terms, otherwise leave it out.\n")
        // TPL-03: the app packs topics into columns; it needs compact content grouped by topic.
        append("Compact, like the best student's notebook: short phrases, no filler or connecting sentences. Group by topic: each topic starts with a Heading.\n")
        val solved = listOf(NoteElement.WorkedExample, NoteElement.StepSolution).filter { it in t.elements }
        if (solved.isNotEmpty()) append("A computation is ONE ${solved.joinToString(" or ")} (not loose Paragraph + Formula lines).\n")
        if (NoteElement.Table in t.elements) append("Table only for real tabular data, never to lay out steps.\n")
        append(instructions(t.ai, request.rtl))
        if (t.ai.note.isNotBlank()) {
            append("\n## The user's instruction (highest priority, follow it exactly)\n")
            append(t.ai.note.trim()).append('\n')
        }
    }

    /** The template's AI instructions as prompt lines. */
    fun instructions(ai: AiInstructions, rtl: Boolean): String = buildString {
        append("\n## Notes\n")
        val notes = languageName(ai.notesLanguage)
        if (notes == null) {
            append("- Language: the board's own, element by element. Never translate.\n")
        } else {
            append("- Language: write all notes in $notes, translating where needed; formulas unchanged.\n")
        }
        if (rtl) append("- The board reads right to left.\n")
        val terms = languageName(ai.termsLanguage)
        if (terms != null) when (ai.termsStyle) {
            TermsStyle.InParentheses -> append("- Technical terms: the first time, add the $terms term in parentheses.\n")
            TermsStyle.Only -> append("- Technical terms: write them in $terms only.\n")
            TermsStyle.None -> Unit
        }
        append(
            when (ai.detail) {
                DetailLevel.Summary -> "- Detail: a short summary, only the essentials.\n"
                DetailLevel.Full -> "- Detail: everything on the board.\n"
                DetailLevel.CheatSheet -> "- Detail: a terse cheat sheet: key formulas and rules, no prose.\n"
            },
        )
        if (ai.stepByStep) append("- Solutions step by step, each step with a short reason.\n")
        if (ai.glossary) {
            if (terms != null) {
                append("- REQUIRED, never empty: terms = [[term as in the notes, term in $terms]] for every technical term on the page (usually 4–10).\n")
            } else {
                // Without a terms language the model repeated the notes' own word on both sides.
                append("- terms = [[term as in the notes, the board's original term]] only where the board wrote it in another language; otherwise no terms.\n")
            }
        }
    }

    /** User turn sent with a board photo. */
    const val USER = "Board photo. Write the notes as elements."

    /**
     * User turn for a follow-up photo of the same lecture: the page's current elements go along, and
     * the model returns ONE merged list (new content added, duplicates removed, teaching order).
     */
    fun followUpUser(current: NoteDocument): String =
        "These notes are already on the page:\n" + NoteWire.encodeForAi(current) +
            "\nThe photo shows the board later in the same lecture (maybe partly the same). Return ONE merged elements list: " +
            "keep everything already there (a Diagram with \"ref\" stays exactly as given), add what is new, remove duplicates, re-order by teaching order, only the allowed elements."

    fun user(request: NotesRequest): String = request.current?.takeIf { !it.isEmpty }?.let(::followUpUser) ?: USER

    /** A BCP-47 code ("he") or a name as the model reads it ("Hebrew"); null stays null. */
    fun languageName(code: String?): String? {
        if (code.isNullOrBlank()) return null
        NotesLanguage.entries.firstOrNull { it.code == code || it.promptName.equals(code, ignoreCase = true) }?.promptName?.let { return it }
        return Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).takeIf { it.isNotBlank() } ?: code
    }
}
