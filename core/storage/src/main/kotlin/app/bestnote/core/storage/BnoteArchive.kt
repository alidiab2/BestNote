package app.bestnote.core.storage

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The single-file `.bnote` zip used for export, share and backup. `.ink` chunks are STORED
 * (already compact), JSON and assets are DEFLATED. The manifest is the last entry.
 */
object BnoteArchive {
    /** Writes [pkg] as a `.bnote` zip. Does not close [out]. */
    fun export(pkg: NotebookPackage, out: OutputStream) {
        val zip = ZipOutputStream(out)
        for ((path, bytes) in BnoteFormat.toEntries(pkg)) {
            val entry = ZipEntry(path)
            if (path.endsWith(".ink")) {
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            } else {
                entry.method = ZipEntry.DEFLATED
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
        zip.finish()
    }

    /**
     * Reads a `.bnote` zip. Unknown entries are ignored; nothing is written to disk.
     * @throws UnsupportedFormatVersionException for files from a newer BestNote.
     * @throws CorruptChunkException for damaged or incomplete archives.
     */
    fun import(input: InputStream): NotebookPackage {
        val entries = HashMap<String, ByteArray>()
        val zip = ZipInputStream(input)
        while (true) {
            val e = zip.nextEntry ?: break
            if (!e.isDirectory) entries[e.name] = zip.readBytes()
        }
        return BnoteFormat.fromEntries { entries[it] }
    }
}
