package app.bestnote.engine.stylus

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory

/**
 * Allocation budget of the per-event stylus path (runs 120-240 times a second while writing).
 * Only what the event itself needs may be allocated: its samples, the event and its list. Boxed
 * index ranges and intermediate lists used to triple this.
 */
class StylusEventAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

    private fun bytesPerCall(event: FakePointerEvent): Long {
        val mx = threads!!
        var sink = 0
        repeat(WARMUP) { sink += StylusEventMapper.map(event).size }
        val before = mx.currentThreadAllocatedBytes
        repeat(CALLS) { sink += StylusEventMapper.map(event).size }
        val bytes = mx.currentThreadAllocatedBytes - before
        assertTrue(sink > 0)
        return bytes / CALLS
    }

    @Test fun moveWithHistoryAllocatesOnlyItsSamples() {
        assumeTrue(threads?.isThreadAllocatedMemorySupported == true)
        threads!!.isThreadAllocatedMemoryEnabled = true
        // A typical 120 Hz frame from a 240 Hz pen: 2 historical samples + the current one.
        val move = FakePointerEvent(
            StylusEventMapper.ACTION_MOVE,
            listOf(FakePointer(0, StylusEventMapper.TOOL_TYPE_STYLUS, 10f, 10f, history = listOf(Triple(8f, 8f, 0.4f), Triple(9f, 9f, 0.5f)))),
        )
        val perEvent = bytesPerCall(move)
        // 3 samples (~40 B each) + event + 2 lists; the old mapper took ~3x this.
        assertTrue("allocated $perEvent bytes per MOVE", perEvent <= MOVE_BUDGET_BYTES)
    }

    @Test fun downAllocatesOneSample() {
        assumeTrue(threads?.isThreadAllocatedMemorySupported == true)
        threads!!.isThreadAllocatedMemoryEnabled = true
        val down = FakePointerEvent(StylusEventMapper.ACTION_DOWN, listOf(FakePointer(0, StylusEventMapper.TOOL_TYPE_STYLUS, 10f, 10f)))
        val perEvent = bytesPerCall(down)
        assertTrue("allocated $perEvent bytes per DOWN", perEvent <= DOWN_BUDGET_BYTES)
    }

    private companion object {
        const val WARMUP = 20_000
        const val CALLS = 50_000
        const val MOVE_BUDGET_BYTES = 280L
        const val DOWN_BUDGET_BYTES = 140L
    }
}
