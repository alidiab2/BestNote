package app.bestnote.ai.vision

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionDiagnosticsTest {
    @After
    fun reset() {
        VisionDiagnostics.captureRaw = false
        VisionDiagnostics.privateSession = false
        VisionDiagnostics.listener = null
        VisionDiagnostics.clear()
    }

    @Test
    fun `token usage of every provider is recorded and added up`() {
        VisionDiagnostics.resetTotals()
        VisionDiagnostics.recordResponse("openai", "m", 200, """{"usage":{"prompt_tokens":1200,"completion_tokens":300,"prompt_tokens_details":{"cached_tokens":0}}}""")
        assertEquals(1200, VisionDiagnostics.latest()!!.promptTokens)
        assertEquals(300, VisionDiagnostics.latest()!!.completionTokens)
        // Gemini reports thinking apart; it is billed as output.
        VisionDiagnostics.recordResponse("gemini", "m", 200, """{"usageMetadata":{"promptTokenCount":1000,"candidatesTokenCount":400,"thoughtsTokenCount":100}}""")
        assertEquals(500, VisionDiagnostics.latest()!!.completionTokens)
        VisionDiagnostics.recordResponse("claude", "m", 200, """{"usage":{"input_tokens":800,"cache_creation_input_tokens":5,"output_tokens":200}}""")
        assertEquals(800, VisionDiagnostics.latest()!!.promptTokens)
        VisionDiagnostics.recordResponse("openai", "m", 400, """{"error":{"message":"bad"}}""")
        assertNull(VisionDiagnostics.latest()!!.promptTokens)
        assertEquals(TokenTotals(calls = 4, promptTokens = 3000, completionTokens = 1000), VisionDiagnostics.totals())
        val text = VisionDiagnostics.format(VisionDiagnostics.recent()[2])
        assertTrue(text, text.contains("tokens: prompt=800 completion=200") && text.contains("session: calls=4 prompt=3000 completion=1000"))
    }

    @Test
    fun `raw bodies are kept only when capture is on`() {
        VisionDiagnostics.recordResponse("gemini", "m", 200, "{\"a\":1}")
        assertNull(VisionDiagnostics.latest()!!.raw)
        VisionDiagnostics.captureRaw = true
        VisionDiagnostics.recordResponse("gemini", "m", 200, "{\"a\":1}")
        assertEquals("{\"a\":1}", VisionDiagnostics.latest()!!.raw)
    }

    @Test
    fun `no raw body is kept while a locked notebook is open`() {
        VisionDiagnostics.captureRaw = true
        VisionDiagnostics.privateSession = true
        VisionDiagnostics.recordResponse("gemini", "m", 200, "{\"text\":\"my diary\"}")
        assertNull(VisionDiagnostics.latest()!!.raw)
    }

    @Test
    fun `outcome attaches to the latest call and only five are kept`() {
        var seen = 0
        VisionDiagnostics.listener = { seen = it.size }
        repeat(7) { VisionDiagnostics.recordResponse("claude", "m$it", 200, null) }
        VisionDiagnostics.recordOutcome(dropReasons = listOf("block 0 (math): no formula"), error = VisionError.BadResponse("x"))
        val all = VisionDiagnostics.recent()
        assertEquals(5, all.size)
        assertEquals(5, seen)
        assertEquals("m6", all.last().model)
        assertEquals(listOf("block 0 (math): no formula"), all.last().dropReasons)
        assertTrue(VisionDiagnostics.format(all.last()).contains("BadResponse"))
    }

    @Test
    fun `redactor scrubs keys and auth headers`() {
        val text = """
            x-goog-api-key: AIzaSyA1234567890abcdefghijklmnopqrstu
            Authorization: Bearer sk-proj-abcdefghijklmnopqrstuvwxyz123456
            "x-api-key": "sk-ant-api03-abcdefghijklmnop"
            groq gsk_abcdefghijklmnopqrstuvwxyz
            my own secret hunter2-pass
        """.trimIndent()
        val out = Redactor.redact(text, extraSecrets = listOf("hunter2-pass"))
        listOf("AIzaSy", "sk-proj", "sk-ant", "gsk_", "hunter2").forEach { assertFalse(it, out.contains(it)) }
        assertTrue(out.contains("x-goog-api-key: [REDACTED]"))
    }

    @Test
    fun `recorded bodies are redacted`() {
        VisionDiagnostics.captureRaw = true
        VisionDiagnostics.recordResponse("openai", "m", 401, "{\"error\":\"bad key sk-abcdefghijklmnopqrstuvwxyz\"}")
        assertFalse(VisionDiagnostics.latest()!!.raw!!.contains("sk-abc"))
    }
}
