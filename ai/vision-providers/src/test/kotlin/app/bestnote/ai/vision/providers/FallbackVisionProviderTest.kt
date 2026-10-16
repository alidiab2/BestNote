package app.bestnote.ai.vision.providers

import app.bestnote.ai.vision.AiDocument
import app.bestnote.ai.vision.BoardRequest
import app.bestnote.core.model.LayoutTemplate
import app.bestnote.ai.vision.VisionError
import app.bestnote.ai.vision.VisionImage
import app.bestnote.ai.vision.VisionProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackVisionProviderTest {
    private val image = VisionImage(ByteArray(0), "image/jpeg")
    private val request = BoardRequest(LayoutTemplate.LectureSummary)
    private val calls = mutableListOf<String>()

    private fun provider(failing: Map<String, VisionError>, skipped: MutableSet<String> = mutableSetOf()) = FallbackVisionProvider(
        candidates = { listOf("gemini-3.1-pro-preview", "gemini-3.8-flash", "gemini-3.5-flash-lite") },
        providerFor = { model ->
            object : VisionProvider {
                override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest): AiDocument {
                    calls += model
                    failing[model]?.let { throw it }
                    return AiDocument(title = model, blocks = emptyList())
                }

                override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float) = error("unused")
            }
        },
        skipped = skipped,
    )

    @Test fun quotaExhaustedModelFallsBackToTheNext() = runTest {
        val skipped = mutableSetOf<String>()
        val doc = provider(mapOf("gemini-3.1-pro-preview" to VisionError.RateLimited(57607, quotaExhausted = true)), skipped).reconstructBoard(image, request)
        assertEquals("gemini-3.8-flash", doc.title)
        assertTrue("gemini-3.1-pro-preview" in skipped)
    }

    @Test fun skippedModelsAreNotTriedAgain() = runTest {
        val p = provider(emptyMap(), mutableSetOf("gemini-3.1-pro-preview"))
        p.reconstructBoard(image, request)
        assertEquals(listOf("gemini-3.8-flash"), calls)
    }

    @Test fun otherErrorsAreNotRetried() = runTest {
        val result = runCatching { provider(mapOf("gemini-3.1-pro-preview" to VisionError.InvalidKey())).reconstructBoard(image, request) }
        assertTrue(result.exceptionOrNull() is VisionError.InvalidKey)
        assertEquals(listOf("gemini-3.1-pro-preview"), calls)
    }
}
