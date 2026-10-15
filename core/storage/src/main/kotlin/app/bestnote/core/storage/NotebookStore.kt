/**
 * :core:storage — persistence of notebook *content*, independent of Android.
 *
 * The `.bnote` v1 format is specified in `docs/architecture/file-format.md`:
 * ```
 * manifest.json          format version, notebook metadata, ordered page ids, asset list
 * pages/<pageId>.json    page metadata (index, template, size)
 * pages/<pageId>.ink     binary strokes: `BNIK` header (version + CRC32) + samples, see [InkChunkCodec]
 * assets/<name>          images, PDFs and audio
 * ```
 * [FileNotebookStore] keeps each notebook unpacked as a directory with that layout (atomic
 * temp + rename writes, manifest last); [BnoteArchive] produces/reads the single-file `.bnote` zip.
 * [InMemoryNotebookStore] is the volatile implementation for tests and previews.
 */
package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PageElement

/**
 * One page and its binary stroke chunk, stored at `pages/<id>.ink`. [strokes] is normally produced by
 * [InkChunkCodec.encode]; stores treat it as opaque bytes. [id] is stable across reorders and must
 * match [PAGE_ID_PATTERN] because it becomes a file name.
 */
class PageContent(
    val page: Page,
    val strokes: ByteArray,
    val id: String = "page-${page.index}",
    /** Text boxes and images on the page, bottom to top; stored in `pages/<id>.json` (TEXT-01, MEDIA-01). */
    val elements: List<PageElement> = emptyList(),
) {
    init {
        require(PAGE_ID_PATTERN.matches(id)) { "Invalid page id '$id'" }
    }

    companion object {
        val PAGE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/** Everything inside one `.bnote` container. Asset keys are their archive paths. */
class NotebookPackage(
    val notebook: Notebook,
    val pages: List<PageContent>,
    val assets: Map<String, ByteArray> = emptyMap(),
) {
    init {
        require(pages.all { it.page.notebookId == notebook.id }) { "Pages must belong to ${notebook.id}" }
        require(pages.map { it.id }.toSet().size == pages.size) { "Page ids must be unique" }
    }
}

/** Reads and writes whole notebooks. Implementations must be safe to call from any coroutine. */
interface NotebookStore {
    suspend fun list(): List<Notebook>
    suspend fun read(id: NotebookId): NotebookPackage?
    suspend fun write(pkg: NotebookPackage)

    /**
     * Commits [pkg] but only rewrites the pages in [changedPageIds] (plus the manifest); the other
     * pages are assumed unchanged since the last write. Used by [Autosaver] so saving one edited
     * page of a long notebook costs one page, not the whole notebook. The default rewrites everything.
     */
    suspend fun writePages(pkg: NotebookPackage, changedPageIds: Set<String>) = write(pkg)

    /** @return true if a notebook was removed. */
    suspend fun delete(id: NotebookId): Boolean
}
