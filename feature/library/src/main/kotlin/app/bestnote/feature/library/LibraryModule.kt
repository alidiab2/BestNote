package app.bestnote.feature.library

import android.content.Context
import app.bestnote.core.database.NotebookDao
import app.bestnote.core.database.toEntity
import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.NotebookStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps the Room table the editor reads titles from in step until DOC-03's repository owns it. */
class RoomIndexMirror @Inject constructor(private val dao: NotebookDao) : NotebookIndexMirror {
    override suspend fun upsert(notebook: Notebook) = dao.upsert(notebook.toEntity())
    override suspend fun remove(id: NotebookId) = dao.delete(id.value)
}

@Module
@InstallIn(SingletonComponent::class)
object LibraryModule {
    @Provides
    @Singleton
    fun repository(store: NotebookStore, @ApplicationContext context: Context, mirror: RoomIndexMirror): LibraryRepository =
        StoreLibraryRepository(store, FileNotebookStore(File(context.filesDir, TRASH_DIR)), mirror)

    @Provides
    @Singleton
    fun inMemoryMetadata(): InMemoryLibraryMetadata = InMemoryLibraryMetadata()

    @Provides
    fun metadata(impl: InMemoryLibraryMetadata): LibraryMetadata = impl

    @Provides
    fun clock(): LibraryClock = LibraryClock(System::currentTimeMillis)

    private const val TRASH_DIR = "trash"
}
