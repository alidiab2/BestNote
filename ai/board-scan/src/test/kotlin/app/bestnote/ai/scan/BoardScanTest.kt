package app.bestnote.ai.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.hypot

class BoardScanTest {
    private fun maxCornerError(a: Quad, b: Quad) = a.corners.zip(b.corners).maxOf { (p, q) -> hypot(p.x - q.x, p.y - q.y) }

    @Test fun `finds the board on every sample photo`() {
        for (sample in SampleBoard.all()) {
            sample.save()
            val found = BoardDetector.detect(sample.raster)
            assertNotNull("${sample.name}: no board found", found)
            val err = maxCornerError(found!!.quad, sample.truth)
            assertTrue("${sample.name}: corners off by $err (${found.quad})", err < 0.03f)
        }
    }

    @Test fun `plain texture has no board`() {
        val rnd = Random(7)
        val w = 400
        val h = 300
        val px = IntArray(w * h) { val v = 90 + rnd.nextInt(60); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertNull(BoardDetector.detect(Raster(w, h, px)))
    }

    @Test fun `quad corners are ordered clockwise from the top left`() {
        val q = Quad.ordered(listOf(Pt(0.9f, 0.85f), Pt(0.1f, 0.1f), Pt(0.12f, 0.9f), Pt(0.88f, 0.12f)))
        assertEquals(Pt(0.1f, 0.1f), q.topLeft)
        assertEquals(Pt(0.88f, 0.12f), q.topRight)
        assertEquals(Pt(0.9f, 0.85f), q.bottomRight)
        assertEquals(Pt(0.12f, 0.9f), q.bottomLeft)
        assertTrue(q.isConvex)
        assertTrue(!Quad(Pt(0f, 0f), Pt(1f, 1f), Pt(1f, 0f), Pt(0f, 1f)).isConvex)
    }

    @Test fun `perspective warp flattens the board`() {
        val sample = SampleBoard.whiteboardDarkWall()
        val src = sample.raster
        val (w, h) = Perspective.outputSize(sample.truth, src.width, src.height)
        val flat = Perspective.warp(src, sample.truth, w, h)
        // Every pixel came from the board: just inside each edge it is board-light, not wall-dark.
        val luma = flat.luma()
        for ((x, y) in listOf(0.02f to 0.5f, 0.98f to 0.5f, 0.5f to 0.03f, 0.5f to 0.97f)) {
            val v = luma[(y * (h - 1)).toInt() * w + (x * (w - 1)).toInt()]
            assertTrue("edge pixel at $x,$y is $v", v > 110f)
        }
        // A point mapped back lands where it should.
        val mid = Perspective.mapBack(sample.truth, 0.5f, 0.5f)
        assertTrue(mid.x in 0.4f..0.6f && mid.y in 0.4f..0.6f)
        val corner = Perspective.mapBack(sample.truth, 1f, 1f)
        assertEquals(sample.truth.bottomRight.x, corner.x, 1e-4f)
        save(flat, "whiteboard-flat")
    }

    @Test fun `enhance evens out a shadow and keeps the ink`() {
        val sample = SampleBoard.whiteboardDarkWall()
        val src = sample.raster
        val (w, h) = Perspective.outputSize(sample.truth, src.width, src.height)
        val flat = Perspective.warp(src, sample.truth, w, h)
        val clean = Enhance.apply(flat)
        save(clean, "whiteboard-enhanced")
        fun stats(r: Raster): Triple<Float, Float, Float> {
            val l = r.luma()
            // Board background: brightest 60 % of pixels in two far-apart patches.
            fun patch(cx: Float, cy: Float): Float {
                val vals = ArrayList<Float>()
                for (y in (cy * h).toInt() - 30..(cy * h).toInt() + 30) for (x in (cx * w).toInt() - 30..(cx * w).toInt() + 30) vals += l[y * w + x]
                vals.sortDescending()
                return vals.take(vals.size * 6 / 10).average().toFloat()
            }
            val ink = l.sorted()[l.size / 100]
            return Triple(patch(0.15f, 0.08f), patch(0.9f, 0.95f), ink)
        }
        val (a0, b0, _) = stats(flat)
        val (a1, b1, ink1) = stats(clean)
        assertTrue("shadow not reduced: before ${abs(a0 - b0)}, after ${abs(a1 - b1)}", abs(a1 - b1) < abs(a0 - b0) / 3)
        assertTrue("background not white: $a1 $b1", a1 > 235f && b1 > 235f)
        assertTrue("ink lost: $ink1", ink1 < 120f)
    }

    @Test fun `blackboard stays dark with bright chalk`() {
        val sample = SampleBoard.blackboardLightWall()
        val src = sample.raster
        val (w, h) = Perspective.outputSize(sample.truth, src.width, src.height)
        val clean = Enhance.apply(Perspective.warp(src, sample.truth, w, h))
        save(clean, "blackboard-enhanced")
        val l = clean.luma().sorted()
        assertTrue("board not dark: ${l[l.size / 2]}", l[l.size / 2] < 80f)
        assertTrue("chalk not bright: ${l[l.size - l.size / 200]}", l[l.size - l.size / 200] > 180f)
    }

    private fun save(r: Raster, name: String) {
        val img = BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, r.width, r.height, r.argb, 0, r.width)
        File("build/scan-samples").mkdirs()
        ImageIO.write(img, "jpg", File("build/scan-samples/$name.jpg"))
    }
}
