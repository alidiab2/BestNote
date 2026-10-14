package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryNotebookStoreTest {
    private fun pkg(id: String, updated: Long, bytes: ByteArray = byteArrayOf(1, 2, 3)): NotebookPackage {
        val nid = NotebookId(id)
        return NotebookPackage(Notebook(nid, "Notebook $id", 0L, updated), listOf(PageContent(Page(nid, 0), bytes)))
    }

    @Test fun writeReadListDelete() = runTest {
        val store = InMemoryNotebookStore()
        store.write(pkg("a", updated = 1))
        store.write(pkg("b", updated = 2))

        assertEquals(listOf("b", "a"), store.list().map { it.id.value })
        assertArrayEquals(byteArrayOf(1, 2, 3), store.read(NotebookId("a"))!!.pages.single().strokes)

        assertTrue(store.delete(NotebookId("a")))
        assertFalse(store.delete(NotebookId("a")))
        assertNull(store.read(NotebookId("a")))
    }

    @Test fun storedBytesAreIsolatedFromCaller() = runTest {
        val store = InMemoryNotebookStore()
        val bytes = byteArrayOf(9)
        store.write(pkg("a", updated = 1, bytes = bytes))
        bytes[0] = 0
        assertEquals(9.toByte(), store.read(NotebookId("a"))!!.pages.single().strokes[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun pagesMustBelongToNotebook() {
        NotebookPackage(Notebook(NotebookId("a"), "A", 0, 0), listOf(PageContent(Page(NotebookId("b"), 0), ByteArray(0))))
    }
}
