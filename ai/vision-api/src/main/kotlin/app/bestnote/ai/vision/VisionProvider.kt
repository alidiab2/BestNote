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
    class BadResponse(detail: String, cause: Throwable? = null) : VisionError("Unusable model output: $detail", cause)
    class Network(cause: Throwable) : VisionError("Network error", cause)
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
}
