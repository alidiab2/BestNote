package app.bestnote.core.storage

import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Where a backup lives: a folder of files addressed by relative paths such as `notebooks/a.bnote`.
 * The Android implementation sits on a Storage Access Framework tree; [DirectoryBackupTarget] is the
 * plain-file one used by tests. Implementations do their own I/O dispatching.
 */
interface BackupTarget {
    suspend fun exists(path: String): Boolean

    /** Names (not paths) of the files directly inside [dir]. Empty when [dir] does not exist. */
    suspend fun list(dir: String): List<String>

    /** Opens [path] for reading and runs [block]; null when it does not exist. */
    suspend fun <T> read(path: String, block: (InputStream) -> T): T?

    /** Creates or replaces [path] with what [block] writes. */
    suspend fun write(path: String, block: (OutputStream) -> Unit)

    suspend fun delete(path: String)
}

/** One notebook inside a backup. [file] is relative to the backup folder. */
@Serializable
data class BackupEntry(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val pageCount: Int,
    val trashed: Boolean,
    val file: String,
)

/**
 * `backup.json`, the last file a backup writes: a backup without it is incomplete and is ignored,
 * and while one is being replaced the old manifest keeps pointing at the old, intact files.
 */
@Serializable
data class BackupManifest(
    val version: Int = LibraryBackup.VERSION,
    val app: String = "BestNote",
    val createdAtMillis: Long,
    /** `.bnote` format version of the notebook files. */
    val bnoteFormat: Int = BnoteFormat.VERSION,
    val notebooks: List<BackupEntry>,
    /** Library-side files (folders, covers, favorites), names relative to `extras/`. */
    val extras: List<String> = emptyList(),
)

class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What a backup run did. */
data class BackupResult(val notebooks: Int, val trashed: Int, val written: Int, val reused: Int, val manifest: BackupManifest)

/** What a restore run did. [failed] holds titles of notebooks that could not be read from the backup. */
data class RestoreResult(
    val restored: Int,
    val skippedNewerLocal: Int,
    val failed: List<String>,
    /** Library-side files from the backup, for the caller to apply. */
    val extras: Map<String, ByteArray>,
    val manifest: BackupManifest,
)

fun interface BackupProgress {
    fun update(done: Int, total: Int)

    companion object {
        val None = BackupProgress { _, _ -> }
    }
}

/**
 * Full backup and restore of the library (DOC-08).
 *
 * Layout inside the chosen folder:
 * ```
 * backup.json                       manifest, written last
 * notebooks/<id>-<updatedAt>.bnote  one `.bnote` zip per notebook (the version is in the name)
 * trash/<id>-<updatedAt>.bnote      notebooks in the Trash, with their deletion marker
 * extras/<name>                     library metadata (folders, covers, favorites)
 * ```
 * A backup into a folder that already holds one is incremental: a notebook whose `updatedAt` is
 * unchanged is not rewritten. Restore never deletes anything: it adds missing notebooks and replaces
 * ones the backup has a newer version of, so on a clean install it reproduces the library exactly.
 */
