package app.bestnote.feature.library

import app.bestnote.core.model.NotebookId

/** A library folder. [colorIndex] indexes `CoverPalette.All`. Real folders arrive with LIB-04. */
data class Folder(val id: String, val name: String, val colorIndex: Int)

/**
 * Library-only facts about a notebook that `:core:model` has no field for yet (cover color, folder,
 * favorite, reading position). Every field is optional: missing data falls back to a sensible default.
 */
data class NotebookMeta(
    val coverIndex: Int? = null,
    val folderId: String? = null,
    val favorite: Boolean = false,
    val quicknote: Boolean = false,
    /** Zero-based page the user was last on. */
    val lastPageIndex: Int? = null,
)

/** Source of [Folder]s and [NotebookMeta]. LIB-04/05/10 will persist these; today they live in memory. */
interface LibraryMetadata {
    fun folders(): List<Folder>
    fun meta(id: NotebookId): NotebookMeta
}

/** In-memory [LibraryMetadata]; the debug seeder fills it with demo folders and favorites. */
class InMemoryLibraryMetadata : LibraryMetadata {
    @Volatile private var folders: List<Folder> = emptyList()
    private val meta = java.util.concurrent.ConcurrentHashMap<NotebookId, NotebookMeta>()

    fun setFolders(list: List<Folder>) {
        folders = list
    }

    fun put(id: NotebookId, value: NotebookMeta) {
        meta[id] = value
    }

    override fun folders(): List<Folder> = folders
    override fun meta(id: NotebookId): NotebookMeta = meta[id] ?: NotebookMeta()
}

/** Stable default cover color for a notebook without one: derived from its id. */
internal fun defaultCoverIndex(id: NotebookId, paletteSize: Int): Int = Math.floorMod(id.value.hashCode(), paletteSize)
