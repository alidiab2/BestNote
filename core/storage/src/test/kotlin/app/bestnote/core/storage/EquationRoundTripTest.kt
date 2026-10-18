package app.bestnote.core.storage

import app.bestnote.core.model.EquationElement
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement
import app.bestnote.core.model.TextElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** MATH-01: equations survive the `.bnote` format (v10), older files still read, and locked notebooks keep them. */
class EquationRoundTripTest {
    @get:Rule val tmp = TemporaryFolder()
    private val nid = NotebookId("eq")

    private val quadratic = EquationElement(
        "el-q", 300f, 200f, 260f, 70f, "x = \\frac{-b \\pm \\sqrt{b^2 - 4ac}}{2a}", sizePt = 28f, colorArgb = 0xFF2A4BD7.toInt(),
        rotation = 0.3f, locked = true, group = "g-1",
    )
    private val inline = EquationElement("el-i", 100f, 400f, 120f, 30f, "\\text{السرعة} = \\frac{d}{t}", display = false)
    private val words = TextElement("el-t", 100f, 80f, 180f, 30f, "Quadratic formula")

    private fun pkg(elements: List<PageElement> = listOf(words, quadratic, inline)) = NotebookPackage(
        Notebook(nid, "Maths", 1, 2, pageCount = 1),
        listOf(PageContent(Page(nid, 0), InkChunkCodec.encode(randomStrokes(3, seed = 1)), "p0", elements)),
    )

    @Test fun equationsRoundTripThroughTheFormat() {
        val entries = BnoteFormat.toEntries(pkg())
        val back = BnoteFormat.fromEntries { entries[it] }
        assertEquals(listOf(words, quadratic, inline), back.pages[0].elements)
        val page = entries.getValue("pages/p0.json").decodeToString()
        assertTrue(page.contains("\"type\":\"equation\"") || page.contains("\"type\": \"equation\""))
        assertTrue(page.contains("\\\\frac{-b"))
    }

    @Test fun formatIsTenAndNineStillReads() {
        assertEquals(10, BnoteFormat.VERSION)
        assertTrue(BnoteFormat.SEALED_FORMAT > BnoteFormat.VERSION)
        val v9 = BnoteFormat.toEntries(pkg(listOf(words))).mapValues { (k, v) ->
            if (k.endsWith(".json")) v.decodeToString().replace(Regex("\"format\":\\s*10"), "\"format\": 9").encodeToByteArray() else v
        }
        assertTrue(v9.getValue(BnoteFormat.MANIFEST).decodeToString().contains("\"format\": 9"))
        assertEquals(listOf(words), BnoteFormat.fromEntries { v9[it] }.pages[0].elements)
    }

    @Test fun unusableEquationNumbersAreDroppedNotFatal() {
        val entries = BnoteFormat.toEntries(pkg(listOf(quadratic)))
        val broken = entries.mapValues { (k, v) -> if (k == "pages/p0.json") v.decodeToString().replace("\"w\":260.0", "\"w\":-1.0").replace("\"w\": 260.0", "\"w\": -1.0").encodeToByteArray() else v }
        assertTrue(BnoteFormat.fromEntries { broken[it] }.pages[0].elements.isEmpty())
    }

    @Test fun lockedNotebookKeepsItsEquations() = runTest {
        val ring = NotebookKeyring()
        val store = FileNotebookStore(tmp.root, keyring = ring)
        val original = pkg()
        store.write(original)
        val key = SealCrypto.newDataKey()
        val header = SealHeader(keyCheck = SealCrypto.keyCheck(key), wraps = listOf(KeyWrap(KeyWrap.KIND_DEVICE, "AAAA", alias = "test")))
        assertTrue(store.seal(nid, key, header))
        ring.put(nid, key, header)
        assertEquals(listOf(words, quadratic, inline), store.read(nid)!!.pages[0].elements)
        // Edited while unlocked: the new equation is saved encrypted and reads back.
        val p0 = original.pages[0]
        val edited = PageContent(p0.page, p0.strokes, p0.id, listOf(quadratic.copy(latex = "\\sum_{k=1}^{n} k")))
        store.writePages(NotebookPackage(original.notebook.copy(updatedAtMillis = 50), listOf(edited)), setOf(edited.id))
        assertEquals("\\sum_{k=1}^{n} k", (store.read(nid)!!.pages[0].elements.single() as EquationElement).latex)
    }

    @Test fun equationStyleRoundTrips() {
        val hand = quadratic.copy(style = app.bestnote.core.model.EquationStyle.Handwritten)
        val back = BnoteFormat.fromEntries(BnoteFormat.toEntries(pkg(listOf(hand, inline)))::get)
        assertEquals(listOf(hand, inline), back.pages[0].elements)
    }
}
