package app.bestnote.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** REC-02/03 on a real device: the FTS5 page index, search by Arabic and English words, cleanup with the notebook. */
@RunWith(AndroidJUnit4::class)
class PageSearchIndexTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: BestNoteDatabase
    private lateinit var search: PageSearchIndex

    @Before fun setUp() {
        context.deleteDatabase(NAME)
        db = BestNoteDatabase.configure(Room.databaseBuilder(context, BestNoteDatabase::class.java, NAME))
        search = PageSearchIndex(db)
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase(NAME)
    }

    private suspend fun page(nb: String, page: String, index: Int, vararg words: Pair<String, Float>, kind: String = "Ink") {
        val dao = db.searchDao()
        dao.replacePage(
            SearchPageEntity(nb, page, index, "sig", inkIndexed = true, indexedAtMillis = 1L, widthPt = 595f, heightPt = 842f),
            words.map { (w, x) -> SearchSegmentEntity(notebookId = nb, pageId = page, kind = kind, text = w, folded = app.bestnote.core.common.SearchText.fold(w), left = x, top = 10f, right = x + 40f, bottom = 30f) },
        )
    }

    @Test fun findsEnglishAndArabicWordsWithTheirBoxes() = runTest {
        db.notebookDao().upsert(Notebook(NotebookId("n1"), "Physics", 1, 2).toEntity())
        page("n1", "p0", 0, "Newton" to 10f, "second" to 60f, "law" to 110f)
        page("n1", "p1", 1, "قانون" to 200f, "التسارع" to 100f)

        val hit = search.search("second law").single()
        assertEquals("p0", hit.pageId)
        assertEquals(listOf(60f, 110f), hit.highlights.map { it.left }.sorted())
        assertEquals("p1", search.search("تسارع").single().pageId) // part of a word, Arabic
        assertEquals("p1", search.search("اَلتَّسَارُع").single().pageId) // with marks
        assertTrue(search.search("newton gravity").isEmpty()) // every word must be on the page
        assertEquals(1, search.search("la").size) // short words fall back to LIKE
        assertEquals(listOf(10f), search.highlights(NotebookId("n1"), "p0", "newton").map { it.left })
    }

    @Test fun replacingAPageReplacesItsWordsAndDeletingTheNotebookRemovesThem() = runTest {
        db.notebookDao().upsert(Notebook(NotebookId("n1"), "Physics", 1, 2).toEntity())
        page("n1", "p0", 0, "alpha" to 10f)
        page("n1", "p0", 0, "omega" to 10f)
        assertTrue(search.search("alpha").isEmpty())
        assertEquals(1, search.search("omega").size)
        // Typed text outranks handwriting of the same word.
        page("n1", "p1", 1, "omega" to 10f, kind = "Text")
        assertEquals("p1", search.search("omega").first().pageId)

        db.notebookDao().delete("n1")
        assertTrue(search.search("omega").isEmpty())
        assertEquals(0, db.searchDao().segmentCount())
    }

    private companion object {
        const val NAME = "page-search-test.db"
    }
}
