package app.bestnote.core.storage

import app.bestnote.core.model.AudioSync
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.ShapeLink
import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** A stored chunk or manifest is damaged (bad magic, CRC mismatch, truncated, malformed). */
class CorruptChunkException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The data was written by a newer BestNote; the reader must ask the user to update the app. */
class UnsupportedFormatVersionException(val found: Int, val supported: Int, what: String) :
    IOException("$what version $found is newer than supported version $supported; update BestNote")

/** Encodes a page's strokes to bytes and back. A future androidx.ink encoding can sit behind this. */
interface StrokeChunkCodec {
    fun encode(strokes: List<InkStroke>): ByteArray

    /** @throws CorruptChunkException, UnsupportedFormatVersionException */
    fun decode(bytes: ByteArray): List<InkStroke>
}

/**
 * `.ink` chunk, v6 (v1..v5 are still read). v6 adds the clean-shape record after the group id (CANVAS-17):
 * `u8 hasShape (0 or 1)`, then when 1: `u8 idLength | id | u8 kindLength | kind | u16 floatCount | floatCount x f32 points |
 * link start | link end`, each link `u8 idLength (0 = none) | id | f32 at (only when idLength > 0)`. A chunk without
 * shapes is still written as v5 (or v4 without groups), so older apps keep reading notebooks that have none.
 *
 * v5 adds the group id after the fill (CANVAS-14): v5 adds the group id after the fill (CANVAS-14): `u8 idLength (0 = none) | idLength x UTF-8`;
 * a chunk with no grouped stroke is still written as v4, so older apps keep reading ungrouped notebooks.
 * v4 adds the shape fill after the audio stamp:
 * `u8 hasFill (0 or 1) | i32 fillArgb (only when hasFill = 1)`. v3 adds the audio sync stamp after the family key:
 * `u8 idLength (0 = none) | idLength x UTF-8 recording id | i64 offsetMillis (only when idLength > 0)`.
 * v2 adds the brush-family key after the size:
 * `u8 keyLength (0 = none) | keyLength x UTF-8`.
 *
 * v1: All integers big-endian, floats as raw IEEE-754 bits (lossless).
 * ```
 * header  "BNIK" | u16 version | u16 flags (0) | u32 payload length | u32 CRC32(payload)
 * payload u32 strokeCount, then per stroke:
 *           u8 tool | u8 penStyle | i32 colorArgb | f32 size | u32 sampleCount
 *           sampleCount × (f32 x | f32 y | f32 pressure | f32 tiltRad | f32 orientationRad | i64 timeMillis)
 * ```
 */
