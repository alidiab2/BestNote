package app.bestnote.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun keepsTheNewestFiveWithHeaderAndTrace() {
        var now = 1_000L
        val log = CrashLog(tmp.newFolder("crashes"), clock = { now++ })
        repeat(7) { i -> log.record(Thread.currentThread(), IllegalStateException("boom $i"), "BestNote 0.1.0 (abc123)") }
        val recent = log.recent()
        assertEquals(5, recent.size)
        assertTrue(recent.first().text.contains("boom 6"))
        assertTrue(recent.last().text.contains("boom 2"))
        assertTrue(recent.first().text.startsWith("BestNote 0.1.0 (abc123)"))
        assertTrue(recent.first().text.contains("at app.bestnote.core.common.CrashLogTest"))
        log.clear()
        assertEquals(0, log.recent().size)
    }
}