class LibraryBackup(
    private val notebooks: NotebookStore,
    private val trash: NotebookStore,
) {
    suspend fun backup(
        target: BackupTarget,
        extras: Map<String, ByteArray> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis(),
        progress: BackupProgress = BackupProgress.None,
    ): BackupResult {
        val previous = readManifest(target)
        val reusable = previous?.notebooks.orEmpty().filter { !it.trashed }.mapTo(HashSet()) { it.file }
        val live = notebooks.list().map { it to false }
        val binned = trash.list().map { it to true }
        val all = live + binned
        val entries = ArrayList<BackupEntry>(all.size)
        var written = 0
        var reused = 0
        progress.update(0, all.size)
        for ((i, pair) in all.withIndex()) {
            val (nb, trashed) = pair
            currentCoroutineContext().ensureActive()
            val file = fileFor(nb.id, nb.updatedAtMillis, trashed)
            if (!trashed && file in reusable && target.exists(file)) {
                reused++
            } else {
                // Trash entries are always rewritten: their deletion marker can change without updatedAt changing.
                val pkg = (if (trashed) trash else notebooks).read(nb.id)
                    ?: continue // removed while we were backing up
                target.write(file) { BnoteArchive.export(pkg, it) }
                written++
            }
            entries += BackupEntry(nb.id.value, nb.title, nb.updatedAtMillis, nb.pageCount, trashed, file)
            progress.update(i + 1, all.size)
        }
        for ((name, bytes) in extras) {
            requireValidExtraName(name)
            target.write("$EXTRAS_DIR$name") { it.write(bytes) }
        }
        val manifest = BackupManifest(createdAtMillis = nowMillis, notebooks = entries, extras = extras.keys.sorted())
        target.write(MANIFEST) { it.write(json.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray()) }
        // Only after the new manifest is in place is it safe to drop what it no longer references.
        val keep = entries.mapTo(HashSet()) { it.file } + manifest.extras.map { "$EXTRAS_DIR$it" }
        for (dir in listOf(NOTEBOOKS_DIR, TRASH_DIR, EXTRAS_DIR)) {
            for (name in target.list(dir)) if ("$dir$name" !in keep) target.delete("$dir$name")
        }
        return BackupResult(entries.count { !it.trashed }, entries.count { it.trashed }, written, reused, manifest)
    }

    /** @throws BackupException if [target] holds no complete backup, or one from a newer BestNote. */
    suspend fun restore(target: BackupTarget, progress: BackupProgress = BackupProgress.None): RestoreResult {
        val manifest = readManifest(target) ?: throw BackupException("No BestNote backup found in this folder")
        val localLive = notebooks.list().associate { it.id to it.updatedAtMillis }
        val localTrash = trash.list().associate { it.id to it.updatedAtMillis }
        val failed = ArrayList<String>()
        var restored = 0
        var skipped = 0
        progress.update(0, manifest.notebooks.size)
        for ((i, entry) in manifest.notebooks.withIndex()) {
            currentCoroutineContext().ensureActive()
            val store = if (entry.trashed) trash else notebooks
            val local = (if (entry.trashed) localTrash else localLive)[NotebookId(entry.id)]
            if (local != null && local >= entry.updatedAtMillis) {
                skipped++
            } else {
                val pkg = try {
                    target.read(entry.file) { BnoteArchive.import(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    null
                }
                if (pkg == null || pkg.notebook.id.value != entry.id) {
                    failed += entry.title
                } else {
                    store.write(pkg)
                    restored++
                }
            }
            progress.update(i + 1, manifest.notebooks.size)
        }
        val extras = HashMap<String, ByteArray>()
        for (name in manifest.extras) {
            requireValidExtraName(name)
            target.read("$EXTRAS_DIR$name") { it.readBytes() }?.let { extras[name] = it }
        }
        return RestoreResult(restored, skipped, failed, extras, manifest)
    }

    /** The manifest of the backup in [target], or null if there is none (or it is unreadable). */
    suspend fun readManifest(target: BackupTarget): BackupManifest? {
        val text = target.read(MANIFEST) { it.readBytes().decodeToString() } ?: return null
        val manifest = try {
            json.decodeFromString(BackupManifest.serializer(), text)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (manifest.version > VERSION) throw BackupException("This backup was made by a newer BestNote (backup format ${manifest.version})")
        return manifest
    }

    private fun fileFor(id: NotebookId, updatedAt: Long, trashed: Boolean) =
        "${if (trashed) TRASH_DIR else NOTEBOOKS_DIR}${FileNotebookStore.encodeDirName(id.value)}-$updatedAt.bnote"

    private fun requireValidExtraName(name: String) =
        require(name.isNotEmpty() && name.split('/').none { it.isEmpty() || it == ".." || it == "." } && !name.contains('\\')) { "Invalid extra '$name'" }

    companion object {
        const val VERSION = 1
        const val MANIFEST = "backup.json"
        const val NOTEBOOKS_DIR = "notebooks/"
        const val TRASH_DIR = "trash/"
        const val EXTRAS_DIR = "extras/"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    }
}