object InkChunkCodec : StrokeChunkCodec {
    const val VERSION = 6
    private const val VERSION_GROUPS = 5
    private const val VERSION_NO_GROUPS = 4
    private const val MAX_SHAPE_FLOATS = 0xFFFF
    private const val MAX_FAMILY_BYTES = 255
    const val HEADER_SIZE = 16
    private val MAGIC = byteArrayOf('B'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), 'K'.code.toByte())
    private const val STROKE_HEADER = 1 + 1 + 4 + 4 + 4
    private const val SAMPLE_SIZE = 5 * 4 + 8

    // Stable on-disk codes; never reuse or renumber.
    private val TOOL_CODES = mapOf(Tool.Pen to 0, Tool.Pencil to 1, Tool.Highlighter to 2, Tool.Eraser to 3, Tool.Lasso to 4)
    private val STYLE_CODES = mapOf(PenStyle.Fountain to 0, PenStyle.Ball to 1, PenStyle.Brush to 2)
    private val EMPTY = ByteArray(0)
    private val TOOLS = TOOL_CODES.entries.associate { (k, v) -> v to k }
    private val STYLES = STYLE_CODES.entries.associate { (k, v) -> v to k }

    override fun encode(strokes: List<InkStroke>): ByteArray {
        val families = strokes.map { s ->
            val key = s.brush.family?.encodeToByteArray() ?: EMPTY
            require(key.size <= MAX_FAMILY_BYTES) { "Brush family key too long" }
            key
        }
        val audioIds = strokes.map { s ->
            val id = s.audio?.recordingId?.encodeToByteArray() ?: EMPTY
            require(id.size <= MAX_FAMILY_BYTES) { "Recording id too long" }
            id
        }
        val groups = strokes.map { s ->
            val id = s.brush.group?.encodeToByteArray() ?: EMPTY
            require(id.size <= MAX_FAMILY_BYTES) { "Group id too long" }
            id
        }
        val shapes = strokes.map { s -> s.brush.shape?.let(::shapeBytes) }
        val version = when {
            shapes.any { it != null } -> VERSION
            groups.any { it.isNotEmpty() } -> VERSION_GROUPS
            else -> VERSION_NO_GROUPS
        }
        val withGroups = version >= VERSION_GROUPS
        val withShapes = version >= VERSION
        val payloadSize = 4 + strokes.indices.sumOf { i ->
            STROKE_HEADER.toLong() + 1 + families[i].size + 1 + audioIds[i].size + 1 + (if (strokes[i].brush.fillArgb == null) 0 else 4) + (if (withGroups) 1 + groups[i].size else 0) + (if (audioIds[i].isEmpty()) 0 else 8) +
                (if (withShapes) 1 + (shapes[i]?.size ?: 0) else 0) +
                strokes[i].samples.size.toLong() * SAMPLE_SIZE
        }
        require(payloadSize <= Int.MAX_VALUE - HEADER_SIZE) { "Page too large for one chunk" }
        val buf = ByteBuffer.allocate(HEADER_SIZE + payloadSize.toInt()).order(ByteOrder.BIG_ENDIAN)
        buf.position(HEADER_SIZE)
        buf.putInt(strokes.size)
        for ((i, s) in strokes.withIndex()) {
            buf.put(TOOL_CODES.getValue(s.brush.tool).toByte())
            buf.put(STYLE_CODES.getValue(s.brush.penStyle).toByte())
            buf.putInt(s.brush.colorArgb)
            buf.putFloat(s.brush.size)
            buf.put(families[i].size.toByte())
            buf.put(families[i])
            buf.put(audioIds[i].size.toByte())
            if (audioIds[i].isNotEmpty()) {
                buf.put(audioIds[i])
                buf.putLong(s.audio!!.offsetMillis)
            }
            val fill = s.brush.fillArgb
            buf.put((if (fill == null) 0 else 1).toByte())
            if (fill != null) buf.putInt(fill)
            if (withGroups) {
                buf.put(groups[i].size.toByte())
                buf.put(groups[i])
            }
            if (withShapes) {
                val shape = shapes[i]
                buf.put((if (shape == null) 0 else 1).toByte())
                if (shape != null) buf.put(shape)
            }
            buf.putInt(s.samples.size)
            for (p in s.samples) {
                buf.putFloat(p.x); buf.putFloat(p.y); buf.putFloat(p.pressure)
                buf.putFloat(p.tiltRad); buf.putFloat(p.orientationRad); buf.putLong(p.timeMillis)
            }
        }
        val array = buf.array()
        val crc = CRC32().apply { update(array, HEADER_SIZE, array.size - HEADER_SIZE) }.value
        buf.position(0)
        buf.put(MAGIC).putShort(version.toShort()).putShort(0).putInt(array.size - HEADER_SIZE).putInt(crc.toInt())
        return array
    }

    override fun decode(bytes: ByteArray): List<InkStroke> {
        if (bytes.size < HEADER_SIZE) throw CorruptChunkException("Chunk shorter than header (${bytes.size} bytes)")
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(4).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) throw CorruptChunkException("Bad chunk magic")
        val version = buf.short.toInt() and 0xFFFF
        if (version > VERSION) throw UnsupportedFormatVersionException(version, VERSION, "Ink chunk")
        if (version < 1) throw CorruptChunkException("Bad chunk version $version")
        buf.short // flags, reserved
        val length = buf.int
        val storedCrc = buf.int.toLong() and 0xFFFFFFFFL
        if (length.toLong() != (bytes.size - HEADER_SIZE).toLong()) {
            throw CorruptChunkException("Payload length $length does not match ${bytes.size - HEADER_SIZE}")
        }
        val crc = CRC32().apply { update(bytes, HEADER_SIZE, length) }.value
        if (crc != storedCrc) throw CorruptChunkException("CRC32 mismatch")
        return try {
            readPayload(buf, version)
        } catch (e: BufferUnderflowException) {
            throw CorruptChunkException("Truncated payload", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptChunkException("Malformed payload: ${e.message}", e)
        }
    }

    private fun readPayload(buf: ByteBuffer, version: Int): List<InkStroke> {
        val count = buf.int
        if (count < 0 || count.toLong() * STROKE_HEADER > buf.remaining()) throw CorruptChunkException("Bad stroke count $count")
        val strokes = ArrayList<InkStroke>(count)
        repeat(count) {
            val tool = TOOLS[buf.get().toInt()] ?: throw CorruptChunkException("Unknown tool code")
            val style = STYLES[buf.get().toInt()] ?: throw CorruptChunkException("Unknown pen style code")
            val color = buf.int
            val size = buf.float
            val family = if (version >= 2) {
                val bytes = ByteArray(buf.get().toInt() and 0xFF).also { buf.get(it) }
                if (bytes.isEmpty()) null else bytes.decodeToString()
            } else {
                null
            }
            val audio = if (version >= 3) {
                val idBytes = ByteArray(buf.get().toInt() and 0xFF).also { buf.get(it) }
                if (idBytes.isEmpty()) null else AudioSync(idBytes.decodeToString(), buf.long)
            } else {
                null
            }
            val fill = if (version >= 4 && buf.get().toInt() != 0) buf.int else null
            val group = if (version >= VERSION_GROUPS) {
                val bytes = ByteArray(buf.get().toInt() and 0xFF).also { buf.get(it) }
                if (bytes.isEmpty()) null else bytes.decodeToString()
            } else {
                null
            }
            val shape = if (version >= VERSION && buf.get().toInt() != 0) readShape(buf) else null
            val n = buf.int
            if (n < 0 || n.toLong() * SAMPLE_SIZE > buf.remaining()) throw CorruptChunkException("Bad sample count $n")
            val samples = ArrayList<StylusSample>(n)
            repeat(n) {
                samples += StylusSample(buf.float, buf.float, buf.float, buf.float, buf.float, buf.long)
            }
            strokes += InkStroke(InkBrush(tool, style, color, size, family, audio, fill, group, shape), samples)
        }
        if (buf.hasRemaining()) throw CorruptChunkException("${buf.remaining()} trailing bytes")
        return strokes
    }

    private fun shortString(text: String, what: String): ByteArray {
        val b = text.encodeToByteArray()
        require(b.size <= MAX_FAMILY_BYTES) { "$what too long" }
        return b
    }

    /** The v6 shape record after its `hasShape` byte. */
    private fun shapeBytes(shape: ShapeInfo): ByteArray {
        val id = shortString(shape.id, "Shape id")
        val kind = shortString(shape.kind, "Shape kind")
        require(shape.points.size <= MAX_SHAPE_FLOATS) { "Too many shape points" }
        val links = listOf(shape.start, shape.end).map { l -> l?.let { shortString(it.targetId, "Link target") to it.at } }
        val size = 1 + id.size + 1 + kind.size + 2 + 4 * shape.points.size + links.sumOf { 1 + (it?.first?.size?.plus(4) ?: 0) }
        val b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        b.put(id.size.toByte()).put(id).put(kind.size.toByte()).put(kind)
        b.putShort(shape.points.size.toShort())
        shape.points.forEach(b::putFloat)
        for (l in links) {
            if (l == null || l.first.isEmpty()) {
                b.put(0)
            } else {
                b.put(l.first.size.toByte()).put(l.first).putFloat(l.second)
            }
        }
        return b.array()
    }

    private fun readShape(buf: ByteBuffer): ShapeInfo {
        fun text() = ByteArray(buf.get().toInt() and 0xFF).also { buf.get(it) }.decodeToString()
        val id = text()
        val kind = text()
        val count = buf.short.toInt() and 0xFFFF
        if (count % 2 != 0 || count * 4L > buf.remaining()) throw CorruptChunkException("Bad shape point count $count")
        val points = List(count) { buf.float }
        fun link(): ShapeLink? {
            val target = text()
            return if (target.isEmpty()) null else ShapeLink(target, buf.float)
        }
        val start = link()
        val end = link()
        return ShapeInfo(id, kind, points, start, end)
    }
}
