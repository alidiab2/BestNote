package app.bestnote.ai.vision.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Which structured-output field carries the schema. */
enum class SchemaField {
    JsonSchema,
    OpenApi,

    /** JSON mode without a schema: the last resort when an endpoint rejects both schema fields. */
    None,
}

/** Builds `generateContent` request bodies. Pure: no key, no I/O. */
object GeminiRequests {
    fun generateContent(
        system: String,
        user: String,
        image: ByteArray,
        mimeType: String,
        temperature: Double,
        schemaField: SchemaField = SchemaField.JsonSchema,
        maxOutputTokens: Int = GeminiConfig.MAX_OUTPUT_TOKENS,
    ): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", system.trim()) } }
        }
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") {
                    addJsonObject {
                        putJsonObject("inlineData") {
                            put("mimeType", mimeType)
                            put("data", Base64.getEncoder().encodeToString(image))
                        }
                    }
                    addJsonObject { put("text", user) }
                }
            }
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            when (schemaField) {
                SchemaField.JsonSchema -> put("responseJsonSchema", GeminiSchema.document)
                SchemaField.OpenApi -> put("responseSchema", GeminiSchema.openApi)
                SchemaField.None -> Unit
            }
            put("temperature", temperature)
            // Explicit, so a dense board is not cut off by a lower server default; thinking shares it.
            put("maxOutputTokens", maxOutputTokens)
        }
    }

    /** JPEG / PNG / WebP by magic number, so a PNG from the scanner is labelled correctly. */
    fun sniffMime(bytes: ByteArray, fallback: String): String = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> fallback
    }
}
