package app.bestnote.core.storage

import app.bestnote.core.model.NotebookId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThumbnailCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun writeReplacesAtomicallyAndDeletes() = runTest {
        val cache = ThumbnailCache(tmp.root.resolve("thumbs"))
        val id = NotebookId("a/b c")
        val f = cache.write(id, byteArrayOf(1, 2))
        assertEquals(tmp.root.resolve("thumbs"), f.parentFile)
        assertEquals("a%2Fb%20c.webp", f.name)
        cache.write(id, byteArrayOf(3))
        assertArrayEquals(byteArrayOf(3), f.readBytes())
        assertEquals(listOf(f.name), f.parentFile.list()!!.toList())
        assertTrue(cache.delete(id))
        assertFalse(f.exists())
    }

    @Test fun staleWhenRenderedBeforeTheLastEdit() {
        assertTrue(ThumbnailCache.isStale(null, 5))
        assertTrue(ThumbnailCache.isStale(4, 5))
        assertFalse(ThumbnailCache.isStale(5, 5))
    }
}
