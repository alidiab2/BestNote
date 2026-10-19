package app.bestnote.ai.scan.bench

import app.bestnote.ai.scan.figure.FigureRedraw
import app.bestnote.engine.layout.FigureSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-001 regression gate on the owner's boards (alidiab2/BestNote-requests#2): the figure bench's
 * scores may not fall below what round 2 reached (docs/research/board-figures.md). Values measured:
 * oracle f1 0.951 / 6 false corners, Sonnet answer 0.894, Opus answer 0.946 with labels 0.96.
 * Before round 2: 0.937 / 13, 0.875 / 13, 0.512 with labels 0.67.
 */
class FigureFidelityTest {
    private fun mean(s: List<FigureBench.Score>) = s.map { it.f1 }.average()

    @Test fun `the CV redraw follows the teacher's drawings`() {
        val scores = FigureBench.truths.map { FigureBench.score(it, FigureBench.oracle(it), "gate/oracle") }
        scores.forEach(::println)
        assertTrue("mean f1 ${mean(scores)}", mean(scores) >= 0.94)
        assertTrue("every drawing: $scores", scores.all { it.f1 >= 0.85 })
        // Curves are curves: few corners where the teacher's line runs smooth (13 before).
        assertTrue("false corners ${scores.sumOf { it.kinks }}", scores.sumOf { it.kinks } <= 8)
        assertEquals("all labels typeset", scores.sumOf { it.labelsTotal }, scores.sumOf { it.labelsFound })
    }

    @Test fun `the owner model, Sonnet 5_5, gets the drawings redrawn`() {
        val drawn = FigureBench.sonnet.flatMap { FigureBench.live(it).entries }.associate { it.key to it.value }
        val scores = FigureBench.truths.filter { it.id in drawn }.map { FigureBench.score(it, drawn[it.id], "gate/sonnet") }
        scores.forEach(::println)
        assertTrue("mean f1 ${mean(scores)}", mean(scores) >= 0.88)
        assertTrue("false corners", scores.sumOf { it.kinks } <= 6)
    }

    @Test fun `with library parameters from Opus 5_5 the photo still wins and every drawing is found`() {
        val drawn = FigureBench.opus.flatMap { FigureBench.live(it).entries }.associate { it.key to it.value }
        val scores = FigureBench.truths.map { FigureBench.score(it, drawn[it.id], "gate/opus") }
        scores.forEach(::println)
        // A box read as pixels on a busy board lost "before"; the library's made-up ticks cost the rest (0.51 before).
        assertTrue("every drawing found: $scores", scores.none { it.source == "missing" })
        assertTrue("redrawn from the photo", scores.all { it.source == FigureSource.Redrawn.name })
        assertTrue("mean f1 ${mean(scores)}", mean(scores) >= 0.93)
        assertTrue("no made-up labels", scores.sumOf { it.extraLabels } <= 3)
    }

    @Test fun `a library drawing is still the fallback when there is no photo`() {
        val drawn = FigureBench.opus.flatMap { FigureBench.live(it, resolve = { d, _, k -> FigureRedraw.resolve(d, null, k) }).entries }
        assertTrue(drawn.all { it.value?.source == FigureSource.FromData })
    }
}
