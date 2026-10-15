package app.bestnote.debug.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPlanTest {
    private fun sample(i: Int, t: Double) = """{"x":${i * 0.5},"y":1.0,"t_ms":$t,"pressure":0.5,"tiltRad":0.8,"orientationRad":2.3}"""

    private val text = buildString {
        append("""{"format":"bestnote-strokes","version":1,"units":"mm","name":"t","width":10,"height":5,"strokes":[""")
        // 25 ink samples at 240 Hz starting at 100 ms, then a 4-sample hover, then a tap with the button
        append("""{"type":"ink","button":false,"samples":[""")
        append((0 until 25).joinToString(",") { sample(it, 100 + it * 1000.0 / 240) })
        append("""]},{"type":"hover","button":false,"samples":[""")
        append((0 until 4).joinToString(",") { sample(it, 300 + it * 1000.0 / 240) })
        append("""]},{"type":"ink","button":true,"samples":[${sample(1, 400.0)}]}]}""")
    }

    @Test
    fun parsesStrokesAndTypes() {
        val f = StrokeFile.parse(text)
        assertEquals(3, f.strokes.size)
        assertEquals(25, f.strokes[0].samples.size)
        assertTrue(f.strokes[1].hover)
        assertTrue(f.strokes[2].button)
    }

    @Test
    fun batchesSamplesPerFrameAndKeepsEverySample() {
        val events = ReplayPlan.build(StrokeFile.parse(text), pxPerMm = 10f, originX = 5f, originY = 5f, frameMs = 1000.0 / 120)
        val ink = events.filter { it.strokeIndex == 0 }
        assertEquals(ReplayAction.Down, ink.first().action)
        assertEquals(ReplayAction.Up, ink.last().action)
        assertEquals(25, ink.sumOf { it.samples.size })
        // 240 Hz samples in 120 Hz frames: moves carry two samples (one historical)
        assertTrue(ink.filter { it.action == ReplayAction.Move }.all { it.samples.size in 1..3 })
        assertTrue(ink.any { it.samples.size == 2 })
        // times never go backwards and start at zero
        assertEquals(0.0, events.first().atMs, 1e-9)
        assertTrue(events.zipWithNext().all { (a, b) -> b.atMs >= a.atMs })
        // mm -> px with origin
        assertEquals(5f, ink.first().samples.first().x, 1e-4f)
        assertEquals(15f, ink.first().samples.first().y, 1e-4f)
    }

    @Test
    fun hoverAndButtonActions() {
        val events = ReplayPlan.build(StrokeFile.parse(text), 10f, 0f, 0f, frameMs = 0.0)
        val hover = events.filter { it.strokeIndex == 1 }.map { it.action }
        assertEquals(listOf(ReplayAction.HoverEnter, ReplayAction.HoverMove, ReplayAction.HoverMove, ReplayAction.HoverExit), hover)
        val tap = events.filter { it.strokeIndex == 2 }
        assertEquals(listOf(ReplayAction.Down, ReplayAction.Up), tap.map { it.action })
        assertTrue(tap.all { it.button })
    }

    @Test
    fun fitScaleShrinksOnlyWhenNeeded() {
        val f = StrokeFile.parse(text) // 10 x 5 mm
        assertEquals(1f, ReplayPlan.fitScale(f, 10f, 1000f, 1000f, 0f), 1e-6f)
        assertEquals(0.5f, ReplayPlan.fitScale(f, 10f, 50f, 1000f, 0f), 1e-6f)
    }
}
