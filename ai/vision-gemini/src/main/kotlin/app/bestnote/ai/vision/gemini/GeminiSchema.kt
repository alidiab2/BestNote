package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.AiSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The provider-neutral [AiSchema] as Gemini takes it: plain JSON Schema for `responseJsonSchema`,
 * the OpenAPI subset (upper-case types, `propertyOrdering`) for the older `responseSchema`.
 */
object GeminiSchema {
    val document: JsonObject get() = AiSchema.document(AiSchema.Dialect.JsonSchema)
    val openApi: JsonObject get() = AiSchema.document(AiSchema.Dialect.OpenApi)

    fun toOpenApi(element: JsonElement): JsonElement = AiSchema.toOpenApi(element)
}
