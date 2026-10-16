package app.bestnote.ai.vision

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

class CanaryTest {
    private val image = VisionImage(ByteArray(0), "image/jpeg", canary = "4821")

    private fun expectNotSeen(answer: String, truncated: Boolean = false) {
        try {
            Canary.verify(answer, truncated, image, "deepseek-v4-pro")
            fail("expected ImageNotSeen for: $answer")
        } catch (e: VisionError.ImageNotSeen) {
            assertEquals("deepseek-v4-pro", e.model)
        }
    }

    @Test fun `codes are four digits without a leading zero`() {
        val random = Random(1)
        repeat(500) { assertTrue(Canary.newCode(random).matches(Regex("[1-9][0-9]{3}"))) }
    }

    @Test fun `reads seen from plain, fenced, numeric and spaced answers`() {
        assertEquals("4821", Canary.seen("""{"seen":"4821","elements":[]}"""))
        assertEquals("4821", Canary.seen("Here you go:\n```json\n{ \"seen\" : \"4821\", \"elements\": [] }\n```"))
        assertEquals("4821", Canary.seen("""{"seen":4821,"elements":[]}"""))
        assertEquals("4821", Canary.seen("""{"seen":"4 8 2 1","elements":[]}"""))
        assertNull(Canary.seen("""{"elements":[{"e":"Title","t":"seen"}]}"""))
        assertNull(Canary.seen("""{"seen":"","elements":[]}"""))
    }

    @Test fun `a matching code passes, also on a cut-off answer`() {
        Canary.verify("""{"seen":"4821","elements":[]}""", false, image, "m")
        Canary.verify("""{"seen":"4821","elements":[{"e":"Ti""", true, image, "m")
    }

    @Test fun `a missing or wrong code is ImageNotSeen`() {
        expectNotSeen("""{"elements":[{"e":"Title","t":"Newton's laws"}]}""")
        expectNotSeen("""{"seen":"1234","elements":[]}""")
        expectNotSeen("""{"seen":"","elements":[]}""")
        expectNotSeen("""{"seen":"4821x","elements":[]}""".replace("4821x", "482"))
    }

    @Test fun `a cut-off answer without seen is left to the truncation handling, a wrong one is not`() {
        Canary.verify("""{"ele""", true, image, "m")
        expectNotSeen("""{"seen":"9999","ele""", truncated = true)
    }

    @Test fun `images without a canary are never checked`() {
        Canary.verify("""{"elements":[]}""", false, VisionImage(ByteArray(0), "image/jpeg"), "m")
        assertEquals("sys", Canary.system("sys", VisionImage(ByteArray(0), "image/jpeg")))
    }

    @Test fun `the prompt asks for seen first`() {
        val prompt = Canary.system("sys", image)
        assertTrue(prompt.startsWith("sys"))
        assertTrue("\"seen\"" in prompt && "FIRST" in prompt)
    }

    @Test fun `schema gets a required seen listed first`() {
        val base = NoteSchema.document(app.bestnote.engine.layout.NoteTemplates.builtIns().first(), AiSchema.Dialect.Closed)
        val schema = Canary.withSeen(base, image)
        val props = schema["properties"] as JsonObject
        assertEquals("seen", props.keys.first())
        assertEquals("elements", props.keys.elementAt(1))
        assertTrue(JsonPrimitive("seen") in (schema["required"] as JsonArray))
        assertTrue(JsonPrimitive("elements") in (schema["required"] as JsonArray))
        assertEquals(base, Canary.withSeen(base, VisionImage(ByteArray(0), "image/jpeg")))
    }

    @Test fun `the wire parsers ignore the seen field`() {
        val doc = NoteWire.answer("""{"seen":"4821","elements":[{"e":"Title","t":"Partial fractions"}]}""")
        assertEquals(1, doc.elements.size)
        assertNotNull(AiDocumentParser.parse("""{"seen":"4821","title":"T","blocks":[{"type":"paragraph","order":0,"text":"x"}]}""").document)
    }

    @Test fun `strip geometry fits the digits`() {
        for ((w, h) in listOf(1600 to 1000, 300 to 100, 3000 to 4000, 40 to 40)) {
            val strip = Canary.stripHeight(w, h)
            assertTrue(strip in 56..220)
            val size = Canary.textSize(w, strip)
            assertTrue(size <= strip * 0.78f + 0.01f)
            assertTrue("four digits (about 2.4 em) fit the width", size * 2.4f <= w + 0.01f)
        }
    }

    @Test fun `prompt tokens are read from every provider's usage block`() {
        assertEquals(744, Canary.promptTokens("""{"usage":{"prompt_tokens":744,"completion_tokens":2}}"""))
        assertEquals(1200, Canary.promptTokens("""{"usageMetadata":{"promptTokenCount":1200}}"""))
        assertEquals(900, Canary.promptTokens("""{"usage":{"input_tokens":900,"output_tokens":5}}"""))
        assertNull(Canary.promptTokens("{}"))
    }

    @Test fun `few prompt tokens for the prompt size are logged, plausible ones are not`() {
        // 3,000 characters of prompt are about 750-1,000 tokens of text alone.
        assertNotNull(Canary.tokenHint(744, 3_000))
        assertNull(Canary.tokenHint(1_803, 3_000))
        assertNull(Canary.tokenHint(null, 3_000))
        VisionDiagnostics.clear()
        VisionDiagnostics.recordResponse("openai-compatible", "deepseek-v4-pro", 200, "{}")
        Canary.noteUsage("""{"usage":{"prompt_tokens":744}}""", 3_000)
        assertTrue(VisionDiagnostics.latest()!!.parseNotes.single().contains("image was probably not counted"))
    }
}
