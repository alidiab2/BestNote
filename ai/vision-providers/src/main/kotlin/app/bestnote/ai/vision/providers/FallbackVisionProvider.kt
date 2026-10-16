package app.bestnote.ai.vision.providers

import app.bestnote.ai.vision.BoardRequest
import app.bestnote.ai.vision.NotesRequest
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider

/**
 * For an automatic model choice: calls the best candidate and, when that model is out of quota or
 * unknown to the key (free tiers often allow 0 requests on pro and preview models), moves on to the
 * next candidate. Models that failed this way are skipped for the rest of the session.
 */
internal class FallbackVisionProvider(
    private val candidates: suspend () -> List<String>,
    private val providerFor: (model: String) -> VisionProvider,
    private val skipped: MutableSet<String>,
) : VisionProvider {
    override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest) = attempt { it.reconstructBoard(image, request) }

    override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?) = attempt { it.beautify(selection, widthPt, heightPt, language) }

    override suspend fun transcribe(selection: VisionImage, language: String?) = attempt { it.transcribe(selection, language) }

    override suspend fun writeNotes(image: VisionImage, request: NotesRequest) = attempt { it.writeNotes(image, request) }

    private suspend fun <T> attempt(call: suspend (VisionProvider) -> T): T {
        val models = candidates().filterNot { it in skipped }.take(MAX_ATTEMPTS).ifEmpty { candidates().take(1) }
        var last: VisionError? = null
        for (model in models) {
            try {
                return call(providerFor(model))
            } catch (e: VisionError.RateLimited) {
                last = e
            } catch (e: VisionError.ModelUnavailable) {
                last = e
            }
            synchronized(skipped) { skipped += model }
        }
        throw last ?: VisionError.BadResponse("no model to call")
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
