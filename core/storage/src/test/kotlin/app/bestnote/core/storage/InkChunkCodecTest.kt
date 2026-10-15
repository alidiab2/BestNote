package app.bestnote.core.storage

import app.bestnote.core.model.InkBrush
import app.bestnote.core.model.InkStroke
import app.bestnote.core.model.PenStyle
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
            InkBrush(inkTools.random(r), PenStyle.entries.random(r), r.nextInt(), r.nextFloat() * 40f),
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
        assertEquals(InkChunkCodec.VERSION, (bytes[4].toInt() shl 8) or bytes[5].toInt())
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
}
