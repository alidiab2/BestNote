package app.bestnote.core.storage

import app.bestnote.core.model.InkStroke
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.zip.CRC32

/**
 * Append-only journal of one page's stroke edits (DOC-11), stored next to its chunk as
 * `pages/<id>.jrnl`. Autosaving an edit then costs the size of the edit instead of the size of the page;
 * the chunk is rewritten ("compaction") only when the journal grows large, when the editor idles or closes.
 * Load is the chunk plus a replay of the journal.
 *
 * ```
 * file    "BNJL" | u16 version (1) | u16 flags (0) | u32 baseId        header (12 bytes)
 *         record*
 * record  u32 payloadLength | u32 CRC32(payload) | payload
 * payload u32 baseCount                    stroke count the edit applies to
 *         u32 removedCount | removedCount x u32 index (ascending, in the list before the edit)
 *         u32 addedCount   | addedCount x u32 index (ascending, in the list after the edit)
 *         u32 chunkLength  | chunk         the added strokes, one [StrokeChunkCodec] chunk
 * ```
 * [baseId] is the identity ([chunkId]) of the chunk the records apply to. A chunk rewrite changes it, so
 * a journal that survives a crash between "chunk written" and "journal deleted" is recognised as stale and
 * ignored. Every record is checksummed and fsynced when appended; replay stops at the first record that is
 * short, fails its checksum or does not fit the list (a torn last write), so a crash loses at most the
 * edit that was being written.
 */
