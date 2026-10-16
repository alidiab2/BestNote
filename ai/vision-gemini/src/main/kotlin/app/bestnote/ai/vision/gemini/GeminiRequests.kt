package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.AiSchema

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

/**
 * How much the model may think before answering. Low thinking reads a board almost as well, much
 * faster and with far fewer tokens. Newer models take `thinkingLevel`, 2.5 models a token budget;
 * the provider steps down on a 400.
 */
enum class Thinking { Level, Budget, None }

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
        thinking: Thinking = Thinking.None,
        /** The response schema in a dialect: the old blocks ([AiSchema]) or a template's elements ([app.bestnote.ai.vision.NoteSchema]). */
        schema: (AiSchema.Dialect) -> JsonObject = { AiSchema.document(it) },
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
                SchemaField.JsonSchema -> put("responseJsonSchema", schema(AiSchema.Dialect.JsonSchema))
                SchemaField.OpenApi -> put("responseSchema", schema(AiSchema.Dialect.OpenApi))
                SchemaField.None -> Unit
            }
            put("temperature", temperature)
            // Explicit, so a dense board is not cut off by a lower server default; thinking shares it.
            put("maxOutputTokens", maxOutputTokens)
            when (thinking) {
                Thinking.Level -> putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
                Thinking.Budget -> putJsonObject("thinkingConfig") { put("thinkingBudget", LOW_THINKING_BUDGET) }
                Thinking.None -> Unit
            }
        }
    }

    /** A text-only conversation (no image, no schema): JSON mode when [json]. */
    fun chat(
        system: String,
        turns: List<app.bestnote.ai.vision.ChatTurn>,
        temperature: Double,
        maxOutputTokens: Int,
        thinking: Thinking,
        json: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { addJsonObject { put("text", system.trim()) } }
        }
        putJsonArray("contents") {
            turns.forEach { t ->
                addJsonObject {
                    put("role", if (t.fromUser) "user" else "model")
                    putJsonArray("parts") { addJsonObject { put("text", t.text) } }
                }
            }
        }
        putJsonObject("generationConfig") {
            if (json) put("responseMimeType", "application/json")
            put("temperature", temperature)
            put("maxOutputTokens", maxOutputTokens)
            when (thinking) {
                Thinking.Level -> putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
                Thinking.Budget -> putJsonObject("thinkingConfig") { put("thinkingBudget", LOW_THINKING_BUDGET) }
                Thinking.None -> Unit
            }
        }
    }

    /** Tokens of thinking for 2.5-style models: enough to lay out a board, not to over-think it. */
    const val LOW_THINKING_BUDGET = 1_024

    /** JPEG / PNG / WebP by magic number, so a PNG from the scanner is labelled correctly. */
    fun sniffMime(bytes: ByteArray, fallback: String): String = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> fallback
    }
}
