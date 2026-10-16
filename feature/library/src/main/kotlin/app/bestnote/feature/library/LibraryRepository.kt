package app.bestnote.feature.library

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import app.bestnote.core.model.Page
import app.bestnote.core.model.PaperTemplate
import app.bestnote.core.storage.FileNotebookStore
import app.bestnote.core.storage.InkChunkCodec
import app.bestnote.core.storage.NotebookKeyring
import app.bestnote.core.storage.NotebookLockedException
import app.bestnote.core.storage.NotebookPackage
import app.bestnote.core.storage.NotebookStore
import app.bestnote.core.storage.PageContent
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A notebook in the Trash, restorable until [deletedAtMillis] + [StoreLibraryRepository.TRASH_RETENTION_MILLIS]. */
data class TrashedNotebook(val notebook: Notebook, val deletedAtMillis: Long)

/**
 * Everything the library reads and changes. Backed only by the [NotebookStore] interface, so the
 * DOC-03 Room index can replace [list] later without touching the screen.
 */
interface LibraryRepository {
    /** Notebooks, most recently edited first. */
    suspend fun list(): List<Notebook>
    suspend fun create(title: String, template: PaperTemplate = PaperTemplate.Ruled, format: PaperFormat = PaperFormat()): Notebook
    suspend fun rename(id: NotebookId, title: String): Notebook?

    /** Copies [id] (pages, ink, assets) under a new id and [title]. @return null if [id] does not exist. */
    suspend fun duplicate(id: NotebookId, title: String): Notebook?

    /** Moves [id] to the Trash. @return false if it did not exist. */
    suspend fun moveToTrash(id: NotebookId): Boolean

    /** Brings [id] back from the Trash. @return false if it was not in the Trash. */
    suspend fun restore(id: NotebookId): Boolean
    suspend fun trash(): List<TrashedNotebook>

    /** Permanently removes Trash entries older than the retention period. @return how many. */
    suspend fun purgeExpired(): Int

    /** Paths of the cached page thumbnails (DOC-10) that exist for [ids]. */
    suspend fun thumbnails(ids: List<NotebookId>): Map<NotebookId, String> = emptyMap()
}

/** Where a notebook's cached thumbnail lives, if it has one. Implementations do their own I/O dispatching. */
fun interface NotebookThumbnails {
    suspend fun existing(ids: List<NotebookId>): Map<NotebookId, String>
}

/**
 * Optional mirror of library changes into an index (today the Room table the editor reads its
 * title from). DOC-03's repository will own this; until then the library keeps it in step.
 */
interface NotebookIndexMirror {
    suspend fun upsert(notebook: Notebook)
    suspend fun remove(id: NotebookId)

    object None : NotebookIndexMirror {
        override suspend fun upsert(notebook: Notebook) = Unit
        override suspend fun remove(id: NotebookId) = Unit
    }
}

/**
 * [LibraryRepository] over two stores: the library itself and a Trash store with the same format.
 * A trashed notebook keeps its full package (pages, assets) plus a small marker asset holding the
 * deletion time, so restoring is lossless.
 */
