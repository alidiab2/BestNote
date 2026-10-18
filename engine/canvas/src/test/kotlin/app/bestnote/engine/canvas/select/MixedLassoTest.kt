package app.bestnote.engine.canvas.select

import app.bestnote.core.model.EquationElement
import app.bestnote.core.model.ImageElement
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextAlignment
import app.bestnote.core.model.TextElement
import app.bestnote.core.model.TextRun
import app.bestnote.core.model.RunStyle
import app.bestnote.engine.canvas.document.PagedDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** CANVAS-20: the lasso picks every kind through its filter, transforms them together, and styles all text boxes at once. */
class MixedLassoTest {
    /** A fake stroke: its centerline and whether it is a recognized shape. */
    private data class Fake(val name: String, val xs: FloatArray, val ys: FloatArray, val shape: Boolean = false, val closed: Boolean = false)

    private val ink = Fake("ink", floatArrayOf(20f, 40f, 60f), floatArrayOf(20f, 25f, 20f))
    private val square = Fake("square", floatArrayOf(100f, 140f, 140f, 100f, 100f), floatArrayOf(20f, 20f, 60f, 60f, 20f), shape = true, closed = true)
    private val text = TextElement("t", 50f, 100f, 60f, 20f, "مرحبا")
    private val hebrew = TextElement("h", 130f, 100f, 60f, 20f, "שלום", bold = true)
    private val image = ImageElement("i", 50f, 160f, 40f, 30f, "assets/a.jpg", 400, 300)
    private val equation = EquationElement("e", 130f, 160f, 40f, 20f, "x^2")
    private val locked = ImageElement("l", 90f, 200f, 20f, 20f, "assets/b.jpg", 100, 100, locked = true)
    private val strokes = listOf(ink, square)
    private val elements: List<PageElement> = listOf(text, hebrew, image, equation, locked)

    /** A lasso loop around the whole page area used here. */
    private val all = LassoPolygon(floatArrayOf(0f, 200f, 200f, 0f), floatArrayOf(0f, 0f, 240f, 240f))

    private fun pick(filter: LassoFilter): List<String> =
        LassoPick.strokes(strokes, all, filter, { it.shape }, { it.xs to it.ys }).map { it.name } +
            LassoPick.elements(elements, all, filter).map { it.id }

    @Test fun lassoPicksEveryKindTogetherAndEachFilterOnlyItsKind() {
        assertEquals(listOf("ink", "square", "t", "h", "i", "e"), pick(LassoFilter.All)) // never the locked picture
        assertEquals(listOf("ink"), pick(LassoFilter.Handwriting))
        assertEquals(listOf("square"), pick(LassoFilter.Shapes))
        assertEquals(listOf("t", "h", "e"), pick(LassoFilter.Text))
        assertEquals(listOf("i"), pick(LassoFilter.Images))
    }

    @Test fun tapSelectsOneObjectOfAKindTheFilterTakes() {
        assertEquals("i", LassoPick.tappedElement(elements, 50f, 160f, LassoFilter.All)?.id)
        assertNull(LassoPick.tappedElement(elements, 50f, 160f, LassoFilter.Text))
        // Locked pictures are still tappable (to unlock them).
        assertEquals("l", LassoPick.tappedElement(elements, 90f, 200f, LassoFilter.Images)?.id)
        // A shape: on its line or inside it (closed); never plain handwriting.
        fun shapeAt(x: Float, y: Float, f: LassoFilter = LassoFilter.All) =
            LassoPick.tappedShape(strokes, x, y, 3f, f, { it.shape }, { it.closed }, { it.xs to it.ys })?.name
        assertEquals("square", shapeAt(141f, 40f))
        assertEquals("square", shapeAt(120f, 40f))
        assertNull(shapeAt(40f, 25f))
        assertNull(shapeAt(120f, 40f, LassoFilter.Handwriting))
        assertNull(shapeAt(170f, 40f))
    }

    @Test fun selectAllFollowsTheFilterAndSkipsLocked() {
        val (s, e) = LassoPick.all(strokes, elements, LassoFilter.All) { it.shape }
        assertEquals(2, s.size)
        assertEquals(listOf("t", "h", "i", "e"), e.map { it.id })
        val (s2, e2) = LassoPick.all(strokes, elements, LassoFilter.Text) { it.shape }
        assertTrue(s2.isEmpty())
        assertEquals(listOf("t", "h", "e"), e2.map { it.id })
    }

