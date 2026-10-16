package app.bestnote.feature.smartboard

import app.bestnote.ai.vision.AiBlock
import app.bestnote.ai.vision.AiDocument
import org.junit.Assert.assertEquals
import org.junit.Test

class BoardMergeTest {
    @Test fun `photos are merged in order with unique ids and anchors`() {
        val a = AiDocument("Limits", listOf(AiBlock("math", id = "m", order = 1, latex = "x"), AiBlock("heading", order = 0, text = "Def")))
        val b = AiDocument("Derivatives", listOf(AiBlock("math", id = "m", order = 0, latex = "y"), AiBlock("margin_note", order = 1, annotates = "m", text = "!")))
        val merged = BoardMerge.merge(listOf(a, b))
        assertEquals("Limits", merged.title)
        val sorted = merged.blocks.sortedBy { it.order }
        assertEquals(listOf("heading", "math", "heading", "math", "margin_note"), sorted.map { it.type })
        assertEquals("Derivatives", sorted[2].text)
        assertEquals("p2_m", sorted[3].id)
        assertEquals("p2_m", sorted[4].annotates)
        assertEquals("m", sorted[1].id)
        assertEquals((0..4).toList(), sorted.map { it.order })
    }
}
