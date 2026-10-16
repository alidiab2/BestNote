package app.bestnote.core.storage

import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cached cover thumbnails (DOC-10), one encoded image per notebook in [dir]. Thumbnails are a
 * cache: they live outside the `.bnote` package, are written atomically, and can be deleted at any
 * time. A thumbnail is identified by the notebook's `updatedAtMillis` it was rendered from, so it is
 * invalidated by any later edit ([isStale]).
 */
class ThumbnailCache(val dir: File, private val io: CoroutineDispatcher = Dispatchers.IO) {
    fun fileFor(id: NotebookId): File = File(dir, FileNotebookStore.encodeDirName(id.value) + EXTENSION)

    /** Atomically replaces the thumbnail of [id] with [bytes]. @return the cached file. */
    suspend fun write(id: NotebookId, bytes: ByteArray): File = withContext(io) {
        fileFor(id).also { AtomicFiles.write(it, bytes) }
    }

    suspend fun delete(id: NotebookId): Boolean = withContext(io) { fileFor(id).delete() }

    companion object {
        const val EXTENSION = ".webp"

        /** True when the thumbnail rendered at [renderedFromUpdatedAt] predates the notebook's last edit. */
        fun isStale(renderedFromUpdatedAt: Long?, notebookUpdatedAt: Long): Boolean =
            renderedFromUpdatedAt == null || renderedFromUpdatedAt < notebookUpdatedAt
    }
}