class StoreLibraryRepository(
    private val store: NotebookStore,
    private val trashStore: NotebookStore,
    private val index: NotebookIndexMirror = NotebookIndexMirror.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val thumbnailSource: NotebookThumbnails? = null,
    /** LIB-11: session keys, so a copy or a Trash entry of an unlocked locked notebook is written encrypted too. */
    private val keyring: NotebookKeyring? = null,
) : LibraryRepository {

    /** [id]'s package, or null when it is missing or locked (the screen unlocks before asking). */
    private suspend fun NotebookStore.readUnlocked(id: NotebookId): NotebookPackage? = try {
        read(id)
    } catch (@Suppress("SwallowedException") e: NotebookLockedException) {
        null
    }

    override suspend fun thumbnails(ids: List<NotebookId>): Map<NotebookId, String> =
        thumbnailSource?.existing(ids).orEmpty()

    override suspend fun list(): List<Notebook> = store.list().sortedByDescending { it.updatedAtMillis }

    override suspend fun create(title: String, template: PaperTemplate, format: PaperFormat): Notebook {
        val now = clock()
        val notebook = Notebook(NotebookId(UUID.randomUUID().toString()), title.trim().ifEmpty { title }, now, now, template)
        store.write(emptyPackage(notebook, format))
        index.upsert(notebook)
        return notebook
    }

    override suspend fun duplicate(id: NotebookId, title: String): Notebook? {
        val pkg = store.readUnlocked(id) ?: return null
        val now = clock()
        val copy = pkg.notebook.copy(id = NotebookId(UUID.randomUUID().toString()), title = title.trim().ifEmpty { pkg.notebook.title }, createdAtMillis = now, updatedAtMillis = now)
        // The copy of a locked notebook is locked with the same key (written encrypted, never in plain).
        keyring?.get(id)?.let { keyring.put(copy.id, it.dataKey, it.header) }
        val pages = pkg.pages.map { PageContent(it.page.copy(notebookId = copy.id), it.strokes.copyOf(), it.id, it.elements) }
        store.write(NotebookPackage(copy, pages, pkg.assets.mapValues { it.value.copyOf() }, pkg.recordings, pkg.extras))
        index.upsert(copy)
        return copy
    }

    override suspend fun rename(id: NotebookId, title: String): Notebook? {
        val clean = title.trim()
        if (clean.isEmpty()) return null
        val pkg = store.readUnlocked(id) ?: return null
        val renamed = pkg.notebook.copy(title = clean, updatedAtMillis = clock())
        store.write(NotebookPackage(renamed, pkg.pages, pkg.assets, pkg.recordings, pkg.extras))
        index.upsert(renamed)
        return renamed
    }

    override suspend fun moveToTrash(id: NotebookId): Boolean {
        // A locked notebook moves as it is, still encrypted; its visible date then says when it was trashed.
        if (store is FileNotebookStore && trashStore is FileNotebookStore && store.moveSealedTo(id, trashStore, clock())) {
            index.remove(id)
            return true
        }
        val pkg = store.readUnlocked(id) ?: return false
        val marker = DELETED_AT_ASSET to clock().toString().toByteArray()
        trashStore.write(NotebookPackage(pkg.notebook, pkg.pages, pkg.assets + marker, pkg.recordings, pkg.extras))
        store.delete(id)
        index.remove(id)
        return true
    }

    override suspend fun restore(id: NotebookId): Boolean {
        if (store is FileNotebookStore && trashStore is FileNotebookStore && trashStore.moveSealedTo(id, store)) {
            store.list().firstOrNull { it.id == id }?.let { index.upsert(it) }
            return true
        }
        val pkg = trashStore.readUnlocked(id) ?: return false
        val restored = NotebookPackage(pkg.notebook, pkg.pages, pkg.assets - DELETED_AT_ASSET, pkg.recordings, pkg.extras)
        store.write(restored)
        trashStore.delete(id)
        index.upsert(restored.notebook)
        return true
    }

    override suspend fun trash(): List<TrashedNotebook> = trashStore.list().mapNotNull { notebook ->
        if (trashStore.isSealed(notebook.id)) {
            return@mapNotNull TrashedNotebook(notebook, notebook.updatedAtMillis) // locked: its stub's date is the trash date
        }
        val pkg = trashStore.readUnlocked(notebook.id) ?: return@mapNotNull null
        TrashedNotebook(notebook, pkg.deletedAt())
    }.sortedByDescending { it.deletedAtMillis }

    override suspend fun purgeExpired(): Int {
        val cutoff = clock() - TRASH_RETENTION_MILLIS
        return trash().filter { it.deletedAtMillis < cutoff }.count { trashStore.delete(it.notebook.id) }
    }

    private fun NotebookPackage.deletedAt(): Long =
        assets[DELETED_AT_ASSET]?.decodeToString()?.toLongOrNull() ?: notebook.updatedAtMillis

    companion object {
        val TRASH_RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(30)
        internal const val DELETED_AT_ASSET = "assets/library/deleted-at"

        /** A fresh notebook: one empty page per [Notebook.pageCount], all in [format]. */
        fun emptyPackage(notebook: Notebook, format: PaperFormat = PaperFormat()): NotebookPackage = NotebookPackage(
            notebook,
            List(notebook.pageCount) { i ->
                PageContent(
                    Page(notebook.id, i, notebook.template, format.widthPt, format.heightPt),
                    InkChunkCodec.encode(emptyList()),
                    "page-$i",
                )
            },
        )
    }
}
