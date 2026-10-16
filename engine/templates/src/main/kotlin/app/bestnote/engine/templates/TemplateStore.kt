package app.bestnote.engine.templates

import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NoteTemplates
import java.io.File

/**
 * The user's templates, one JSON file each in [dir] (`<id>.json`), next to the built-ins. Additive: a file
 * this version cannot read is skipped, never deleted, and unknown keys survive a round trip through newer
 * apps because the file is only rewritten when the user saves that template. Built-ins are read-only:
 * [duplicate] makes an editable copy. Pure JVM (java.io), so it is tested without a device.
 */
class TemplateStore(private val dir: File, private val fonts: () -> FontOptions = { FontOptions.Bundled }) {
    /** Built-ins first, then the user's templates by name. */
    fun all(): List<NoteTemplate> = NoteTemplates.builtIns() + mine()

    fun mine(): List<NoteTemplate> = dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { read(f) }.getOrNull() }
        .sortedBy { it.name.lowercase() }

    /** The template with [id], or a built-in fallback (the first) when it no longer exists. */
    fun byId(id: String?): NoteTemplate = find(id) ?: NoteTemplates.byId(null)

    fun find(id: String?): NoteTemplate? {
        if (id == null) return null
        NoteTemplates.builtIns().firstOrNull { it.id == id }?.let { return it }
        if (!safeId(id)) return null
        val f = File(dir, "$id.json")
        return if (f.isFile) runCatching { read(f) }.getOrNull() else null
    }

    /** Saves a user template (built-ins and invalid ids are refused). Values are clamped; returns what was stored. */
    fun save(template: NoteTemplate): NoteTemplate {
        require(!template.builtIn && NoteTemplates.builtIns().none { it.id == template.id }) { "built-in templates are read-only; duplicate first" }
        require(safeId(template.id)) { "bad template id" }
        val clean = TemplateClamp.clamp(template.copy(builtIn = false), fonts()).template
        dir.mkdirs()
        val target = File(dir, "${clean.id}.json")
        val tmp = File(dir, "${clean.id}.json.part")
        tmp.writeText(TemplateJson.encodeToString(clean))
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not save the template" }
        }
        return clean
    }

    fun delete(id: String): Boolean = safeId(id) && NoteTemplates.builtIns().none { it.id == id } && File(dir, "$id.json").delete()

    fun rename(id: String, name: String): NoteTemplate? = find(id)?.takeIf { !it.builtIn }?.let { save(it.copy(name = name)) }

    /** An editable copy of any template (built-in or mine) under a new id, named "<name> copy". */
    fun duplicate(id: String): NoteTemplate? {
        val src = find(id) ?: return null
        val names = all().map { it.name }.toSet()
        var name = src.name + " copy"
        var n = 2
        while (name in names) name = src.name + " copy " + n++
        val copy = src.copy(id = newId(), name = name, builtIn = false, look = src.look.copy(id = "tmp"))
        return save(copy.copy(look = copy.look.copy(id = copy.id)))
    }

    /** A fresh user template: the first built-in's look, named [name]. */
    fun create(name: String, from: NoteTemplate = NoteTemplates.byId(null)): NoteTemplate {
        val id = newId()
        return save(from.copy(id = id, name = name, builtIn = false, look = from.look.copy(id = id)))
    }

    /** Imports a template file as a NEW user template (see [TemplateExchange.import]). */
    fun import(text: String): NoteTemplate {
        val t = TemplateExchange.import(text, fonts())
        val id = newId()
        var name = t.name
        val names = all().map { it.name }.toSet()
        var n = 2
        while (name in names) name = t.name + " " + n++
        return save(t.copy(id = id, name = name, look = t.look.copy(id = id)))
    }

    fun newId(): String {
        var n = (dir.listFiles().orEmpty().mapNotNull { it.name.removePrefix("user-").removeSuffix(".json").toIntOrNull() }.maxOrNull() ?: 0) + 1
        while (File(dir, "user-$n.json").exists()) n++
        return "user-$n"
    }

    private fun read(f: File): NoteTemplate {
        val id = f.nameWithoutExtension
        val base = NoteTemplates.byId(null).let { it.copy(id = id, name = id, builtIn = false, look = it.look.copy(id = id)) }
        return TemplateJson.decode(f.readText(), base)?.let { it.copy(id = id, look = it.look.copy(id = id), builtIn = false) }
            ?: error("unreadable template ${f.name}")
    }

    companion object {
        fun safeId(id: String) = id.isNotEmpty() && id.length <= 40 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
