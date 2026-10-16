package app.bestnote.ai.vision

import app.bestnote.core.model.CalloutTone
import app.bestnote.core.model.InkSemantic
import app.bestnote.core.model.LayoutTemplate
import app.bestnote.core.model.PageBlock
import app.bestnote.core.model.PageSize
import app.bestnote.core.model.RectPt
import app.bestnote.core.model.VectorShape
import app.bestnote.engine.layout.NotebookPalette
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelinesTest {
    // A physics board the way a model answers it: logical order differs from the list order,
    // colours carry intent, the diagram is wobbly, and two blocks are unusable.
    private val response = """
    {
      "title": "Inclined plane",
      "blocks": [
        {"type": "proof", "id": "p", "order": 3, "title": "Acceleration along the slope", "steps": [
          {"latex": "F_\\parallel = mg\\sin\\theta", "because": "component of gravity"},
          {"latex": "a = g\\sin\\theta", "because": "Newton's second law"},
          {"text": "", "latex": ""}
        ]},
        {"type": "margin_note", "annotates": "p", "text": "Ignore friction for now", "ink_color": "green"},
        {"type": "definition", "order": 0, "title": "Normal force", "text": "Perpendicular to the surface"},
        {"type": "math", "order": 2, "latex": "${'$'}${'$'}F = ma${'$'}${'$'}", "ink_color": "red"},
        {"type": "math", "order": 4, "latex": "\\input{secrets}"},
        {"type": "sticker"},
        {"type": "diagram", "order": 1, "diagram": {"size": [200, 120], "shapes": [
          {"kind": "polygon", "points": [0,120, 199,118, 200,20]},
          {"kind": "arrow", "points": [125,65, 126,115], "color": "red"},
          {"kind": "label", "points": [128,118], "text": "mg", "latex": true},
          {"kind": "path", "d": "M 0 0 <script>"}
        ]}},
        {"type": "callout", "order": 5, "text": "Steeper slope, faster slide", "semantic": "essential"}
      ]
    }
    """.trimIndent()

    private val fake = object : VisionProvider {
        val doc = Json { ignoreUnknownKeys = true }.decodeFromString(AiDocument.serializer(), response)
        override suspend fun reconstructBoard(image: VisionImage, request: BoardRequest) = doc
        override suspend fun beautify(selection: VisionImage, widthPt: Float, heightPt: Float, language: String?) = doc
    }

    private var n = 0
    private val ids = { "b${n++}" }
    private val png = VisionImage(ByteArray(1), "image/png")

    private suspend fun reconstruct(template: LayoutTemplate = LayoutTemplate.Minimalist) =
        BoardReconstructor(fake, RoughMeasurer, ids).run(png, BoardRequest(template), PageSize.A4)

    @Test fun `keeps valid blocks and drops broken ones`() = runTest {
        val r = reconstruct()
        assertEquals(2, r.dropped) // forbidden LaTeX + unknown type with no words
        assertEquals(7, r.pages.flatten().size)
    }

    @Test fun `blocks follow the logical lecture order, not the list order`() = runTest {
        val blocks = reconstruct().pages.flatten().filter { it !is PageBlock.MarginNote }
        val kinds = blocks.map { it::class.simpleName }
        assertEquals(listOf("Heading", "Callout", "Diagram", "Math", "Proof", "Callout"), kinds)
        // Reading order on the page matches the sequence.
        assertTrue(blocks.zipWithNext().all { (a, b) -> a.frame.top < b.frame.top })
    }

    @Test fun `red formulas are boxed and colour intent is kept`() = runTest {
        val blocks = reconstruct().pages.flatten()
        val math = blocks.filterIsInstance<PageBlock.Math>().single()
        assertEquals("F = ma", math.latex)
        assertTrue(math.boxed)
        assertEquals(InkSemantic.Essential, math.semantic)
        val definition = blocks.filterIsInstance<PageBlock.Callout>().first()
        assertEquals(CalloutTone.Definition, definition.tone)
        assertEquals(CalloutTone.KeyResult, blocks.filterIsInstance<PageBlock.Callout>().last().tone)
    }

    @Test fun `proofs keep their justified steps and drop empty ones`() = runTest {
        val proof = reconstruct().pages.flatten().filterIsInstance<PageBlock.Proof>().single()
        assertEquals(2, proof.steps.size)
        assertEquals("Newton's second law", proof.steps[1].justification)
    }

    @Test fun `margin note sits beside the proof it annotates`() = runTest {
        val blocks = reconstruct().pages.flatten()
        val proof = blocks.filterIsInstance<PageBlock.Proof>().single()
        val note = blocks.filterIsInstance<PageBlock.MarginNote>().single()
        assertEquals(proof.id, note.anchor)
        assertEquals(proof.frame.top, note.frame.top)
        assertTrue(note.frame.right < proof.frame.left)
        assertEquals(InkSemantic.Annotation, note.semantic)
    }

    @Test fun `diagram is redrawn with a ruler and recoloured with the notebook palette`() = runTest {
        val d = reconstruct().pages.flatten().filterIsInstance<PageBlock.Diagram>().single().diagram
        assertEquals(3, d.shapes.size) // the unsafe path is dropped
        val slope = d.shapes.filterIsInstance<VectorShape.Polyline>().single()
        assertEquals(slope.points[0].y, slope.points[1].y) // ground line is level
        val force = d.shapes.filterIsInstance<VectorShape.Arrow>().single()
        assertEquals(force.from.x, force.to.x) // gravity points straight down
        assertEquals(NotebookPalette.ESSENTIAL, force.style.colorArgb)
    }

    @Test fun `cornell puts margin notes in the cue column`() = runTest {
        val blocks = reconstruct(LayoutTemplate.Cornell).pages.flatten()
        val note = blocks.filterIsInstance<PageBlock.MarginNote>().single()
        val proof = blocks.filterIsInstance<PageBlock.Proof>().single()
        assertTrue(note.frame.right < proof.frame.left)
    }

    @Test fun `beautified blocks stay inside the selection's column`() = runTest {
        val bounds = RectPt(100f, 200f, 400f, 900f)
        val blocks = NoteBeautifier(fake, RoughMeasurer, ids).run(png, bounds)
        assertTrue(blocks.all { it.frame.left == 100f && it.frame.right == 400f && it.frame.top >= 200f })
    }
}
