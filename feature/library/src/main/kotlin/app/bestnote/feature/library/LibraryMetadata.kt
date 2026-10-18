package app.bestnote.feature.library

import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * A library folder. [colorIndex] indexes `CoverPalette.All`; [parentId] makes it a subfolder.
 */
@Serializable
data class Folder(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val parentId: String? = null,
    /** LIB-11: notebooks in this folder (and its subfolders) are locked, and new ones are locked as they arrive. */
    val locked: Boolean = false,
)

/** Cover finish drawn over the cover color (see `CoverChoices.kt`). */
@Serializable
enum class CoverStyle { Plain, Stripes, Dots, Grid }

/** What a library entry holds. PDFs arrive with PDF-01 (Import PDF); the filter is ready for them. */
@Serializable
enum class NotebookKind { Notebook, Pdf }

/**
 * Library-only facts about a notebook that `:core:model` has no field for (cover, folder, favorite,
 * reading position). Every field is optional: missing data falls back to a sensible default.
 */
@Serializable
data class NotebookMeta(
    val coverIndex: Int? = null,
    val coverStyle: CoverStyle = CoverStyle.Plain,
    val folderId: String? = null,
    val favorite: Boolean = false,
    val quicknote: Boolean = false,
    /** Zero-based page the user was last on. */
    val lastPageIndex: Int? = null,
    val kind: NotebookKind = NotebookKind.Notebook,
    /** LIB-10: file name (inside the covers directory) of a custom cover image, or null for the colored cover. */
    val coverImage: String? = null,
    /** LIB-10: colour tags, indexes into `CoverPalette.All`, sorted and distinct. */
    val tags: List<Int> = emptyList(),
)

/** Current on-disk format of [MetadataSnapshot]. 1 = folders, covers, favorites; 2 adds [NotebookMeta.coverImage] and [NotebookMeta.tags]. */
internal const val METADATA_VERSION = 2

/**
 * Brings a snapshot read from disk up to [METADATA_VERSION]. Every v2 field is optional with a default,
 * so a v1 file already decodes; this normalizes it (clean tags, no blank image names) and stamps the version.
 */
internal fun MetadataSnapshot.migrated(): MetadataSnapshot = copy(
    version = METADATA_VERSION,
    notebooks = notebooks.mapValues { (_, m) ->
        m.copy(coverImage = m.coverImage?.takeIf { it.isNotBlank() }, tags = m.tags.filter { it >= 0 }.distinct().sorted())
    },
)

/** Everything [LibraryMetadata] keeps, as written to disk. */
@Serializable
data class MetadataSnapshot(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val version: Int = METADATA_VERSION,
    val folders: List<Folder> = emptyList(),
    val notebooks: Map<String, NotebookMeta> = emptyMap(),
)

/** Folders and per-notebook [NotebookMeta]. Reads are cheap snapshots; writes persist before returning. */
interface LibraryMetadata {
    fun folders(): List<Folder>
    fun meta(id: NotebookId): NotebookMeta

    /** Loads persisted data. Called once before the first library load. */
    suspend fun load() = Unit
    suspend fun update(ids: Collection<NotebookId>, transform: (NotebookMeta) -> NotebookMeta)
    suspend fun remove(ids: Collection<NotebookId>)

    /** Drops metadata of notebooks that no longer exist anywhere (library or Trash). */
    suspend fun retain(ids: Set<NotebookId>)

    /** Adds or replaces a folder (matched by id). */
    suspend fun putFolder(folder: Folder)

    /** Deletes [id] and its subfolders. Their notebooks move up to the deleted folder's parent. */
    suspend fun deleteFolder(id: String)
}

suspend fun LibraryMetadata.update(id: NotebookId, transform: (NotebookMeta) -> NotebookMeta) = update(listOf(id), transform)

/** In-memory [LibraryMetadata]; [FileLibraryMetadata] adds persistence. Tests use this directly. */
open class InMemoryLibraryMetadata : LibraryMetadata {
    private val mutex = Mutex()

    @Volatile
    protected var snapshot: MetadataSnapshot = MetadataSnapshot()

    /** Test helper: replaces all folders without persisting. */
    fun setFolders(list: List<Folder>) {
        snapshot = snapshot.copy(folders = list)
    }

