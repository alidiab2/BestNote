package app.bestnote.feature.library

import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.PageSearchHit
import app.bestnote.core.model.SegmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageResultGroupsTest {
    private fun result(nb: String, page: Int, title: String = nb) = PageResult(
        PageSearchHit(NotebookId(nb), "p$page", page, "", SegmentKind.Ink, emptyList(), null, 595f, 842f),
        title,
    )

    @Test fun groupsByNotebookInOrderOfBestPage() {
        val groups = groupPageResults(listOf(result("b", 4), result("a", 1), result("b", 0), result("c", 2)))
        assertEquals(listOf("b", "a", "c"), groups.map { it.notebookId.value })
    }

    @Test fun pagesInsideANotebookGoInReadingOrder() {
        val groups = groupPageResults(listOf(result("b", 4), result("b", 0), result("b", 2)))
        assertEquals(listOf(0, 2, 4), groups.single().pages.map { it.hit.pageIndex })
    }

    @Test fun carriesTheTitle() {
        assertEquals("Physics", groupPageResults(listOf(result("n", 0, "Physics"))).single().notebookTitle)
    }

    @Test fun noResultsNoGroups() = assertTrue(groupPageResults(emptyList()).isEmpty())
}
