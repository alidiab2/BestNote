package app.bestnote.engine.templates

import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.NoteTemplate

/** One visible difference between two templates. [key] is stable (the UI localises it); [from]/[to] are short values. */
data class TemplateChangeItem(val key: String, val from: String, val to: String)

/** What changed between two versions of a template, for "show what changed" after the AI edits one. */
object TemplateDiff {
    fun between(a: NoteTemplate, b: NoteTemplate): List<TemplateChangeItem> = buildList {
        fun add(key: String, from: Any?, to: Any?) {
            if (from != to) add(TemplateChangeItem(key, from?.toString() ?: "–", to?.toString() ?: "–"))
        }
        add("name", a.name, b.name)
        add("font.latin", a.look.latinFont.id, b.look.latinFont.id)
        add("font.arabic", a.look.arabicFont.id, b.look.arabicFont.id)
        add("font.hebrew", a.look.hebrewFont.id, b.look.hebrewFont.id)
        add("equations", a.look.equationStyle.name, b.look.equationStyle.name)
        add("bodyScale", "%.2f".format(a.look.bodyScale), "%.2f".format(b.look.bodyScale))
        add("spaceBeforeHeading", a.look.spaceBeforeHeading, b.look.spaceBeforeHeading)
        add("marginArrows", a.look.marginArrows, b.look.marginArrows)
        add("layout", a.look.layout, b.look.layout)
        add("density", a.look.density, b.look.density)
        add("paper", a.look.paper?.name, b.look.paper?.name)
        add("highlight", TemplateJson.hex(a.look.highlight), TemplateJson.hex(b.look.highlight))
        NoteRole.entries.forEach { r ->
            val x = a.look.role(r)
            val y = b.look.role(r)
            add("color.${r.name}", x.color?.let(TemplateJson::hex) ?: "pen", y.color?.let(TemplateJson::hex) ?: "pen")
            add("scale.${r.name}", "%.2f".format(x.scale), "%.2f".format(y.scale))
            add("decoration.${r.name}", x.decoration, y.decoration)
        }
        add("ai.notesLanguage", a.ai.notesLanguage ?: "board", b.ai.notesLanguage ?: "board")
        add("ai.termsLanguage", a.ai.termsLanguage ?: "none", b.ai.termsLanguage ?: "none")
        add("ai.termsStyle", a.ai.termsStyle, b.ai.termsStyle)
        add("ai.detail", a.ai.detail, b.ai.detail)
        add("ai.stepByStep", a.ai.stepByStep, b.ai.stepByStep)
        add("ai.glossary", a.ai.glossary, b.ai.glossary)
        add("ai.note", a.ai.note.take(40), b.ai.note.take(40))
        val added = (b.elements - a.elements).map(NoteElement::name).sorted()
        val removed = (a.elements - b.elements).map(NoteElement::name).sorted()
        if (added.isNotEmpty()) add(TemplateChangeItem("elements.added", "", added.joinToString(", ")))
        if (removed.isNotEmpty()) add(TemplateChangeItem("elements.removed", "", removed.joinToString(", ")))
    }
}
