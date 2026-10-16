package app.bestnote.core.storage

import app.bestnote.core.model.AudioSync
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PenStyle
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
 * `.ink` chunk, v4 (v1..v3 are still read). v4 adds the shape fill after the audio stamp:
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
    const val VERSION = 4
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
        val payloadSize = 4 + strokes.indices.sumOf { i ->
            STROKE_HEADER.toLong() + 1 + families[i].size + 1 + audioIds[i].size + 1 + (if (strokes[i].brush.fillArgb == null) 0 else 4) + (if (audioIds[i].isEmpty()) 0 else 8) +
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
            buf.putInt(s.samples.size)
            for (p in s.samples) {
                buf.putFloat(p.x); buf.putFloat(p.y); buf.putFloat(p.pressure)
                buf.putFloat(p.tiltRad); buf.putFloat(p.orientationRad); buf.putLong(p.timeMillis)
            }
        }
        val array = buf.array()
        val crc = CRC32().apply { update(array, HEADER_SIZE, array.size - HEADER_SIZE) }.value
        buf.position(0)
        buf.put(MAGIC).putShort(VERSION.toShort()).putShort(0).putInt(array.size - HEADER_SIZE).putInt(crc.toInt())
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
            val n = buf.int
            if (n < 0 || n.toLong() * SAMPLE_SIZE > buf.remaining()) throw CorruptChunkException("Bad sample count $n")
            val samples = ArrayList<StylusSample>(n)
            repeat(n) {
                samples += StylusSample(buf.float, buf.float, buf.float, buf.float, buf.float, buf.long)
            }
            strokes += InkStroke(InkBrush(tool, style, color, size, family, audio, fill), samples)
        }
        if (buf.hasRemaining()) throw CorruptChunkException("${buf.remaining()} trailing bytes")
        return strokes
    }
}
