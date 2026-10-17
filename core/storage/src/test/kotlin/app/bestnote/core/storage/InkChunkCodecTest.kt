package app.bestnote.core.storage

import app.bestnote.core.model.AudioSync
import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PenStyle
import app.bestnote.core.model.ShapeInfo
import app.bestnote.core.model.ShapeLink
import app.bestnote.core.model.StylusSample
import app.bestnote.core.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

internal fun randomStrokes(count: Int, seed: Int = 42, maxSamples: Int = 48): List<InkStroke> {
    val r = Random(seed)
    val inkTools = Tool.entries.filter { it.inks }
    fun f() = when (r.nextInt(20)) {
        0 -> -0f
        1 -> Float.MIN_VALUE
        2 -> Float.MAX_VALUE
        else -> Float.fromBits(r.nextInt()).takeIf { it.isFinite() } ?: r.nextFloat()
    }
    return List(count) {
        var t = r.nextLong(0, 1L shl 42)
        InkStroke(
            InkBrush(
                inkTools.random(r), PenStyle.entries.random(r), r.nextInt(), r.nextFloat() * 40f,
                family = listOf(null, "fountain.v1", "highlighter-chisel.v1", "قلم.v2").random(r),
                fillArgb = if (r.nextBoolean()) null else r.nextInt(),
            ),
            List(r.nextInt(1, maxSamples + 1)) {
                t += r.nextInt(0, 20)
                StylusSample(f(), f(), r.nextFloat(), r.nextFloat() * 1.6f, r.nextFloat() * 6.3f - 3.15f, t)
            },
        )
    }
}

class InkChunkCodecTest {
    @Test fun tenThousandRandomStrokesRoundTripLosslessly() {
        val strokes = randomStrokes(10_000)
        val decoded = InkChunkCodec.decode(InkChunkCodec.encode(strokes))
        assertEquals(strokes.size, decoded.size)
        // Bit-exact comparison, stricter than Float equality.
        strokes.zip(decoded).forEach { (a, b) ->
            assertEquals(a.brush, b.brush)
            assertEquals(a.samples.size, b.samples.size)
            a.samples.zip(b.samples).forEach { (p, q) ->
                assertEquals(p.x.toRawBits(), q.x.toRawBits())
                assertEquals(p.y.toRawBits(), q.y.toRawBits())
                assertEquals(p.pressure.toRawBits(), q.pressure.toRawBits())
                assertEquals(p.tiltRad.toRawBits(), q.tiltRad.toRawBits())
                assertEquals(p.orientationRad.toRawBits(), q.orientationRad.toRawBits())
                assertEquals(p.timeMillis, q.timeMillis)
            }
        }
        assertEquals(strokes, decoded)
    }

    @Test fun emptyPageRoundTrips() {
        val bytes = InkChunkCodec.encode(emptyList())
        assertEquals(InkChunkCodec.HEADER_SIZE + 4, bytes.size)
        assertEquals(emptyList<InkStroke>(), InkChunkCodec.decode(bytes))
    }

    @Test fun headerHasMagicAndVersion() {
        val bytes = InkChunkCodec.encode(randomStrokes(3))
        assertEquals("BNIK", bytes.copyOfRange(0, 4).decodeToString())
        assertEquals(4, (bytes[4].toInt() shl 8) or bytes[5].toInt()) // no grouped stroke: written as v4 (CANVAS-14)
    }

    @Test fun everyFlippedPayloadByteIsDetectedByCrc() {
        val bytes = InkChunkCodec.encode(randomStrokes(5, maxSamples = 4))
        for (i in InkChunkCodec.HEADER_SIZE until bytes.size) {
            val bad = bytes.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            val e = assertThrows(CorruptChunkException::class.java) { InkChunkCodec.decode(bad) }
            assertTrue(e.message!!.contains("CRC"))
        }
    }

