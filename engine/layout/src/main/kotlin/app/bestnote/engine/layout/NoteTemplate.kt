package app.bestnote.engine.layout

/**
 * The building blocks the AI may use. The AI only says which element and what goes in it; the app
 * lays it out and styles it from the [NoteTemplate], so answers are short, fast and always neat.
 */
enum class NoteElement {
    Title, Heading, Paragraph, Bullets, Definition, Theorem, Formula, KeyResult, Example,
    StepSolution, Note, Warning, MarginRemark, Comparison, Table, Summary, Glossary, Diagram,
}

/** How much the AI writes. */
enum class DetailLevel { Summary, Full, CheatSheet }

/** Where technical terms in [AiInstructions.termsLanguage] go relative to the notes' own words. */
enum class TermsStyle { InParentheses, Only, None }

/**
 * What the AI is told for every page written with a template.
 *
 * @property notesLanguage language of the notes (BCP-47 such as "ar", "he", "en"); null = same as the board.
 * @property termsLanguage technical terms also given in this language (e.g. "he" for Hebrew lectures); null = none.
 * @property note the user's own words to the AI, sent verbatim (e.g. "write in Arabic, keep the terms in Hebrew").
 * @property glossary collect the terms of every page into the notebook's glossary.
 */
data class AiInstructions(
    val notesLanguage: String? = null,
    val termsLanguage: String? = null,
    val termsStyle: TermsStyle = TermsStyle.InParentheses,
    val detail: DetailLevel = DetailLevel.Full,
    val stepByStep: Boolean = true,
    val glossary: Boolean = true,
    val note: String = "",
)

/**
 * A note template: how pages look ([look]: colours per role, fonts, sizes, spacing; its paper null =
 * the notebook page's own paper, never asked), what the AI is told ([ai]) and which [elements] it may
 * use. Chosen BEFORE capture; saved per notebook so every page of a notebook matches. Built-in
 * templates have stable ids; user templates are designed in the template editor (by hand or with the AI).
 */
data class NoteTemplate(
    val id: String,
    val name: String,
    val look: StyleTemplate,
    val ai: AiInstructions = AiInstructions(),
    val elements: Set<NoteElement> = NoteElement.entries.toSet(),
    val builtIn: Boolean = false,
)

/** The built-in templates, one per built-in style. Ids are stored per notebook, so never rename one. */
object NoteTemplates {
    private val cheatSheetElements = NoteElement.entries.toSet() - setOf(NoteElement.Example, NoteElement.Paragraph)

    fun builtIns(): List<NoteTemplate> = StyleTemplates.all.map { look ->
        NoteTemplate(
            id = look.id,
            name = look.id.replaceFirstChar { it.uppercase() },
            look = look,
            ai = if (look.id == "cheatsheet") AiInstructions(detail = DetailLevel.CheatSheet, stepByStep = false) else AiInstructions(),
            elements = if (look.id == "cheatsheet") cheatSheetElements else NoteElement.entries.toSet(),
            builtIn = true,
        )
    }
}
