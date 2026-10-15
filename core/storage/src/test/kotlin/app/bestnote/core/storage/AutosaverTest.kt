package app.bestnote.core.storage

import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class AutosaverTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Records every commit; can be made to fail or to block. */
    private class RecordingStore : NotebookStore {
        val delegate = InMemoryNotebookStore()
        val commits = mutableListOf<Set<String>>()
        var failNext = 0
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun list() = delegate.list()
        override suspend fun read(id: NotebookId) = delegate.read(id)
        override suspend fun write(pkg: NotebookPackage) = writePages(pkg, pkg.pages.map { it.id }.toSet())
        override suspend fun delete(id: NotebookId) = delegate.delete(id)
        override suspend fun writePages(pkg: NotebookPackage, changedPageIds: Set<String>) {
            gate?.await()
            if (failNext > 0) {
                failNext--
                throw IOException("disk full")
            }
            commits += changedPageIds
            delegate.write(pkg)
        }
    }

    private val nid = NotebookId("nb")

    private fun initial(pages: Int = 3) = NotebookPackage(
        Notebook(nid, "Notes", 1, 1, pageCount = pages),
        List(pages) { PageContent(Page(nid, it), InkChunkCodec.encode(emptyList()), "p$it") },
    )

    private fun TestScope.saver(store: NotebookStore, saved: MutableList<NotebookPackage>? = null) = Autosaver(
        initial(),
        store,
        backgroundScope,
        cpu = StandardTestDispatcher(testScheduler),
        clock = { 1_000 + testScheduler.currentTime },
        timeSource = testScheduler.timeSource,
        onSaved = { saved?.add(it) },
    )

    private fun strokes(seed: Int): List<InkStroke> = randomStrokes(5, seed)

    private suspend fun NotebookStore.page(id: String) = InkChunkCodec.decode(read(nid)!!.pages.first { it.id == id }.strokes)

    @Test fun burstOfEditsIsDebouncedIntoOneSave() = runTest {
        val store = RecordingStore()
        val s = saver(store)
        repeat(4) {
            s.updatePage("p0", strokes(it))
            testScheduler.advanceTimeBy(100)
        }
        runCurrent()
        assertTrue(store.commits.isEmpty())
        assertEquals(SaveState.Dirty, s.state.value)
        testScheduler.advanceTimeBy(Autosaver.DEFAULT_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertEquals(listOf(setOf("p0")), store.commits)
        assertEquals(strokes(3), store.page("p0"))
        assertEquals(SaveState.Saved, s.state.value)
        assertFalse(s.hasUnsavedChanges)
    }

    @Test fun continuousWritingStillSavesWithinMaxDelay() = runTest {
        val store = RecordingStore()
        val s = saver(store)
        repeat(30) {
            s.updatePage("p1", strokes(it))
            testScheduler.advanceTimeBy(300) // never quiet for the debounce window
        }
        // 9 s of continuous edits: a save at least every max delay.
        assertTrue("commits=${store.commits.size}", store.commits.size >= 9_000 / Autosaver.DEFAULT_MAX_DELAY_MILLIS.toInt() - 1)
    }

    @Test fun onlyDirtyPagesAreRewritten() = runTest {
        val store = RecordingStore()
        val saved = mutableListOf<NotebookPackage>()
        val s = saver(store, saved)
        s.updatePage("p2", strokes(1))
        s.updatePage("p0", strokes(2))
        s.flush()
        assertEquals(listOf(setOf("p2", "p0")), store.commits)
        assertEquals(strokes(2), store.page("p0"))
        assertEquals(emptyList<InkStroke>(), store.page("p1"))
        assertEquals(strokes(1), store.page("p2"))
        assertEquals(1, saved.size)
        assertTrue(saved[0].notebook.updatedAtMillis > 1)
        assertEquals(saved[0].notebook, s.notebook)
    }

    @Test fun flushWithNothingPendingWritesNothing() = runTest {
        val store = RecordingStore()
        saver(store).flush()
        assertTrue(store.commits.isEmpty())
    }

    @Test fun editsMadeDuringASaveAreSavedNext() = runTest {
        val store = RecordingStore()
        val s = saver(store)
        val gate = CompletableDeferred<Unit>().also { store.gate = it }
        s.updatePage("p0", strokes(1))
        val first = launch { s.flush() }
        runCurrent() // first save is now blocked inside the store
        s.updatePage("p0", strokes(2)) // returns immediately although the disk is busy
        s.updatePage("p1", strokes(3))
        gate.complete(Unit)
        first.join()
        s.flush()
        assertEquals(listOf(setOf("p0"), setOf("p0", "p1")), store.commits)
        assertEquals(strokes(2), store.page("p0"))
        assertEquals(strokes(3), store.page("p1"))
    }

    @Test fun failedSaveIsReportedAndRetried() = runTest {
        val store = RecordingStore().apply { failNext = 2 }
        val s = saver(store)
        s.updatePage("p0", strokes(7))
        testScheduler.advanceTimeBy(Autosaver.DEFAULT_DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertTrue(s.state.value is SaveState.Failed)
        assertTrue(s.hasUnsavedChanges)
        testScheduler.advanceTimeBy(10_000) // two retries with backoff (background work is not "idle"-tracked)
        runCurrent()
        assertEquals(SaveState.Saved, s.state.value)
        assertEquals(strokes(7), store.page("p0"))
    }

    @Test fun closeFlushesAndRejectsLaterEdits() = runTest {
        val store = RecordingStore()
        val s = saver(store)
        s.updatePage("p1", strokes(4))
        s.close()
        assertEquals(strokes(4), store.page("p1"))
        val e = runCatching { s.updatePage("p1", strokes(5)) }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
    }

    @Test fun unknownPageIsRejected() = runTest {
        val e = runCatching { saver(RecordingStore()).updatePage("nope", strokes(1)) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test fun encodingIsDeferredToTheSaveNotTheCaller() = runTest {
        var encodes = 0
        val codec = object : StrokeChunkCodec {
            override fun encode(strokes: List<InkStroke>): ByteArray = InkChunkCodec.encode(strokes).also { encodes++ }
            override fun decode(bytes: ByteArray) = InkChunkCodec.decode(bytes)
        }
        val s = Autosaver(initial(), RecordingStore(), backgroundScope, codec, StandardTestDispatcher(testScheduler))
        repeat(50) { s.updatePage("p0", strokes(it)) }
        assertEquals(0, encodes)
        s.flush()
        assertEquals(1, encodes)
    }

    @Test fun savesThroughTheFileStoreTouchOnlyDirtyPageFiles() = runTest {
        val store = FileNotebookStore(tmp.root)
        store.write(initial())
        val untouched = tmp.root.resolve("nb/pages/p1.ink").apply { setLastModified(1_000_000) }
        val s = saver(store)
        s.updatePage("p0", strokes(9))
        s.flush()
        assertEquals(1_000_000, untouched.lastModified())
        val reread = FileNotebookStore(tmp.root).read(nid)!!
        assertEquals(strokes(9), InkChunkCodec.decode(reread.pages[0].strokes))
        assertArrayEquals(InkChunkCodec.encode(emptyList()), reread.pages[1].strokes)
        assertEquals(s.notebook, reread.notebook)
    }

    @Test fun firstSaveCreatesTheWholePackage() = runTest {
        val store = FileNotebookStore(tmp.root)
        val s = saver(store)
        s.updatePage("p2", strokes(1))
        s.flush()
        val reread = store.read(nid)!!
        assertEquals(listOf("p0", "p1", "p2"), reread.pages.map { it.id })
        assertEquals(strokes(1), InkChunkCodec.decode(reread.pages[2].strokes))
    }
}
