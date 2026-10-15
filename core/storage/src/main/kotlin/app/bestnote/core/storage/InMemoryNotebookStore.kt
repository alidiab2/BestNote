package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Volatile [NotebookStore] for tests and previews. Byte arrays are copied defensively. */
class InMemoryNotebookStore : NotebookStore {
    private val mutex = Mutex()
    private val notebooks = LinkedHashMap<NotebookId, NotebookPackage>()

    override suspend fun list(): List<Notebook> = mutex.withLock {
        notebooks.values.map { it.notebook }.sortedByDescending { it.updatedAtMillis }
    }

    override suspend fun read(id: NotebookId): NotebookPackage? = mutex.withLock { notebooks[id]?.deepCopy() }

    override suspend fun write(pkg: NotebookPackage) {
        mutex.withLock { notebooks[pkg.notebook.id] = pkg.deepCopy() }
    }

    override suspend fun delete(id: NotebookId): Boolean = mutex.withLock { notebooks.remove(id) != null }

    private fun NotebookPackage.deepCopy() = NotebookPackage(
        notebook = notebook,
        pages = pages.map { PageContent(it.page, it.strokes.copyOf(), it.id, it.elements) },
        assets = assets.mapValues { it.value.copyOf() },
    )
}
