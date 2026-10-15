package app.bestnote.engine.canvas.select

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialGridTest {
    @Test fun queryReturnsOnlyNearbyItemsInInsertionOrder() {
        val grid = SpatialGrid<String>(cellSize = 10f)
        grid.insert("far", Box(500f, 500f, 510f, 510f))
        grid.insert("a", Box(0f, 0f, 5f, 5f))
        grid.insert("b", Box(4f, 4f, 30f, 8f))
        grid.insert("c", Box(100f, 0f, 105f, 5f))
        assertEquals(listOf("a", "b"), grid.query(Box(0f, 0f, 12f, 12f)))
        assertEquals(listOf("b"), grid.query(Box(20f, 0f, 25f, 10f)))
    }

    @Test fun itemSpanningManyCellsIsReturnedOnce() {
        val grid = SpatialGrid<String>(cellSize = 10f)
        grid.insert("long", Box(0f, 0f, 200f, 5f))
        assertEquals(listOf("long"), grid.query(Box(0f, 0f, 200f, 200f)))
    }

    @Test fun oversizedItemsAndQueriesStillWork() {
        val grid = SpatialGrid<String>(cellSize = 1f)
        grid.insert("huge", Box(-1e5f, -1e5f, 1e5f, 1e5f))
        grid.insert("dot", Box(3f, 3f, 3.5f, 3.5f))
        assertEquals(listOf("huge", "dot"), grid.query(Box(0f, 0f, 1e4f, 1e4f)))
        assertEquals(listOf("huge"), grid.query(Box(50f, 50f, 51f, 51f)))
    }

    @Test fun negativeCoordinatesAreIndexed() {
        val grid = SpatialGrid<String>(cellSize = 10f)
        grid.insert("neg", Box(-25f, -25f, -21f, -21f))
        assertTrue(grid.query(Box(-30f, -30f, -20f, -20f)).contains("neg"))
        assertTrue(grid.query(Box(0f, 0f, 5f, 5f)).isEmpty())
    }
}
