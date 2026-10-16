package app.bestnote.core.database

import android.content.Context
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.storage.Autosaver
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.core.storage.NotebookPackage
import app.bestnote.core.storage.NotebookStore
import app.bestnote.core.storage.PageContent
import app.bestnote.core.storage.ThumbnailCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A notebook opened for editing: its content as read, and the autosaver to send edits to. */
class OpenNotebook(val pkg: NotebookPackage, val autosaver: Autosaver)

/**
 * Keeps notebook packages (the source of truth, :core:storage) and the Room index in step:
 * opening a notebook for editing with autosave, mirroring every save into the index, caching
 * thumbnails, and rebuilding the index from the packages.
 */
@Singleton
class NotebookRepository @Inject constructor(
    private val store: NotebookStore,
    private val dao: NotebookDao,
    private val thumbnails: ThumbnailCache,
) {
    /**
     * Process-wide scope for saves that must outlive the screen that started them (closing an
     * editor flushes and renders its thumbnail here).
     */
    val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val savedNotebooks = MutableSharedFlow<NotebookId>(extraBufferCapacity = SAVED_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A notebook was saved to disk (every autosave commit), e.g. for the search index to catch up with it (REC-02). */
    val saved: SharedFlow<NotebookId> = savedNotebooks.asSharedFlow()

    /**
     * Opens [id] for editing: reads its package, or creates one from the index entry for a notebook
     * that has never been saved, and records it as last opened. The returned [Autosaver] mirrors
     * every save into the index. Call [Autosaver.close] when done.
     */
    suspend fun openForEditing(id: NotebookId, now: Long = System.currentTimeMillis()): OpenNotebook {
        store.compactJournals(id) // leftovers of a crash or a missed close: fold them before the saver takes over
        val pkg = store.read(id) ?: newPackage(dao.get(id.value)?.toModel() ?: Notebook(id, "Notebook", now, now))
            .also { store.write(it) }
        dao.syncFromPackage(pkg.notebook)
        dao.markOpened(id.value, now)
        return OpenNotebook(pkg, Autosaver(pkg, store, backgroundScope, onSaved = {
            dao.syncFromPackage(it.notebook)
            savedNotebooks.tryEmit(it.notebook.id)
        }))
    }

    /** True when [id]'s thumbnail is missing or was rendered before [updatedAtMillis]. */
    suspend fun thumbnailIsStale(id: NotebookId, updatedAtMillis: Long): Boolean {
        val row = dao.get(id.value) ?: return true
        val path = row.thumbnailPath ?: return true
        return ThumbnailCache.isStale(row.thumbnailUpdatedAtMillis, updatedAtMillis) || !File(path).isFile
    }

    /** Caches [encodedImage] as the thumbnail of [id], rendered from the notebook as of [renderedFromUpdatedAt]. */
    suspend fun saveThumbnail(id: NotebookId, renderedFromUpdatedAt: Long, encodedImage: ByteArray) {
        val file = thumbnails.write(id, encodedImage)
        dao.setThumbnail(id.value, file.path, renderedFromUpdatedAt)
    }

    /** Adds or refreshes an index row for every readable package. Packages win (ADR-0004). */
    suspend fun reconcileIndex() {
        store.list().forEach { dao.syncFromPackage(it) }
    }

    private fun newPackage(notebook: Notebook) = NotebookPackage(
        notebook,
        List(notebook.pageCount) { i ->
            val page = Page(notebook.id, i, notebook.template)
            PageContent(page, InkChunkCodec.encode(emptyList()), "page-$i")
        },
    )
}

private const val SAVED_BUFFER = 8

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides
    @Singleton
    fun notebookStore(@ApplicationContext context: Context): NotebookStore = FileNotebookStore(File(context.filesDir, "notebooks"))

    @Provides
    @Singleton
    fun thumbnailCache(@ApplicationContext context: Context): ThumbnailCache = ThumbnailCache(File(context.filesDir, "thumbnails"))
}
