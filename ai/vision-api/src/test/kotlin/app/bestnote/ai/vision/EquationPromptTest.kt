package app.bestnote.ai.vision

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MATH-01: the strict LaTeX-only equation prompt and reading its answer (recorded responses, no live calls). */
class EquationPromptTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun `equation prompt is built on the persona and asks for LaTeX only`() {
        val p = Prompts.equation()
        assertTrue(p.startsWith("You are an elite, meticulous student"))
        listOf("ONE block", "`latex`", "LaTeX maths ONLY", "Never solve", "\\text{", "aligned", "no blocks").forEach { assertTrue(it, p.contains(it)) }
        assertTrue(p.contains("no $ or"))
    }

    @Test fun `a recorded answer gives its LaTeX`() {
        val answer = """{"blocks":[{"type":"math","order":0,"latex":"\\int_0^1 x^2\\,dx = \\frac{1}{3}","text":""}]}"""
        assertEquals("\\int_0^1 x^2\\,dx = \\frac{1}{3}", json.decodeFromString(AiDocument.serializer(), answer).equationLatex())
    }

    @Test fun `several blocks become lines and text-only answers still count`() {
        val doc = AiDocument(
            blocks = listOf(
                AiBlock(type = "math", order = 1, latex = "y = 2"),
                AiBlock(type = "math", order = 0, latex = "x = 1"),
                AiBlock(type = "paragraph", order = 2, text = "z=3"),
            ),
        )
        assertEquals("x = 1 \\\\ y = 2 \\\\ z=3", doc.equationLatex())
        assertEquals("", AiDocument().equationLatex())
    }

    @Test fun `demo provider reads an equation and others refuse`() = runTest {
        assertTrue(DemoVisionProvider(latencyMs = 0).readEquation(VisionImage(ByteArray(1), "image/png")).equationLatex().startsWith("x = \\frac"))
    }
}
