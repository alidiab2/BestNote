package app.bestnote.core.storage

import app.bestnote.core.model.Notebook
import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [NotebookStore] backed by unpacked `.bnote` directories: `<root>/<notebookId>/manifest.json`,
 * `pages/…`, `assets/…`. Every file is written to `<file>.tmp`, fsynced and atomically renamed;
 * `manifest.json` is committed last, then files it no longer references are removed. A crash at any
 * point therefore leaves a manifest that points only at complete files.
 *
 * All I/O runs on [io] (never the caller's thread) and is serialized by one mutex.
 */
class FileNotebookStore(
    private val root: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NotebookStore {
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

    override suspend fun write(pkg: NotebookPackage) = locked {
        val dir = dirOf(pkg.notebook.id)
        val entries = BnoteFormat.toEntries(pkg)
        for ((path, bytes) in entries) writeAtomically(File(dir, path), bytes) // manifest is last
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

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

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
