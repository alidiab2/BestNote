package app.bestnote.feature.library

import app.bestnote.core.model.NotebookId

/** The matching pages of one notebook, best page first. */
data class PageResultGroup(val notebookId: NotebookId, val notebookTitle: String, val pages: List<PageResult>)

/**
 * Groups page results by notebook (REC-03). [results] arrive best first; notebooks keep the order of their best page,
 * and inside a notebook the pages go in reading order (page number) so a long notebook reads top to bottom.
 */
fun groupPageResults(results: List<PageResult>): List<PageResultGroup> =
    results.groupBy { it.hit.notebookId }.map { (id, pages) ->
        PageResultGroup(id, pages.first().notebookTitle, pages.sortedBy { it.hit.pageIndex })
    }
