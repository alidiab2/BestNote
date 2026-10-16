package app.bestnote.ai.vision.openai

import app.bestnote.ai.vision.VisionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses Chat Completions responses (OpenAI and compatible servers) and maps errors. Pure: no I/O. */
object OpenAiResponses {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Answer(val text: String, val truncated: Boolean)

    fun answer(raw: String): Answer {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw VisionError.BadResponse("response is not JSON", it, raw) }
        // Some compatible servers answer 200 with an error object.
        (root["error"] as? JsonObject)?.let { throw VisionError.BadResponse(it["message"]?.jsonPrimitive?.contentOrNull ?: "error", raw = raw) }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw VisionError.BadResponse("no choices in response", raw = raw)
        val message = choice["message"] as? JsonObject
        message?.get("refusal")?.let { r -> if (r is JsonPrimitive && r.isString && r.content.isNotBlank()) throw VisionError.Blocked("refusal") }
        val finish = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        if (finish == "content_filter") throw VisionError.Blocked("content_filter")
        val text = when (val content = message?.get("content")) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            // Content parts (some servers): join the text parts.
            is JsonArray -> content.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }.joinToString("")
            else -> ""
        }
        val truncated = finish == "length"
        if (text.isBlank()) {
            if (truncated) throw VisionError.Truncated(null)
            throw VisionError.BadResponse("empty response (finish_reason=$finish)", raw = raw)
        }
        return Answer(text, truncated)
    }

    /** True when a 400 says the server does not support this `response_format` (step down a level). */
    fun rejectsResponseFormat(code: Int, body: String): Boolean {
        if (code != 400 && code != 422) return false
        val m = message(body).lowercase()
        return "response_format" in m || "json_schema" in m || "json_object" in m || "response format" in m || "structured output" in m
    }

    /** Maps a non-200 response; the message never contains the key. */
    fun errorFor(code: Int, body: String, retryAfterHeader: String?, model: String): VisionError {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject }.getOrNull()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(300)
        val errCode = error?.get("code")?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val m = message.lowercase()
        return when {
            code == 401 || errCode == "invalid_api_key" -> VisionError.InvalidKey()
            code == 404 || errCode == "model_not_found" || ("model" in m && ("does not exist" in m || "not found" in m)) ->
                VisionError.ModelUnavailable(model, message)
            code == 400 && "image" in m && ("support" in m || "vision" in m) -> VisionError.ModelUnavailable(model, "this model cannot read images: $message")
            code == 403 -> VisionError.InvalidKey()
            code == 429 -> VisionError.RateLimited(retryAfterHeader?.trim()?.toDoubleOrNull()?.let { kotlin.math.ceil(it).toLong() })
            code in 500..599 -> VisionError.ServiceUnavailable(code, message)
            else -> VisionError.BadResponse("HTTP $code: $message")
        }
    }

    private fun message(body: String) = runCatching {
        (json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body
}
