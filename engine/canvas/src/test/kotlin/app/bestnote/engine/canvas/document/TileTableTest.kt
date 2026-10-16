package app.bestnote.engine.canvas.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileTableTest {
    private class Bmp(val bytes: Long)

    private fun table(budget: Long = 1_000) = TileTable<Bmp, String>(budget, sizeOf = { it.bytes })

    private fun key(col: Int, row: Int, page: String = "p", scale: Float = 1f) = TileTable.Key(page, scale, col, row)

    @Test
    fun rangeCoversRectAndClampsToPage() {
        val s = TileMath.SIZE.toFloat()
        assertEquals(TileRange(0, 0, 1, 2), TileMath.range(10f, 10f, s + 1, 2 * s + 1, 1200, 1700))
        assertEquals(TileRange(2, 3, 2, 3), TileMath.range(-50f, -50f, 99_999f, 99_999f, 1200, 1700)?.let { TileRange(it.c1, it.r1, it.c1, it.r1) })
        assertNull(TileMath.range(10f, 10f, 10f, 20f, 1200, 1700))
        assertNull(TileMath.range(5000f, 0f, 6000f, 20f, 1200, 1700))
    }

    @Test
    fun diffSkipsCommonPrefixAndSuffix() {
        val a = "a"; val b = "b"; val c = "c"; val d = "d"
        val appended = IdentityDiff.of(listOf(a, b), listOf(a, b, c))
        assertTrue(appended.additive)
        assertEquals(listOf(c), appended.added)
        val spliced = IdentityDiff.of(listOf(a, b, c), listOf(a, d, c))
        assertEquals(listOf(b), spliced.removed)
        assertEquals(listOf(d), spliced.added)
        assertFalse(spliced.additive)
        assertTrue(IdentityDiff.of(listOf(a, b), listOf(a, b)).isEmpty)
        assertTrue(IdentityDiff.of(listOf(a, b, c), listOf(c, b, a)).all)
    }

    @Test
    fun installOnlyAcceptsTheCurrentGeneration() {
        val t = table()
        val e = t.obtain(key(0, 0))
        val gen = e.gen
        t.invalidate("p", 1f, 1f, 2f, 2f, 0f, null)
        assertFalse(t.install(key(0, 0), gen, Bmp(10), false))
        assertTrue(t.install(key(0, 0), e.gen, Bmp(10), false))
        assertTrue(e.fresh)
        assertEquals(10, t.bytes)
    }

    @Test
    fun invalidationTouchesOnlyTilesUnderTheChange() {
        val t = table()
        val keys = listOf(key(0, 0), key(1, 0), key(0, 1), key(1, 1))
        keys.forEach { k -> t.obtain(k).also { t.install(k, it.gen, Bmp(1), false) } }
        // A stroke well inside tile (1, 1) at scale 1.
        val s = TileMath.SIZE.toFloat()
        t.invalidate("p", s + 100, s + 100, s + 140, s + 120, 2f, null)
        assertEquals(listOf(true, true, true, false), keys.map { t.peek(it)!!.fresh })
        // Other pages and other scales of this page are separate tiles.
        t.obtain(key(0, 0, page = "q")).also { t.install(key(0, 0, page = "q"), it.gen, Bmp(1), false) }
        t.invalidate("p", 0f, 0f, 10f, 10f, 0f, null)
        assertTrue(t.peek(key(0, 0, page = "q"))!!.fresh)
    }

    @Test
    fun additionsKeepTheOldBitmapWithAnOverlay() {
        val t = table()
        val e = t.obtain(key(0, 0))
        t.install(key(0, 0), e.gen, Bmp(1), false)
        t.invalidate("p", 1f, 1f, 5f, 5f, 0f, "new stroke")
        assertFalse(e.fresh)
        assertTrue(e.showable)
        assertEquals(listOf("new stroke"), e.overlay)
        assertTrue(e.needsRender)
        // A removal on top of it: the bitmap is wrong now, so it's drawn live until re-rendered.
        t.invalidate("p", 1f, 1f, 5f, 5f, 0f, null)
        assertFalse(e.showable)
        assertTrue(t.install(key(0, 0), e.gen, Bmp(1), false))
        assertTrue(e.fresh)
        assertNull(e.overlay)
    }

    @Test
    fun placeholderInvalidationOnlyHitsPlaceholderTiles() {
        val t = table()
        t.obtain(key(0, 0)).also { t.install(key(0, 0), it.gen, Bmp(1), true) }
        t.obtain(key(1, 0)).also { t.install(key(1, 0), it.gen, Bmp(1), false) }
        t.invalidatePage("p", onlyPlaceholders = true)
        assertFalse(t.peek(key(0, 0))!!.fresh)
        assertTrue(t.peek(key(1, 0))!!.fresh)
    }

    @Test
    fun lruEvictsOldTilesButNotRecentlyDrawnOnes() {
        val t = table(budget = 30)
        t.now = 0
        for (c in 0 until 3) t.obtain(key(c, 0)).also { t.install(key(c, 0), it.gen, Bmp(10), false) }
        assertEquals(30, t.bytes)
        t.now = 5_000
        t.obtain(key(0, 0)) // drawn again just now
        t.obtain(key(9, 0)).also { t.install(key(9, 0), it.gen, Bmp(10), false) }
        assertTrue(t.bytes <= 30)
        assertTrue(t.peek(key(0, 0)) != null)
        assertTrue(t.peek(key(9, 0)) != null)
        assertNull(t.peek(key(1, 0)))
    }

    @Test
    fun retainDropsRemovedPages() {
        val t = table()
        t.obtain(key(0, 0, page = "a")).also { t.install(key(0, 0, page = "a"), it.gen, Bmp(5), false) }
        t.obtain(key(0, 0, page = "b")).also { t.install(key(0, 0, page = "b"), it.gen, Bmp(5), false) }
        t.retain(setOf("b"))
        assertEquals(setOf("b"), t.pages())
        assertEquals(5, t.bytes)
    }

    private fun put(t: TileTable<Bmp, String>, k: TileTable.Key) = t.obtain(k).also { t.install(k, it.gen, Bmp(1), false) }

    @Test
    fun standInScalesAreNearestFirstPreferringSharper() {
        val t = table(budget = 1_000_000)
        for (scale in listOf(0.5f, 1f, 2f, 3f, 0.9f)) put(t, key(0, 0, scale = scale))
        put(t, key(0, 0, page = "other", scale = 1.1f))
        val out = FloatArray(4)
        val n = t.scalesOf("p", 1f, out)
        // 1f itself is the current scale. Costs: 0.9 -> 2 ln(1/0.9) = 0.21, 2 -> ln 2, 3 -> ln 3, 0.5 -> 2 ln 2.
        assertEquals(4, n)
        assertEquals(listOf(0.9f, 2f, 3f, 0.5f), out.take(n))
    }

    @Test
    fun standInScalesSkipTilesAnEditMadeWrong() {
        val t = table(budget = 1_000_000)
        put(t, key(0, 0, scale = 2f))
        put(t, key(0, 0, scale = 0.5f))
        t.invalidate("p", 0f, 0f, 10f, 10f, 0f, null) // an erase: every scale's tile under it is wrong
        val out = FloatArray(4)
        assertEquals(0, t.scalesOf("p", 1f, out))
        put(t, key(0, 0, scale = 0.5f)) // re-rendered
        assertEquals(1, t.scalesOf("p", 1f, out))
        assertEquals(0.5f, out[0])
    }

    @Test
    fun standInScalesKeepOnlyTheBestWhenFull() {
        val t = table(budget = 1_000_000)
        for (scale in listOf(8f, 0.25f, 3f, 1.5f, 1.2f, 0.8f)) put(t, key(0, 0, scale = scale))
        val out = FloatArray(2)
        assertEquals(2, t.scalesOf("p", 1f, out))
        assertEquals(listOf(1.2f, 1.5f), out.toList())
        assertTrue(standInCost(0.5f, 1f) > standInCost(2f, 1f))
    }
}
