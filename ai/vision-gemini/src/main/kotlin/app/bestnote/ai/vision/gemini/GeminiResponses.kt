package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.AiBlock
import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.VisionError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
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

    /** The model's answer text from a 200 response, or the [VisionError] explaining why there is none. */
    fun candidateText(raw: String): String {
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
            "MAX_TOKENS" -> throw VisionError.BadResponse("the response was cut off (MAX_TOKENS)")
            "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY" -> throw VisionError.Blocked(finish)
            else -> if (text.isBlank()) throw VisionError.BadResponse("stopped: $finish")
        }
        if (text.isBlank()) throw VisionError.BadResponse("empty response")
        return text
    }

    /**
     * The model's JSON → [AiDocument]. Blocks are decoded one by one so a single malformed block
     * is dropped instead of failing the whole page; [app.bestnote.ai.vision.DocumentMapper] then
     * validates what is left.
     */
    fun document(text: String): AiDocument {
        val root = runCatching { json.parseToJsonElement(stripFence(text)) }.getOrNull() as? JsonObject
            ?: throw VisionError.BadResponse("the notes are not a JSON object")
        val blocks = (root["blocks"] as? JsonArray)
            ?: throw VisionError.BadResponse("the notes have no blocks")
        val title = (root["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val decoded = blocks.mapNotNull { runCatching { json.decodeFromJsonElement<AiBlock>(it) }.getOrNull() }
        if (blocks.isNotEmpty() && decoded.isEmpty()) throw VisionError.BadResponse("no block matched the schema")
        return AiDocument(title = title, blocks = decoded)
    }

    /** Some models wrap JSON in ``` fences even in JSON mode. */
    private fun stripFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        return t.substringAfter('\n').substringBeforeLast("```").trim()
    }

    /** True when a 400 says the endpoint does not know `responseJsonSchema` (retry with `responseSchema`). */
    fun rejectsJsonSchema(code: Int, body: String) = code == 400 && "responseJsonSchema" in body

    /** Maps a non-200 response to a [VisionError]. The message never contains the key. */
    fun errorFor(code: Int, body: String, retryAfterHeader: String?, model: String): VisionError {
        val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonObject }.getOrNull()
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(300)
        val status = error?.get("status")?.jsonPrimitive?.contentOrNull.orEmpty()
        val keyProblem = "API key" in message || "API_KEY" in message || status == "UNAUTHENTICATED"
        return when {
            code == 400 && keyProblem -> VisionError.InvalidKey()
            code == 401 -> VisionError.InvalidKey()
            code == 403 && "model" in message.lowercase() -> VisionError.BadResponse("model $model is not available: $message")
            code == 403 -> VisionError.InvalidKey()
            code == 404 -> VisionError.BadResponse("model $model is not available: $message")
            code == 429 -> VisionError.RateLimited(retryAfterSeconds(retryAfterHeader, error))
            code in 500..599 -> VisionError.BadResponse("Gemini is unavailable (HTTP $code): $message")
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
