package app.bestnote.engine.templates

import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.NoteTemplates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Why a template file could not be imported. */
class TemplateImportException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NotATemplate, TooLarge, TooNew }
}

/**
 * Template files (`*.bntemplate.json`): `{"bestnoteTemplate": 1, "template": {…}}` so a template can be shared
 * by any file transfer. Import never trusts the file: it is size-limited, decoded on top of a safe base, clamped
 * (fonts the device lacks fall back to the script's default) and always becomes a NEW user template.
 */
object TemplateExchange {
    const val EXTENSION = ".bntemplate.json"
    const val MIME = "application/json"
    const val MAX_BYTES = 256 * 1024
    private const val MARKER = "bestnoteTemplate"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun fileName(t: NoteTemplate): String = t.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').ifEmpty { "template" }.take(40) + EXTENSION

    fun export(t: NoteTemplate): String {
        val o = buildJsonObject {
            put(MARKER, TemplateJson.VERSION)
            put("template", TemplateJson.encode(t, includeId = false))
        }
        return Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonObject.serializer(), o)
    }

    /**
     * Parses a template file. The result has NO usable id yet (give it one with [TemplateStore.newId]) and
     * is flagged as user-made. [fonts] decides which font ids exist on this device.
     */
    fun import(text: String, fonts: FontOptions = FontOptions.Bundled): NoteTemplate {
        if (text.length > MAX_BYTES) throw TemplateImportException(TemplateImportException.Kind.TooLarge, "over $MAX_BYTES")
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw TemplateImportException(TemplateImportException.Kind.NotATemplate, "not JSON")
        val version = (root[MARKER] as? JsonPrimitive)?.intOrNull
            ?: throw TemplateImportException(TemplateImportException.Kind.NotATemplate, "missing $MARKER")
        if (version > TemplateJson.VERSION) throw TemplateImportException(TemplateImportException.Kind.TooNew, "version $version")
        val body = root["template"] as? JsonObject ?: throw TemplateImportException(TemplateImportException.Kind.NotATemplate, "missing template")
        val base = NoteTemplates.byId(null).let { it.copy(id = "imported", name = "Imported", builtIn = false, look = it.look.copy(id = "imported")) }
        // Ids in a file are the sender's: drop them.
        val decoded = TemplateJson.decode(JsonObject(body - "id"), base)
        return TemplateClamp.clamp(decoded.copy(builtIn = false), fonts).template
    }
}
