package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.AiDocumentParser
import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.VisionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parses `generateContent` responses and maps HTTP errors to [VisionError]. Pure: no I/O. */
object GeminiResponses {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /** The answer text; [truncated] when the model hit the output limit (what was written is salvaged). */
    data class Answer(val text: String, val truncated: Boolean)

    /** The model's answer text from a 200 response, or the [VisionError] explaining why there is none. */
    fun candidateText(raw: String): String = answer(raw).text

    fun answer(raw: String): Answer {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }
            .getOrElse { throw VisionError.BadResponse("response is not JSON", it) }
        root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
            throw VisionError.Blocked(it)
        }
        val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw VisionError.BadResponse("no candidates in response")
        val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
        val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.map { it.jsonObject }
            // Thought summaries, if a model returns them, are not the answer.
            ?.filterNot { it["thought"]?.jsonPrimitive?.booleanOrNull == true }
            ?.mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            .orEmpty()
        when (finish) {
            null, "STOP", "FINISH_REASON_UNSPECIFIED" -> Unit
            // Thinking can use the whole budget and leave no answer at all.
            "MAX_TOKENS" -> if (text.isBlank()) throw VisionError.Truncated(null) else return Answer(text, truncated = true)
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY" -> throw VisionError.Blocked(finish)
            else -> if (text.isBlank()) throw VisionError.BadResponse("stopped: $finish")
        }
        if (text.isBlank()) throw VisionError.BadResponse("empty response")
        return Answer(text, truncated = false)
    }

    /** The model's JSON → [AiDocument] through the shared, forgiving [AiDocumentParser]. */
    fun document(text: String, truncated: Boolean = false): AiDocument = AiDocumentParser.answer(text, truncated)

    /** True when a 400 rejects the `thinkingConfig` we sent (unknown field, or not for this model). */
    fun rejectsThinking(code: Int, body: String) = code == 400 && "thinking" in body.lowercase()

    /** True when a 400 says the endpoint does not know `responseJsonSchema` (retry with `responseSchema`). */
    fun rejectsJsonSchema(code: Int, body: String) = code == 400 && "responseJsonSchema" in body

    /** True when a 400 rejects the OpenAPI `responseSchema` (unknown field, or "too many states" for a complex schema). */
    fun rejectsResponseSchema(code: Int, body: String) =
        code == 400 && ("responseSchema" in body || "schema" in body.lowercase() || "too many states" in body.lowercase())

    /** Maps a non-200 response to a [VisionError]. The message never contains the key. */
    fun errorFor(code: Int, body: String, retryAfterHeader: String?, model: String): VisionError {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(300)
        val status = error?.get("status")?.jsonPrimitive?.contentOrNull.orEmpty()
        val keyProblem = "API key" in message || "API_KEY" in message || status == "UNAUTHENTICATED"
        return when {
            code == 400 && keyProblem -> VisionError.InvalidKey()
            code == 401 -> VisionError.InvalidKey()
            code == 403 && "model" in message.lowercase() -> VisionError.ModelUnavailable(model, message)
            code == 403 -> VisionError.InvalidKey()
            code == 404 -> VisionError.ModelUnavailable(model, message)
            code == 429 -> VisionError.RateLimited(retryAfterSeconds(retryAfterHeader, error), quotaExhausted = "quota" in message.lowercase())
            code in 500..599 -> VisionError.ServiceUnavailable(code, message)
            else -> VisionError.BadResponse("HTTP $code: $message")
        }
    }

    /** `Retry-After` header, else the `RetryInfo.retryDelay` ("17s") in the error details. */
    private fun retryAfterSeconds(header: String?, error: JsonObject?): Long? {
        header?.trim()?.toLongOrNull()?.let { return it }
        val details = runCatching { error?.get("details")?.jsonArray }.getOrNull() ?: return null
        return details.firstNotNullOfOrNull { d ->
            runCatching { d.jsonObject["retryDelay"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?.removeSuffix("s")?.toDoubleOrNull()?.let { kotlin.math.ceil(it).toLong() }
        }
    }
}
