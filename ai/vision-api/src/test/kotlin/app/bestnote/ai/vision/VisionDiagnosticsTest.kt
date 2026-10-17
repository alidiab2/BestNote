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
