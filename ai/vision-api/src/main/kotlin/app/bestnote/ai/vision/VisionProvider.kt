package app.bestnote.ai.vision

import app.bestnote.core.model.LayoutTemplate

/** An image handed to a provider. */
class VisionImage(val bytes: ByteArray, val mimeType: String)

/** What the provider needs besides the image. [rtl] is true for Arabic/Hebrew boards. */
data class BoardRequest(val template: LayoutTemplate, val rtl: Boolean = false, val language: String? = null)

sealed class VisionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingKey : VisionError("No API key configured")
    class InvalidKey : VisionError("The API key was rejected")
    class RateLimited(val retryAfterSeconds: Long?) : VisionError("Rate limited")
    class Blocked(reason: String) : VisionError("Response blocked: $reason")
    /**
     * The provider answered but nothing usable came out. [raw] is the model's answer text when there
     * was one (for diagnostics only; it never contains the key).
     */
    class BadResponse(detail: String, cause: Throwable? = null, val raw: String? = null) : VisionError("Unusable model output: $detail", cause)

    /** The model id is unknown to this key or endpoint (404, or 403 naming the model). */
    class ModelUnavailable(val model: String, detail: String) : VisionError("Model $model is not available: $detail")

    /** The provider is overloaded or down (5xx after the retry). */
    class ServiceUnavailable(val code: Int, detail: String) : VisionError("The AI provider is unavailable (HTTP $code): $detail")

    /** The answer was cut off by the output limit and nothing complete could be salvaged. */
    class Truncated(val raw: String?) : VisionError("The answer was cut off before any note was complete")
    class Network(cause: Throwable) : VisionError("Network error", cause)
}

/** The AI providers BestNote can call, all bring-your-own-key. */
enum class AiProviderKind(val id: String) {
    Gemini("gemini"),
    Claude("claude"),
    OpenAi("openai"),

    /** Any OpenAI-compatible Chat Completions endpoint: OpenRouter, Groq, DeepSeek, local Ollama… */
    OpenAiCompatible("openai_compatible"),
    ;

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Gemini
    }
}

/** What a provider reads per request. The key is never logged, cached or persisted by providers. */
interface ProviderCredentials {
    suspend fun apiKey(): String?
    suspend fun model(): String

    /** Only for [AiProviderKind.OpenAiCompatible] (and tests); null means the provider's own endpoint. */
    suspend fun baseUrl(): String? = null
}

/**
 * Implemented per provider in its own module (e.g. `:ai:vision-gemini`). Providers must send
 * [Prompts.system] as the system instruction and request JSON matching [AiDocument].
 * All calls are cancellable suspend functions.
 */
interface VisionProvider {
    /** Whiteboard/blackboard photo → the notes an expert student would have written. */
    suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument

    /**
     * Rasterised lasso selection of the user's own handwriting → the same content, tidied.
     * Coordinates in the result are in the selection's own box.
     */
    suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float): AiDocument

    /**
     * Rasterised handwriting → its exact words ("Convert with AI"), one paragraph block per line.
     * [language] is a hint such as "Arabic"; null lets the model decide.
     */
    suspend fun transcribe(selection: VisionImage, language: String?): AiDocument =
        throw VisionError.BadResponse("this provider cannot transcribe handwriting")
}
