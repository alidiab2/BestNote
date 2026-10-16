package app.bestnote.engine.templates

import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.RoleStyle
import app.bestnote.engine.layout.StyleTemplate

/** The scripts a template picks a font for. */
enum class ScriptKey { Latin, Arabic, Hebrew }

/** Which fonts exist, by script, so a template (or the AI) can only name real ones. */
interface FontOptions {
    /** The ids usable for [script] (bundled, downloaded, the user's own), the first being the default. */
    fun ids(script: ScriptKey): List<String>

    /** Display name for the AI prompt and the UI. */
    fun nameOf(id: String): String

    /** Exact id, or null when it does not exist. */
    fun isKnown(id: String): Boolean = ScriptKey.entries.any { id in ids(it) }

    companion object {
        /** Only the bundled hands (tests, and before the registry has loaded). */
        val Bundled = object : FontOptions {
            override fun ids(script: ScriptKey) = when (script) {
                ScriptKey.Latin -> listOf("caveat", "aref-ruqaa", "system")
                ScriptKey.Arabic -> listOf("aref-ruqaa", "system")
                ScriptKey.Hebrew -> listOf("gveret-levin", "system")
            }
            override fun nameOf(id: String) = id
        }
    }
}

/**
 * Keeps a template inside what the layout can draw well, whoever wrote it (the user's file, the AI):
 * sizes and spacing in sane ranges, colours opaque and not the paper's, fonts that exist (else the
 * script's default), at least a few elements, text lengths bounded. Never throws; reports what it changed.
 */
object TemplateClamp {
    const val MAX_NAME = 60
    const val MAX_NOTE = 800
    const val MIN_ROLE_SCALE = 0.7f
    const val MAX_ROLE_SCALE = 2.2f
    const val MIN_BODY = 0.6f
    const val MAX_BODY = 1.3f
    const val MAX_SPACE_BEFORE = 3

    /** Elements without which a page cannot be written. */
    val required: Set<NoteElement> = setOf(NoteElement.Title, NoteElement.Heading)

    data class Result(val template: NoteTemplate, val adjusted: List<String>)

    fun clamp(t: NoteTemplate, fonts: FontOptions = FontOptions.Bundled): Result {
        val notes = ArrayList<String>()
        fun <T : Comparable<T>> T.fix(lo: T, hi: T, what: String): T = coerceIn(lo, hi).also { if (it != this) notes += what }

        val l = t.look
        fun font(f: HandFont, script: ScriptKey, what: String): HandFont =
            if (f.id in fonts.ids(script)) f else HandFont(fonts.ids(script).first()).also { notes += what }
        val roles = NoteRole.entries.associateWith { r ->
            val s = l.role(r)
            RoleStyle(
                color = s.color?.let { c -> (0xFF000000L or (c and 0xFFFFFF)).also { if (it != c) notes += "color.${r.name}" }.let(::readable) },
                scale = s.scale.fix(MIN_ROLE_SCALE, MAX_ROLE_SCALE, "scale.${r.name}"),
                decoration = s.decoration,
            )
        }
        val look: StyleTemplate = l.copy(
            latinFont = font(l.latinFont, ScriptKey.Latin, "font.latin"),
            arabicFont = font(l.arabicFont, ScriptKey.Arabic, "font.arabic"),
            hebrewFont = font(l.hebrewFont, ScriptKey.Hebrew, "font.hebrew"),
            bodyScale = l.bodyScale.fix(MIN_BODY, MAX_BODY, "bodyScale"),
            spaceBeforeHeading = l.spaceBeforeHeading.fix(0, MAX_SPACE_BEFORE, "spaceBeforeHeading"),
            highlight = 0x66000000L or (l.highlight and 0xFFFFFF),
            roles = roles,
        )
        val ai = t.ai.copy(
            notesLanguage = t.ai.notesLanguage?.let(::language),
            termsLanguage = t.ai.termsLanguage?.let(::language),
            note = t.ai.note.take(MAX_NOTE).also { if (it.length != t.ai.note.length) notes += "ai.note" },
        )
        val elements = (t.elements + required).also { if (it.size != t.elements.size) notes += "elements" }
        val name = t.name.trim().take(MAX_NAME).ifEmpty { "Template" }
        return Result(t.copy(name = name, look = look, ai = ai, elements = elements), notes)
    }

    /** A language tag such as "ar", "he", "en", "fr", "pt-BR" (letters and dashes only, short); else null (= same as the board). */
    private fun language(code: String): String? = code.trim().takeIf { Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?").matches(it) }?.lowercase()

    /** Pure white or near-white text would vanish on the paper: darken it. */
    private fun readable(argb: Long): Long {
        val r = (argb shr 16 and 0xFF).toInt()
        val g = (argb shr 8 and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        val luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
        return if (luminance > 215) 0xFF000000L or (((r * 0.5).toInt() shl 16) or ((g * 0.5).toInt() shl 8) or (b * 0.5).toInt()).toLong() else argb
    }
}
