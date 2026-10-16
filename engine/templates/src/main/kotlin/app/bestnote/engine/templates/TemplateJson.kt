package app.bestnote.engine.templates

import app.bestnote.core.model.PaperTemplate
import app.bestnote.engine.layout.AiInstructions
import app.bestnote.engine.layout.Decoration
import app.bestnote.engine.layout.DetailLevel
import app.bestnote.engine.layout.HandFont
import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteRole
import app.bestnote.engine.layout.NoteTemplate
import app.bestnote.engine.layout.RoleStyle
import app.bestnote.engine.layout.RuledPaper
import app.bestnote.engine.layout.StyleTemplate
import app.bestnote.engine.layout.PageLayout
import app.bestnote.engine.layout.Density
import app.bestnote.engine.layout.TermsStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The JSON form of a [NoteTemplate]: what user templates are stored as, what a template file holds, and
 * the shape "Design with AI" speaks. It has the three parts of the owner's design:
 *
 * ```
 * { "name": "...",
 *   "look": { "fonts": {"latin","arabic","hebrew"}, "bodyScale", "spaceBeforeHeading", "marginArrows", "layout" (flow|packed), "density" (airy|normal|dense),
 *             "highlight": "#RRGGBB", "paper": null | "Ruled"|…,
 *             "roles": { "title": {"color": "#RRGGBB"|null, "scale", "decoration"}, "heading": …, "body": … } },
 *   "ai":   { "notesLanguage": null|"ar"|"he"|"en"|…, "termsLanguage", "termsStyle", "detail",
 *             "stepByStep", "glossary", "note" },
 *   "elements": ["Title","Heading",…] }
 * ```
 *
 * Decoding is forgiving and layered on a base template: anything missing keeps the base's value, unknown
 * keys are ignored. Values are NOT range-checked here; run [TemplateClamp] on the result.
 */
object TemplateJson {
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** JSON keys of the roles, in reading order. */
    val roleKeys: Map<NoteRole, String> = NoteRole.entries.associateWith { it.name.replaceFirstChar(Char::lowercase) }

    fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

    fun encode(t: NoteTemplate, includeId: Boolean = true): JsonObject = buildJsonObject {
        if (includeId) put("id", t.id)
        put("name", t.name)
        putJsonObject("look") {
            val l = t.look
            putJsonObject("fonts") {
                put("latin", l.latinFont.id)
                put("arabic", l.arabicFont.id)
                put("hebrew", l.hebrewFont.id)
            }
            put("bodyScale", l.bodyScale.toDouble())
            put("spaceBeforeHeading", l.spaceBeforeHeading)
            put("marginArrows", l.marginArrows)
            put("layout", l.layout.name.lowercase())
            put("density", l.density.name.lowercase())
            put("highlight", hex(l.highlight))
            put("paper", l.paper?.name?.let(::JsonPrimitive) ?: JsonNull)
            putJsonObject("roles") {
                NoteRole.entries.forEach { r ->
                    val s = l.role(r)
                    putJsonObject(roleKeys.getValue(r)) {
                        put("color", s.color?.let { JsonPrimitive(hex(it)) } ?: JsonNull)
                        put("scale", s.scale.toDouble())
                        put("decoration", s.decoration.name.replaceFirstChar(Char::lowercase))
                    }
                }
            }
        }
        putJsonObject("ai") {
            val a = t.ai
            put("notesLanguage", a.notesLanguage?.let(::JsonPrimitive) ?: JsonNull)
            put("termsLanguage", a.termsLanguage?.let(::JsonPrimitive) ?: JsonNull)
            put("termsStyle", a.termsStyle.name.replaceFirstChar(Char::lowercase))
            put("detail", a.detail.name.replaceFirstChar(Char::lowercase))
            put("stepByStep", a.stepByStep)
            put("glossary", a.glossary)
            put("note", a.note)
        }
        putJsonArray("elements") { NoteElement.entries.filter { it in t.elements }.forEach { add(JsonPrimitive(it.name)) } }
    }

    fun encodeToString(t: NoteTemplate, includeId: Boolean = true): String = pretty.encodeToString(JsonObject.serializer(), encode(t, includeId))

    /** Reads [text]; null when it is not a JSON object. [base] supplies everything the JSON leaves out. */
    fun decode(text: String, base: NoteTemplate): NoteTemplate? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()?.let { decode(it, base) }

