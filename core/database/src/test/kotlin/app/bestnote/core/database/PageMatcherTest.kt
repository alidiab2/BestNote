package app.bestnote.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMatcherTest {
    private fun seg(id: Long, page: String, folded: String, kind: String = "Ink", nb: String = "n1") =
        SearchSegmentEntity(id, nb, page, kind, folded, folded, 0f, 0f, 10f, 10f)

    @Test fun pageNeedsEveryWord() {
        val matches = PageMatcher.match(
            mapOf(
                "newton" to listOf(seg(1, "p1", "newton"), seg(2, "p2", "newton")),
                "law" to listOf(seg(3, "p1", "law")),
            ),
        )
        assertEquals(listOf("p1"), matches.map { it.pageId })
        assertEquals(setOf(1L, 3L), matches[0].segments.map { it.id }.toSet())
    }

    @Test fun noMatchWhenAWordIsMissing() {
        assertTrue(PageMatcher.match(mapOf("a" to listOf(seg(1, "p1", "a")), "b" to emptyList())).isEmpty())
        assertTrue(PageMatcher.match(emptyMap()).isEmpty())
    }

    @Test fun wholeWordsAndTypedTextRankFirst() {
        val matches = PageMatcher.match(
            mapOf(
                "cat" to listOf(
                    seg(1, "part", "category"),
                    seg(2, "word", "cat"),
                    seg(3, "typed", "cat", kind = "Text"),
                ),
            ),
        )
        assertEquals(listOf("typed", "word", "part"), matches.map { it.pageId })
    }

    @Test fun sameWordInTwoNotebooksStaysSeparate() {
        val matches = PageMatcher.match(mapOf("x" to listOf(seg(1, "p", "x", nb = "a"), seg(2, "p", "x", nb = "b"))))
        assertEquals(setOf("a", "b"), matches.map { it.notebookId }.toSet())
    }
}
