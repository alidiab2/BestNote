/**
 * :core:storage — persistence of notebook *content*, independent of Android.
 *
 * ## The planned `.bnote` container
 * One notebook = one `.bnote` file, a ZIP archive so it can be shared, backed up and
 * inspected with standard tools:
 * ```
 * manifest.json              format version, notebook metadata, ordered page list
 *                            (id, template, size), asset index, app version that wrote it
 * pages/<pageId>.strokes     per-page binary stroke data (androidx.ink-encoded input batches
 *                            + brush parameters), one file per page so saving a page
 *                            rewrites only that entry
 * assets/<sha256>.<ext>      images, PDFs and audio, content-addressed and de-duplicated
 * thumbnails/<pageId>.webp   optional, regenerated on demand
 * ```
 * Writers produce a new archive next to the old one and atomically rename it, so a crash
 * never leaves a half-written notebook. Unknown entries are preserved for forward compatibility.
 *
 * This module defines the [NotebookStore] contract and an [InMemoryNotebookStore]; the
 * ZIP-backed implementation lands with the editor's save pipeline.
 */
package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page

/** Binary stroke payload of one page, as it will be stored at `pages/<pageId>.strokes`. */
class PageContent(val page: Page, val strokes: ByteArray)

/** Everything inside one `.bnote` container. Asset keys are their archive paths. */
class NotebookPackage(
    val notebook: Notebook,
    val pages: List<PageContent>,
    val assets: Map<String, ByteArray> = emptyMap(),
) {
    init {
        require(pages.all { it.page.notebookId == notebook.id }) { "Pages must belong to ${notebook.id}" }
    }
}

/** Reads and writes whole notebooks. Implementations must be safe to call from any coroutine. */
interface NotebookStore {
    suspend fun list(): List<Notebook>
    suspend fun read(id: NotebookId): NotebookPackage?
    suspend fun write(pkg: NotebookPackage)

    /** @return true if a notebook was removed. */
    suspend fun delete(id: NotebookId): Boolean
}
