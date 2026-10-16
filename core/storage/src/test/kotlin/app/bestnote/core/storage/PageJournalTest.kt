package app.bestnote.core.storage

import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.TextElement
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** DOC-11: the per-page edit journal, against a real [FileNotebookStore]. */
class PageJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private val nid = NotebookId("nb")
    private val base = randomStrokes(300, seed = 7, maxSamples = 20)

    private fun store() = FileNotebookStore(tmp.root)
    private fun pageFile(ext: String) = File(tmp.root, "nb/pages/p0.$ext")

    private suspend fun seed(store: FileNotebookStore, strokes: List<InkStroke> = base) {
        store.write(
            NotebookPackage(Notebook(nid, "N", 1, 1, pageCount = 1), listOf(PageContent(Page(nid, 0), InkChunkCodec.encode(strokes), "p0"))),
        )
    }

    private suspend fun FileNotebookStore.strokes() = InkChunkCodec.decode(read(nid)!!.pages.single().strokes)

    private suspend fun TestScope.open(store: FileNotebookStore, strokes: List<InkStroke> = base, onSaved: (NotebookPackage) -> Unit = {}): Autosaver {
        store.compactJournals(nid)
        val pkg = store.read(nid)!!
        val saver = Autosaver(
            pkg, store, backgroundScope, cpu = Dispatchers.Unconfined, idleCompactMillis = 0,
            maxJournalOps = JOURNAL_OPS, onSaved = { onSaved(it) },
        )
        saver.adoptBaseline("p0", strokes)
        return saver
    }

    private fun same(a: List<InkStroke>, b: List<InkStroke>) = assertEquals(a, b)

    @Test fun `an added stroke costs its own size, and chunk plus journal reads back as the edit`() = runTest {
        val store = store()
        seed(store)
        val chunkBefore = pageFile("ink").readBytes()
        val saver = open(store)
        val extra = randomStrokes(1, seed = 99, maxSamples = 20)
        saver.updatePage("p0", base + extra)
        saver.flush()
        assertArrayEquals("the chunk is not rewritten", chunkBefore, pageFile("ink").readBytes())
        assertTrue("journal ${pageFile("jrnl").length()} B vs chunk ${chunkBefore.size} B", pageFile("jrnl").length() < chunkBefore.size / 20)
        same(base + extra, store.strokes())
    }

    @Test fun `erased and transformed strokes are journalled`() = runTest {
        val store = store()
        seed(store)
        val saver = open(store)
        val moved = base[10].copy(samples = base[10].samples.map { it.copy(x = it.x + 1f) })
        val edited = base.filterIndexed { i, _ -> i != 3 && i != 200 }.map { if (it === base[10]) moved else it }
        saver.updatePage("p0", edited)
        saver.flush()
        assertTrue(pageFile("jrnl").isFile)
        same(edited, store.strokes())
    }

    @Test fun `a torn last record is ignored and the earlier edits survive`() = runTest {
        val store = store()
        seed(store)
        val saver = open(store)
        val one = base + randomStrokes(1, seed = 1, maxSamples = 20)
        saver.updatePage("p0", one)
        saver.flush()
        val afterOne = pageFile("jrnl").length()
        saver.updatePage("p0", one + randomStrokes(1, seed = 2, maxSamples = 20))
        saver.flush()
        val full = pageFile("jrnl").readBytes()
        for (keep in listOf(afterOne + 1, afterOne + 5, afterOne + 8, afterOne + 100, full.size - 1L)) {
            pageFile("jrnl").writeBytes(full.copyOf(keep.toInt()))
            assertEquals("kept $keep of ${full.size}", one, store.strokes())
        }
        pageFile("jrnl").writeBytes(full)
        // A flipped byte inside the second record fails its checksum: same result.
        RandomAccessFile(pageFile("jrnl"), "rw").use { it.seek(afterOne + 20); it.write(it.read() xor 0x55) }
        same(one, store.strokes())
    }

    @Test fun `appending after a torn tail overwrites it`() = runTest {
        val store = store()
        seed(store)
        val first = open(store)
        val one = base + randomStrokes(1, seed = 1, maxSamples = 20)
        first.updatePage("p0", one)
        first.flush()
        pageFile("jrnl").appendBytes(byteArrayOf(0, 0, 1, 0, 9, 9)) // a crash mid-append
        // Next session: open() folds what is valid, then continues from a clean state.
        val second = open(FileNotebookStore(tmp.root), one)
        val two = one + randomStrokes(1, seed = 2, maxSamples = 20)
        second.updatePage("p0", two)
        second.flush()
        same(two, store().strokes())
    }

    @Test fun `compaction gives the same page as chunk plus journal`() = runTest {
        val store = store()
        seed(store)
        val saver = open(store)
        var current = base
        repeat(5) { i ->
            current = current.filterIndexed { k, _ -> k % 50 != i } + randomStrokes(2, seed = 10 + i, maxSamples = 20)
            saver.updatePage("p0", current)
            saver.flush()
        }
        val replayed = store.strokes()
        same(current, replayed)
        saver.compact()
        assertFalse("journal is gone", pageFile("jrnl").exists())
        same(replayed, InkChunkCodec.decode(pageFile("ink").readBytes()))
        assertArrayEquals(InkChunkCodec.encode(current), pageFile("ink").readBytes())
    }

    @Test fun `closing compacts, and the journal limits trigger compaction`() = runTest {
        val store = store()
        seed(store)
        val saver = open(store)
        var current = base
        repeat(JOURNAL_OPS + 1) { i ->
            current = current + randomStrokes(1, seed = 50 + i, maxSamples = 20)
            saver.updatePage("p0", current)
            saver.flush()
        }
        assertFalse("past $JOURNAL_OPS edits the page is rewritten", pageFile("jrnl").exists())
        same(current, InkChunkCodec.decode(pageFile("ink").readBytes()))
        current = current + randomStrokes(1, seed = 77, maxSamples = 20)
        saver.updatePage("p0", current)
        saver.flush()
        assertTrue(pageFile("jrnl").isFile)
        saver.close()
        assertFalse(pageFile("jrnl").exists())
        same(current, InkChunkCodec.decode(pageFile("ink").readBytes()))
    }

    @Test fun `a journal that survived a crash after compaction is not replayed twice`() = runTest {
        val store = store()
        seed(store)
        val saver = open(store)
        val edited = base + randomStrokes(3, seed = 5, maxSamples = 20)
        saver.updatePage("p0", edited)
        saver.flush()
        val journal = pageFile("jrnl").readBytes()
        saver.compact()
        pageFile("jrnl").writeBytes(journal) // crash between "chunk renamed" and "journal deleted"
        same(edited, store.strokes())
        store.compactJournals(nid)
        assertFalse(pageFile("jrnl").exists())
        same(edited, store.strokes())
    }

    @Test fun `undo and redo after a reload`() = runTest {
        val store = store()
        seed(store)
        val withStroke = base + randomStrokes(1, seed = 3, maxSamples = 20)
        val first = open(store)
        first.updatePage("p0", withStroke)
        first.flush() // process dies here: no close()
        // Reopen: strokes come from disk, so they are new instances; the editor's undo works on those.
        val reopened = store().strokes()
        same(withStroke, reopened)
        val second = open(store(), reopened)
        second.updatePage("p0", reopened.dropLast(1)) // undo
        second.flush()
        same(base, store().strokes())
        second.updatePage("p0", reopened) // redo
        second.flush()
        same(withStroke, store().strokes())
        second.close()
        same(withStroke, store().strokes())
    }

    @Test fun `element and layout saves keep the journal valid and the chunk untouched`() = runTest {
        val store = store()
        seed(store)
        val saver = open(store)
        val edited = base + randomStrokes(2, seed = 8, maxSamples = 20)
        saver.updatePage("p0", edited)
        saver.flush()
        val chunk = pageFile("ink").readBytes()
        val box = TextElement("t1", 10f, 10f, 100f, 20f, "hi")
        saver.updateElements("p0", listOf(box))
        saver.flush()
        assertTrue(pageFile("jrnl").isFile)
        assertArrayEquals(chunk, pageFile("ink").readBytes())
        val pkg = store().read(nid)!!
        assertEquals(listOf<Any>(box), pkg.pages.single().elements)
        same(edited, InkChunkCodec.decode(pkg.pages.single().strokes))
    }

    @Test fun `a reordering edit falls back to a whole page save`() = runTest {
        val store = store()
        seed(store)
        val saver = open(store)
        saver.updatePage("p0", base.reversed())
        saver.flush()
        assertFalse(pageFile("jrnl").exists())
        same(base.reversed(), store.strokes())
    }

    @Test fun `diff only emits what changed`() {
        val edit = PageJournal.diff(base, base.take(100) + base.drop(101) + base[0], 64)
        assertNull("moving a stroke to the end is a remove plus an add", edit?.takeIf { false })
        assertNotNull(edit)
        assertEquals(1, edit!!.removed.size)
        assertEquals(1, edit.added.size)
    }

    private companion object {
        const val JOURNAL_OPS = 4
    }
}
