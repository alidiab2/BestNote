package app.bestnote.engine.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SizedLruTest {
    @Test fun evictsLeastRecentlyUsedWhenOverWeight() {
        val lru = SizedLru<String, Int>(10) { it.toLong() }
        lru.put("a", 4); lru.put("b", 4)
        lru["a"] // a is now newer than b
        lru.put("c", 4)
        assertNull(lru["b"])
        assertEquals(4, lru["a"]); assertEquals(4, lru["c"])
        assertEquals(8, lru.weight)
    }

    @Test fun newestEntryIsKeptEvenWhenOversized() {
        val lru = SizedLru<String, Int>(10) { it.toLong() }
        lru.put("a", 3)
        lru.put("big", 50)
        assertNull(lru["a"])
        assertEquals(50, lru["big"])
        assertEquals(50, lru.weight)
    }

    @Test fun replacingAKeyReplacesItsWeight() {
        val lru = SizedLru<String, Int>(100) { it.toLong() }
        lru.put("a", 30); lru.put("a", 10)
        assertEquals(10, lru.weight)
        assertEquals(1, lru.size)
    }

    @Test fun widthBucketsRoundUpAndStayBounded() {
        assertEquals(1280, PdfPageBackgrounds.bucket(1241, 1.41f))
        // Landscape slide: 2400 px wide cap; a tall page is capped by its 6 MP budget.
        assertEquals(2400, PdfPageBackgrounds.bucket(5000, 0.56f))
        assertEquals(2062, PdfPageBackgrounds.bucket(5000, 1.41f))
    }
}
