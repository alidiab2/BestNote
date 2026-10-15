package app.bestnote.core.database

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PaperTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookSearchTest {
    @Test fun userTextBecomesOneLiteralPhrase() {
        assertEquals("\"a OR b\"", NotebookSearch.phrase("a OR b"))
        assertEquals("\"say \"\"hi\"\"\"", NotebookSearch.phrase("say \"hi\""))
    }

    @Test fun likeWildcardsAreEscaped() {
        assertEquals("50\\% off\\_x\\\\", NotebookSearch.escapeLike("50% off_x\\"))
    }

    @Test fun entityMappingRoundTripsAndThumbnailStaleness() {
        val nb = Notebook(NotebookId("n"), "T", 1, 5, PaperTemplate.Dotted, 3)
        val row = nb.toEntity()
        assertEquals(nb, row.toModel())
        assertTrue(row.thumbnailIsStale)
        assertFalse(row.copy(thumbnailPath = "/x", thumbnailUpdatedAtMillis = 5).thumbnailIsStale)
        assertTrue(row.copy(thumbnailPath = "/x", thumbnailUpdatedAtMillis = 4).thumbnailIsStale)
        assertEquals(PaperTemplate.Ruled, row.copy(template = "Gone").toModel().template)
    }
}
