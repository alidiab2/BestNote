package app.bestnote.ai.vision.claude

import app.bestnote.ai.vision.AiSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Where requests go and how patient we are. Default: Anthropic's endpoint with the user's own key. */
data class ClaudeConfig(
    val baseUrl: String = "https://api.anthropic.com/",
    /** Non-streaming; a dense board with adaptive thinking can take a while. */
    val timeoutSeconds: Long = 240,
    val retryDelayMs: Long = 1_500,
    /** Non-streaming ceiling that stays well inside HTTP timeouts and fits a full board. */
    val maxTokens: Int = 16_000,
) {
    companion object {
        /**
         * Last-resort id when the key's live model list (`GET /v1/models`) is unavailable: the
         * current Opus, the pro-class pick for board reconstruction.
         */
        const val DEFAULT_MODEL = "claude-opus-5-5"

        const val API_VERSION = "2023-06-01"
        const val KEY_HEADER = "x-api-key"

        /** Server-side refusal fallback, `"default"` form (routes by refusal category). */
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        /** Models that accept `fallbacks: "default"` on the Claude API. */
        val FALLBACK_MODELS = setOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-opus-5", "claude-fable-5-1")
    }
}

/** Builds Messages API request bodies. Pure: no key, no I/O. */
object ClaudeRequests {
    /**
     * One user turn with the image first, then the instruction; the expert-student prompt as
     * `system`; JSON constrained by `output_config.format` ([structured] false drops it for an
     * endpoint or model that rejects it). Thinking is left at the model default (adaptive).
     */
    fun messages(
        model: String,
        system: String,
        user: String,
        image: ByteArray,
        mimeType: String,
        maxTokens: Int,
        structured: Boolean = true,
        fallbacks: Boolean = model in ClaudeConfig.FALLBACK_MODELS,
        schema: JsonObject = AiSchema.document(AiSchema.Dialect.Closed),
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", maxTokens)
        put("system", system.trim())
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "image")
                        putJsonObject("source") {
                            put("type", "base64")
                            put("media_type", mimeType)
                            put("data", Base64.getEncoder().encodeToString(image))
                        }
                    }
                    addJsonObject {
                        put("type", "text")
                        put("text", user)
                    }
                }
            }
        }
        if (structured) {
            putJsonObject("output_config") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("schema", schema)
                }
            }
        }
        if (fallbacks) put("fallbacks", "default")
    }

    /** Request headers; the key only ever travels in [ClaudeConfig.KEY_HEADER]. */
    fun headers(key: String, fallbacks: Boolean): Map<String, String> = buildMap {
        put(ClaudeConfig.KEY_HEADER, key)
        put("anthropic-version", ClaudeConfig.API_VERSION)
        if (fallbacks) put("anthropic-beta", ClaudeConfig.FALLBACK_BETA)
    }

    /** JPEG / PNG / WebP / GIF by magic number; Claude accepts these four. */
    fun sniffMime(bytes: ByteArray, fallback: String): String = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "GIF8" -> "image/gif"
        else -> fallback
    }
}
