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

    /** Journal file -> (chunk it applies to, end of its last good record), so an append does not rescan the file. */
    private val journalEnds = HashMap<String, Pair<Int, Long>>()

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

    override suspend fun readExtra(id: NotebookId, name: String): ByteArray? = locked {
        if (!NotebookPackage.EXTRA_NAME.matches(name)) return@locked null
        File(dirOf(id), BnoteFormat.EXTRAS_DIR + name).takeIf { it.isFile }?.readBytes()
    }

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
                syncDirectory(File(dir, BnoteFormat.EXTRAS_DIR))
            }
            // Extras are small and change in place, so they are always written.
            if (changedPaths == null || path == BnoteFormat.MANIFEST || path in changedPaths || !file.isFile || path.startsWith(BnoteFormat.EXTRAS_DIR)) {
                if (path.endsWith(".ink")) {
                    // An unchanged chunk (a metadata-only save) is not rewritten, and its journal stays valid.
                    if (!sameOnDisk(file, bytes)) {
                        writeAtomically(file, bytes)
                        dropStaleJournal(File(dir, path.removeSuffix(".ink") + PageJournal.EXT), PageJournal.chunkId(bytes))
                    }
                } else {
                    writeAtomically(file, bytes)
                }
            }
        }
        syncDirectory(dir)
        removeUnreferenced(dir, entries.keys)
    }

    override suspend fun appendJournal(id: NotebookId, pageId: String, baseId: Int, record: ByteArray): Long? = locked {
        require(PageContent.PAGE_ID_PATTERN.matches(pageId)) { "Invalid page id '$pageId'" }
        val file = File(dirOf(id), journalPath(pageId))
        val key = file.path
        try {
            var end = journalEnds[key]?.takeIf { it.first == baseId }?.second
            if (end == null) {
                val scan = if (file.isFile) PageJournal.scan(file.readBytes(), InkChunkCodec) else null
                end = if (scan != null && scan.baseId == baseId) scan.validEnd else -1L
            }
            val data: ByteArray
            if (end < 0) {
                data = PageJournal.header(baseId) + record
                end = 0
                PageJournal.appendAt(file, 0, data)
                syncDirectory(file.parentFile)
            } else {
                data = record
                PageJournal.appendAt(file, end, data)
            }
            val newEnd = end + data.size
            journalEnds[key] = baseId to newEnd
            newEnd
        } catch (e: IOException) {
            journalEnds.remove(key)
            throw e
        }
    }

    override suspend fun compactJournals(id: NotebookId) = locked {
        val dir = dirOf(id)
        File(dir, BnoteFormat.PAGES_DIR).listFiles { f -> f.name.endsWith(PageJournal.EXT) }.orEmpty().forEach { journal ->
            val ink = File(journal.parentFile, journal.name.removeSuffix(PageJournal.EXT) + ".ink")
            val folded = if (ink.isFile) foldJournal(ink.readBytes(), journal) else null
            if (folded != null) writeAtomically(ink, folded)
            journalEnds.remove(journal.path)
            journal.delete()
        }
    }

    /** [chunk] with the valid records of [journal] replayed, or null when there is nothing to fold or the chunk is damaged. */
    private fun foldJournal(chunk: ByteArray, journal: File): ByteArray? {
        val scan = PageJournal.scan(journal.readBytes(), InkChunkCodec) ?: return null
        if (scan.edits.isEmpty() || scan.baseId != PageJournal.chunkId(chunk)) return null
        val strokes = try {
            InkChunkCodec.decode(chunk)
        } catch (_: CorruptChunkException) {
            return null
        }
        var current = strokes
        for (edit in scan.edits) current = PageJournal.apply(current, edit) ?: break
        return InkChunkCodec.encode(current)
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
        return BnoteFormat.fromEntries { path ->
            val bytes = File(dir, path).takeIf { it.isFile }?.readBytes()
            // A page's chunk plus its journal, so every reader (export, backup, search) sees the latest edit.
            val journal = if (bytes != null && path.endsWith(".ink")) File(dir, path.removeSuffix(".ink") + PageJournal.EXT) else null
            if (journal != null && journal.isFile) foldJournal(bytes!!, journal) ?: bytes else bytes
        }
    }

    private suspend fun <T> locked(block: suspend () -> T): T = withContext(io) { mutex.withLock { block() } }

    private fun dirOf(id: NotebookId) = File(root, encodeDirName(id.value))

    private fun journalPath(pageId: String) = BnoteFormat.PAGES_DIR + pageId + PageJournal.EXT

    /** True when [file] already holds the ink chunk [bytes] (same size, header and checksum). */
    private fun sameOnDisk(file: File, bytes: ByteArray): Boolean {
        if (!file.isFile || file.length() != bytes.size.toLong() || bytes.size < InkChunkCodec.HEADER_SIZE) return false
        val head = ByteArray(InkChunkCodec.HEADER_SIZE)
        val onDisk = try {
            file.inputStream().use { it.read(head) == head.size }
        } catch (_: IOException) {
            false
        }
        return onDisk && PageJournal.isInkChunk(bytes) && head.contentEquals(bytes.copyOf(head.size))
    }

    /** Deletes [journal] unless it applies to the chunk [chunkId] that was just written. */
    private fun dropStaleJournal(journal: File, chunkId: Int) {
        if (!journal.isFile) return
        val base = try {
            val head = ByteArray(PageJournal.HEADER_SIZE)
            journal.inputStream().use { if (it.read(head) == head.size) PageJournal.scan(head, InkChunkCodec)?.baseId else null }
        } catch (_: IOException) {
            null
        }
        if (base != chunkId) {
            journalEnds.remove(journal.path)
            journal.delete()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) = AtomicFiles.write(target, bytes, faults)

    private fun syncDirectory(dir: File) = AtomicFiles.syncDirectory(dir)

    /** Deletes page/asset files (and stale temp files) the just-committed manifest no longer references. */
    private fun removeUnreferenced(dir: File, keep: Set<String>) {
        dir.walkBottomUp().forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            when {
                f == dir -> Unit
                f.isFile && rel !in keep && !(rel.endsWith(PageJournal.EXT) && rel.removeSuffix(PageJournal.EXT) + ".ink" in keep) -> {
                    journalEnds.remove(f.path)
                    f.delete()
                }
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