    fun decode(o: JsonObject, base: NoteTemplate): NoteTemplate {
        val look = (o["look"] as? JsonObject)
        val ai = (o["ai"] as? JsonObject)
        val name = o.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: base.name
        val newLook = if (look == null) base.look else decodeLook(look, base.look)
        val newAi = if (ai == null) base.ai else decodeAi(ai, base.ai)
        val elements = (o["elements"] as? JsonArray)?.let { arr ->
            arr.mapNotNull { e -> (e as? JsonPrimitive)?.contentOrNull?.let { n -> NoteElement.entries.firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } } }.toSet()
        } ?: base.elements
        return base.copy(id = o.str("id")?.takeIf { it.isNotBlank() } ?: base.id, name = name, look = newLook.copy(id = o.str("id")?.takeIf { it.isNotBlank() } ?: base.id), ai = newAi, elements = elements)
    }

    private fun decodeLook(l: JsonObject, base: StyleTemplate): StyleTemplate {
        val fonts = l["fonts"] as? JsonObject
        val roles = l["roles"] as? JsonObject
        val paper = if (l.containsKey("paper")) l.str("paper")?.let { n -> RuledPaper.writable.firstOrNull { it.name.equals(n, ignoreCase = true) } } else base.paper
        return base.copy(
            paper = paper,
            latinFont = fonts?.str("latin")?.let(::HandFont) ?: base.latinFont,
            arabicFont = fonts?.str("arabic")?.let(::HandFont) ?: base.arabicFont,
            hebrewFont = fonts?.str("hebrew")?.let(::HandFont) ?: base.hebrewFont,
            bodyScale = l.num("bodyScale")?.toFloat() ?: base.bodyScale,
            spaceBeforeHeading = l.num("spaceBeforeHeading")?.toInt() ?: base.spaceBeforeHeading,
            marginArrows = l.bool("marginArrows") ?: base.marginArrows,
            layout = l.str("layout")?.let { v -> PageLayout.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) } } ?: base.layout,
            density = l.str("density")?.let { v -> Density.entries.firstOrNull { it.name.equals(v.trim(), ignoreCase = true) } } ?: base.density,
            highlight = l.str("highlight")?.let(::parseColor)?.let { 0x66000000L or (it and 0xFFFFFF) } ?: base.highlight,
            roles = if (roles == null) base.roles else NoteRole.entries.associateWith { r ->
                val prev = base.role(r)
                val ro = roles[roleKeys.getValue(r)] as? JsonObject ?: return@associateWith prev
                RoleStyle(
                    color = if (ro.containsKey("color")) ro.str("color").let { c -> if (c == null) null else parseColor(c) ?: prev.color } else prev.color,
                    scale = ro.num("scale")?.toFloat() ?: prev.scale,
                    decoration = ro.str("decoration")?.let { d -> Decoration.entries.firstOrNull { it.name.equals(d, ignoreCase = true) } } ?: prev.decoration,
                )
            },
        )
    }

    private fun decodeAi(a: JsonObject, base: AiInstructions): AiInstructions = AiInstructions(
        notesLanguage = if (a.containsKey("notesLanguage")) a.str("notesLanguage")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("board", true) } else base.notesLanguage,
        termsLanguage = if (a.containsKey("termsLanguage")) a.str("termsLanguage")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", true) } else base.termsLanguage,
        termsStyle = a.str("termsStyle")?.let { v -> TermsStyle.entries.firstOrNull { it.name.equals(v, true) } } ?: base.termsStyle,
        detail = a.str("detail")?.let { v -> DetailLevel.entries.firstOrNull { it.name.equals(v, true) } } ?: base.detail,
        stepByStep = a.bool("stepByStep") ?: base.stepByStep,
        glossary = a.bool("glossary") ?: base.glossary,
        note = a.str("note") ?: base.note,
    )

    /** "#RGB", "#RRGGBB", "#AARRGGBB" (with or without #) → opaque ARGB; null when it is not a colour. */
    fun parseColor(text: String): Long? {
        val h = text.trim().removePrefix("#")
        val v = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("").toLongOrNull(16)
            6 -> h.toLongOrNull(16)
            8 -> h.toLongOrNull(16)?.and(0xFFFFFF)
            else -> null
        } ?: return null
        return 0xFF000000L or v
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() }
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
}
