package app.bestnote.ai.vision

import app.bestnote.engine.layout.NoteElement
import app.bestnote.engine.layout.NoteTemplate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema of the compact element format ([NoteWire]) for one template: `e` is limited to the
 * template's elements and only the fields those elements use are offered, so the schema (sent with
 * every request) and the answer stay small. Same three dialects as [AiSchema].
 */
object NoteSchema {
    fun document(template: NoteTemplate, dialect: AiSchema.Dialect = AiSchema.Dialect.JsonSchema): JsonObject = document(template.elements, template.ai.glossary, dialect)

    fun document(elements: Set<NoteElement>, glossary: Boolean, dialect: AiSchema.Dialect): JsonObject {
        val closed = dialect == AiSchema.Dialect.Closed
        val json = build(elements.sortedBy { it.ordinal }, glossary, closed)
        return if (dialect == AiSchema.Dialect.OpenApi) AiSchema.toOpenApi(json) as JsonObject else json
    }

    private fun build(elements: List<NoteElement>, glossary: Boolean, closed: Boolean): JsonObject {
        fun obj(props: List<Pair<String, JsonElement>>, required: List<String>) = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            if (closed) put("additionalProperties", false)
        }
        val has = elements.toSet()
        fun any(vararg e: NoteElement) = e.any { it in has }
        val strArr = arr(str())
        fun element(nested: Boolean): JsonObject {
            val names = elements.filter { !nested || it != NoteElement.Columns }.map { it.name }
            val props = ArrayList<Pair<String, JsonElement>>()
            props += "e" to enumStr(names)
            props += "t" to str()
            if (any(NoteElement.Definition, NoteElement.Theorem, NoteElement.Example, NoteElement.KeyResult, NoteElement.StepSolution, NoteElement.Diagram, NoteElement.WorkedExample, NoteElement.Summary)) props += "h" to str()
            if (any(NoteElement.Formula, NoteElement.KeyResult, NoteElement.NumberedFormula)) props += "m" to str()
            if (any(NoteElement.Bullets, NoteElement.Summary)) props += "items" to strArr
            if (any(NoteElement.StepSolution, NoteElement.Derivation, NoteElement.Example)) {
                props += "steps" to arr(obj(listOf("s" to str(), "m" to str(), "why" to str()), emptyList()))
            }
            if (any(NoteElement.Table, NoteElement.Comparison, NoteElement.VariableLegend, NoteElement.WorkedExample)) props += "rows" to arr(strArr)
            if (NoteElement.Glossary in has) props += "terms" to arr(strArr)
            if (NoteElement.MarginRemark in has && !nested) props += "target" to int()
            if (any(NoteElement.NumberedFormula, NoteElement.Derivation)) props += "n" to int()
            if (NoteElement.WorkedExample in has) props += "parts" to arr(obj(listOf("q" to str(), "s" to strArr, "r" to str()), emptyList()))
            if (NoteElement.Diagram in has) {
                props += "fbd" to arr(arr(str()))
                props += "flow" to obj(listOf("nodes" to strArr, "edges" to arr(arr(str()))), emptyList())
                props += "axes" to obj(listOf("x" to str(), "y" to str(), "pts" to arr(arr(num()))), emptyList())
            }
            return obj(props, listOf("e"))
        }
        val el = element(nested = false)
        val withCols = if (NoteElement.Columns in has) {
            val inner = element(nested = true)
            JsonObject(el + ("properties" to JsonObject((el["properties"] as JsonObject) + ("cols" to arr(arr(inner))))))
        } else {
            el
        }
        val root = ArrayList<Pair<String, JsonElement>>()
        root += "elements" to arr(withCols)
        if (glossary) root += "terms" to arr(strArr)
        return obj(root, listOf("elements"))
    }

    private fun type(t: String) = buildJsonObject { put("type", t) }
    private fun str() = type("string")
    private fun int() = type("integer")
    private fun num() = type("number")
    private fun enumStr(values: List<String>) = buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
    }
    private fun arr(items: JsonElement) = buildJsonObject {
        put("type", "array")
        put("items", items)
    }
}
