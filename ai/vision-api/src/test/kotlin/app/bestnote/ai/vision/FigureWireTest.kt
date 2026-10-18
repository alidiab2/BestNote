package app.bestnote.ai.vision

import app.bestnote.core.model.RectPt
import app.bestnote.core.model.VectorDiagram
import app.bestnote.core.model.VectorShape
import app.bestnote.core.model.Vec2
import app.bestnote.engine.layout.BoardFigure
import app.bestnote.engine.layout.FigureKind
import app.bestnote.engine.layout.FigureSource
import app.bestnote.engine.layout.NoteBlock
import app.bestnote.engine.layout.NoteDocument
import app.bestnote.engine.layout.NoteElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** AI-12: figure elements on the wire — parsed forgivingly, sanitised, stored and sent back without their drawing. */
class FigureWireTest {
    @Test fun `a figure is parsed with its box, kind and placed labels`() {
        val doc = NoteWire.parse(
            """{"elements":[{"e":"Diagram","h":"Incline","k":"free body diagram","box":[420,180,610,520],"lbl":[["N",440,250],["mg",500,500],["37^\\circ",470,470],"F"]}]}""",
        )
        val f = doc.elements.single().figure!!
        assertEquals(FigureKind.Diagram, f.kind)
        assertEquals(0.42f, f.box!!.left, 1e-4f)
        assertEquals(0.52f, f.box!!.bottom, 1e-4f)
        assertEquals(listOf("N", "mg", "37^\\circ", "F"), f.labels.map { it.text })
        assertEquals(Vec2(0.44f, 0.25f), f.labels[0].at)
        assertNull(f.labels[3].at)
        assertEquals(FigureSource.Pending, f.source)
    }

    @Test fun `bad boxes are sanitised or dropped, never fail the page`() {
        fun box(raw: String) = NoteWire.parse("""{"elements":[{"e":"Diagram","k":"graph","box":$raw}]}""").elements.single().figure?.box
        // Swapped corners, fractions instead of 0–1000, out of range.
        assertEquals(0.1f, box("[600,500,100,200]")!!.left, 1e-4f)
        assertEquals(0.3f, box("[0.1,0.2,0.3,0.4]")!!.right, 1e-4f)
        assertEquals(1f, box("[100,100,1400,900]")!!.right, 1e-4f)
        assertNull(box("[1,2,3]"))
        assertNull(box("[100,100,102,500]")) // a sliver
        assertNull(box("[-5,10,200,300]"))
        assertNull(box("\"top left\""))
    }

    @Test fun `a box alone makes a Diagram, simple data is read`() {
        val doc = NoteWire.parse(
            """{"elements":[{"box":[0,0,500,500],"k":"plot","graph":{"x":[-2,2],"y":[0,4],"f":"x^2"}},
               {"e":"Diagram","k":"shape","box":[100,100,300,300],"shape":{"type":"Triangle","pts":[[100,300],[300,300],[300,100]]}},
               {"e":"Diagram","k":"table","box":[0,600,400,800],"rows":[["t","v"],["0","1"]]}]}""",
        )
        assertEquals(NoteElement.Diagram, doc.elements[0].element)
        assertEquals("x^2", doc.elements[0].figure!!.graph!!.expr)
        assertEquals(3, doc.elements[1].figure!!.shape!!.points.size)
        assertEquals(FigureKind.Table, doc.elements[2].figure!!.kind)
        assertEquals(2, doc.elements[2].rows.size)
    }

    @Test fun `a stored figure keeps its drawing and source, the AI copy leaves them out`() {
        val drawn = VectorDiagram(RectPt(0f, 0f, 100f, 80f), listOf(VectorShape.Line(Vec2(10f, 70f), Vec2(90f, 70f)), VectorShape.Arrow(Vec2(50f, 40f), Vec2(50f, 10f))))
        val doc = NoteDocument(
            listOf(
                NoteBlock(NoteElement.Heading, text = "Forces"),
                NoteBlock(NoteElement.Diagram, head = "Incline", diagram = drawn, figure = BoardFigure(FigureKind.Diagram, app.bestnote.engine.layout.FigureBox(0.2f, 0.2f, 0.6f, 0.7f), source = FigureSource.Redrawn)),
            ),
        )
        val back = NoteWire.parse(NoteWire.encode(doc))
        assertEquals(FigureSource.Redrawn, back.elements[1].figure!!.source)
        assertEquals(0.2f, back.elements[1].figure!!.box!!.left, 1e-3f)
        assertNotNull(back.elements[1].diagram)
        val forAi = NoteWire.encodeForAi(doc)
        assertFalse("no drawing sent to the AI: $forAi", forAi.contains("shapes") || forAi.contains("box"))
        assertTrue(forAi.contains("\"ref\":0"))
        // The model keeps the ref; the drawing comes back.
        val merged = NoteWire.parse("""{"elements":[{"e":"Heading","t":"Forces"},{"e":"Diagram","h":"Incline","k":"diagram","ref":0},{"e":"Formula","m":"N=mg\\cos\\alpha"}]}""")
        val re = NoteWire.reattach(merged, doc)
        assertEquals(drawn.shapes.size, re.elements[1].diagram!!.shapes.size)
        assertEquals(FigureSource.Redrawn, re.elements[1].figure!!.source)
    }

    @Test fun `the prompt asks for boxes and labels, not described drawings, and stays compact`() {
        val t = app.bestnote.engine.layout.NoteTemplates.byId("classic")
        val system = NotePrompts.system(NotesRequest(t))
        assertTrue(system.contains("box = [left, top, right, bottom]"))
        assertTrue(system.contains("lbl"))
        assertFalse(system.contains("fbd"))
        val schema = NoteSchema.document(t).toString()
        assertTrue(schema.contains("\"box\"") && schema.contains("\"lbl\""))
        assertFalse(schema.contains("\"fbd\""))
    }
}
