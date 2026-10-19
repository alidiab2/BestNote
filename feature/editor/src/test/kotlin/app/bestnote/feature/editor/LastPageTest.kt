package app.bestnote.feature.editor

import app.bestnote.core.storage.NotebookPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** REQ-006: the last page viewed survives in a notebook extra, so the editor can reopen on it. */
class LastPageTest {
    @Test fun extraNameIsAValidNotebookExtra() {
        assertTrue(NotebookPackage.EXTRA_NAME.matches(LastPage.EXTRA))
    }

    @Test fun decodesWhatItEncodes() {
        assertEquals(7, LastPage.decode(LastPage.encode(7)))
        assertEquals(0, LastPage.decode(LastPage.encode(0)))
        assertEquals(999, LastPage.decode(LastPage.encode(999)))
    }

    @Test fun nothingStoredOpensOnTheFirstPage() {
        assertEquals(0, LastPage.decode(null))
        assertEquals(0, LastPage.decode(ByteArray(0)))
    }

    @Test fun unusableContentNeverThrowsAndFallsBackToTheFirstPage() {
        assertEquals(0, LastPage.decode("not json".encodeToByteArray()))
        assertEquals(0, LastPage.decode("""{"page":"3"}""".encodeToByteArray()))
        assertEquals(0, LastPage.decode("""{"other":3}""".encodeToByteArray()))
        assertEquals(0, LastPage.decode("""{"page":-3}""".encodeToByteArray()))
    }
}
