package app.bestnote.ai.vision.openai

import app.bestnote.ai.vision.AiSchema
import app.bestnote.ai.vision.Prompts
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/** Where requests go and how patient we are. */
data class OpenAiConfig(
    /** OpenAI itself; an OpenAI-compatible endpoint passes its own base URL through the credentials. */
    val baseUrl: String = OPENAI_BASE_URL,
    val timeoutSeconds: Long = 240,
    val retryDelayMs: Long = 1_500,
    val maxTokens: Int = 16_000,
    /** Thinking models: "low" answers a board well at a fraction of the time and tokens of the default. */
    val reasoningEffort: String = "low",
    /** Sent to compatible endpoints only; OpenAI's reasoning models accept only their default. */
    val temperature: Double = 0.2,
) {
    companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com/v1/"

        /** [AiProviderKind.OpenRouter][app.bestnote.ai.vision.AiProviderKind.OpenRouter]: the user's own OpenRouter account. */
        const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1/"

        /**
         * Servers whose models think unless told not to, and accept `thinking: {type: disabled}`.
         * DeepSeek ignored `reasoning_effort: low` (owner's key: 4,399 of 5,392 output tokens were
         * thinking); disabled, the same board reads just as well at a fraction of the cost and time.
         */
        fun turnsThinkingOff(baseUrl: String) = "api.deepseek.com" in baseUrl

        /** Last-resort id when the key's live model list is unavailable (newest flagship, 2026-10). */
        const val DEFAULT_MODEL = "gpt-5.6-terra"

        /** Base URLs offered for "OpenAI-compatible" (the user can type any other). */
        val COMPATIBLE_PRESETS = linkedMapOf(
            "OpenRouter" to OPENROUTER_BASE_URL,
            "Groq" to "https://api.groq.com/openai/v1/",
            "DeepSeek" to "https://api.deepseek.com/v1/",
            "Ollama" to "http://localhost:11434/v1/",
        )
    }
}

/** How the answer is constrained; endpoints differ, so the provider steps down on a 400. */
enum class ResponseFormat {
    /** `json_schema` with [AiSchema] (OpenAI, OpenRouter, Groq and Ollama on capable models). */
    JsonSchema,

    /** `json_object`: valid JSON, shape from the prompt (DeepSeek, older servers). */
    JsonObject,

    /** No `response_format`; the prompt asks for JSON and the parser digs it out. */
    None,
}

/** Builds Chat Completions request bodies. Pure: no key, no I/O. */
object OpenAiRequests {
    /**
     * System message with the expert-student prompt, one user message with the instruction and the
     * photo as a `data:` URL. [official] picks OpenAI's own parameter names (`max_completion_tokens`,
     * no temperature for reasoning models); compatible servers get the widely supported `max_tokens`.
     */
    fun chat(
        model: String,
        system: String,
        user: String,
        image: ByteArray,
        mimeType: String,
        format: ResponseFormat,
        official: Boolean,
        config: OpenAiConfig = OpenAiConfig(),
        /** `reasoning_effort` for thinking models ("low" keeps answers fast and cheap); null = omit. */
        reasoningEffort: String? = null,
        maxTokens: Int = config.maxTokens,
        schema: JsonObject = AiSchema.document(AiSchema.Dialect.Closed),
        jsonHint: String = Prompts.JSON_ONLY_HINT,
        /** DeepSeek-style `thinking: {type: disabled}`: no hidden reasoning tokens at all. */
        thinkingOff: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("model", model)
        reasoningEffort?.let { put("reasoning_effort", it) }
        if (thinkingOff) putJsonObject("thinking") { put("type", "disabled") }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", if (format == ResponseFormat.JsonSchema) system.trim() else system.trim() + "\n\n" + jsonHint)
            }
            addJsonObject {
                put("role", "user")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", user)
                    }
                    addJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") {
                            put("url", "data:$mimeType;base64," + Base64.getEncoder().encodeToString(image))
                            put("detail", "high")
                        }
                    }
                }
            }
        }
        when (format) {
            ResponseFormat.JsonSchema -> putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "notebook_page")
                    // Not strict: strict mode needs every property required, and most fields are optional.
                    put("strict", false)
                    put("schema", schema)
                }
            }
            ResponseFormat.JsonObject -> putJsonObject("response_format") { put("type", "json_object") }
            ResponseFormat.None -> Unit
        }
        if (official) {
            put("max_completion_tokens", maxTokens)
        } else {
            put("max_tokens", maxTokens)
            put("temperature", config.temperature)
        }
    }

    /** A text-only conversation (TPL-02): [format] JsonObject asks for a JSON object; low reasoning effort keeps it quick. */
    fun text(
        model: String,
        system: String,
        turns: List<app.bestnote.ai.vision.ChatTurn>,
        format: ResponseFormat,
        official: Boolean,
        config: OpenAiConfig = OpenAiConfig(),
        reasoningEffort: String? = null,
        maxTokens: Int = config.maxTokens,
        /** DeepSeek-style `thinking: {type: disabled}`: no hidden reasoning tokens at all. */
        thinkingOff: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("model", model)
        reasoningEffort?.let { put("reasoning_effort", it) }
        if (thinkingOff) putJsonObject("thinking") { put("type", "disabled") }
        putJsonArray("messages") {
            addJsonObject {
                put("role", "system")
                put("content", system.trim() + if (format == ResponseFormat.JsonObject || format == ResponseFormat.None) "\n\nAnswer with one JSON object only, no prose and no code fences." else "")
            }
            turns.forEach { t ->
                addJsonObject {
                    put("role", if (t.fromUser) "user" else "assistant")
                    put("content", t.text)
                }
            }
        }
        if (format == ResponseFormat.JsonObject) putJsonObject("response_format") { put("type", "json_object") }
        if (official) {
            put("max_completion_tokens", maxTokens)
        } else {
            put("max_tokens", maxTokens)
            put("temperature", config.temperature)
        }
    }

    /** A one-word completion used by "Test connection" on compatible endpoints (checks key + model). */
    fun ping(model: String, official: Boolean): JsonObject = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", "Reply with OK.")
            }
        }
        put(if (official) "max_completion_tokens" else "max_tokens", 16)
    }

    /** Bearer auth; OpenRouter also likes to know which app is calling. Empty key (local Ollama) → no auth. */
    fun headers(key: String?, baseUrl: String): Map<String, String> = buildMap {
        key?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        if ("openrouter.ai" in baseUrl) {
            put("HTTP-Referer", "https://github.com/alidiab2/BestNote")
            put("X-Title", "BestNote")
        }
    }

    fun sniffMime(bytes: ByteArray, fallback: String): String = when {
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> fallback
    }
}
