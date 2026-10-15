package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * [NotebookStore] backed by unpacked `.bnote` directories: `<root>/<notebookId>/manifest.json`,
 * `pages/…`, `assets/…`. Every file is written to `<file>.tmp`, fsynced and atomically renamed;
 * `manifest.json` is committed last, then files it no longer references are removed. A crash at any
 * point therefore leaves a manifest that points only at complete files.
 *
 * All I/O runs on [io] (never the caller's thread) and is serialized by one mutex.
 */
class FileNotebookStore internal constructor(
    private val root: File,
    private val io: CoroutineDispatcher,
    /** Test hook called at every write step; throwing from it simulates a crash at that point. */
    private val faults: (WriteStep) -> Unit,
) : NotebookStore {
    constructor(root: File, io: CoroutineDispatcher = Dispatchers.IO) : this(root, io, {})

    private val mutex = Mutex()

    /** Notebooks whose manifest can be read. Damaged or newer-format notebooks are skipped. */
    override suspend fun list(): List<Notebook> = locked {
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }.orEmpty().mapNotNull { dir ->
            val manifest = File(dir, BnoteFormat.MANIFEST)
            if (!manifest.isFile) return@mapNotNull null
            try {
                BnoteFormat.readNotebook(manifest.readBytes())
            } catch (_: IOException) {
                null
            }
        }.sortedByDescending { it.updatedAtMillis }
    }

    /**
     * @throws UnsupportedFormatVersionException if written by a newer BestNote.
     * @throws CorruptChunkException if the manifest or a referenced file is damaged or missing.
     */
    override suspend fun read(id: NotebookId): NotebookPackage? = locked { readLocked(id) }

    override suspend fun write(pkg: NotebookPackage) = locked { writeLocked(pkg, changed = null) }

    /**
     * Rewrites only [changedPageIds]; any other entry that is missing on disk (a new page, a first
     * save, an asset) is written too, so the committed manifest never points at a missing file.
     */
    override suspend fun writePages(pkg: NotebookPackage, changedPageIds: Set<String>) =
        locked { writeLocked(pkg, changedPageIds) }

    private fun writeLocked(pkg: NotebookPackage, changed: Set<String>?) {
        val dir = dirOf(pkg.notebook.id)
        val entries = BnoteFormat.toEntries(pkg)
        val changedPaths = changed?.flatMapTo(HashSet()) { listOf(BnoteFormat.pageMetaPath(it), BnoteFormat.pageInkPath(it)) }
        for ((path, bytes) in entries) { // manifest is last
            val file = File(dir, path)
            if (path == BnoteFormat.MANIFEST) {
                // Page and asset renames must be durable before the manifest that points at them.
                syncDirectory(File(dir, BnoteFormat.PAGES_DIR))
                syncDirectory(File(dir, BnoteFormat.ASSETS_DIR))
            }
            if (changedPaths == null || path == BnoteFormat.MANIFEST || path in changedPaths || !file.isFile) {
                writeAtomically(file, bytes)
            }
        }
        syncDirectory(dir)
        removeUnreferenced(dir, entries.keys)
    }

    override suspend fun delete(id: NotebookId): Boolean = locked {
        val dir = dirOf(id)
        if (!dir.exists()) return@locked false
        // Rename first so a half-deleted notebook never shows up in list().
        val trash = File(root, ".trash-${dir.name}-${System.nanoTime()}")
        if (!dir.renameTo(trash)) throw IOException("Cannot delete $dir")
        trash.deleteRecursively()
        true
    }

    /** Writes the notebook as a single `.bnote` zip. @return false if it does not exist. */
    suspend fun exportTo(id: NotebookId, out: OutputStream): Boolean = locked {
        val pkg = readLocked(id) ?: return@locked false
        BnoteArchive.export(pkg, out)
        true
    }

    /** Imports a `.bnote` zip, replacing any notebook with the same id. */
    suspend fun importFrom(input: InputStream): NotebookPackage {
        val pkg = withContext(io) { BnoteArchive.import(input) }
        write(pkg)
        return pkg
    }

    private fun readLocked(id: NotebookId): NotebookPackage? {
        val dir = dirOf(id)
        if (!File(dir, BnoteFormat.MANIFEST).isFile) return null
        return BnoteFormat.fromEntries { path -> File(dir, path).takeIf { it.isFile }?.readBytes() }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(io) { mutex.withLock { block() } }

    private fun dirOf(id: NotebookId) = File(root, encodeDirName(id.value))

    private fun writeAtomically(target: File, bytes: ByteArray) = AtomicFiles.write(target, bytes, faults)

    private fun syncDirectory(dir: File) = AtomicFiles.syncDirectory(dir)

    /** Deletes page/asset files (and stale temp files) the just-committed manifest no longer references. */
    private fun removeUnreferenced(dir: File, keep: Set<String>) {
        dir.walkBottomUp().forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            when {
                f == dir -> Unit
                f.isFile && rel !in keep -> f.delete()
                f.isDirectory && f.list().isNullOrEmpty() -> f.delete()
            }
        }
    }

    companion object {
        /** Percent-encodes anything outside `[A-Za-z0-9_-]`, so any id is a safe single path segment. */
        internal fun encodeDirName(id: String): String = buildString {
            for (b in id.encodeToByteArray()) {
                val c = b.toInt().toChar()
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-') append(c)
                else append('%').append("%02X".format(b.toInt() and 0xFF))
            }
        }
    }
}
