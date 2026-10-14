package app.bestnote.ai.vision

import app.bestnote.core.model.LayoutTemplate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {
    @Test fun `every prompt is built on the expert student persona`() {
        val prompts = LayoutTemplate.entries.map { Prompts.board(BoardRequest(it)) } + Prompts.beautify()
        prompts.forEach { p ->
            assertTrue(p.startsWith("You are an elite, meticulous student"))
            listOf("LOGICAL flow", "ruler and compass", "`semantic`", "LaTeX").forEach { assertTrue(it, p.contains(it)) }
        }
    }

    @Test fun `board prompt adapts to template and reading direction`() {
        val p = Prompts.board(BoardRequest(LayoutTemplate.Cornell, rtl = true, language = "Arabic"))
        assertTrue(p.contains("Cornell"))
        assertTrue(p.contains("right-to-left"))
        assertTrue(p.contains("Arabic"))
        assertFalse(Prompts.board(BoardRequest(LayoutTemplate.Minimalist)).contains("panels go from right to left"))
    }
}
