package app.bestnote.engine.layout

import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.PaperTemplate

/**
 * What a piece of the notes IS, whatever the board looked like. A [StyleTemplate] gives every role
 * one fixed colour, size and decoration, so the eye reads the structure at a glance and every Smart
 * Board page of a notebook looks the same.
 */
enum class NoteRole {
    Title, Heading, Subheading, Definition, Theorem, Formula, Result, Example, Step, Note, Warning, Margin, Body;

    companion object {
        /** The role of a laid-out block. A boxed formula is a key [Result]. */
        fun of(block: PageBlock): NoteRole = when (block) {
            is PageBlock.Heading -> when (block.level) { 1 -> Title; 2 -> Heading; else -> Subheading }
            is PageBlock.Paragraph -> Body
            is PageBlock.Bullets -> Body
            is PageBlock.Math -> if (block.boxed) Result else Formula
            is PageBlock.Proof -> Step
            is PageBlock.MarginNote -> Margin
            is PageBlock.Diagram -> Body
            is PageBlock.Callout -> when (block.tone) {
                CalloutTone.Definition -> Definition
                CalloutTone.Theorem -> Theorem
                CalloutTone.KeyResult -> Result
                CalloutTone.Warning -> Warning
                CalloutTone.Example -> Example
                CalloutTone.Summary, CalloutTone.Info -> Note
            }
        }
    }
}

/** How a role's label or heading is marked, the way a student would by hand. */
enum class Decoration { None, Underline, DoubleUnderline, Highlight, Box }

/**
 * One role's look. [color] null = the user's pen colour. [scale] multiplies the body text size.
 * [decoration] marks headings/labels; for formulas and results, [Decoration.Box] draws the pen box.
 */
data class RoleStyle(val color: Long? = null, val scale: Float = 1f, val decoration: Decoration = Decoration.None)

/** The handwriting fonts a template writes with (bundled, SIL OFL). */
enum class HandFont { Caveat, ArefRuqaa }

/**
 * A Smart Board design template: paper, fonts, sizes, one colour per role, decorations and spacing.
 * Data only, so adding a style is adding a preset to [StyleTemplates].
 *
 * @property bodyScale body text size as a fraction of the line gap.
 * @property paper the paper it writes on; null keeps the notebook page's own.
 * @property spaceBeforeHeading blank lines before a heading (skipped at the top of a page).
 * @property marginArrows margin notes get a curved arrow to what they comment on.
 * @property highlight the highlighter colour for [Decoration.Highlight].
 */
data class StyleTemplate(
    val id: String,
    val paper: PaperTemplate?,
    val latinFont: HandFont = HandFont.Caveat,
    val arabicFont: HandFont = HandFont.ArefRuqaa,
    val bodyScale: Float = 0.9f,
    val roles: Map<NoteRole, RoleStyle>,
    val spaceBeforeHeading: Int = 1,
    val marginArrows: Boolean = true,
    val highlight: Long = 0x66FFE066L,
) {
    fun role(r: NoteRole): RoleStyle = roles[r] ?: roles[NoteRole.Body] ?: RoleStyle()

    /** Roles shown in the picker's colour legend, in reading order. */
    val legend: List<Pair<NoteRole, Long?>>
        get() = LEGEND.map { it to role(it).color }

    private companion object {
        val LEGEND = listOf(NoteRole.Title, NoteRole.Heading, NoteRole.Definition, NoteRole.Result, NoteRole.Example, NoteRole.Step, NoteRole.Warning, NoteRole.Margin)
    }
}

/** The built-in styles. Ids are stored per notebook, so never rename one. */
object StyleTemplates {
    private const val NAVY = 0xFF1E2A5AL
    private const val BLUE = 0xFF2A4BD7L
    private const val TEAL = 0xFF00796BL
    private const val RED = 0xFFD9462BL
    private const val GREEN = 0xFF2F7D57L
    private const val PURPLE = 0xFF7B3FB5L
    private const val ORANGE = 0xFFE07B00L
    private const val BROWN = 0xFF8D5524L
    private const val GREY = 0xFF5F6670L
    private const val BLACK = 0xFF1E2433L

