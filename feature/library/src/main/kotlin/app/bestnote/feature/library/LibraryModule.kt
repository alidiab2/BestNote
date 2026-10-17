package app.bestnote.feature.library

import android.content.Context
import app.bestnote.core.database.NotebookDao
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.NotebookKeyring
import app.bestnote.core.storage.NotebookStore
import app.bestnote.core.storage.ThumbnailCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps DOC-03's Room index in step with library changes: a new or renamed notebook is visible to
 * search right away, and a trashed one leaves it (DOC-03 only syncs when a notebook is opened or
 * saved). Index-only columns (folder, thumbnail) survive a sync.
 */
class RoomIndexMirror @Inject constructor(private val dao: NotebookDao) : NotebookIndexMirror {
    override suspend fun upsert(notebook: Notebook) = dao.syncFromPackage(notebook)
    override suspend fun remove(id: NotebookId) = dao.delete(id.value)
}

/** Thumbnails from DOC-10's [ThumbnailCache]; a stale one is still better than none. */
class CachedThumbnails @Inject constructor(private val cache: ThumbnailCache) : NotebookThumbnails {
    override suspend fun existing(ids: List<NotebookId>): Map<NotebookId, String> = withContext(Dispatchers.IO) {
        ids.mapNotNull { id -> cache.fileFor(id).takeIf { it.isFile }?.let { id to it.path } }.toMap()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {
    @Provides
    @Singleton
    fun repository(
        store: NotebookStore,
        @ApplicationContext context: Context,
        mirror: RoomIndexMirror,
        thumbnails: CachedThumbnails,
        keyring: NotebookKeyring,
    ): LibraryRepository = StoreLibraryRepository(
        store,
        FileNotebookStore(File(context.filesDir, TRASH_DIR), keyring = keyring),
        mirror,
        thumbnailSource = thumbnails,
        keyring = keyring,
    )

    @Provides
    fun locks(impl: ManagerLibraryLocks): LibraryLocks = impl

    @Provides
    fun pdfImporting(service: PdfImportService): PdfImporting = service

    @Provides
    @Singleton
    fun metadata(@ApplicationContext context: Context): LibraryMetadata =
        FileLibraryMetadata(File(context.filesDir, METADATA_FILE))

    @Provides
    fun clock(): LibraryClock = LibraryClock(System::currentTimeMillis)

    private const val TRASH_DIR = "trash"
    private const val METADATA_FILE = "library/metadata.json"
}
