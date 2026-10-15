package app.bestnote.ai.vision.gemini

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema of the provider-neutral [app.bestnote.ai.vision.AiDocument], sent as Gemini's
 * `responseJsonSchema` so the model is constrained to it (JSON mode + response schema).
 * Mirrors `Schema.kt`: everything optional except block `type`, shape `kind` and diagram `size`.
 */
object GeminiSchema {
    val document: JsonObject by lazy {
        obj(
            "title" to str(),
            "blocks" to arr(block()),
            required = emptyList(),
        )
    }

    private fun block() = obj(
        "type" to enumStr("heading", "definition", "paragraph", "bullets", "callout", "math", "proof", "margin_note", "diagram"),
        "id" to str(),
        "order" to int(),
        "role" to enumStr("title", "cues", "main", "summary"),
        "level" to int(),
        "title" to str(),
        "text" to str(),
        "items" to arr(str()),
        "tone" to enumStr("info", "definition", "theorem", "key_result", "warning", "example", "summary"),
        "latex" to str(),
        "boxed" to bool(),
        "steps" to arr(obj("latex" to str(), "text" to str(), "because" to str(), required = emptyList())),
        "annotates" to str(),
        "semantic" to enumStr("regular", "essential", "annotation", "emphasis"),
        "ink_color" to str(),
        "diagram" to obj(
            "size" to arr(num()),
            "shapes" to arr(shape()),
            required = listOf("size"),
        ),
        "source_box" to arr(num()),
        required = listOf("type"),
    )

    private fun shape() = obj(
        "kind" to enumStr("line", "arrow", "rect", "ellipse", "polyline", "polygon", "path", "label"),
        "points" to arr(num()),
        "rotation" to num(),
        "d" to str(),
        "text" to str(),
        "latex" to bool(),
        "dashed" to bool(),
        "color" to str(),
        required = listOf("kind"),
    )

    private fun type(t: String) = buildJsonObject { put("type", t) }
    private fun str() = type("string")
    private fun int() = type("integer")
    private fun num() = type("number")
    private fun bool() = type("boolean")
    private fun enumStr(vararg values: String) = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }
    private fun arr(items: JsonElement) = buildJsonObject {
        put("type", "array")
        put("items", items)
    }
    private fun obj(vararg props: Pair<String, JsonElement>, required: List<String>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

    /** JSON Schema → the OpenAPI subset the older `responseSchema` field takes (upper-case types). */
    fun toOpenApi(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.mapValues { (k, v) ->
                if (k == "type" && v is JsonPrimitive && v.isString) JsonPrimitive(v.content.uppercase()) else toOpenApi(v)
            },
        )
        is JsonArray -> JsonArray(element.map(::toOpenApi))
        else -> element
    }
}
