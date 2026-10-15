package app.bestnote.ai.vision.gemini

import app.bestnote.ai.vision.VisionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiResponsesTest {

    private inline fun <reified T : VisionError> assertFails(block: () -> Unit): T {
        try {
            block()
        } catch (e: VisionError) {
            assertTrue("expected ${T::class.simpleName}, got $e", e is T)
            return e as T
        }
        fail("expected ${T::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun parsesARecordedBoardResponseAndSkipsThoughts() {
        val text = GeminiResponses.candidateText(Fixtures.read("board_response.json"))
        assertTrue(text.startsWith("{"))
        val doc = GeminiResponses.document(text)

        assertEquals("Derivatives", doc.title)
        assertEquals(listOf("definition", "math", "margin_note", "diagram"), doc.blocks.map { it.type })
        val math = doc.blocks[1]
        assertEquals("f'(x)=\\lim_{h\\to 0}\\frac{f(x+h)-f(x)}{h}", math.latex)
        assertEquals(true, math.boxed)
        assertEquals("essential", math.semantic)
        assertEquals("red", math.inkColor)
        assertEquals(listOf(0.1f, 0.2f, 0.6f, 0.35f), math.sourceBox)
        assertEquals("m1", doc.blocks[2].annotates)
        val diagram = doc.blocks[3].diagram!!
        assertEquals(listOf(200f, 100f), diagram.size)
        assertEquals(listOf("arrow", "label"), diagram.shapes.map { it.kind })
    }

    @Test
    fun dropsOnlyTheMalformedBlock() {
        // Second block: diagram without its required size; third: unknown keys and a null field.
        val doc = GeminiResponses.document(
            """{"blocks":[{"type":"paragraph","text":"ok"},{"type":"diagram","diagram":{"shapes":[]}},{"type":"math","latex":"x","extra":1,"order":null}]}""",
        )
        assertEquals(listOf("paragraph", "math"), doc.blocks.map { it.type })
        assertNull(doc.blocks[1].order)
    }

    @Test
    fun acceptsFencedJsonAndRejectsNonsense() {
        assertEquals(1, GeminiResponses.document("```json\n${Fixtures.NOTES}\n```").blocks.size)
        assertFails<VisionError.BadResponse> { GeminiResponses.document("I can't read this board") }
        assertFails<VisionError.BadResponse> { GeminiResponses.document("""{"title":"no blocks"}""") }
        assertFails<VisionError.BadResponse> { GeminiResponses.document("""{"blocks":[{"text":"no type"}]}""") }
    }

    @Test
    fun blockedTruncatedAndEmptyResponses() {
        assertFails<VisionError.Blocked> { GeminiResponses.candidateText("""{"promptFeedback":{"blockReason":"SAFETY"}}""") }
        assertFails<VisionError.Blocked> { GeminiResponses.candidateText(Fixtures.ok("", finish = "IMAGE_SAFETY")) }
        assertFails<VisionError.BadResponse> { GeminiResponses.candidateText(Fixtures.ok("{\"blocks\":[", finish = "MAX_TOKENS")) }
        assertFails<VisionError.BadResponse> { GeminiResponses.candidateText("""{"candidates":[]}""") }
        assertFails<VisionError.BadResponse> { GeminiResponses.candidateText(Fixtures.ok("  ")) }
        assertFails<VisionError.BadResponse> { GeminiResponses.candidateText("<html>502</html>") }
    }

    @Test
    fun mapsHttpErrors() {
        assertTrue(GeminiResponses.errorFor(400, Fixtures.read("invalid_key.json"), null, "m") is VisionError.InvalidKey)
        assertTrue(GeminiResponses.errorFor(403, Fixtures.error(403, "Permission denied"), null, "m") is VisionError.InvalidKey)
        val missing = GeminiResponses.errorFor(404, Fixtures.error(404, "models/gemini-x is not found"), null, "gemini-x")
        assertTrue(missing is VisionError.BadResponse && "gemini-x" in missing.message!!)
        assertTrue(GeminiResponses.errorFor(503, Fixtures.error(503, "overloaded"), null, "m") is VisionError.BadResponse)
        assertTrue(GeminiResponses.errorFor(400, "not json", null, "m") is VisionError.BadResponse)
    }

    @Test
    fun rateLimitCarriesTheRetryDelay() {
        val fromDetails = GeminiResponses.errorFor(429, Fixtures.read("rate_limited.json"), null, "m") as VisionError.RateLimited
        assertEquals(18L, fromDetails.retryAfterSeconds)
        val fromHeader = GeminiResponses.errorFor(429, Fixtures.read("rate_limited.json"), "5", "m") as VisionError.RateLimited
        assertEquals(5L, fromHeader.retryAfterSeconds)
        assertNull((GeminiResponses.errorFor(429, "{}", null, "m") as VisionError.RateLimited).retryAfterSeconds)
    }

    @Test
    fun detectsAnEndpointThatRejectsResponseJsonSchema() {
        assertTrue(GeminiResponses.rejectsJsonSchema(400, Fixtures.error(400, "Unknown name \\\"responseJsonSchema\\\"")))
        assertTrue(!GeminiResponses.rejectsJsonSchema(400, Fixtures.read("invalid_key.json")))
    }
}
