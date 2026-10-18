package app.bestnote.ai.vision

import app.bestnote.core.model.LayoutTemplate

/**
 * An image handed to a provider. [canary]: the 4-digit code drawn on the image's bottom strip ([Canary]);
 * the provider asks the model to read it back and throws [VisionError.ImageNotSeen] when it does not.
 */
class VisionImage(val bytes: ByteArray, val mimeType: String, val canary: String? = null)

/** One message of a text-only conversation (TPL-02 "Design with AI"). */
data class ChatTurn(val fromUser: Boolean, val text: String)

/** What the provider needs besides the image. [rtl] is true for Arabic/Hebrew boards. */
data class BoardRequest(val template: LayoutTemplate, val rtl: Boolean = false, val language: String? = null)

sealed class VisionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingKey : VisionError("No API key configured")
    class InvalidKey : VisionError("The API key was rejected")
    /** [quotaExhausted]: the plan's quota for this model is used up (free tiers: often 0), not a short burst limit. */
    class RateLimited(val retryAfterSeconds: Long?, val quotaExhausted: Boolean = false) :
        VisionError(if (quotaExhausted) "Quota exhausted" else "Rate limited")
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

    /**
     * The model answered but never saw the photo: it did not read back the canary code drawn on the image
     * ([Canary]). Some models silently drop image input and invent notes; those must never be shown.
     */
    class ImageNotSeen(val model: String) : VisionError("Model $model did not receive the image")

    class Network(cause: Throwable) : VisionError("Network error (${cause.javaClass.simpleName}: ${cause.message.orEmpty().take(120)})", cause)
}

/** The AI providers BestNote can call, all bring-your-own-key. */
enum class AiProviderKind(val id: String) {
    Gemini("gemini"),
    Claude("claude"),
    OpenAi("openai"),

    /**
     * OpenRouter with the user's own account (AI-11 "Sign in with OpenRouter", or a pasted key):
     * GPT, Claude, Gemini, DeepSeek… billed to the user's OpenRouter credits. OpenAI wire format.
     */
    OpenRouter("openrouter"),

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
    suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String? = null): AiDocument

    /**
     * Rasterised handwriting → its exact words ("Convert with AI"), one paragraph block per line.
     * [language] is a hint such as "Arabic"; null lets the model decide.
     */
    suspend fun transcribe(selection: VisionImage, language: String?): AiDocument =
        throw VisionError.BadResponse("this provider cannot transcribe handwriting")

    /**
     * Rasterised handwritten maths → its LaTeX ("Convert to equation", MATH-01), as one `math` block
     * ([Prompts.equation]). No blocks means the model saw no maths. Read it with [equationLatex].
     */
    suspend fun readEquation(selection: VisionImage): AiDocument =
        throw VisionError.BadResponse("this provider cannot read equations")

    /**
     * Template first (TPL-01): board photo → the template's built-in elements in the compact format
     * ([NoteWire], [NoteSchema], [NotePrompts]). With [NotesRequest.current] (a follow-up photo) the answer
     * is ONE merged list of the page's notes and the new board. Providers without the element format
     * fall back to [reconstructBoard] and convert the blocks.
     */
    suspend fun writeNotes(image: VisionImage, request: NotesRequest): app.bestnote.engine.layout.NoteDocument {
        val fresh = LegacyNotes.fromAiDocument(reconstructBoard(image, request.legacy()))
        return request.current?.let { NoteMerge.merge(it, fresh) } ?: fresh
    }

    /**
     * Text only (TPL-02 "Design with AI"): [system] + the conversation [turns] → the model's answer text,
     * with low reasoning. With [json] the model is asked for a single JSON object. No image, no schema.
     */
    suspend fun completeText(system: String, turns: List<ChatTurn>, json: Boolean = true): String =
        throw VisionError.BadResponse("this provider cannot chat")
}
