package app.bestnote.ai.vision

import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.NoteElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI-12a: Claude (claude-opus-5-5, HTTP 200) answers that Smart Board rejected on the owner's device,
 * in the shapes the owner reported (reconstructed from the error report: element wrapped one level
 * deeper; a junk object before the real one, in a fence). Both must give the full notes.
 */
class ClaudeAnswersTest {
    private fun answer(name: String) = javaClass.getResource("/answers/$name")!!.readText()

    @Test fun `elements wrapped in an inner e object are unwrapped`() {
        val doc = NoteWire.answer(answer("claude_nested_e.json"))
        assertEquals(
            listOf(
                NoteElement.Title, NoteElement.Heading, NoteElement.Paragraph, NoteElement.Diagram, NoteElement.Formula,
                NoteElement.VariableLegend, NoteElement.WorkedExample, NoteElement.KeyResult, NoteElement.StarNote,
            ),
            doc.elements.map { it.element },
        )
        assertEquals("עבודה של כוח לא קבוע", doc.elements[0].text)
        assertEquals(3, doc.elements[5].rows.size)
        assertEquals("4\\,(J)", doc.elements[6].parts.single().result)
        assertNotNull(doc.elements[3].diagram)
    }

    @Test fun `the fuller of two JSON objects wins, fences stripped`() {
        val doc = NoteWire.answer(answer("claude_two_objects.txt"))
        assertEquals(5, doc.elements.size)
        assertEquals("עבודה של כוח לא קבוע", doc.elements[0].text)
        assertEquals(NoteElement.Diagram, doc.elements[2].element)
    }

    @Test fun `top-level values are split with strings and braces inside them respected`() {
        val values = AiDocumentParser.topLevelValues("""junk {"a":"x}{"} more [1,[2]] {"b":"\"{"} {"open":""")
        assertEquals(listOf("""{"a":"x}{"}""", "[1,[2]]", """{"b":"\"{"}"""), values)
    }

    @Test fun `the F-dx trapezoid is drawn as a real graph with ticks and a hatched area`() {
        val d = NoteWire.answer(answer("claude_nested_e.json")).elements[3].diagram!!
        val labels = d.shapes.filterIsInstance<VectorShape.Label>().map { it.text }
        assertTrue(labels.containsAll(listOf("5", "10", "15", "100")))
        val curve = d.shapes.filterIsInstance<VectorShape.Polyline>().single()
        assertEquals(4, curve.points.size)
        // Flat top: the two middle points at the same height, above the axis.
        assertEquals(curve.points[1].y, curve.points[2].y, 0.01f)
        assertTrue(curve.points[1].y < curve.points[0].y - 50f)
        val hatch = d.shapes.filterIsInstance<VectorShape.Line>().filter { it.from.x == it.to.x && !it.style.dashed && it.to.y < it.from.y - 3f }
        assertTrue("hatched area: ${hatch.size}", hatch.size >= 10)
        assertTrue(d.shapes.any { it is VectorShape.Line && it.style.dashed })
    }
}