    /** Test helper: sets one notebook's metadata without persisting. */
    fun put(id: NotebookId, value: NotebookMeta) {
        snapshot = snapshot.copy(notebooks = snapshot.notebooks + (id.value to value))
    }

    override fun folders(): List<Folder> = snapshot.folders
    override fun meta(id: NotebookId): NotebookMeta = snapshot.notebooks[id.value] ?: NotebookMeta()

    override suspend fun update(ids: Collection<NotebookId>, transform: (NotebookMeta) -> NotebookMeta) = edit { s ->
        s.copy(notebooks = s.notebooks + ids.associate { it.value to transform(s.notebooks[it.value] ?: NotebookMeta()) })
    }

    override suspend fun remove(ids: Collection<NotebookId>) = edit { s ->
        s.copy(notebooks = s.notebooks - ids.map { it.value }.toSet())
    }

    override suspend fun retain(ids: Set<NotebookId>) = edit { s ->
        val keep = ids.mapTo(HashSet()) { it.value }
        s.copy(notebooks = s.notebooks.filterKeys { it in keep })
    }

    override suspend fun putFolder(folder: Folder) = edit { s ->
        val exists = s.folders.any { it.id == folder.id }
        s.copy(folders = if (exists) s.folders.map { if (it.id == folder.id) folder else it } else s.folders + folder)
    }

    override suspend fun deleteFolder(id: String) = edit { s ->
        val target = s.folders.firstOrNull { it.id == id } ?: return@edit s
        val doomed = descendantsOf(id, s.folders) + id
        s.copy(
            folders = s.folders.filterNot { it.id in doomed },
            notebooks = s.notebooks.mapValues { (_, m) -> if (m.folderId in doomed) m.copy(folderId = target.parentId) else m },
        )
    }

    private suspend fun edit(change: (MetadataSnapshot) -> MetadataSnapshot) = mutex.withLock {
        val next = change(snapshot)
        if (next != snapshot) {
            snapshot = next
            persist(next)
        }
    }

    protected suspend fun replaceAll(value: MetadataSnapshot) = mutex.withLock { snapshot = value }

    /** Called with the new state after every change. */
    protected open suspend fun persist(value: MetadataSnapshot) = Unit
}

/** [LibraryMetadata] kept in one small JSON file (atomic temp + rename writes). */
class FileLibraryMetadata(private val file: File) : InMemoryLibraryMetadata() {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    override suspend fun load() {
        val loaded = withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext null
            try {
                json.decodeFromString(MetadataSnapshot.serializer(), file.readText())
            } catch (e: SerializationException) {
                // A corrupt file must not take the library down; keep a copy for diagnosis.
                file.copyTo(File(file.path + ".corrupt"), overwrite = true)
                null
            } catch (e: IllegalArgumentException) {
                file.copyTo(File(file.path + ".corrupt"), overwrite = true)
                null
            }
        }
        if (loaded != null) replaceAll(loaded.migrated())
    }

    override suspend fun persist(value: MetadataSnapshot) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(MetadataSnapshot.serializer(), value))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Could not write ${file.path}")
        }
    }
}

/** The outermost locked folder on [folderId]'s path (itself included), or null when none is locked (LIB-11). */
internal fun lockedAncestor(folderId: String?, folders: List<Folder>): Folder? =
    folderPath(folderId, folders.associateBy { it.id }).firstOrNull { it.locked }

/** Ids of every folder below [id]. */
internal fun descendantsOf(id: String, folders: List<Folder>): Set<String> {
    val out = HashSet<String>()
    var frontier = setOf(id)
    while (frontier.isNotEmpty()) {
        val next = folders.filter { it.parentId in frontier && it.id !in out }.map { it.id }.toSet()
        out += next
        frontier = next
    }
    return out
}

/** [id]'s chain from the top-level folder down to itself (empty if unknown). */
internal fun folderPath(id: String?, byId: Map<String, Folder>): List<Folder> {
    val path = ArrayList<Folder>()
    var cur = id?.let(byId::get)
    while (cur != null && path.none { it.id == cur!!.id }) {
        path += cur
        cur = cur.parentId?.let(byId::get)
    }
    return path.asReversed()
}

/** Stable default cover color for a notebook without one: derived from its id. */
internal fun defaultCoverIndex(id: NotebookId, paletteSize: Int): Int = Math.floorMod(id.value.hashCode(), paletteSize)