    val Classic = StyleTemplate(
        id = "classic",
        paper = null,
        roles = mapOf(
            NoteRole.Title to RoleStyle(NAVY, 1.45f, Decoration.DoubleUnderline),
            NoteRole.Heading to RoleStyle(BLUE, 1.2f, Decoration.Underline),
            NoteRole.Subheading to RoleStyle(BLUE, 1.05f),
            NoteRole.Definition to RoleStyle(TEAL, 1f, Decoration.Underline),
            NoteRole.Theorem to RoleStyle(PURPLE, 1f, Decoration.Underline),
            NoteRole.Formula to RoleStyle(null, 1f),
            NoteRole.Result to RoleStyle(RED, 1f, Decoration.Box),
            NoteRole.Example to RoleStyle(ORANGE, 1f, Decoration.Underline),
            NoteRole.Step to RoleStyle(null, 1f),
            NoteRole.Note to RoleStyle(GREY, 1f, Decoration.Underline),
            NoteRole.Warning to RoleStyle(RED, 1f, Decoration.Underline),
            NoteRole.Margin to RoleStyle(GREEN, 0.85f),
            NoteRole.Body to RoleStyle(null, 1f),
        ),
    )

    val Minimal = StyleTemplate(
        id = "minimal",
        paper = null,
        spaceBeforeHeading = 1,
        roles = mapOf(
            NoteRole.Title to RoleStyle(BLACK, 1.4f, Decoration.Underline),
            NoteRole.Heading to RoleStyle(BLACK, 1.15f),
            NoteRole.Subheading to RoleStyle(BLACK, 1.05f),
            NoteRole.Definition to RoleStyle(BLUE, 1f),
            NoteRole.Theorem to RoleStyle(BLUE, 1f),
            NoteRole.Formula to RoleStyle(BLACK, 1f),
            NoteRole.Result to RoleStyle(BLUE, 1f, Decoration.Box),
            NoteRole.Example to RoleStyle(GREY, 1f),
            NoteRole.Step to RoleStyle(BLACK, 1f),
            NoteRole.Note to RoleStyle(GREY, 1f),
            NoteRole.Warning to RoleStyle(BLUE, 1f, Decoration.Underline),
            NoteRole.Margin to RoleStyle(GREY, 0.85f),
            NoteRole.Body to RoleStyle(BLACK, 1f),
        ),
    )

    val Colorful = StyleTemplate(
        id = "colorful",
        paper = null,
        highlight = 0x66FFD54FL,
        roles = mapOf(
            NoteRole.Title to RoleStyle(PURPLE, 1.5f, Decoration.Highlight),
            NoteRole.Heading to RoleStyle(BLUE, 1.2f, Decoration.Highlight),
            NoteRole.Subheading to RoleStyle(TEAL, 1.05f, Decoration.Underline),
            NoteRole.Definition to RoleStyle(TEAL, 1f, Decoration.Highlight),
            NoteRole.Theorem to RoleStyle(PURPLE, 1f, Decoration.Highlight),
            NoteRole.Formula to RoleStyle(NAVY, 1f),
            NoteRole.Result to RoleStyle(RED, 1f, Decoration.Box),
            NoteRole.Example to RoleStyle(ORANGE, 1f, Decoration.Highlight),
            NoteRole.Step to RoleStyle(BROWN, 1f),
            NoteRole.Note to RoleStyle(GREY, 1f, Decoration.Underline),
            NoteRole.Warning to RoleStyle(RED, 1f, Decoration.Highlight),
            NoteRole.Margin to RoleStyle(GREEN, 0.85f),
            NoteRole.Body to RoleStyle(null, 1f),
        ),
    )

    val Cornell = Classic.copy(id = "cornell", paper = PaperTemplate.Cornell)

    val CheatSheet = Classic.copy(
        id = "cheatsheet",
        paper = PaperTemplate.RuledNarrow,
        bodyScale = 0.85f,
        spaceBeforeHeading = 0,
        marginArrows = false,
        roles = Classic.roles + mapOf(
            NoteRole.Title to RoleStyle(NAVY, 1.25f, Decoration.Underline),
            NoteRole.Heading to RoleStyle(BLUE, 1.05f, Decoration.Box),
            NoteRole.Subheading to RoleStyle(BLUE, 1f),
        ),
    )

    val all: List<StyleTemplate> = listOf(Classic, Minimal, Colorful, Cornell, CheatSheet)

    fun byId(id: String?): StyleTemplate = all.firstOrNull { it.id == id } ?: Classic
}