    @Test fun corruptHeaderAndTruncationAreDetected() {
        val bytes = InkChunkCodec.encode(randomStrokes(5))
        assertThrows(CorruptChunkException::class.java) { InkChunkCodec.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }) }
        assertThrows(CorruptChunkException::class.java) { InkChunkCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(CorruptChunkException::class.java) { InkChunkCodec.decode(bytes.copyOf(10)) }
        assertThrows(CorruptChunkException::class.java) { InkChunkCodec.decode(bytes + 0) }
        // Corrupt stored CRC itself.
        assertThrows(CorruptChunkException::class.java) { InkChunkCodec.decode(bytes.copyOf().also { it[15] = (it[15] + 1).toByte() }) }
    }

    @Test fun newerChunkVersionIsRefused() {
        val bytes = InkChunkCodec.encode(randomStrokes(2)).also { it[4] = 0; it[5] = (InkChunkCodec.VERSION + 1).toByte() }
        val e = assertThrows(UnsupportedFormatVersionException::class.java) { InkChunkCodec.decode(bytes) }
        assertEquals(InkChunkCodec.VERSION + 1, e.found)
    }

    @Test fun version1ChunksStillDecodeWithoutFamily() {
        val strokes = randomStrokes(30, seed = 5).map { it.copy(brush = it.brush.copy(family = null, fillArgb = null)) }
        assertEquals(strokes, InkChunkCodec.decode(encodeV1(strokes)))
    }

    @Test fun familyKeyRoundTrips() {
        val s = randomStrokes(1)[0]
        val withKey = s.copy(brush = s.brush.copy(family = "brush-pen.v1"))
        assertEquals("brush-pen.v1", InkChunkCodec.decode(InkChunkCodec.encode(listOf(withKey)))[0].brush.family)
    }

    @Test fun audioSyncRoundTripsAndOtherStrokesStayUnsynced() {
        val a = randomStrokes(3, seed = 8)
        val synced = listOf(
            a[0].copy(brush = a[0].brush.copy(audio = AudioSync("rec-1", 12_345))),
            a[1].copy(brush = a[1].brush.copy(audio = null)),
            a[2].copy(brush = a[2].brush.copy(audio = AudioSync("r2", 0))),
        )
        val back = InkChunkCodec.decode(InkChunkCodec.encode(synced))
        assertEquals(synced, back)
        assertEquals(AudioSync("rec-1", 12_345), back[0].audio)
        assertEquals(null, back[1].audio)
    }

    @Test fun shapeFillRoundTripsAndOlderChunksHaveNone() {
        val a = randomStrokes(3, seed = 9).map { it.copy(brush = it.brush.copy(fillArgb = null)) }
        val filled = listOf(
            a[0].copy(brush = a[0].brush.copy(fillArgb = 0x80336699.toInt())),
            a[1],
            a[2].copy(brush = a[2].brush.copy(fillArgb = 0, audio = AudioSync("r", 5))),
        )
        val back = InkChunkCodec.decode(InkChunkCodec.encode(filled))
        assertEquals(filled, back)
        assertEquals(0x80336699.toInt(), back[0].brush.fillArgb)
        assertEquals(null, back[1].brush.fillArgb)
        assertEquals(0, back[2].brush.fillArgb)
        val v1 = randomStrokes(5, seed = 6).map { it.copy(brush = it.brush.copy(family = null, fillArgb = null)) }
        assertEquals(v1, InkChunkCodec.decode(encodeV1(v1)))
    }

    @Test fun groupsRoundTripAndUngroupedChunksStayReadableByV4Readers() {
        val a = randomStrokes(3, seed = 11).map { it.copy(brush = it.brush.copy(group = null)) }
        val grouped = listOf(a[0].copy(brush = a[0].brush.copy(group = "g-abc")), a[1], a[2].copy(brush = a[2].brush.copy(group = "g-abc")))
        val bytes = InkChunkCodec.encode(grouped)
        assertEquals(5, java.nio.ByteBuffer.wrap(bytes).getShort(4).toInt())
        val back = InkChunkCodec.decode(bytes)
        assertEquals(grouped, back)
        assertEquals(listOf("g-abc", null, "g-abc"), back.map { it.brush.group })
        // No grouped stroke: still the v4 layout, so apps from before CANVAS-14 open the notebook.
        assertEquals(4, java.nio.ByteBuffer.wrap(InkChunkCodec.encode(a)).getShort(4).toInt())
    }

    @Test fun shapesAndConnectorLinksRoundTripAndShapelessChunksKeepTheOlderLayout() {
        val a = randomStrokes(3, seed = 17).map { it.copy(brush = it.brush.copy(group = null, shape = null)) }
        val box = ShapeInfo("s-box", "Rectangle", listOf(10f, 10f, 60f, 10f, 60f, 40f, 10f, 40f))
        val arrow = ShapeInfo("s-arrow", "Arrow", listOf(0f, 0f, 10f, 25f, 6f, 22f, 12f, 21f), start = null, end = ShapeLink("s-box", 3.5f))
        val circle = ShapeInfo("s-c", "Circle", listOf(5f, 5f, 9f, 5f, 5f, 9f), start = ShapeLink("x", -1f), end = ShapeLink("s-box", 0f))
        val shaped = listOf(
            a[0].copy(brush = a[0].brush.copy(shape = box, group = "g-1")),
            a[1].copy(brush = a[1].brush.copy(shape = arrow)),
            a[2].copy(brush = a[2].brush.copy(shape = circle)),
        )
        val bytes = InkChunkCodec.encode(shaped)
        assertEquals(6, java.nio.ByteBuffer.wrap(bytes).getShort(4).toInt())
        val back = InkChunkCodec.decode(bytes)
        assertEquals(shaped, back)
        assertEquals(ShapeLink("s-box", 3.5f), back[1].brush.shape!!.end)
        assertTrue(back[2].brush.shape!!.start!!.isCenter)
        // Mixed: shapes next to plain strokes.
        val mixed = listOf(shaped[0], a[1])
        assertEquals(mixed, InkChunkCodec.decode(InkChunkCodec.encode(mixed)))
        // No shape: the v5 (grouped) or v4 layout, which apps from before CANVAS-17 read; they decode with no shape.
        val grouped = listOf(a[0].copy(brush = a[0].brush.copy(group = "g")), a[1])
        val v5 = InkChunkCodec.encode(grouped)
        assertEquals(5, java.nio.ByteBuffer.wrap(v5).getShort(4).toInt())
        assertEquals(listOf(null, null), InkChunkCodec.decode(v5).map { it.brush.shape })
        assertEquals(4, java.nio.ByteBuffer.wrap(InkChunkCodec.encode(a)).getShort(4).toInt())
    }

    /** The original v1 layout (no family key), as written by DOC-01 builds. */
    private fun encodeV1(strokes: List<InkStroke>): ByteArray {
        val tools = mapOf(Tool.Pen to 0, Tool.Pencil to 1, Tool.Highlighter to 2)
        val payload = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(payload).run {
            writeInt(strokes.size)
            for (s in strokes) {
                writeByte(tools.getValue(s.brush.tool)); writeByte(s.brush.penStyle.ordinal)
                writeInt(s.brush.colorArgb); writeFloat(s.brush.size); writeInt(s.samples.size)
                for (p in s.samples) {
                    writeFloat(p.x); writeFloat(p.y); writeFloat(p.pressure); writeFloat(p.tiltRad); writeFloat(p.orientationRad); writeLong(p.timeMillis)
                }
            }
        }
        val body = payload.toByteArray()
        val crc = java.util.zip.CRC32().apply { update(body) }.value
        val out = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(out).run {
            write("BNIK".toByteArray()); writeShort(1); writeShort(0); writeInt(body.size); writeInt(crc.toInt()); write(body)
        }
        return out.toByteArray()
    }
}
