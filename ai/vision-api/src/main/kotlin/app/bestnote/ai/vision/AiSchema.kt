package app.bestnote.ai.vision

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema of the provider-neutral [AiDocument], shared by every provider's structured-output
 * mode.
 *
 * Every block **requires** `type`, `order` and `text`. With everything optional (the first cut),
 * constrained decoders were free to emit `{"type":"heading"}` with no words at all, and
 * [DocumentMapper] then dropped every block ("Nothing on this photo could be read"). `text` is the
 * block's words in plain form, so the mapper can always salvage something even when the
 * type-specific field (`latex`, `items`, `steps`) is missing.
 */
object AiSchema {
    enum class Dialect {
        /** Plain JSON Schema (Gemini `responseJsonSchema`). */
        JsonSchema,

        /** JSON Schema with `additionalProperties: false` on every object (Claude, OpenAI). */
        Closed,

        /** The OpenAPI subset of Gemini's older `responseSchema`: upper-case types, `propertyOrdering`. */
        OpenApi,
    }

    val BLOCK_TYPES = listOf("heading", "definition", "paragraph", "bullets", "callout", "math", "proof", "margin_note", "diagram")
    val SHAPE_KINDS = listOf("line", "arrow", "rect", "ellipse", "polyline", "polygon", "path", "label")
    val BLOCK_REQUIRED = listOf("type", "order", "text")

    private val json: JsonObject by lazy { build(closed = false) }
    private val closed: JsonObject by lazy { build(closed = true) }
    private val openApi: JsonObject by lazy { toOpenApi(json) as JsonObject }

    fun document(dialect: Dialect = Dialect.JsonSchema): JsonObject = when (dialect) {
        Dialect.JsonSchema -> json
        Dialect.Closed -> closed
        Dialect.OpenApi -> openApi
    }

    private fun build(closed: Boolean): JsonObject {
        fun obj(vararg props: Pair<String, JsonElement>, required: List<String>, description: String? = null) = buildJsonObject {
            put("type", "object")
            description?.let { put("description", it) }
            putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            if (closed) put("additionalProperties", false)
        }

        val step = obj(
            "latex" to str("The step as LaTeX, or \"\" when it is words only."),
            "text" to str("The step in words, or \"\" when it is maths only."),
            "because" to str("Why this step follows, when the board gives it."),
            required = listOf("latex", "text"),
        )
        val shape = obj(
            "kind" to enumStr(SHAPE_KINDS),
            "points" to arr(num(), "Flat list [x0,y0,x1,y1,…] in the diagram's size box; rect/ellipse: two corners."),
            "rotation" to num(),
            "d" to str(),
            "text" to str(),
            "latex" to bool(),
            "dashed" to bool(),
            "color" to str(),
            required = listOf("kind", "points"),
        )
        val diagram = obj(
            "size" to arr(num(), "[width, height] of the box the shape coordinates use."),
            "shapes" to arr(shape),
            required = listOf("size", "shapes"),
        )
        val block = obj(
            "type" to enumStr(BLOCK_TYPES),
            "order" to int("Position in the teaching sequence: 0, 1, 2…"),
            "text" to str(
                "The block's words as plain text, always filled: heading text, definition body, paragraph, " +
                    "bullet lines joined by newlines, a formula as written, or a one-line caption for a diagram.",
            ),
            "id" to str(),
            "role" to enumStr(listOf("title", "cues", "main", "summary")),
            "level" to int(),
            "title" to str(),
            "items" to arr(str()),
            "tone" to enumStr(listOf("info", "definition", "theorem", "key_result", "warning", "example", "summary")),
            "latex" to str(),
            "boxed" to bool(),
            "steps" to arr(step),
            "annotates" to str(),
            "semantic" to enumStr(listOf("regular", "essential", "annotation", "emphasis")),
            "ink_color" to str(),
            "diagram" to diagram,
            "source_box" to arr(num(), "[x0,y0,x1,y1] normalised 0..1 in the photo."),
            required = BLOCK_REQUIRED,
        )
        return obj(
            "title" to str("The lecture's main topic."),
            "blocks" to arr(block),
            required = listOf("title", "blocks"),
        )
    }

    private fun type(t: String, description: String? = null) = buildJsonObject {
        put("type", t)
        description?.let { put("description", it) }
    }
    private fun str(description: String? = null) = type("string", description)
    private fun int(description: String? = null) = type("integer", description)
    private fun num() = type("number")
    private fun bool() = type("boolean")
    private fun enumStr(values: List<String>) = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }
    private fun arr(items: JsonElement, description: String? = null) = buildJsonObject {
        put("type", "array")
        description?.let { put("description", it) }
        put("items", items)
    }

    /**
     * JSON Schema → the OpenAPI subset Gemini's `responseSchema` takes: upper-case types and an
     * explicit `propertyOrdering` (otherwise properties are generated alphabetically, so `type` and
     * `text` would come after everything else).
     */
    fun toOpenApi(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            buildMap {
                element.forEach { (k, v) ->
                    when {
                        k == "additionalProperties" -> Unit
                        k == "type" && v is JsonPrimitive && v.isString -> put(k, JsonPrimitive(v.content.uppercase()))
                        else -> put(k, toOpenApi(v))
                    }
                }
                val props = element["properties"] as? JsonObject
                if (props != null) put("propertyOrdering", JsonArray(props.keys.map(::JsonPrimitive)))
            },
        )
        is JsonArray -> JsonArray(element.map(::toOpenApi))
        else -> element
    }
}