internal object PageJournal {
    const val VERSION = 1
    const val EXT = ".jrnl"
    const val HEADER_SIZE = 12
    private const val RECORD_HEADER = 8
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'N'.code.toByte(), 'J'.code.toByte(), 'L'.code.toByte())
    private val INK_MAGIC = byteArrayOf('B'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'K'.code.toByte())

    /** One edit: remove the strokes at [removed] (indices before), then insert [added] at their final indices. */
    class Edit(val baseCount: Int, val removed: IntArray, val addedIndices: IntArray, val added: List<InkStroke>)

    /** Identity of a chunk: the CRC stored in its header (any other bytes: a CRC of all of them). */
    fun chunkId(chunk: ByteArray): Int {
        if (isInkChunk(chunk)) {
            return ByteBuffer.wrap(chunk, 12, 4).order(ByteOrder.BIG_ENDIAN).int
        }
        return CRC32().apply { update(chunk) }.value.toInt()
    }

    fun isInkChunk(bytes: ByteArray) = bytes.size >= InkChunkCodec.HEADER_SIZE && INK_MAGIC.indices.all { bytes[it] == INK_MAGIC[it] }

    /**
     * The edit that turns [old] into [new] (strokes compared by identity), or null when it is not worth
     * journalling: the order of surviving strokes changed, or [maxAdded] or more strokes are new.
     */
    fun diff(old: List<InkStroke>, new: List<InkStroke>, maxAdded: Int): Edit? {
        var prefix = 0
        val common = minOf(old.size, new.size)
        while (prefix < common && old[prefix] === new[prefix]) prefix++
        var suffix = 0
        while (suffix < common - prefix && old[old.size - 1 - suffix] === new[new.size - 1 - suffix]) suffix++
        val oldEnd = old.size - suffix
        val newEnd = new.size - suffix
        val inNew = IdentityHashMap<InkStroke, Boolean>(newEnd - prefix)
        for (i in prefix until newEnd) inNew[new[i]] = true
        val inOld = IdentityHashMap<InkStroke, Boolean>(oldEnd - prefix)
        for (i in prefix until oldEnd) inOld[old[i]] = true
        val removed = ArrayList<Int>()
        for (i in prefix until oldEnd) if (!inNew.containsKey(old[i])) removed += i
        val addedIdx = ArrayList<Int>()
        val added = ArrayList<InkStroke>()
        for (i in prefix until newEnd) if (!inOld.containsKey(new[i])) {
            if (added.size >= maxAdded) return null
            addedIdx += i
            added += new[i]
        }
        val edit = Edit(old.size, removed.toIntArray(), addedIdx.toIntArray(), added)
        // Applying it must give exactly [new] (guards reordered or repeated strokes).
        val applied = apply(old, edit) ?: return null
        if (applied.size != new.size || applied.indices.any { applied[it] !== new[it] }) return null
        return edit
    }

    /** [list] with [edit] applied, or null when the edit does not fit it. */
    fun apply(list: List<InkStroke>, edit: Edit): List<InkStroke>? {
        if (list.size != edit.baseCount) return null
        val out = ArrayList<InkStroke>(list.size - edit.removed.size + edit.added.size)
        var r = 0
        for (i in list.indices) {
            if (r < edit.removed.size && edit.removed[r] == i) r++ else out += list[i]
        }
        if (r != edit.removed.size) return null
        for ((k, at) in edit.addedIndices.withIndex()) {
            if (at < 0 || at > out.size) return null
            out.add(at, edit.added[k])
        }
        return out
    }

    fun header(baseId: Int): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN).put(MAGIC).putShort(VERSION.toShort()).putShort(0).putInt(baseId).array()

    /** The framed bytes to append for [edit]. */
    fun record(edit: Edit, codec: StrokeChunkCodec): ByteArray {
        val chunk = codec.encode(edit.added)
        val payload = ByteBuffer.allocate(4 + 4 + edit.removed.size * 4 + 4 + edit.addedIndices.size * 4 + 4 + chunk.size).order(ByteOrder.BIG_ENDIAN)
        payload.putInt(edit.baseCount).putInt(edit.removed.size)
        edit.removed.forEach { payload.putInt(it) }
        payload.putInt(edit.addedIndices.size)
        edit.addedIndices.forEach { payload.putInt(it) }
        payload.putInt(chunk.size).put(chunk)
        val body = payload.array()
        val crc = CRC32().apply { update(body) }.value
        return ByteBuffer.allocate(RECORD_HEADER + body.size).order(ByteOrder.BIG_ENDIAN).putInt(body.size).putInt(crc.toInt()).put(body).array()
    }

    /** What a journal file holds. [validEnd] is where the last good record ends (the file is cut back there before appending). */
    class Scan(val baseId: Int, val edits: List<Edit>, val validEnd: Long)

    /** Reads [bytes]; null when the header is missing or damaged. A torn or corrupt tail ends the scan, it never throws. */
    fun scan(bytes: ByteArray, codec: StrokeChunkCodec): Scan? {
        if (bytes.size < HEADER_SIZE || !MAGIC.indices.all { bytes[it] == MAGIC[it] }) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buf.position(4)
        val version = buf.short.toInt() and 0xFFFF
        if (version != VERSION) return null
        buf.short
        val baseId = buf.int
        val edits = ArrayList<Edit>()
        var end = HEADER_SIZE
        while (bytes.size - end >= RECORD_HEADER) {
            buf.position(end)
            val length = buf.int
            val crc = buf.int.toLong() and 0xFFFFFFFFL
            if (length < 0 || length > bytes.size - end - RECORD_HEADER) break
            if (CRC32().apply { update(bytes, end + RECORD_HEADER, length) }.value != crc) break
            val edit = try {
                parse(ByteBuffer.wrap(bytes, end + RECORD_HEADER, length).order(ByteOrder.BIG_ENDIAN), codec)
            } catch (_: java.io.IOException) {
                null
            } catch (_: java.nio.BufferUnderflowException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: break
            edits += edit
            end += RECORD_HEADER + length
        }
        return Scan(baseId, edits, end.toLong())
    }

    private fun parse(buf: ByteBuffer, codec: StrokeChunkCodec): Edit? {
        val baseCount = buf.int
        val removed = IntArray(buf.int.also { if (it < 0 || it * 4 > buf.remaining()) return null }) { buf.int }
        val addedIdx = IntArray(buf.int.also { if (it < 0 || it * 4 > buf.remaining()) return null }) { buf.int }
        val len = buf.int
        if (len < 0 || len > buf.remaining()) return null
        val chunk = ByteArray(len).also { buf.get(it) }
        val added = codec.decode(chunk)
        if (added.size != addedIdx.size) return null
        return Edit(baseCount, removed, addedIdx, added)
    }

    /** [strokes] of the chunk with every valid record of [journal] replayed, or [strokes] untouched for a stale or empty journal. */
    fun replay(chunk: ByteArray, strokes: List<InkStroke>, journal: ByteArray, codec: StrokeChunkCodec): List<InkStroke> {
        val scan = scan(journal, codec) ?: return strokes
        if (scan.baseId != chunkId(chunk)) return strokes
        var current = strokes
        for (edit in scan.edits) current = apply(current, edit) ?: break
        return current
    }

    /** Appends [data] to [file] at [at] (cutting off anything after it), fsynced. */
    fun appendAt(file: File, at: Long, data: ByteArray) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(at)
            raf.seek(at)
            raf.write(data)
            raf.fd.sync()
        }
    }
}
