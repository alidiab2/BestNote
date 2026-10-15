package app.bestnote.engine.stylus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SampleRateMeterTest {
    @Test fun measures240Hz() {
        val m = SampleRateMeter()
        m.start()
        // 241 samples, 1/240 s apart, rounded to whole millis like MotionEvent times.
        for (i in 0..240) m.add(1_000L + (i * 1000L) / 240)
        assertEquals(240f, m.hz!!, 1f)
    }

    @Test fun tooShortIsUnknownAndStartResets() {
        val m = SampleRateMeter()
        m.add(0); m.add(4)
        assertNull(m.hz)
        for (i in 0..10) m.add(100L + i * 8)
        m.start()
        assertEquals(0, m.samples)
        assertNull(m.hz)
    }
}