    @Test fun mixedTransformMovesEverythingAsOneUndoStep() {
        val d = PagedDocument().apply { reset(listOf(Page(NotebookId("nb"), 0)), listOf("p0")) }
        d.replaceElements(0, elements)
        val m = Affine.scaleAbout(2f, 0f, 0f)
        d.editContent(0, elements = d.pages[0].elements.map { ElementTransforms.transformed(it, m) })
        val after = d.pages[0].elements.associateBy { it.id }
        assertEquals(100f, after.getValue("t").cx, 1e-3f)
        assertEquals(40f, (after.getValue("t") as TextElement).sizePt, 1e-3f) // text grows with the box
        assertEquals(80f, after.getValue("i").width, 1e-3f)
        assertEquals(EquationElement.DEFAULT_SIZE_PT * 2, (after.getValue("e") as EquationElement).sizePt, 1e-3f)
        assertSame(locked, after.getValue("l")) // locked stays put
        d.undo()
        assertEquals(elements, d.pages[0].elements)
    }

    @Test fun bulkTextStyleChangesEveryTextBoxAndUndoesInOneStep() {
        val d = PagedDocument().apply { reset(listOf(Page(NotebookId("nb"), 0)), listOf("p0")) }
        d.replaceElements(0, elements)
        val style = TextBulkStyle(font = "noto-naskh-arabic", sizeStep = 2f, italic = true, align = TextAlignment.Center)
        d.editContent(0, elements = d.pages[0].elements.map { if (it is TextElement) style.applyTo(it) else it })
        val texts = d.pages[0].elements.filterIsInstance<TextElement>()
        assertEquals(listOf("noto-naskh-arabic", "noto-naskh-arabic"), texts.map { it.font })
        assertEquals(listOf(22f, 22f), texts.map { it.sizePt })
        assertTrue(texts.all { it.italic && it.align == TextAlignment.Center })
        assertTrue(texts.single { it.id == "h" }.bold) // what the change does not name stays as it was
        assertSame(image, d.pages[0].elements.single { it.id == "i" })
        d.undo()
        assertEquals(elements, d.pages[0].elements)
    }

    @Test fun bulkStyleDetails() {
        // Unchanged boxes stay the same instance (no empty undo step).
        assertSame(text, TextBulkStyle(bold = false).applyTo(text))
        // Size is clamped; a box colour or font replaces per-run ones so the change shows everywhere.
        val big = text.copy(sizePt = TextElement.MAX_SIZE_PT, runs = listOf(TextRun(0, 2, RunStyle(bold = true, colorArgb = 0xFFFF0000.toInt(), font = "hand"))))
        assertEquals(TextElement.MAX_SIZE_PT, TextBulkStyle(sizeStep = 2f).applyTo(big).sizePt)
        val recolored = TextBulkStyle(colorArgb = 0xFF0000FF.toInt()).applyTo(big)
        assertNull(recolored.runs.single().style.colorArgb)
        assertEquals("hand", recolored.runs.single().style.font)
        assertNull(TextBulkStyle(clearFont = true).applyTo(big.copy(font = "lora")).font)
        // The bar's state: on only when every box has it.
        val s = TextBulkStyle.summary(listOf(text, hebrew))
        assertFalse(s.bold)
        assertTrue(TextBulkStyle.summary(listOf(hebrew)).bold)
        assertNull(s.font)
        assertFalse(s.mixedFonts)
        assertTrue(TextBulkStyle.summary(listOf(text, hebrew.copy(font = "heebo"))).mixedFonts)
    }

    @Test fun nearMeasuresDistanceToThePolyline() {
        assertTrue(LassoPick.near(floatArrayOf(0f, 10f), floatArrayOf(0f, 0f), 5f, 2f, 2f))
        assertFalse(LassoPick.near(floatArrayOf(0f, 10f), floatArrayOf(0f, 0f), 5f, 3f, 2f))
        assertFalse(LassoPick.near(FloatArray(0), FloatArray(0), 0f, 0f, 1f))
    }
}
