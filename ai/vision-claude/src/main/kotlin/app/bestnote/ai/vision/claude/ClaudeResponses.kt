package app.bestnote.ai.vision.claude

import app.bestnote.ai.vision.VisionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses Messages API responses and maps HTTP errors to [VisionError]. Pure: no I/O. */
object ClaudeResponses {
    private val json = Json { ignoreUnknownKeys = true }

    data class Answer(val text: String, val truncated: Boolean)

    /**
     * The answer text from a 200 response: every `text` block joined (thinking and fallback blocks
     * are skipped). `stop_reason` is checked first: `refusal` → [VisionError.Blocked], `max_tokens`
     * → [Answer.truncated] so the complete blocks can still be salvaged.
     */
    fun answer(raw: String): Answer {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw VisionError.BadResponse("response is not JSON", it, raw) }
        val stop = root["stop_reason"]?.jsonPrimitive?.contentOrNull
        if (stop == "refusal") {
            val category = (root["stop_details"] as? JsonObject)?.get("category")?.jsonPrimitive?.contentOrNull
            throw VisionError.Blocked(category ?: "refusal")
        }
        val text = (root["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            .joinToString("")
        val truncated = stop == "max_tokens" || stop == "model_context_window_exceeded"
        if (text.isBlank()) {
            if (truncated) throw VisionError.Truncated(null)
            throw VisionError.BadResponse("empty response (stop_reason=$stop)", raw = raw)
        }
        return Answer(text, truncated)
    }

    /** True when a 400 is about `output_config` / the schema: retry once without structured output. */
    fun rejectsStructuredOutput(code: Int, body: String): Boolean {
        if (code != 400) return false
        val m = message(body).lowercase()
        return "output_config" in m || "json_schema" in m || "schema" in m || "output_format" in m
    }

    /** True when a 400 is about the `fallbacks` parameter (older model or endpoint): retry without it. */
    fun rejectsFallbacks(code: Int, body: String) = code == 400 && "fallback" in message(body).lowercase()

    /** Maps a non-200 response. The message never contains the key (Anthropic never echoes it). */
    fun errorFor(code: Int, body: String, retryAfterHeader: String?, model: String): VisionError {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject }.getOrNull()
        val type = error?.get("type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(300)
        return when {
            code == 401 || type == "authentication_error" -> VisionError.InvalidKey()
            code == 403 || type == "permission_error" -> VisionError.InvalidKey()
            code == 404 || type == "not_found_error" -> VisionError.ModelUnavailable(model, message)
            code == 400 && "model" in message.lowercase() && ("not found" in message.lowercase() || "invalid" in message.lowercase()) ->
                VisionError.ModelUnavailable(model, message)
            code == 429 || type == "rate_limit_error" -> VisionError.RateLimited(retryAfterHeader?.trim()?.toDoubleOrNull()?.let { kotlin.math.ceil(it).toLong() })
            code == 529 || code in 500..599 || type == "overloaded_error" -> VisionError.ServiceUnavailable(code, message)
            else -> VisionError.BadResponse("HTTP $code: $message")
        }
    }

    private fun message(body: String) = runCatching {
        (json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body
}
